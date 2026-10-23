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
            self.buf[self.pos..self.pos + 4].try_into().unwrap_or([0; 4]),
        );
        self.pos += 4;
        v
    }
    fn u16(&mut self) -> u16 {
        let v = u16::from_le_bytes(
            self.buf[self.pos..self.pos + 2].try_into().unwrap_or([0; 2]),
        );
        self.pos += 2;
        v
    }
    fn u8(&mut self) -> u8 {
        let v = if self.pos < self.buf.len() { self.buf[self.pos] } else { 0 };
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

/// Parses a whole TXD file (port of `TxdParser.parse`).
pub fn parse(data: &[u8]) -> Result<TxdFile, String> {
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

    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        if r.remaining() < 12 {
            break;
        }
        let native_id = r.i32() as u32;
        let native_size = r.i32();
        let _native_version = r.i32();
        if native_id != CHUNK_TEXTURE_NATIVE {
            let next = (r.pos as i64 + native_size as i64).min(data.len() as i64);
            r.seek(next.max(0) as usize);
            continue;
        }
        let native_start = r.pos;
        let native_end = (native_start + native_size.max(0) as usize).min(data.len());

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
        let depth = r.u8() as u32;
        let mipmap_count = r.u8() as usize;
        let _raster_type = r.u8();
        let _compression = r.u8();

        let has_palette = raster_format & FORMAT_PAL8 != 0 || depth == 8;
        let palette = if has_palette && r.pos + 1024 <= data.len() {
            Some(r.bytes(1024))
        } else {
            None
        };

        let data_size = r.i32();
        if data_size < 0 {
            return Err("Format TXD rusak: ukuran data tekstur negatif".to_string());
        }
        let mut raw_pixels = vec![0u8; data_size as usize];
        let to_read = (data_size as usize).min(r.remaining());
        raw_pixels[..to_read].copy_from_slice(&r.bytes(to_read));

        let mut mip_levels = Vec::new();
        let mut level_index = 1;
        while level_index < mipmap_count && r.pos + 4 <= inner_end && r.pos + 4 <= data.len() {
            let level_size = r.i32();
            if level_size < 0
                || r.pos + level_size as usize > inner_end
                || r.pos + level_size as usize > data.len()
            {
                break;
            }
            mip_levels.push(r.bytes(level_size as usize));
            level_index += 1;
        }

        if r.pos < inner_end {
            r.seek(inner_end);
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

        entries.push(TxdTexture {
            name: if name.is_empty() { format!("texture_{i}") } else { name },
            mask_name,
            width,
            height,
            depth,
            raster_format,
            d3d_format,
            filter_flags,
            platform_id,
            raw_pixels,
            palette,
            mipmap_count: mipmap_count.max(1),
            mip_levels,
        });
    }
    Ok(TxdFile { textures: entries, rw_version })
}

pub fn parse_file(path: &std::path::Path) -> Result<TxdFile, String> {
    let data = std::fs::read(path)
        .map_err(|e| format!("File TXD tidak ditemukan: {} ({e})", path.display()))?;
    parse(&data)
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
            if v <= 0xFF { v as u8 } else { b'?' }
        })
        .collect()
}

fn build_native_chunk(entry: &TxdTexture, rw_version: u32) -> Vec<u8> {
    let has_palette = entry.palette.as_ref().map(|p| p.len() == 1024).unwrap_or(false);
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

/// Serializes textures into a TXD file (port of `TxdBuilder.buildToBytes`).
pub fn build_to_bytes(entries: &[TxdTexture], rw_version: u32) -> Vec<u8> {
    let native_chunks: Vec<Vec<u8>> =
        entries.iter().map(|e| build_native_chunk(e, rw_version)).collect();
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
    use crate::codec::dxt::{interpolate_color, rgb565_to_argb};
    let mut pixels = vec![0u32; width * height];
    let blocks_x = (width + 3) / 4;
    let blocks_y = (height + 3) / 4;
    let mut offset = 0usize;
    let mut palette = [0u32; 4];
    for by in 0..blocks_y {
        for bx in 0..blocks_x {
            if offset + 16 > data.len() {
                break;
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
            let lookup = u32::from_le_bytes(
                data[color_offset + 4..color_offset + 8].try_into().unwrap(),
            );
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
                    pixels[y * width + x] = (alpha << 24) | rgb;
                }
            }
            offset += 16;
        }
    }
    pixels
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
    for i in 0..limit {
        let p = i * 4;
        let b = raw[p] as u32;
        let g = raw[p + 1] as u32;
        let r = raw[p + 2] as u32;
        let a = raw[p + 3] as u32;
        pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgb24(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 3);
    for i in 0..limit {
        let p = i * 3;
        let b = raw[p] as u32;
        let g = raw[p + 1] as u32;
        let r = raw[p + 2] as u32;
        pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgb565(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 2);
    for i in 0..limit {
        let p = i * 2;
        let c = (raw[p] as u32) | ((raw[p + 1] as u32) << 8);
        let r = (((c >> 11) & 0x1F) * 255 + 15) / 31;
        let g = (((c >> 5) & 0x3F) * 255 + 31) / 63;
        let b = ((c & 0x1F) * 255 + 15) / 31;
        pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgba1555(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 2);
    for i in 0..limit {
        let p = i * 2;
        let c = (raw[p] as u32) | ((raw[p + 1] as u32) << 8);
        let a = if c & 0x8000 != 0 { 255 } else { 0 };
        let r = (((c >> 10) & 0x1F) * 255 + 15) / 31;
        let g = (((c >> 5) & 0x1F) * 255 + 15) / 31;
        let b = ((c & 0x1F) * 255 + 15) / 31;
        pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    pixels
}

fn decode_rgba4444(raw: &[u8], width: usize, height: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; width * height];
    let limit = pixels.len().min(raw.len() / 2);
    for i in 0..limit {
        let p = i * 2;
        let c = (raw[p] as u32) | ((raw[p + 1] as u32) << 8);
        let a = ((c >> 12) & 0x0F) * 17;
        let r = ((c >> 8) & 0x0F) * 17;
        let g = ((c >> 4) & 0x0F) * 17;
        let b = (c & 0x0F) * 17;
        pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
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

fn pack(a: u32, r: u32, g: u32, b: u32) -> u32 {
    ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
}

fn sample_nearest(
    src: &[u32], sw: usize, sh: usize, x: usize, y: usize, dw: usize, dh: usize,
) -> u32 {
    let sx = (((2 * x + 1) * sw) / (2 * dw)).min(sw - 1);
    let sy = (((2 * y + 1) * sh) / (2 * dh)).min(sh - 1);
    src[sy * sw + sx]
}

fn sample_box(
    src: &[u32], sw: usize, sh: usize, x: usize, y: usize, dw: usize, dh: usize,
) -> u32 {
    let x0 = (x * sw) / dw;
    let x1 = (((x + 1) * sw) / dw).max(x0 + 1).min(sw);
    let y0 = (y * sh) / dh;
    let y1 = (((y + 1) * sh) / dh).max(y0 + 1).min(sh);
    let (mut sa, mut sr, mut sg, mut sb, mut count) = (0u32, 0u32, 0u32, 0u32, 0u32);
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
    pack(sa / count, sr / count, sg / count, sb / count)
}

fn sample_bilinear(
    src: &[u32], sw: usize, sh: usize, x: usize, y: usize, dw: usize, dh: usize,
) -> u32 {
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
    pack(mix(24), mix(16), mix(8), mix(0))
}

/// Resamples to exactly `dw` x `dh` (port of `MipmapGenerator.resample`).
pub fn resample(
    src: &[u32], sw: usize, sh: usize, dw: usize, dh: usize, filter: MipFilter,
) -> Vec<u32> {
    if sw == dw && sh == dh {
        return src.to_vec();
    }
    let mut out = vec![0u32; dw * dh];
    for y in 0..dh {
        for x in 0..dw {
            out[y * dw + x] = match filter {
                MipFilter::Nearest => sample_nearest(src, sw, sh, x, y, dw, dh),
                MipFilter::Box => sample_box(src, sw, sh, x, y, dw, dh),
                MipFilter::Bilinear => sample_bilinear(src, sw, sh, x, y, dw, dh),
            };
        }
    }
    out
}

/// One halving step (port of `MipmapGenerator.downscaleHalf`).
pub fn downscale_half(
    src: &[u32], sw: usize, sh: usize, filter: MipFilter,
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
    src: &[u32], sw: usize, sh: usize, max_dimension: usize,
) -> (Vec<u32>, usize, usize) {
    let (nw, nh) = resized_dimensions(sw, sh, max_dimension);
    if nw == sw && nh == sh {
        return (src.to_vec(), sw, sh);
    }
    (resample(src, sw, sh, nw, nh, MipFilter::Bilinear), nw, nh)
}

/// Full mip chain below the source (ports of `generateChain`).
pub fn generate_chain(
    src: &[u32], sw: usize, sh: usize, filter: MipFilter,
) -> Vec<(Vec<u32>, usize, usize)> {
    let mut levels = Vec::new();
    let (mut cur, mut cw, mut ch) = (src.to_vec(), sw, sh);
    while cw > 1 || ch > 1 {
        let (next, nw, nh) = downscale_half(&cur, cw, ch, filter);
        levels.push((next.clone(), nw, nh));
        cur = next;
        cw = nw;
        ch = nh;
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
        Self { generate_mipmaps: false, mip_filter: MipFilter::Box, max_dimension: None }
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
        replace_texture(entry, px, *w, *h, as_dxt1, options);
    }
    let bytes = build_to_bytes(&txd.textures, txd.rw_version);
    std::fs::write(path, bytes).map_err(|e| e.to_string())?;
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
                let all_names: Vec<String> =
                    txd.textures.iter().map(|e| e.name.clone()).collect();
                let unique = unique_name(&all_names, desired);
                txd.textures.push(create_entry(&unique, px, *w, *h, None, options));
                added += 1;
            }
            None => {
                txd.textures.push(create_entry(desired, px, *w, *h, None, options));
                added += 1;
            }
        }
    }
    if added + overwritten > 0 {
        let bytes = build_to_bytes(&txd.textures, txd.rw_version);
        std::fs::write(path, bytes).map_err(|e| e.to_string())?;
    }
    Ok((added, overwritten))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(name: &str) -> TxdTexture {
        TxdTexture { name: name.to_string(), ..TxdTexture::default() }
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
}
