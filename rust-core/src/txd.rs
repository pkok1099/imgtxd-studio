//! RenderWare .txd (Texture Dictionary) — faithful port of the Kotlin
//! `TxdParser`, `TxdBuilder`, `DxtDecoder`, and `MipmapGenerator`.
//!
//! Byte-parity with the old Kotlin engine is the contract (golden hashes
//! captured from the Kotlin implementation before its removal): chunk
//! walking, palette/mip handling, the min/max DXT1 encoder (shared with
//! `codec::dxt`), and the exact NEAREST/BOX/BILINEAR resampling math of
//! the Kotlin mipmap generator all reproduce the original outputs.
//!
//! Supported raster payloads (decode): DXT1, DXT3, DXT5, ETC1, PAL8,
//! RGBA32 (BGRA), RGB24 (BGR), RGB565, RGBA1555, RGBA4444.

pub const CHUNK_STRUCT: u32 = 0x0001;
pub const CHUNK_EXTENSION: u32 = 0x0003;
pub const CHUNK_TEXTURE_NATIVE: u32 = 0x0015;
pub const CHUNK_TEXTURE_DICTIONARY: u32 = 0x0016;

pub const RW_VERSION_SA: u32 = 0x1803_FFFF;

pub const PLATFORM_D3D9: u32 = 0x09;

pub const FORMAT_1555: u32 = 0x0100;
pub const FORMAT_565: u32 = 0x0200;
pub const FORMAT_4444: u32 = 0x0300;
pub const FORMAT_8888: u32 = 0x0500;
pub const FORMAT_888: u32 = 0x0600;
pub const FORMAT_PAL8: u32 = 0x2000;
pub const FORMAT_PAL4: u32 = 0x4000;

pub const FOURCC_DXT1: u32 = 0x3154_5844;
pub const FOURCC_DXT3: u32 = 0x3354_5844;
pub const FOURCC_DXT5: u32 = 0x3554_5844;
pub const FOURCC_ETC1: u32 = 0x3143_5445;

/// Human-readable format label (port of `RwChunk.getFormatName`).
pub fn format_name(raster_format: u32, d3d_format: u32, depth: u32) -> String {
    match d3d_format {
        FOURCC_DXT1 => "DXT1".to_string(),
        FOURCC_DXT3 => "DXT3".to_string(),
        FOURCC_DXT5 => "DXT5".to_string(),
        FOURCC_ETC1 => "ETC1".to_string(),
        _ => {
            if raster_format & FORMAT_PAL8 != 0 {
                "Paletted 8-bit".to_string()
            } else if raster_format & FORMAT_PAL4 != 0 {
                "Paletted 4-bit".to_string()
            } else if raster_format & FORMAT_8888 != 0 || depth == 32 {
                "RGBA 32-bit".to_string()
            } else if raster_format & FORMAT_888 != 0 || depth == 24 {
                "RGB 24-bit".to_string()
            } else if raster_format & FORMAT_565 != 0 {
                "RGB 565".to_string()
            } else if raster_format & FORMAT_1555 != 0 {
                "RGBA 1555".to_string()
            } else if raster_format & FORMAT_4444 != 0 {
                "RGBA 4444".to_string()
            } else {
                format!("Raw ({depth}-bit)")
            }
        }
    }
}

#[derive(Clone, Debug)]
pub struct TxdTexture {
    pub name: String,
    pub mask_name: String,
    pub width: usize,
    pub height: usize,
    pub depth: u32,
    pub raster_format: u32,
    pub d3d_format: u32,
    pub filter_flags: u32,
    pub platform_id: u32,
    /// Encoded level-0 data.
    pub raw_pixels: Vec<u8>,
    pub palette: Option<Vec<u8>>,
    pub mipmap_count: usize,
    /// Encoded data of mipmap levels 1..n.
    pub mip_levels: Vec<Vec<u8>>,
}

impl Default for TxdTexture {
    fn default() -> Self {
        Self {
            name: String::new(),
            mask_name: String::new(),
            width: 0,
            height: 0,
            depth: 0,
            raster_format: FORMAT_8888,
            d3d_format: 0,
            filter_flags: 0x1102,
            platform_id: PLATFORM_D3D9,
            raw_pixels: Vec::new(),
            palette: None,
            mipmap_count: 1,
            mip_levels: Vec::new(),
        }
    }
}

#[derive(Clone, Debug)]
pub struct TxdFile {
    pub textures: Vec<TxdTexture>,
    pub rw_version: u32,
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }
    fn i32(&mut self) -> i32 {
        let v = i32::from_le_bytes(
            self.buf[self.pos..self.pos + 4]
                .try_into()
                .unwrap_or([0; 4]),
        );
        self.pos += 4;
        v
    }
    fn u16(&mut self) -> u16 {
        let v = u16::from_le_bytes(
            self.buf[self.pos..self.pos + 2]
                .try_into()
                .unwrap_or([0; 2]),
        );
        self.pos += 2;
        v
    }
    fn u8(&mut self) -> u8 {
        let v = if self.pos < self.buf.len() {
            self.buf[self.pos]
        } else {
            0
        };
        self.pos += 1;
        v
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        let take = n.min(self.remaining());
        let out = self.buf[self.pos..self.pos + take].to_vec();
        self.pos += take;
        out
    }
    fn seek(&mut self, pos: usize) {
        self.pos = pos.min(self.buf.len());
    }
}

fn latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

fn clean_name(raw: &str) -> String {
    match raw.find('\0') {
        Some(i) => raw[..i].to_string(),
        None => raw.to_string(),
    }
}

/// Header fields of one TEXTURE_NATIVE entry, valid up to (and including)
/// the level-0 data size. Split from the payload reader so listing paths can
/// test parseability cheaply: the only two failure sites of an entry live in
/// the header (the dimension cap and a negative data size) — everything
/// after that is infallible by construction.
struct NativeEntryHeader {
    /// End of the inner struct chunk (upper bound of the mip loop).
    inner_end: usize,
    platform_id: u32,
    filter_flags: u32,
    name: String,
    mask_name: String,
    raster_format: u32,
    d3d_format: u32,
    width: usize,
    height: usize,
    depth: u32,
    mipmap_count: usize,
    palette: Option<Vec<u8>>,
    /// Validated level-0 data size (was ≥ 0 on the wire).
    data_size: u32,
}

/// Reads and validates the fixed part of a TEXTURE_NATIVE entry. On success
/// the reader is positioned right before the level-0 pixel data; on failure
/// it sits at an arbitrary offset inside the entry (callers re-seek via the
/// native chunk size).
fn parse_native_header(r: &mut Reader) -> Result<NativeEntryHeader, String> {
    let data_len = r.buf.len();
    let _inner_id = r.i32();
    let inner_size = r.i32();
    let _inner_version = r.i32();
    let inner_end = r.pos + inner_size.max(0) as usize;

    let platform_id = r.i32() as u32;
    let filter_flags = r.i32() as u32;
    let name = clean_name(&latin1(&r.bytes(32)));
    let mask_name = clean_name(&latin1(&r.bytes(32)));
    let raster_format = r.i32() as u32;
    let d3d_format = r.i32() as u32;
    let width = r.u16() as usize;
    let height = r.u16() as usize;
    // Cap texture dimensions: real game TXDs are ≤ 2048×2048 (mobile mods
    // sometimes 4096). Same bound as texdb::MAX_TEXDB_DIM — one shared
    // constant keeps every parser honest. A malicious TXD with
    // width=height=65535 (u16::MAX) would request a 16 GiB allocation
    // downstream in decode_dxt1/decode_etc1 → process abort (panic=abort).
    if width > crate::texdb::MAX_TEXDB_DIM || height > crate::texdb::MAX_TEXDB_DIM {
        return Err(format!(
            "Dimensi tekstur TXD tidak masuk akal: {width}x{height}"
        ));
    }
    let depth = r.u8() as u32;
    let mipmap_count = r.u8() as usize;
    let _raster_type = r.u8();
    let _compression = r.u8();

    let has_palette = raster_format & FORMAT_PAL8 != 0 || depth == 8;
    // Same presence rule as the Kotlin parser: the palette counts as present
    // only when all 1024 bytes actually fit in the file.
    let palette = if has_palette && r.pos + 1024 <= data_len {
        Some(r.bytes(1024))
    } else {
        None
    };

    let data_size = r.i32();
    if data_size < 0 {
        return Err("Format TXD rusak: ukuran data tekstur negatif".to_string());
    }

    Ok(NativeEntryHeader {
        inner_end,
        platform_id,
        filter_flags,
        name,
        mask_name,
        raster_format,
        d3d_format,
        width,
        height,
        depth,
        mipmap_count,
        palette,
        data_size: data_size as u32,
    })
}

/// Copies the pixel payload after a validated header. Infallible by
/// construction: the data size is clamped to the bytes actually remaining
/// (DoS cap, see below), mip sizes are bounds-checked, and the trailing
/// extension walk is clamped — mirroring the Kotlin parser, which also
/// degrades to zero-filled/truncated data in exactly these spots.
fn parse_native_payload(
    r: &mut Reader,
    h: NativeEntryHeader,
    native_end: usize,
    slot_index: usize,
) -> TxdTexture {
    let data_len = r.buf.len();
    // Cap the allocation at the bytes actually remaining in the buffer.
    // A malicious/corrupt TXD can claim `data_size` up to i32::MAX (≈2 GiB)
    // while the file contains only a few KB after the size field. Without
    // this cap, `vec![0u8; data_size as usize]` would attempt a multi-GiB
    // allocation and abort the process (panic=abort). Real TXDs are
    // well-formed so `data_size == remaining` and behavior is unchanged.
    let actual = (h.data_size as usize).min(r.remaining());
    let mut raw_pixels = vec![0u8; actual];
    raw_pixels.copy_from_slice(&r.bytes(actual));

    let mut mip_levels = Vec::new();
    let mut level_index = 1;
    while level_index < h.mipmap_count && r.pos + 4 <= h.inner_end && r.pos + 4 <= data_len {
        let level_size = r.i32();
        if level_size < 0
            || r.pos + level_size as usize > h.inner_end
            || r.pos + level_size as usize > data_len
        {
            break;
        }
        mip_levels.push(r.bytes(level_size as usize));
        level_index += 1;
    }

    if r.pos < h.inner_end {
        r.seek(h.inner_end);
    }
    if r.pos < native_end {
        if r.remaining() >= 12 {
            let _ext_id = r.i32();
            let ext_size = r.i32();
            let _ext_ver = r.i32();
            let next = (r.pos as i64 + ext_size as i64).min(native_end as i64);
            r.seek(next.max(0) as usize);
        } else {
            r.seek(native_end);
        }
    }

    TxdTexture {
        name: if h.name.is_empty() {
            format!("texture_{slot_index}")
        } else {
            h.name
        },
        mask_name: h.mask_name,
        width: h.width,
        height: h.height,
        depth: h.depth,
        raster_format: h.raster_format,
        d3d_format: h.d3d_format,
        filter_flags: h.filter_flags,
        platform_id: h.platform_id,
        raw_pixels,
        palette: h.palette,
        mipmap_count: h.mipmap_count.max(1),
        mip_levels,
    }
}

/// Parses one TEXTURE_NATIVE entry (the native chunk header is already
/// consumed; `r` is positioned at the inner struct chunk). Shared verbatim
/// by `parse` and the targeted single-texture scan `decode_entry_pixels_at`.
fn parse_native_entry(
    r: &mut Reader,
    slot_index: usize,
    native_size: i32,
) -> Result<TxdTexture, String> {
    let native_start = r.pos;
    let native_end = (native_start + native_size.max(0) as usize).min(r.buf.len());
    let header = parse_native_header(r)?;
    Ok(parse_native_payload(r, header, native_end, slot_index))
}

fn parse_header(data: &[u8]) -> Result<(Reader<'_>, usize, u32), String> {
    let mut r = Reader { buf: data, pos: 0 };
    if r.remaining() < 12 {
        return Err("Format TXD rusak: ukuran file terlalu kecil".to_string());
    }
    let root_id = r.i32() as u32;
    let _root_size = r.i32();
    let rw_version = r.i32() as u32;
    if root_id != CHUNK_TEXTURE_DICTIONARY {
        return Err(format!(
            "Bukan file TXD RenderWare yang valid (Root Chunk: 0x{root_id:04X})"
        ));
    }
    let struct_id = r.i32() as u32;
    let struct_size = r.i32();
    let _struct_version = r.i32();
    if struct_id != CHUNK_STRUCT || struct_size < 4 {
        return Err("Header TXD struct tidak valid".to_string());
    }
    let count = r.u16() as usize;
    let _device_id = r.u16();
    let remaining_struct = struct_size - 4;
    if remaining_struct > 0 {
        r.seek(r.pos + remaining_struct as usize);
    }
    Ok((r, count, rw_version))
}

/// Parses a whole TXD file (port of `TxdParser.parse`). All-or-nothing per
/// dictionary: one unparseable entry fails the file. This strictness is the
/// contract for every write path (a rebuild that silently drops entries
/// would lose data); listing/preview use `parse_lenient` instead.
pub fn parse(data: &[u8]) -> Result<TxdFile, String> {
    let (mut r, count, rw_version) = parse_header(data)?;
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        if r.remaining() < 12 {
            break;
        }
        let before = r.pos;
        let native_id = r.i32() as u32;
        let native_size = r.i32();
        let _native_version = r.i32();
        let native_start = r.pos;
        let native_end = (native_start + native_size.max(0) as usize).min(data.len());
        if native_id != CHUNK_TEXTURE_NATIVE {
            r.seek(native_end);
        } else {
            entries.push(parse_native_entry(&mut r, i, native_size)?);
        }
        // A crafted size field pointing at/before its own header would make
        // the walk revisit the same bytes forever — stop instead of hanging
        // the caller (previously a negative size could even seek backward).
        if r.pos <= before {
            break;
        }
    }
    Ok(TxdFile {
        textures: entries,
        rw_version,
    })
}

/// Like `parse`, but a TEXTURE_NATIVE entry whose header cannot be parsed is
/// skipped instead of failing the whole dictionary. For listing/preview only:
/// write paths keep the strict all-or-nothing `parse`, because a rebuild that
/// silently drops entries would lose data. The file header itself must still
/// be valid — a broken root/struct chunk stays a hard error, so the UI can
/// tell "not a TXD" apart from "one bad texture".
pub fn parse_lenient(data: &[u8]) -> Result<TxdFile, String> {
    let (mut r, count, rw_version) = parse_header(data)?;
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        if r.remaining() < 12 {
            break;
        }
        let before = r.pos;
        let native_id = r.i32() as u32;
        let native_size = r.i32();
        let _native_version = r.i32();
        let native_start = r.pos;
        let native_end = (native_start + native_size.max(0) as usize).min(data.len());
        if native_id != CHUNK_TEXTURE_NATIVE {
            r.seek(native_end);
        } else {
            match parse_native_header(&mut r) {
                Ok(header) => entries.push(parse_native_payload(&mut r, header, native_end, i)),
                // Unparseable entry: skip its whole native chunk and keep
                // listing the healthy majority.
                Err(_) => r.seek(native_end),
            }
        }
        // Same forward-progress guarantee as `parse`.
        if r.pos <= before {
            break;
        }
    }
    Ok(TxdFile {
        textures: entries,
        rw_version,
    })
}

/// Targeted single-texture scan: walks only the chunk headers and copies
/// just texture `index`'s payload, instead of materializing every entry's
/// pixel data like `parse` does. The index space matches `parse_lenient`
/// (an entry whose header cannot be parsed consumes no index), so results
/// equal `parse_lenient(path)?.textures.get(index)` + `decode_pixels` for
/// well-formed files, at a fraction of the cost on large dictionaries.
pub fn decode_entry_pixels_at(path: &std::path::Path, index: usize) -> Option<Vec<u32>> {
    let data = std::fs::read(path).ok()?;
    let (mut r, count, _rw_version) = parse_header(&data).ok()?;
    let mut produced = 0usize;
    while produced < count {
        if r.remaining() < 12 {
            break;
        }
        let before = r.pos;
        let native_id = r.i32() as u32;
        let native_size = r.i32();
        let _native_version = r.i32();
        let native_start = r.pos;
        let native_end = (native_start + native_size.max(0) as usize).min(data.len());
        if native_id != CHUNK_TEXTURE_NATIVE {
            r.seek(native_end);
        } else {
            // Unparseable headers consume no index (same space as
            // parse_lenient); both paths below skip the whole chunk.
            if let Ok(header) = parse_native_header(&mut r) {
                if produced == index {
                    let entry = parse_native_payload(&mut r, header, native_end, produced);
                    return decode_pixels(&entry);
                }
                produced += 1;
            }
            // Not the requested texture (or unparseable): skip its whole
            // native chunk — the position a full `parse_native_entry` would
            // land on after walking the entry.
            r.seek(native_end);
        }
        // Same forward-progress guarantee as `parse`.
        if r.pos <= before {
            break;
        }
    }
    None
}

pub fn parse_file(path: &std::path::Path) -> Result<TxdFile, String> {
    let data = std::fs::read(path)
        .map_err(|e| format!("File TXD tidak ditemukan: {} ({e})", path.display()))?;
    parse(&data)
}

/// Lenient counterpart of `parse_file` (see `parse_lenient`).
pub fn parse_file_lenient(path: &std::path::Path) -> Result<TxdFile, String> {
    let data = std::fs::read(path)
        .map_err(|e| format!("File TXD tidak ditemukan: {} ({e})", path.display()))?;
    parse_lenient(&data)
}

// ---------------------------------------------------------------------------
// Building
// ---------------------------------------------------------------------------

fn write_header(out: &mut Vec<u8>, kind: u32, size: usize, version: u32) {
    out.extend_from_slice(&(kind as i32).to_le_bytes());
    out.extend_from_slice(&(size as i32).to_le_bytes());
    out.extend_from_slice(&(version as i32).to_le_bytes());
}

fn latin1_bytes(s: &str) -> Vec<u8> {
    s.chars()
        .map(|c| {
            let v = c as u32;
            if v <= 0xFF {
                v as u8
            } else {
                b'?'
            }
        })
        .collect()
}

fn build_native_chunk(entry: &TxdTexture, rw_version: u32) -> Vec<u8> {
    let has_palette = entry
        .palette
        .as_ref()
        .map(|p| p.len() == 1024)
        .unwrap_or(false);
    let palette_size = if has_palette { 1024 } else { 0 };
    let mip_data_size: usize = entry.mip_levels.iter().map(|l| 4 + l.len()).sum();
    let inner_size = 88 + palette_size + 4 + entry.raw_pixels.len() + mip_data_size;
    let native_size = 12 + inner_size + 12;

    let mut out = Vec::with_capacity(native_size + 24);
    write_header(&mut out, CHUNK_TEXTURE_NATIVE, native_size, rw_version);
    write_header(&mut out, CHUNK_STRUCT, inner_size, rw_version);

    let mut st = Vec::with_capacity(88);
    st.extend_from_slice(&(entry.platform_id as i32).to_le_bytes());
    st.extend_from_slice(&(entry.filter_flags as i32).to_le_bytes());
    let mut name_bytes = [0u8; 32];
    let src = latin1_bytes(&entry.name);
    let n = src.len().min(31);
    name_bytes[..n].copy_from_slice(&src[..n]);
    st.extend_from_slice(&name_bytes);
    let mut mask_bytes = [0u8; 32];
    let src = latin1_bytes(&entry.mask_name);
    let n = src.len().min(31);
    mask_bytes[..n].copy_from_slice(&src[..n]);
    st.extend_from_slice(&mask_bytes);
    st.extend_from_slice(&(entry.raster_format as i32).to_le_bytes());
    st.extend_from_slice(&(entry.d3d_format as i32).to_le_bytes());
    // Dimension sources are all capped (txd::parse and JNI/PNG entry points
    // ≤ 16384), so the u16 disk fields must never wrap. Guard the invariant.
    debug_assert!(
        entry.width <= u16::MAX as usize && entry.height <= u16::MAX as usize,
        "texture dimension exceeds the u16 disk field"
    );
    st.extend_from_slice(&(entry.width as u16).to_le_bytes());
    st.extend_from_slice(&(entry.height as u16).to_le_bytes());
    st.push(entry.depth as u8);
    st.push((1 + entry.mip_levels.len()) as u8);
    st.push(0x04);
    st.push(0);
    out.extend_from_slice(&st);

    if has_palette {
        out.extend_from_slice(entry.palette.as_ref().unwrap());
    }
    out.extend_from_slice(&(entry.raw_pixels.len() as i32).to_le_bytes());
    out.extend_from_slice(&entry.raw_pixels);
    for level in &entry.mip_levels {
        out.extend_from_slice(&(level.len() as i32).to_le_bytes());
        out.extend_from_slice(level);
    }
    write_header(&mut out, CHUNK_EXTENSION, 0, rw_version);
    out
}

/// The TXD struct chunk stores the texture count in a u16 field, so a
/// dictionary can never hold more than 65535 entries (same limit as the
/// reference modding tooling). Writes enforce this instead of wrapping.
pub const MAX_TXD_ENTRIES: usize = u16::MAX as usize;

/// Rejects dictionary sizes the u16 on-disk count field cannot represent.
fn check_entry_count(n: usize) -> Result<(), String> {
    if n > MAX_TXD_ENTRIES {
        Err(format!(
            "Jumlah tekstur melebihi batas format TXD ({MAX_TXD_ENTRIES})"
        ))
    } else {
        Ok(())
    }
}

/// Serializes textures into a TXD file (port of `TxdBuilder.buildToBytes`).
///
/// The struct-chunk count field is u16: callers must keep
/// `entries.len() <= MAX_TXD_ENTRIES` (production write paths go through
/// `check_entry_count`; the debug assert catches dev-tool misuse).
pub fn build_to_bytes(entries: &[TxdTexture], rw_version: u32) -> Vec<u8> {
    debug_assert!(
        entries.len() <= MAX_TXD_ENTRIES,
        "TXD entry count exceeds the u16 disk field"
    );
    let native_chunks: Vec<Vec<u8>> = entries
        .iter()
        .map(|e| build_native_chunk(e, rw_version))
        .collect();
    let total_native: usize = native_chunks.iter().map(|c| c.len()).sum();
    let root_size = 16 + total_native;

    let mut out = Vec::with_capacity(root_size + 12);
    write_header(&mut out, CHUNK_TEXTURE_DICTIONARY, root_size, rw_version);
    write_header(&mut out, CHUNK_STRUCT, 4, rw_version);
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    for chunk in native_chunks {
        out.extend_from_slice(&chunk);
    }
    out
}

// ---------------------------------------------------------------------------
// Decoding (port of `TxdParser.decodeBitmap` + `DxtDecoder`)
// ---------------------------------------------------------------------------

/// DXT3 decode (explicit 4-bit alpha + DXT1 color), port of the Kotlin decoder.
pub fn decode_dxt3(data: &[u8], width: usize, height: usize) -> Vec<u32> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut pixels = vec![0u32; width * height];
    // Large textures: decode block-row bands on worker threads (deterministic,
    // identical output; threshold 16384 blocks = 512x512, decode work per
    // block is small).
    if blocks_x * blocks_y < 16384 {
        decode_dxt3_band(data, width, height, blocks_x, 0, blocks_y, &mut pixels);
        return pixels;
    }
    let workers = crate::parallel::inner_workers(4);
    let rows_per_band = blocks_y.div_ceil(workers).max(4);
    std::thread::scope(|s| {
        let mut rest: &mut [u32] = &mut pixels;
        let mut by_start = 0usize;
        while by_start < blocks_y {
            let by_end = (by_start + rows_per_band).min(blocks_y);
            let y0 = (by_start * 4).min(height);
            let y1 = (by_end * 4).min(height);
            let (cur, tail) = rest.split_at_mut((y1 - y0) * width);
            rest = tail;
            s.spawn(move || {
                decode_dxt3_band(data, width, height, blocks_x, by_start, by_end, cur);
            });
            by_start = by_end;
        }
    });
    pixels
}

/// Decodes block-rows [by_start, by_end) into `pixels` (pixel rows from y0).
fn decode_dxt3_band(
    data: &[u8],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
) {
    use crate::codec::dxt::{interpolate_color, rgb565_to_argb};
    let y0 = (by_start * 4).min(height);
    let band_y = y0 * width;
    let mut offset = by_start * blocks_x * 16;
    let aligned = width.is_multiple_of(4) && height.is_multiple_of(4);
    // Aligned bands take the vectorized whole-band path (bit-identical to
    // the scalar expansion below — differential-tested per random blocks).
    if aligned
        && crate::codec::simd::dxt::dxt3_band(
            data, width, blocks_x, by_start, by_end, pixels, band_y,
        )
    {
        return;
    }
    let mut palette = [0u32; 4];
    for by in by_start..by_end {
        for bx in 0..blocks_x {
            if offset + 16 > data.len() {
                return;
            }
            let alpha_offset = offset;
            let color_offset = offset + 8;
            let c0 = u16::from_le_bytes(data[color_offset..color_offset + 2].try_into().unwrap());
            let c1 =
                u16::from_le_bytes(data[color_offset + 2..color_offset + 4].try_into().unwrap());
            palette[0] = rgb565_to_argb(c0 as u32, 255);
            palette[1] = rgb565_to_argb(c1 as u32, 255);
            palette[2] = interpolate_color(palette[0], palette[1], 2, 1, 3);
            palette[3] = interpolate_color(palette[0], palette[1], 1, 2, 3);
            let lookup =
                u32::from_le_bytes(data[color_offset + 4..color_offset + 8].try_into().unwrap());
            for py in 0..4 {
                let y = by * 4 + py;
                if y >= height {
                    continue;
                }
                for px in 0..4 {
                    let x = bx * 4 + px;
                    if x >= width {
                        continue;
                    }
                    let pixel_idx = py * 4 + px;
                    let alpha_byte = data[alpha_offset + pixel_idx / 2];
                    let a4 = if pixel_idx % 2 == 0 {
                        alpha_byte & 0x0F
                    } else {
                        (alpha_byte >> 4) & 0x0F
                    };
                    let alpha = (a4 as u32 * 255) / 15;
                    let code = ((lookup >> (pixel_idx * 2)) & 0x03) as usize;
                    let rgb = palette[code] & 0x00FF_FFFF;
                    pixels[(y * width + x) - band_y] = (alpha << 24) | rgb;
                }
            }
            offset += 16;
        }
    }
}

fn decode_paletted8(entry: &TxdTexture) -> Vec<u32> {
    let (width, height) = (entry.width, entry.height);
    let mut pixels = vec![0u32; width * height];
    let palette = match &entry.palette {
        Some(p) => p,
        None => return pixels,
    };
    for i in 0..pixels.len().min(entry.raw_pixels.len()) {
        let idx = entry.raw_pixels[i] as usize;
        let pal_offset = idx * 4;
        if pal_offset + 3 < palette.len() {
            let r = palette[pal_offset] as u32;
            let g = palette[pal_offset + 1] as u32;
            let b = palette[pal_offset + 2] as u32;
            let a = palette[pal_offset + 3] as u32;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
    pixels
}

fn decode_rgba32(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 4);
    for (i, out) in pixels[..limit].iter_mut().enumerate() {
        let p = i * 4;
        let b = raw[p] as u32;
        let g = raw[p + 1] as u32;
        let r = raw[p + 2] as u32;
        let a = raw[p + 3] as u32;
        *out = (a << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgb24(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 3);
    for (i, out) in pixels[..limit].iter_mut().enumerate() {
        let p = i * 3;
        let b = raw[p] as u32;
        let g = raw[p + 1] as u32;
        let r = raw[p + 2] as u32;
        *out = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgb565(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 2);
    for (i, out) in pixels[..limit].iter_mut().enumerate() {
        let p = i * 2;
        let c = (raw[p] as u32) | ((raw[p + 1] as u32) << 8);
        let r = (((c >> 11) & 0x1F) * 255 + 15) / 31;
        let g = (((c >> 5) & 0x3F) * 255 + 31) / 63;
        let b = ((c & 0x1F) * 255 + 15) / 31;
        *out = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgba1555(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 2);
    for (i, out) in pixels[..limit].iter_mut().enumerate() {
        let p = i * 2;
        let c = (raw[p] as u32) | ((raw[p + 1] as u32) << 8);
        let a = if c & 0x8000 != 0 { 255 } else { 0 };
        let r = (((c >> 10) & 0x1F) * 255 + 15) / 31;
        let g = (((c >> 5) & 0x1F) * 255 + 15) / 31;
        let b = ((c & 0x1F) * 255 + 15) / 31;
        *out = (a << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgba4444(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 2);
    for (i, out) in pixels[..limit].iter_mut().enumerate() {
        let p = i * 2;
        let c = (raw[p] as u32) | ((raw[p + 1] as u32) << 8);
        let a = ((c >> 12) & 0x0F) * 17;
        let r = ((c >> 8) & 0x0F) * 17;
        let g = ((c >> 4) & 0x0F) * 17;
        let b = (c & 0x0F) * 17;
        *out = (a << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

/// Decodes a texture's level-0 pixels to ARGB (port of `TxdParser.decodeBitmap`).
pub fn decode_pixels(entry: &TxdTexture) -> Option<Vec<u32>> {
    let (width, height) = (entry.width, entry.height);
    if width == 0 || height == 0 || entry.raw_pixels.is_empty() {
        return None;
    }
    let raw = &entry.raw_pixels;
    let pixels = match entry.d3d_format {
        FOURCC_DXT1 => crate::codec::dxt::decode_dxt1(raw, width, height),
        FOURCC_DXT3 => decode_dxt3(raw, width, height),
        FOURCC_DXT5 => crate::codec::dxt::decode_dxt5(raw, width, height),
        FOURCC_ETC1 => crate::codec::etc1::decode_etc1(raw, width, height),
        _ => {
            if entry.raster_format & FORMAT_PAL8 != 0 || entry.depth == 8 {
                decode_paletted8(entry)
            } else if entry.raster_format & FORMAT_8888 != 0 || entry.depth == 32 {
                decode_rgba32(raw, width, height)
            } else if entry.raster_format & FORMAT_888 != 0 || entry.depth == 24 {
                decode_rgb24(raw, width, height)
            } else if entry.raster_format & FORMAT_565 != 0 {
                decode_rgb565(raw, width, height)
            } else if entry.raster_format & FORMAT_1555 != 0 {
                decode_rgba1555(raw, width, height)
            } else if entry.raster_format & FORMAT_4444 != 0 {
                decode_rgba4444(raw, width, height)
            } else if raw.len() >= width * height * 4 {
                decode_rgba32(raw, width, height)
            } else {
                return None;
            }
        }
    };
    Some(pixels)
}

// ---------------------------------------------------------------------------
// Resampling (exact port of the Kotlin `MipmapGenerator`)
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MipFilter {
    Nearest,
    Box,
    Bilinear,
}

impl MipFilter {
    pub fn from_ordinal(v: i32) -> Self {
        match v {
            0 => MipFilter::Nearest,
            1 => MipFilter::Box,
            _ => MipFilter::Bilinear,
        }
    }
}

/// Resamples to exactly `dw` x `dh` (port of `MipmapGenerator.resample`).
///
/// Routes to the shared optimized kernels in [`crate::mipmap::resample_px`]:
/// precomputed per-axis tables instead of per-pixel f64 divide/floor, the
/// vectorized exact-2:1 Box 2x2 kernel (SSSE3/NEON), and row-band
/// parallelism for large outputs. The per-pixel math of those kernels is
/// expression-for-expression identical to the former inline samplers (same
/// taps, same weight products, same summation order, same truncation toward
/// zero), so outputs are bit-identical; `resample_matches_reference_random`
/// and `resample_2x1_large_matches_reference` lock this against the original
/// per-pixel algorithm, kept verbatim in the tests.
pub fn resample(
    src: &[u32],
    sw: usize,
    sh: usize,
    dw: usize,
    dh: usize,
    filter: MipFilter,
) -> Vec<u32> {
    let filter = match filter {
        MipFilter::Nearest => crate::mipmap::MipFilter::Nearest,
        MipFilter::Box => crate::mipmap::MipFilter::Box,
        MipFilter::Bilinear => crate::mipmap::MipFilter::Bilinear,
    };
    crate::mipmap::resample_px(src, sw, sh, dw, dh, filter)
}

/// One halving step (port of `MipmapGenerator.downscaleHalf`).
pub fn downscale_half(
    src: &[u32],
    sw: usize,
    sh: usize,
    filter: MipFilter,
) -> (Vec<u32>, usize, usize) {
    let dw = (sw / 2).max(1);
    let dh = (sh / 2).max(1);
    (resample(src, sw, sh, dw, dh, filter), dw, dh)
}

/// Dimensions after fitting inside `max_dimension` (port of `resizedDimensions`).
pub fn resized_dimensions(width: usize, height: usize, max_dimension: usize) -> (usize, usize) {
    let longest = width.max(height);
    if longest <= max_dimension {
        return (width, height);
    }
    let scale = max_dimension as f64 / longest as f64;
    (
        ((width as f64 * scale) as usize).max(1),
        ((height as f64 * scale) as usize).max(1),
    )
}

/// Reduce-to-fit; the Kotlin default resample filter for this op is BILINEAR.
pub fn reduce_to_fit(
    src: &[u32],
    sw: usize,
    sh: usize,
    max_dimension: usize,
) -> (Vec<u32>, usize, usize) {
    let (nw, nh) = resized_dimensions(sw, sh, max_dimension);
    if nw == sw && nh == sh {
        return (src.to_vec(), sw, sh);
    }
    (resample(src, sw, sh, nw, nh, MipFilter::Bilinear), nw, nh)
}

/// Full mip chain below the source (ports of `generateChain`). Each level
/// is produced from the previous one without an extra per-level clone.
pub fn generate_chain(
    src: &[u32],
    sw: usize,
    sh: usize,
    filter: MipFilter,
) -> Vec<(Vec<u32>, usize, usize)> {
    let mut levels: Vec<(Vec<u32>, usize, usize)> = Vec::new();
    loop {
        let (cur, cw, ch) = match levels.last() {
            Some((px, w, h)) => (px.as_slice(), *w, *h),
            None => (src, sw, sh),
        };
        if cw == 1 && ch == 1 {
            break;
        }
        let (next, nw, nh) = downscale_half(cur, cw, ch, filter);
        levels.push((next, nw, nh));
    }
    levels
}

// ---------------------------------------------------------------------------
// Import / replace (port of `TxdBuilder.replaceTexture` / `createEntry`)
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug)]
pub struct ImportOptions {
    pub generate_mipmaps: bool,
    pub mip_filter: MipFilter,
    /// Reduce-to-fit limit; `None` keeps the source size.
    pub max_dimension: Option<usize>,
}

impl Default for ImportOptions {
    fn default() -> Self {
        Self {
            generate_mipmaps: false,
            mip_filter: MipFilter::Box,
            max_dimension: None,
        }
    }
}

/// Encodes one pixel level: DXT1 or uncompressed BGRA (port of `encodeLevel`).
fn encode_level(pixels: &[u32], width: usize, height: usize, use_dxt1: bool) -> Vec<u8> {
    if use_dxt1 {
        return crate::codec::dxt::encode_dxt1(pixels, width, height);
    }
    let mut bgra = vec![0u8; width * height * 4];
    for (i, pixel) in pixels.iter().enumerate() {
        let p = i * 4;
        bgra[p] = (pixel & 0xFF) as u8;
        bgra[p + 1] = ((pixel >> 8) & 0xFF) as u8;
        bgra[p + 2] = ((pixel >> 16) & 0xFF) as u8;
        bgra[p + 3] = ((pixel >> 24) & 0xFF) as u8;
    }
    bgra
}

/// Replaces an entry's pixels (port of `TxdBuilder.replaceTexture`).
/// `as_dxt1`: None preserves the entry's format family (DXT1 stays DXT1,
/// everything else becomes uncompressed 32-bit).
pub fn replace_texture(
    entry: &mut TxdTexture,
    pixels: &[u32],
    width: usize,
    height: usize,
    as_dxt1: Option<bool>,
    options: &ImportOptions,
) {
    let (mut src, mut sw, mut sh) = (pixels.to_vec(), width, height);
    if let Some(max_dim) = options.max_dimension {
        let (r, rw, rh) = reduce_to_fit(&src, sw, sh, max_dim);
        src = r;
        sw = rw;
        sh = rh;
    }
    entry.width = sw;
    entry.height = sh;

    let use_dxt1 = as_dxt1.unwrap_or(entry.d3d_format == FOURCC_DXT1);
    entry.raw_pixels = encode_level(&src, sw, sh, use_dxt1);
    if use_dxt1 {
        entry.d3d_format = FOURCC_DXT1;
        entry.raster_format = FORMAT_565;
        entry.depth = 16;
    } else {
        entry.d3d_format = 0;
        entry.raster_format = FORMAT_8888;
        entry.depth = 32;
        entry.palette = None;
    }
    entry.mip_levels = if options.generate_mipmaps {
        generate_chain(&src, sw, sh, options.mip_filter)
            .iter()
            .map(|(px, w, h)| encode_level(px, *w, *h, use_dxt1))
            .collect()
    } else {
        Vec::new()
    };
    entry.mipmap_count = 1 + entry.mip_levels.len();
}

/// Creates a new entry from pixels (port of `TxdBuilder.createEntry`).
/// With `as_dxt1` None the encoding is automatic: DXT1 unless the image has
/// smooth (non-binary) alpha.
pub fn create_entry(
    name: &str,
    pixels: &[u32],
    width: usize,
    height: usize,
    as_dxt1: Option<bool>,
    options: &ImportOptions,
) -> TxdTexture {
    let use_dxt1 = as_dxt1.unwrap_or_else(|| {
        !pixels.iter().any(|p| {
            let alpha = (p >> 24) & 0xFF;
            (1..=254).contains(&alpha)
        })
    });
    let mut entry = TxdTexture {
        name: name.to_string(),
        width,
        height,
        depth: if use_dxt1 { 16 } else { 32 },
        ..TxdTexture::default()
    };
    replace_texture(&mut entry, pixels, width, height, Some(use_dxt1), options);
    entry
}

/// `name`, `name_1`, `name_2`, ... until free (port of `TextureNames.uniqueName`).
pub fn unique_name(existing: &[String], desired: &str) -> String {
    let taken: std::collections::HashSet<String> =
        existing.iter().map(|n| n.to_lowercase()).collect();
    if !taken.contains(&desired.to_lowercase()) {
        return desired.to_string();
    }
    let mut index = 1;
    loop {
        let candidate = format!("{desired}_{index}");
        if !taken.contains(&candidate.to_lowercase()) {
            return candidate;
        }
        index += 1;
    }
}

// ---------------------------------------------------------------------------
// File-level operations used by the JNI layer
// ---------------------------------------------------------------------------

/// Wire lines for the editor listing: header, then one line per texture.
pub fn list_lines(txd: &TxdFile) -> Vec<String> {
    let mut lines = vec![format!("OK\t{}\t{}", txd.rw_version, txd.textures.len())];
    for e in &txd.textures {
        lines.push(format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            e.name,
            e.mask_name,
            e.width,
            e.height,
            e.depth,
            e.raster_format,
            e.d3d_format,
            e.filter_flags,
            e.platform_id,
            e.mipmap_count,
            format_name(e.raster_format, e.d3d_format, e.depth)
        ));
    }
    lines
}

/// Batch-replaces textures by index and rewrites the file in place.
pub fn replace_in_file(
    path: &std::path::Path,
    indices: &[usize],
    images: &[(Vec<u32>, usize, usize)],
    as_dxt1: Option<bool>,
    options: &ImportOptions,
) -> Result<String, String> {
    let mut txd = parse_file(path)?;
    if indices.len() != images.len() {
        return Err("Jumlah indeks dan gambar tidak cocok".to_string());
    }
    for (k, &idx) in indices.iter().enumerate() {
        let entry = txd
            .textures
            .get_mut(idx)
            .ok_or_else(|| "Indeks tekstur di luar jangkauan".to_string())?;
        let (px, w, h) = &images[k];
        // Validate before the encoders index pixels[y * width + x]: a bad
        // (w, h, len) triple must be a clean error, not an OOB abort
        // (panic=abort). Mirrors the JNI-side image_dims_ok contract.
        if *w == 0 || *h == 0 || px.len() != w.checked_mul(*h).unwrap_or(0) {
            return Err("Dimensi gambar tidak cocok dengan jumlah piksel".to_string());
        }
        replace_texture(entry, px, *w, *h, as_dxt1, options);
    }
    let bytes = build_to_bytes(&txd.textures, txd.rw_version);
    crate::fsutil::write_atomic(path, &bytes).map_err(|e| e.to_string())?;
    Ok(format!("{} tekstur diganti", indices.len()))
}

/// Conflict policy for batch adds (TXD Tool Import Wizard).
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ConflictPolicy {
    JustAdd,
    Overwrite,
}

/// Batch-adds textures from pixels and rewrites the file in place.
/// Returns (added, overwritten). Per-image semantics mirror the old
/// ViewModel loop: existing name + Overwrite replaces in place; existing
/// name + JustAdd creates a uniquely-named entry; new names are created
/// with automatic encoding.
pub fn add_in_file(
    path: &std::path::Path,
    names: &[String],
    images: &[(Vec<u32>, usize, usize)],
    policy: ConflictPolicy,
    options: &ImportOptions,
) -> Result<(usize, usize), String> {
    let mut txd = parse_file(path)?;
    let mut added = 0usize;
    let mut overwritten = 0usize;
    // Validate every image up front (see replace_in_file): the encoders
    // index pixels[y * width + x] and would abort on a bad triple.
    for (px, w, h) in images {
        if *w == 0 || *h == 0 || px.len() != w.checked_mul(*h).unwrap_or(0) {
            return Err("Dimensi gambar tidak cocok dengan jumlah piksel".to_string());
        }
    }
    for (k, desired) in names.iter().enumerate() {
        let (px, w, h) = &images[k];
        let existing = txd
            .textures
            .iter()
            .position(|e| e.name.eq_ignore_ascii_case(desired));
        match existing {
            Some(idx) if policy == ConflictPolicy::Overwrite => {
                replace_texture(&mut txd.textures[idx], px, *w, *h, None, options);
                overwritten += 1;
            }
            Some(_) => {
                let all_names: Vec<String> = txd.textures.iter().map(|e| e.name.clone()).collect();
                let unique = unique_name(&all_names, desired);
                txd.textures
                    .push(create_entry(&unique, px, *w, *h, None, options));
                added += 1;
            }
            None => {
                txd.textures
                    .push(create_entry(desired, px, *w, *h, None, options));
                added += 1;
            }
        }
    }
    if added + overwritten > 0 {
        // The u16 count field on disk cannot represent more entries — a
        // rebuild past 65535 would wrap silently and corrupt the header.
        check_entry_count(txd.textures.len())?;
        let bytes = build_to_bytes(&txd.textures, txd.rw_version);
        crate::fsutil::write_atomic(path, &bytes).map_err(|e| e.to_string())?;
    }
    Ok((added, overwritten))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(name: &str) -> TxdTexture {
        TxdTexture {
            name: name.to_string(),
            ..TxdTexture::default()
        }
    }

    #[test]
    fn build_parse_roundtrip_is_byte_stable() {
        let mut a = entry("alpha");
        a.width = 4;
        a.height = 4;
        a.depth = 16;
        a.raster_format = FORMAT_565;
        a.d3d_format = FOURCC_DXT1;
        a.raw_pixels = vec![0x11; 8];
        let mut b = entry("beta");
        b.mask_name = "maskb".to_string();
        b.width = 8;
        b.height = 8;
        b.depth = 32;
        b.raw_pixels = vec![0x22; 256];
        b.mip_levels = vec![vec![0x33; 64]];
        b.mipmap_count = 2;
        let bytes = build_to_bytes(&[a, b], RW_VERSION_SA);
        let parsed = parse(&bytes).expect("parse");
        assert_eq!(parsed.textures.len(), 2);
        assert_eq!(parsed.textures[0].raw_pixels, vec![0x11; 8]);
        assert_eq!(parsed.textures[1].mask_name, "maskb");
        assert_eq!(parsed.textures[1].mip_levels.len(), 1);
        assert_eq!(build_to_bytes(&parsed.textures, parsed.rw_version), bytes);
    }

    #[test]
    fn resample_box_averages_channels() {
        let src = vec![0xFF102030, 0xFF203040, 0xFF304050, 0xFF405060];
        let out = resample(&src, 2, 2, 1, 1, MipFilter::Box);
        assert_eq!(out, vec![0xFF283848]);
    }

    #[test]
    fn resample_nearest_picks_center_samples() {
        let src = vec![10, 20, 30, 40];
        let (out, w, h) = downscale_half(&src, 4, 1, MipFilter::Nearest);
        assert_eq!((w, h), (2, 1));
        assert_eq!(out, vec![20, 40]);
    }

    #[test]
    fn resample_bilinear_midpoint() {
        let src = vec![0xFF000000, 0xFF0000FE];
        let out = resample(&src, 2, 1, 1, 1, MipFilter::Bilinear);
        assert_eq!(out, vec![0xFF00007F]);
    }

    // ----- Reference: the pre-pass-11 per-pixel samplers, kept verbatim to
    // lock the routed mipmap kernels bit-for-bit against the original
    // algorithm (same taps, weights, summation order, truncation). -----

    fn ref_resample(
        src: &[u32],
        sw: usize,
        sh: usize,
        dw: usize,
        dh: usize,
        filter: MipFilter,
    ) -> Vec<u32> {
        let sample = |x: usize, y: usize| -> u32 {
            match filter {
                MipFilter::Nearest => {
                    let sx = (((2 * x + 1) * sw) / (2 * dw)).min(sw - 1);
                    let sy = (((2 * y + 1) * sh) / (2 * dh)).min(sh - 1);
                    src[sy * sw + sx]
                }
                MipFilter::Box => {
                    let x0 = (x * sw) / dw;
                    let x1 = (((x + 1) * sw) / dw).max(x0 + 1).min(sw);
                    let y0 = (y * sh) / dh;
                    let y1 = (((y + 1) * sh) / dh).max(y0 + 1).min(sh);
                    let (mut sa, mut sr, mut sg, mut sb, mut count) =
                        (0u32, 0u32, 0u32, 0u32, 0u32);
                    for sy in y0..y1 {
                        for sx in x0..x1 {
                            let p = src[sy * sw + sx];
                            sa += (p >> 24) & 0xFF;
                            sr += (p >> 16) & 0xFF;
                            sg += (p >> 8) & 0xFF;
                            sb += p & 0xFF;
                            count += 1;
                        }
                    }
                    ((sa / count) << 24) | ((sr / count) << 16) | ((sg / count) << 8) | (sb / count)
                }
                MipFilter::Bilinear => {
                    let gx = (x as f64 + 0.5) * sw as f64 / dw as f64 - 0.5;
                    let gy = (y as f64 + 0.5) * sh as f64 / dh as f64 - 0.5;
                    let x0 = (gx.floor() as i64).clamp(0, sw as i64 - 1) as usize;
                    let y0 = (gy.floor() as i64).clamp(0, sh as i64 - 1) as usize;
                    let x1 = (x0 + 1).min(sw - 1);
                    let y1 = (y0 + 1).min(sh - 1);
                    let fx = (gx - x0 as f64).clamp(0.0, 1.0);
                    let fy = (gy - y0 as f64).clamp(0.0, 1.0);
                    let c00 = src[y0 * sw + x0];
                    let c10 = src[y0 * sw + x1];
                    let c01 = src[y1 * sw + x0];
                    let c11 = src[y1 * sw + x1];
                    let mix = |channel: u32| -> u32 {
                        let v00 = ((c00 >> channel) & 0xFF) as f64;
                        let v10 = ((c10 >> channel) & 0xFF) as f64;
                        let v01 = ((c01 >> channel) & 0xFF) as f64;
                        let v11 = ((c11 >> channel) & 0xFF) as f64;
                        let top = v00 + (v10 - v00) * fx;
                        let bottom = v01 + (v11 - v01) * fx;
                        ((top + (bottom - top) * fy) as i64).clamp(0, 255) as u32
                    };
                    (mix(24) << 24) | (mix(16) << 16) | (mix(8) << 8) | mix(0)
                }
            }
        };
        let mut out = vec![0u32; dw * dh];
        for (i, or) in out.iter_mut().enumerate() {
            *or = sample(i % dw, i / dw);
        }
        out
    }

    struct R11(u32);
    impl R11 {
        fn next(&mut self) -> u32 {
            let mut x = self.0;
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            self.0 = x;
            x
        }
    }

    #[test]
    fn resample_matches_reference_random() {
        let mut r = R11(0x005E_ED11);
        let mut src: Vec<u32> = Vec::new();
        for case in 0..48u32 {
            let mut sw = 1 + (r.next() as usize % 48);
            let mut sh = 1 + (r.next() as usize % 48);
            let mut dw = 1 + (r.next() as usize % 48);
            let mut dh = 1 + (r.next() as usize % 48);
            match case % 5 {
                0 => {
                    dw = sw;
                    dh = sh; // equal dims (fast-path copy)
                }
                1 => {
                    sw = dw * 2;
                    sh = dh * 2; // exact 2:1 downscale
                }
                2 => {
                    dw = sw * 2;
                    dh = sh * 2; // exact 2x upscale
                }
                _ => {}
            }
            src.clear();
            src.extend((0..sw * sh).map(|_| r.next()));
            for f in [MipFilter::Nearest, MipFilter::Box, MipFilter::Bilinear] {
                let got = resample(&src, sw, sh, dw, dh, f);
                let want = ref_resample(&src, sw, sh, dw, dh, f);
                assert_eq!(got, want, "case {case}: {sw}x{sh}->{dw}x{dh} {f:?}");
            }
        }
    }

    #[test]
    fn resample_2x1_large_matches_reference() {
        // dst 1024x512 = 524288 px >= PARALLEL_MIN_PX_FAST_KERNEL: locks the
        // routed path through row-band dispatch + the vectorized exact-2:1
        // kernel at scale.
        let mut r = R11(0x0000_AB11);
        let (sw, sh, dw, dh) = (2048usize, 1024usize, 1024usize, 512usize);
        let src: Vec<u32> = (0..sw * sh).map(|_| r.next()).collect();
        for f in [MipFilter::Box, MipFilter::Bilinear] {
            let got = resample(&src, sw, sh, dw, dh, f);
            let want = ref_resample(&src, sw, sh, dw, dh, f);
            assert_eq!(got, want, "{f:?}");
        }
        // Odd dst width in the exact-2:1 case: SIMD pair loop + scalar tail.
        let (sw, sh, dw, dh) = (50usize, 30usize, 25usize, 15usize);
        let src: Vec<u32> = (0..sw * sh).map(|_| r.next()).collect();
        for f in [MipFilter::Box, MipFilter::Bilinear] {
            let got = resample(&src, sw, sh, dw, dh, f);
            let want = ref_resample(&src, sw, sh, dw, dh, f);
            assert_eq!(got, want, "odd dst {f:?}");
        }
    }

    #[test]
    fn chain_halves_to_1x1() {
        let src = vec![0xFF000000; 16];
        let levels = generate_chain(&src, 4, 4, MipFilter::Box);
        assert_eq!(levels.len(), 2);
        assert_eq!((levels[0].1, levels[0].2), (2, 2));
        assert_eq!((levels[1].1, levels[1].2), (1, 1));
    }

    #[test]
    fn decode_dxt3_full_alpha_red() {
        // Alpha nibbles all 0xF, color block c0=0xF800 > c1=0x001F, lookup 0.
        let mut block = vec![0xFF; 8];
        block.extend_from_slice(&[0x00, 0xF8, 0x1F, 0x00, 0, 0, 0, 0]);
        let px = decode_dxt3(&block, 4, 4);
        assert!(px.iter().all(|&p| p == 0xFFFF0000));
    }

    #[test]
    fn decode_raw_formats() {
        // Dispatch quirk inherited from the Kotlin decoder: the FORMAT_8888
        // mask (0x0500) contains the FORMAT_1555 bit (0x0100), so a canonical
        // 1555-tagged entry takes the BGRA32 branch there too — the port
        // keeps the same order and masks on purpose.
        let mut e = entry("f1555");
        e.width = 2;
        e.height = 1;
        e.depth = 16;
        e.raster_format = FORMAT_1555;
        e.raw_pixels = vec![0x00, 0x80, 0x00, 0x7C];
        assert_eq!(decode_pixels(&e).unwrap(), vec![0x7C008000, 0x00000000]);

        let mut p = entry("pal");
        p.width = 1;
        p.height = 1;
        p.depth = 8;
        p.raster_format = FORMAT_PAL8;
        p.palette = Some({
            let mut v = vec![0u8; 1024];
            v[0] = 255;
            v[3] = 255;
            v
        });
        p.raw_pixels = vec![0];
        assert_eq!(decode_pixels(&p).unwrap(), vec![0xFFFF0000]);
    }

    #[test]
    fn create_entry_auto_encoding() {
        let opaque = vec![0xFF112233; 16];
        let e = create_entry("op", &opaque, 4, 4, None, &ImportOptions::default());
        assert_eq!(e.d3d_format, FOURCC_DXT1);
        assert_eq!(e.depth, 16);
        let smooth = vec![0x80112233; 16];
        let e2 = create_entry("sm", &smooth, 4, 4, None, &ImportOptions::default());
        assert_eq!(e2.d3d_format, 0);
        assert_eq!(e2.raster_format, FORMAT_8888);
        assert_eq!(e2.raw_pixels.len(), 64);
    }

    #[test]
    fn lenient_parse_skips_unparseable_entries() {
        let mut good_a = entry("alpha");
        good_a.width = 4;
        good_a.height = 4;
        good_a.depth = 32;
        good_a.raw_pixels = vec![0x11; 64];
        // Header-only corruption: a claimed dimension above MAX_TEXDB_DIM is
        // rejected by parse_native_header before any payload is touched.
        let mut bad = entry("bad");
        bad.width = 65535;
        bad.height = 1;
        bad.depth = 32;
        bad.raw_pixels = vec![0; 4];
        let mut good_b = entry("beta");
        good_b.width = 2;
        good_b.height = 2;
        good_b.depth = 32;
        good_b.raw_pixels = vec![0x22; 16];

        let bytes = build_to_bytes(&[good_a, bad, good_b], RW_VERSION_SA);
        assert!(parse(&bytes).is_err(), "strict parse must still refuse");
        let lenient = parse_lenient(&bytes).expect("lenient parse");
        let names: Vec<&str> = lenient.textures.iter().map(|t| t.name.as_str()).collect();
        assert_eq!(names, ["alpha", "beta"]);
    }

    #[test]
    fn decode_entry_pixels_at_matches_lenient_index_space() {
        let mut good_a = entry("alpha");
        good_a.width = 2;
        good_a.height = 2;
        good_a.depth = 32;
        good_a.raw_pixels = vec![0x11; 16];
        let mut bad = entry("bad");
        bad.width = 65535;
        bad.height = 1;
        bad.depth = 32;
        bad.raw_pixels = vec![0; 4];
        let mut good_b = entry("beta");
        good_b.width = 2;
        good_b.height = 2;
        good_b.depth = 32;
        good_b.raw_pixels = vec![0x22; 16];
        let bytes = build_to_bytes(&[good_a, bad, good_b], RW_VERSION_SA);

        let path =
            std::env::temp_dir().join(format!("imgtxd-txd-lenient-{}.txd", std::process::id()));
        std::fs::write(&path, &bytes).expect("write temp txd");

        let lenient = parse_lenient(&bytes).expect("lenient parse");
        assert_eq!(
            decode_entry_pixels_at(&path, 0),
            decode_pixels(&lenient.textures[0])
        );
        // The malformed middle entry consumes no index: slot 1 is "beta".
        assert_eq!(
            decode_entry_pixels_at(&path, 1),
            decode_pixels(&lenient.textures[1])
        );
        assert_eq!(decode_entry_pixels_at(&path, 2), None);
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn entry_count_guard_enforces_u16_disk_field() {
        assert!(check_entry_count(MAX_TXD_ENTRIES - 1).is_ok());
        assert!(check_entry_count(MAX_TXD_ENTRIES).is_ok());
        assert!(check_entry_count(MAX_TXD_ENTRIES + 1).is_err());
    }

    #[test]
    fn add_in_file_rejects_beyond_u16_entry_limit() {
        // A dictionary already at the format limit must refuse one more
        // entry instead of wrapping the u16 count field on disk.
        let entries: Vec<TxdTexture> = (0..MAX_TXD_ENTRIES)
            .map(|i| {
                let mut e = entry(&format!("t{i}"));
                e.width = 2;
                e.height = 2;
                e.depth = 32;
                e.raw_pixels = vec![0; 16];
                e
            })
            .collect();
        let bytes = build_to_bytes(&entries, RW_VERSION_SA);
        let path =
            std::env::temp_dir().join(format!("imgtxd-txd-limit-{}.txd", std::process::id()));
        std::fs::write(&path, &bytes).expect("write temp txd");

        let err = add_in_file(
            &path,
            &["newone".to_string()],
            &[(vec![0xFF00_FF00; 4], 2, 2)],
            ConflictPolicy::JustAdd,
            &ImportOptions::default(),
        )
        .expect_err("must reject beyond the format limit");
        assert!(err.contains("65535"), "unexpected error: {err}");

        // The guard fires before any write: the dictionary on disk is intact.
        let after = parse(&std::fs::read(&path).expect("read back")).expect("parse");
        assert_eq!(after.textures.len(), MAX_TXD_ENTRIES);
        std::fs::remove_file(&path).ok();
    }
}
