//! GTA mobile texture database (texdb) — `.txt` manifest + `.toc` offsets +
//! `.dat` listings, with the RLE payload codec.
//!
//! Format reverse-engineered from the GTAMods Wiki ("Mobile textures (SA/VC)")
//! and verified against real game caches on 2026-10-23 (see
//! `~/workspace/txd-tool-rea/FORMAT-TEXDB.md`):
//! - Listing (16 bytes): u16 name hash, u16 encoding (GL enum), u16 width,
//!   u16 height|flags (bit 0x8000 set = FULL mip chain stored, clear = level 0
//!   only — empirical, the wiki states the opposite), u32 compressed size
//!   (= span - 12; the RLE stream starts at offset 16 and is csize - 4 long),
//!   u32 RLE indicator (0 = stored raw; otherwise the repeat-marker byte).
//! - RLE stream: literal groups of `seg` bytes; a group starting with the
//!   indicator byte is [indicator][count][seg bytes] and emits seg*count bytes.
//!   `seg` is the format's unit size (DXT block / pixel group).
//! - `.toc`: u32 .dat size, then one i32 offset per manifest entry
//!   (-1 for affiliate entries, which carry no data).

/// Name hash from the wiki algorithm; verified against real listings.
pub fn hash_name(name: &str) -> u16 {
    let mut hash: u32 = 0;
    for &b in name.as_bytes() {
        hash = hash.wrapping_add((hash << 5).wrapping_add(b as u32));
    }
    hash.wrapping_add(hash >> 5) as u16
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TexEncoding {
    Dxt1,      // 0x83F0
    Dxt5,      // 0x83F3
    Etc1,      // 0x8D64 GL_ETC1_RGB8_OES
    Rgb565,    // 0x8033 (render-verified) and 0x8363 (same family)
    Rgba4444,  // 0x8034 (probable layout, same storage family)
    Rgba32,    // 0x1401 GL_UNSIGNED_BYTE (RGBA bytes)
    Unknown(u16),
}

impl TexEncoding {
    pub fn from_u16(v: u16) -> Self {
        match v {
            0x83F0 => Self::Dxt1,
            0x83F3 => Self::Dxt5,
            0x8D64 => Self::Etc1,
            0x8033 | 0x8363 => Self::Rgb565,
            0x8034 => Self::Rgba4444,
            0x1401 => Self::Rgba32,
            other => Self::Unknown(other),
        }
    }

    /// RLE segment size (the format's unit), if known.
    pub fn segment_size(&self) -> Option<usize> {
        match self {
            Self::Dxt1 | Self::Etc1 => Some(8),
            Self::Dxt5 => Some(16),
            Self::Rgb565 | Self::Rgba4444 | Self::Rgba32 => Some(4),
            Self::Unknown(_) => None,
        }
    }

    /// Decompressed size of one mip level.
    pub fn level_size(&self, width: usize, height: usize) -> Option<usize> {
        match self {
            Self::Dxt1 | Self::Etc1 => Some(((width + 3) / 4) * ((height + 3) / 4) * 8),
            Self::Dxt5 => Some(((width + 3) / 4) * ((height + 3) / 4) * 16),
            Self::Rgb565 | Self::Rgba4444 => Some(width * height * 2),
            Self::Rgba32 => Some(width * height * 4),
            Self::Unknown(_) => None,
        }
    }

    /// Total decompressed size over the stored mip range.
    pub fn raw_size(&self, width: usize, height: usize, full_chain: bool) -> Option<usize> {
        let mut total = 0usize;
        let (mut w, mut h) = (width, height);
        loop {
            total += self.level_size(w, h)?;
            if !full_chain || (w == 1 && h == 1) {
                break;
            }
            w = (w / 2).max(1);
            h = (h / 2).max(1);
        }
        Some(total)
    }
}

/// Decodes one RLE stream. Returns `None` on a truncated/corrupt stream.
/// Decoded output may exceed the texture's expected size by a few trailing
/// bytes on some game-built caches; callers should keep the expected prefix.
pub fn rle_decode(payload: &[u8], seg: usize, indicator: u8) -> Option<Vec<u8>> {
    if seg == 0 {
        return None;
    }
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < payload.len() {
        let b = payload[i];
        i += 1;
        if b == indicator {
            if i >= payload.len() {
                return None;
            }
            let count = payload[i] as usize;
            i += 1;
            if i + seg > payload.len() {
                return None;
            }
            let segment = &payload[i..i + seg];
            i += seg;
            for _ in 0..count {
                out.extend_from_slice(segment);
            }
        } else {
            if i + seg - 1 > payload.len() {
                return None;
            }
            out.push(b);
            out.extend_from_slice(&payload[i..i + seg - 1]);
            i += seg - 1;
        }
    }
    Some(out)
}

/// Encodes `data` as an RLE stream the game decoder accepts.
/// Runs of identical `seg`-sized units become repeat groups; a literal unit
/// that would start with the indicator byte is emitted as a count-1 repeat
/// group so it can never be misread.
pub fn rle_encode(data: &[u8], seg: usize, indicator: u8) -> Vec<u8> {
    assert!(seg > 0 && data.len() % seg == 0);
    let units = data.len() / seg;
    let mut out = Vec::new();
    let mut u = 0usize;
    while u < units {
        let unit = &data[u * seg..(u + 1) * seg];
        // Count the run of identical units (cap 255 per group).
        let mut run = 1usize;
        while u + run < units && run < 255 && &data[(u + run) * seg..(u + run + 1) * seg] == unit {
            run += 1;
        }
        if run >= 2 || unit[0] == indicator {
            out.push(indicator);
            out.push(run as u8);
            out.extend_from_slice(unit);
            u += run;
        } else {
            out.extend_from_slice(unit);
            u += 1;
        }
    }
    out
}

/// IEEE CRC32 (used for the manifest's informational png=/img= fields,
/// which the game itself never reads).
pub fn crc32_ieee(data: &[u8]) -> u32 {
    const TABLE: [u32; 256] = {
        let mut table = [0u32; 256];
        let mut i = 0;
        while i < 256 {
            let mut c = i as u32;
            let mut k = 0;
            while k < 8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
                k += 1;
            }
            table[i] = c;
            i += 1;
        }
        table
    };
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc = TABLE[((crc ^ b as u32) & 0xFF) as usize] ^ (crc >> 8);
    }
    crc ^ 0xFFFF_FFFF
}

/// Builds one `.dat` listing (16-byte header + payload) from raw GPU data
/// levels (level 0 first; pass the full chain down to 1x1 for the chain bit,
/// or a single level). Returns `None` when the levels do not add up to the
/// encoding's raw size. RLE is applied when it actually shrinks the payload.
pub fn build_listing(
    name: &str,
    encoding: TexEncoding,
    width: usize,
    height: usize,
    levels: &[&[u8]],
    use_rle: bool,
) -> Option<Vec<u8>> {
    let encoding_raw: u16 = match encoding {
        TexEncoding::Dxt1 => 0x83F0,
        TexEncoding::Dxt5 => 0x83F3,
        TexEncoding::Etc1 => 0x8D64,
        TexEncoding::Rgb565 => 0x8033,
        TexEncoding::Rgba4444 => 0x8034,
        TexEncoding::Rgba32 => 0x1401,
        TexEncoding::Unknown(_) => return None,
    };
    let full_chain = levels.len() > 1;
    if encoding.raw_size(width, height, full_chain)? != levels.iter().map(|l| l.len()).sum::<usize>() {
        return None;
    }
    let raw: Vec<u8> = levels.iter().flat_map(|l| l.iter().copied()).collect();
    let seg = encoding.segment_size()?;
    let (payload, indicator): (Vec<u8>, u32) = if use_rle && raw.len() % seg == 0 {
        let encoded = rle_encode(&raw, seg, 0xFF);
        if encoded.len() < raw.len() {
            (encoded, 0xFF)
        } else {
            (raw, 0)
        }
    } else {
        (raw, 0)
    };
    let mut out = Vec::with_capacity(16 + payload.len());
    out.extend_from_slice(&hash_name(name).to_le_bytes());
    out.extend_from_slice(&encoding_raw.to_le_bytes());
    out.extend_from_slice(&(width as u16).to_le_bytes());
    let hm = (height as u16) | if full_chain { 0x8000 } else { 0 };
    out.extend_from_slice(&hm.to_le_bytes());
    out.extend_from_slice(&((payload.len() + 4) as u32).to_le_bytes());
    out.extend_from_slice(&indicator.to_le_bytes());
    out.extend_from_slice(&payload);
    Some(out)
}

/// Rebuilds `.dat` + `.toc` contents from per-entry listings
/// (`None` = affiliate: no data, toc offset -1).
pub fn rebuild_dat_toc(listings: &[Option<&[u8]>]) -> (Vec<u8>, Vec<u8>) {
    let mut dat = Vec::new();
    let mut offsets = Vec::with_capacity(listings.len());
    for listing in listings {
        match listing {
            Some(bytes) => {
                offsets.push(dat.len() as i32);
                dat.extend_from_slice(bytes);
            }
            None => offsets.push(-1),
        }
    }
    let mut toc = Vec::with_capacity(4 + offsets.len() * 4);
    toc.extend_from_slice(&(dat.len() as u32).to_le_bytes());
    for off in offsets {
        toc.extend_from_slice(&off.to_le_bytes());
    }
    (dat, toc)
}

#[derive(Clone, Debug)]
pub struct ManifestEntry {
    pub name: String,
    /// The manifest line verbatim (trimmed), for faithful rewrites.
    pub line: String,
    pub width: usize,
    pub height: usize,
    /// Target name when this entry is an affiliate (alias) of another texture.
    pub affiliate: Option<String>,
    pub props: Vec<(String, String)>,
}

impl ManifestEntry {
    pub fn prop(&self, key: &str) -> Option<&str> {
        self.props
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Listing {
    pub hash: u16,
    pub encoding: TexEncoding,
    pub encoding_raw: u16,
    pub width: usize,
    pub height: usize,
    pub full_chain: bool,
    pub compressed_size: u32,
    pub rle_indicator: u32,
    /// Byte range of the whole listing (header + payload) inside the .dat file.
    pub span_start: usize,
    pub span_end: usize,
}

impl Listing {
    pub fn payload_range(&self) -> std::ops::Range<usize> {
        self.span_start + 16..self.span_end
    }

    pub fn expected_size(&self) -> Option<usize> {
        self.encoding.raw_size(self.width, self.height, self.full_chain)
    }
}

/// Quote-aware tokenizer matching the game's `LoadEntry` (see the
/// validator in `examples/game_check.rs`): a `"`-quoted span is ONE token,
/// spaces inside it included. Real texture names contain spaces
/// ("crazy paving") and affiliate targets are written as a quoted token
/// `"affiliate=crazy paving"` — whitespace splitting truncates such a
/// target to "crazy" and the affiliate then never resolves (bug found
/// 2026-10-23; the old `split_whitespace` parser did exactly that).
fn quote_tokens(rest: &str) -> Vec<String> {
    let b = rest.as_bytes();
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < b.len() {
        while i < b.len() && (b[i] as char).is_whitespace() {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        if b[i] == b'"' {
            let start = i + 1;
            let mut j = start;
            while j < b.len() && b[j] != b'"' {
                j += 1;
            }
            out.push(rest[start..j].to_string());
            i = (j + 1).min(b.len());
        } else {
            let start = i;
            while i < b.len() && !(b[i] as char).is_whitespace() {
                i += 1;
            }
            out.push(rest[start..i].to_string());
        }
    }
    out
}

fn parse_manifest(text: &str) -> Vec<ManifestEntry> {
    let mut entries = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if !line.starts_with('"') {
            continue;
        }
        let Some(end) = line[1..].find('"') else { continue };
        let name = line[1..1 + end].to_string();
        let rest = &line[1 + end + 1..];
        let mut props = Vec::new();
        for token in quote_tokens(rest) {
            if let Some((k, v)) = token.split_once('=') {
                // Real caches write affiliates as a quoted token:
                //   "name" "affiliate=target"   (key arrives with a quote)
                props.push((
                    k.trim_matches('"').to_string(),
                    v.trim_matches('"').to_string(),
                ));
            }
        }
        let get = |key: &str| {
            props
                .iter()
                .find(|(k, _)| k == key)
                .and_then(|(_, v)| v.parse::<usize>().ok())
                .unwrap_or(0)
        };
        let affiliate = props
            .iter()
            .find(|(k, _)| k == "affiliate")
            .map(|(_, v)| v.clone());
        entries.push(ManifestEntry {
            name,
            line: line.to_string(),
            width: get("width"),
            height: get("height"),
            affiliate,
            props,
        });
    }
    entries
}

pub struct TexdbCache {
    pub manifest: Vec<ManifestEntry>,
    /// The manifest file verbatim, for structure-preserving rewrites.
    pub raw_txt: String,
    /// One slot per manifest entry; `None` for affiliates / missing data.
    pub listings: Vec<Option<Listing>>,
    pub variant: String,
    dat: Vec<u8>,
}

impl TexdbCache {
    /// Loads a cache directory, choosing the first usable variant file
    /// (validated: toc[0] == .dat length and listing hashes match the manifest).
    pub fn load(dir: &std::path::Path, cache_name: &str) -> std::io::Result<Self> {
        let text = std::fs::read_to_string(dir.join(format!("{cache_name}.txt")))?;
        let mut manifest = parse_manifest(&text);
        // Affiliate lines carry no dimensions in real manifests; inherit
        // them from the target texture so UIs can show real sizes.
        let dims: std::collections::HashMap<String, (usize, usize)> = manifest
            .iter()
            .filter(|e| e.affiliate.is_none())
            .map(|e| (e.name.to_lowercase(), (e.width, e.height)))
            .collect();
        for entry in manifest.iter_mut() {
            if entry.width == 0 && entry.height == 0 {
                if let Some(target) = &entry.affiliate {
                    if let Some(&(w, h)) = dims.get(&target.to_lowercase()) {
                        entry.width = w;
                        entry.height = h;
                    }
                }
            }
        }
        let mut variants: Vec<String> = Vec::new();
        for entry in std::fs::read_dir(dir)? {
            let path = entry?.path();
            let fname = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            if let Some(rest) = fname.strip_prefix(&format!("{cache_name}.")) {
                if let Some(v) = rest.strip_suffix(".toc") {
                    variants.push(v.to_string());
                }
            }
        }
        variants.sort();
        let mut last_err = None;
        for variant in variants {
            match Self::load_variant(dir, cache_name, &variant, &manifest, &text) {
                Ok(cache) => return Ok(cache),
                Err(e) => last_err = Some(e),
            }
        }
        Err(last_err.unwrap_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::NotFound, "no usable texdb variant")
        }))
    }

    fn load_variant(
        dir: &std::path::Path,
        cache_name: &str,
        variant: &str,
        manifest: &[ManifestEntry],
        raw_txt: &str,
    ) -> std::io::Result<Self> {
        let dat = std::fs::read(dir.join(format!("{cache_name}.{variant}.dat")))?;
        let toc_bytes = std::fs::read(dir.join(format!("{cache_name}.{variant}.toc")))?;
        if toc_bytes.len() < 4 || (toc_bytes.len() - 4) / 4 < manifest.len() {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "toc too short"));
        }
        let stated = u32::from_le_bytes(toc_bytes[0..4].try_into().unwrap()) as usize;
        if stated != dat.len() {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "toc/dat size mismatch"));
        }
        let offsets: Vec<i64> = (0..manifest.len())
            .map(|i| {
                i32::from_le_bytes(toc_bytes[4 + i * 4..8 + i * 4].try_into().unwrap()) as i64
            })
            .collect();
        let mut listings: Vec<Option<Listing>> = Vec::with_capacity(manifest.len());
        for (i, entry) in manifest.iter().enumerate() {
            let off = offsets[i];
            if off < 0 {
                listings.push(None);
                continue;
            }
            let start = off as usize;
            let end = offsets[i + 1..]
                .iter()
                .find(|&&o| o > off)
                .map(|&o| o as usize)
                .unwrap_or(dat.len());
            if start + 16 > dat.len() || end > dat.len() || end < start + 16 {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "listing out of range"));
            }
            let h = &dat[start..start + 16];
            let hash = u16::from_le_bytes(h[0..2].try_into().unwrap());
            let encoding_raw = u16::from_le_bytes(h[2..4].try_into().unwrap());
            let width = u16::from_le_bytes(h[4..6].try_into().unwrap()) as usize;
            let hm = u16::from_le_bytes(h[6..8].try_into().unwrap());
            let compressed_size = u32::from_le_bytes(h[8..12].try_into().unwrap());
            let rle_indicator = u32::from_le_bytes(h[12..16].try_into().unwrap());
            if hash != hash_name(&entry.name) {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("listing hash mismatch for {}", entry.name),
                ));
            }
            listings.push(Some(Listing {
                hash,
                encoding: TexEncoding::from_u16(encoding_raw),
                encoding_raw,
                width,
                height: (hm & 0x7FFF) as usize,
                full_chain: hm & 0x8000 != 0,
                compressed_size,
                rle_indicator,
                span_start: start,
                span_end: end,
            }));
        }
        Ok(Self {
            raw_txt: raw_txt.to_string(),
            manifest: manifest.to_vec(),
            listings,
            variant: variant.to_string(),
            dat,
        })
    }

    /// The listing's raw bytes inside the .dat file (for cache rebuilds that
    /// keep untouched textures byte-identical).
    pub fn raw_listing(&self, index: usize) -> Option<&[u8]> {
        let listing = self.listings.get(index)?.as_ref()?;
        Some(&self.dat[listing.span_start..listing.span_end])
    }

    /// Decompressed GPU data for a manifest index (expected-size prefix),
    /// or `None` for affiliates / unknown encodings / corrupt streams.
    pub fn decode_raw(&self, index: usize) -> Option<Vec<u8>> {
        let listing = self.listings.get(index)?.as_ref()?;
        let expected = listing.expected_size()?;
        let payload = &self.dat[listing.payload_range()];
        let decoded = if listing.rle_indicator == 0 {
            payload.to_vec()
        } else {
            rle_decode(payload, listing.encoding.segment_size()?, (listing.rle_indicator & 0xFF) as u8)?
        };
        if decoded.len() < expected {
            return None;
        }
        Some(decoded[..expected].to_vec())
    }

    /// Level-0 pixels as ARGB u32 for encodings with a pixel decoder
    /// (DXT1/DXT5 via the codec module, RGB565, RGBA4444, RGBA32).
    pub fn decode_pixels(&self, index: usize) -> Option<Vec<u32>> {
        let listing = self.listings.get(index)?.as_ref()?;
        let raw = self.decode_raw(index)?;
        let (w, h) = (listing.width, listing.height);
        let level0 = listing.encoding.level_size(w, h)?;
        let data = &raw[..level0.min(raw.len())];
        match listing.encoding {
            TexEncoding::Dxt1 => Some(crate::codec::dxt::decode_dxt1(data, w, h)),
            TexEncoding::Dxt5 => Some(crate::codec::dxt::decode_dxt5(data, w, h)),
            TexEncoding::Rgb565 => Some(
                data.chunks_exact(2)
                    .map(|c| {
                        let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                        let r = (((v >> 11) & 0x1F) * 255 + 15) / 31;
                        let g = (((v >> 5) & 0x3F) * 255 + 31) / 63;
                        let b = ((v & 0x1F) * 255 + 15) / 31;
                        0xFF00_0000 | (r << 16) | (g << 8) | b
                    })
                    .collect(),
            ),
            TexEncoding::Rgba4444 => Some(
                data.chunks_exact(2)
                    .map(|c| {
                        let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                        let r = ((v >> 12) & 0xF) * 17;
                        let g = ((v >> 8) & 0xF) * 17;
                        let b = ((v >> 4) & 0xF) * 17;
                        let a = (v & 0xF) * 17;
                        (a << 24) | (r << 16) | (g << 8) | b
                    })
                    .collect(),
            ),
            TexEncoding::Etc1 => Some(crate::codec::etc1::decode_etc1(data, w, h)),
            TexEncoding::Rgba32 => Some(
                data.chunks_exact(4)
                    .map(|c| {
                        ((c[3] as u32) << 24)
                            | ((c[0] as u32) << 16)
                            | ((c[1] as u32) << 8)
                            | c[2] as u32
                    })
                    .collect(),
            ),
            _ => None,
        }
    }
}

/// Decodes PNG bytes to ARGB pixels (the `png` crate, EXPAND transform).
pub fn decode_png_argb(bytes: &[u8]) -> Option<(Vec<u32>, usize, usize)> {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder.read_info().ok()?;
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).ok()?;
    let (w, h) = (info.width as usize, info.height as usize);
    let data = &buf[..info.buffer_size()];
    let px: Vec<u32> = match info.color_type {
        png::ColorType::Rgba => data
            .chunks_exact(4)
            .map(|c| ((c[3] as u32) << 24) | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
            .collect(),
        png::ColorType::Rgb => data
            .chunks_exact(3)
            .map(|c| 0xFF00_0000 | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
            .collect(),
        png::ColorType::Grayscale => data
            .iter()
            .map(|&v| 0xFF00_0000 | ((v as u32) << 16) | ((v as u32) << 8) | v as u32)
            .collect(),
        png::ColorType::GrayscaleAlpha => data
            .chunks_exact(2)
            .map(|c| ((c[1] as u32) << 24) | ((c[0] as u32) << 16) | ((c[0] as u32) << 8) | c[0] as u32)
            .collect(),
        _ => return None,
    };
    if px.len() != w * h {
        return None;
    }
    Some((px, w, h))
}

fn rgb565_bytes(pixels: &[u32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pixels.len() * 2);
    for &p in pixels {
        let v = ((((p >> 16) & 0xFF) >> 3) << 11) | ((((p >> 8) & 0xFF) >> 2) << 5) | ((p & 0xFF) >> 3);
        out.extend_from_slice(&(v as u16).to_le_bytes());
    }
    out
}

fn rgba4444_bytes(pixels: &[u32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pixels.len() * 2);
    for &p in pixels {
        let v = ((((p >> 16) & 0xFF) >> 4) << 12)
            | ((((p >> 8) & 0xFF) >> 4) << 8)
            | (((p & 0xFF) >> 4) << 4)
            | ((p >> 24) & 0xFF) >> 4;
        out.extend_from_slice(&(v as u16).to_le_bytes());
    }
    out
}

fn rgba32_bytes(pixels: &[u32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pixels.len() * 4);
    for &p in pixels {
        out.push(((p >> 16) & 0xFF) as u8);
        out.push(((p >> 8) & 0xFF) as u8);
        out.push((p & 0xFF) as u8);
        out.push((p >> 24) as u8);
    }
    out
}

/// Import format choices, mirroring TXD Tool's image-import dialog.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ImportFormat {
    CompressedAlpha,
    CompressedNoAlpha,
    UncompressedAlpha,
    UncompressedNoAlpha,
    Uncompressed32,
    UncompressedLowMemory,
}

impl ImportFormat {
    pub fn from_i32(v: i32) -> Self {
        match v {
            1 => Self::CompressedNoAlpha,
            2 => Self::UncompressedAlpha,
            3 => Self::UncompressedNoAlpha,
            4 => Self::Uncompressed32,
            5 => Self::UncompressedLowMemory,
            _ => Self::CompressedAlpha,
        }
    }
}

/// Name-conflict policy, mirroring TXD Tool's import wizard.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ConflictPolicy {
    /// Always append a new entry, even when the name exists.
    JustAdd,
    /// Replace the existing entry with the same name.
    Overwrite,
    /// Append as a sibling (hassibling=1) and bind an affiliate to a DFF model.
    CreateSibling,
}

impl ConflictPolicy {
    pub fn from_i32(v: i32) -> Self {
        match v {
            1 => Self::Overwrite,
            2 => Self::CreateSibling,
            _ => Self::JustAdd,
        }
    }
}

#[derive(Clone, Debug)]
pub struct ImportOptions {
    pub format: ImportFormat,
    pub conflict: ConflictPolicy,
    pub sibling_model: Option<String>,
    pub reduce_to: usize,
    pub camnorm: bool,
    pub hash_check: bool,
    pub correct_caps: bool,
    pub use_rle: bool,
    pub generate_mipmaps: bool,
    /// Mip filter: 0 Nearest, 1 Box, 2 Bilinear, 3 Cubic, 4 Gaussian, 5 Lanczos4.
    pub mip_filter: i32,
}

#[derive(Default, Debug)]
pub struct ImportReport {
    pub replaced: usize,
    pub added: usize,
    pub skipped: usize,
    pub siblings: usize,
}

impl ImportReport {
    pub fn to_line(&self) -> String {
        format!(
            "replaced={};added={};skipped={};siblings={}",
            self.replaced, self.added, self.skipped, self.siblings
        )
    }
}

fn import_manifest_line(
    name: &str,
    width: usize,
    height: usize,
    png_crc: u32,
    img_crc: u32,
    encoding: TexEncoding,
    has_alpha: bool,
    camnorm: bool,
    sibling: bool,
) -> String {
    format!(
        "\"{}\" width={} height={} png={:08x} img={:08x}{}{}{}{}streammode=1",
        name,
        width,
        height,
        png_crc,
        img_crc,
        if matches!(encoding, TexEncoding::Dxt1 | TexEncoding::Dxt5) { " format=7" } else { "" },
        if has_alpha { " alphamode=2" } else { "" },
        if camnorm { " camnorm=1" } else { "" },
        if sibling { " hassibling=1" } else { "" },
    )
}

/// Imports images (name, PNG bytes) into the cache in `dir`, TXD Tool style:
/// decode, reduce to fit, encode, build listings, rebuild .dat/.toc/.txt in
/// place (only the validated variant is rewritten). Returns a report line.
pub fn import_textures(
    dir: &std::path::Path,
    cache_name: &str,
    images: &[(String, Vec<u8>)],
    opts: &ImportOptions,
) -> Result<String, String> {
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    let mut lines: Vec<String> = cache.manifest.iter().map(|e| e.line.clone()).collect();
    let mut listings: Vec<Option<Vec<u8>>> = (0..cache.manifest.len())
        .map(|i| cache.raw_listing(i).map(|b| b.to_vec()))
        .collect();
    let mut appended_lines: Vec<String> = Vec::new();
    let mut appended_listings: Vec<Option<Vec<u8>>> = Vec::new();
    let mut report = ImportReport::default();

    let find_entry = |name: &str, lines_len: usize, manifest: &[ManifestEntry]| -> Option<usize> {
        let _ = lines_len;
        manifest
            .iter()
            .position(|e| e.affiliate.is_none() && e.name.eq_ignore_ascii_case(name))
    };

    for (raw_name, png_bytes) in images {
        let Some((pixels, w, h)) = decode_png_argb(png_bytes) else {
            report.skipped += 1;
            continue;
        };
        let twin = find_entry(raw_name, lines.len(), &cache.manifest);
        let name = if opts.correct_caps {
            twin.map(|i| cache.manifest[i].name.clone()).unwrap_or_else(|| raw_name.clone())
        } else {
            raw_name.clone()
        };
        let src = crate::mipmap::PixelImage::new(pixels, w, h);
        let mut chain = vec![src.clone()];
        chain.extend(crate::mipmap::generate_chain(&src, crate::mipmap::MipFilter::from_i32(opts.mip_filter)));
        let base = if opts.reduce_to > 0 {
            chain
                .iter()
                .position(|l| l.width.max(l.height) <= opts.reduce_to)
                .unwrap_or(chain.len() - 1)
        } else {
            0
        };
        let levels = if opts.generate_mipmaps { &chain[base..] } else { &chain[base..base + 1] };
        let has_alpha = levels[0].pixels.iter().any(|p| (p >> 24) & 0xFF < 255);
        let encoding = match opts.format {
            ImportFormat::CompressedAlpha => {
                if has_alpha { TexEncoding::Dxt5 } else { TexEncoding::Dxt1 }
            }
            ImportFormat::CompressedNoAlpha => TexEncoding::Dxt1,
            ImportFormat::UncompressedAlpha | ImportFormat::UncompressedLowMemory => {
                TexEncoding::Rgba4444
            }
            ImportFormat::UncompressedNoAlpha => TexEncoding::Rgb565,
            ImportFormat::Uncompressed32 => TexEncoding::Rgba32,
        };
        let encoded: Vec<Vec<u8>> = levels
            .iter()
            .map(|l| match encoding {
                TexEncoding::Dxt5 => crate::codec::dxt::encode_dxt5(&l.pixels, l.width, l.height),
                TexEncoding::Dxt1 => crate::codec::dxt::encode_dxt1(&l.pixels, l.width, l.height),
                TexEncoding::Rgb565 => rgb565_bytes(&l.pixels),
                TexEncoding::Rgba4444 => rgba4444_bytes(&l.pixels),
                TexEncoding::Rgba32 => rgba32_bytes(&l.pixels),
                _ => Vec::new(),
            })
            .collect();
        let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
        let Some(listing) = build_listing(
            &name,
            encoding,
            levels[0].width,
            levels[0].height,
            &refs,
            opts.use_rle,
        ) else {
            report.skipped += 1;
            continue;
        };
        let is_sibling = opts.conflict == ConflictPolicy::CreateSibling;
        let line = import_manifest_line(
            &name,
            levels[0].width,
            levels[0].height,
            crc32_ieee(png_bytes),
            crc32_ieee(&encoded[0]),
            encoding,
            has_alpha,
            opts.camnorm,
            is_sibling,
        );
        match opts.conflict {
            ConflictPolicy::Overwrite => {
                if let Some(i) = twin {
                    // Raster-hash check: never overwrite an entry whose stored
                    // hash does not match its manifest name.
                    if opts.hash_check {
                        if let Some(existing) = &listings[i] {
                            let stored = u16::from_le_bytes([existing[0], existing[1]]);
                            if stored != hash_name(&cache.manifest[i].name) {
                                report.skipped += 1;
                                continue;
                            }
                        }
                    }
                    listings[i] = Some(listing);
                    lines[i] = line;
                    report.replaced += 1;
                } else {
                    appended_listings.push(Some(listing));
                    appended_lines.push(line);
                    report.added += 1;
                }
            }
            ConflictPolicy::JustAdd => {
                appended_listings.push(Some(listing));
                appended_lines.push(line);
                report.added += 1;
            }
            ConflictPolicy::CreateSibling => {
                appended_listings.push(Some(listing));
                appended_lines.push(line);
                report.siblings += 1;
                if let Some(model) = &opts.sibling_model {
                    let model = model.trim();
                    if !model.is_empty()
                        && !cache.manifest.iter().any(|e| e.name.eq_ignore_ascii_case(model))
                        && !appended_lines.iter().any(|l| l.contains(&format!("\"{model}\"")))
                    {
                        appended_listings.push(None);
                        appended_lines.push(format!(
                            "\"{}\" \"affiliate={}\"",
                            model, name
                        ));
                    }
                }
            }
        }
    }

    let total_listings: Vec<Option<&[u8]>> = listings
        .iter()
        .map(|l| l.as_deref())
        .chain(appended_listings.iter().map(|l| l.as_deref()))
        .collect();
    let (dat, toc) = rebuild_dat_toc(&total_listings);

    // Rewrite the manifest preserving its original structure: texture lines
    // are substituted by ordinal, additions go last.
    let mut out_lines: Vec<String> = Vec::new();
    let mut ordinal = 0usize;
    for raw_line in cache.raw_txt.lines() {
        let line = raw_line.trim_end();
        if line.trim_start().starts_with('"') {
            if ordinal < lines.len() {
                out_lines.push(lines[ordinal].clone());
            }
            ordinal += 1;
        } else {
            out_lines.push(line.to_string());
        }
    }
    out_lines.extend(appended_lines);
    let mut txt = out_lines.join("\n");
    txt.push('\n');

    std::fs::write(dir.join(format!("{cache_name}.txt")), txt).map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{cache_name}.{}.dat", cache.variant)), &dat)
        .map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{cache_name}.{}.toc", cache.variant)), &toc)
        .map_err(|e| e.to_string())?;
    let notes = sync_after_write(dir, cache_name, &cache);
    Ok(with_notes(report.to_line(), notes))
}

/// Rebuilds the cache's .dat/.toc from its current listings (defragment and
/// repair), leaving every texture's bytes untouched.
pub fn rebuild_cache(dir: &std::path::Path, cache_name: &str) -> Result<String, String> {
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    let slots: Vec<Option<&[u8]>> = (0..cache.manifest.len())
        .map(|i| {
            if cache.manifest[i].affiliate.is_some() {
                None
            } else {
                cache.raw_listing(i)
            }
        })
        .collect();
    let (dat, toc) = rebuild_dat_toc(&slots);
    std::fs::write(dir.join(format!("{cache_name}.{}.dat", cache.variant)), &dat)
        .map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{cache_name}.{}.toc", cache.variant)), &toc)
        .map_err(|e| e.to_string())?;
    let notes = sync_after_write(dir, cache_name, &cache);
    Ok(with_notes(
        format!("entries={};bytes={}", cache.manifest.len(), dat.len()),
        notes,
    ))
}

/// Decodes one texture's level-0 pixels without loading the whole .dat:
/// reads only the manifest, the .toc, and that entry's span.
/// Returns (width, height, encoding raw value, ARGB pixels).
pub fn decode_pixels_at(
    dir: &std::path::Path,
    cache_name: &str,
    index: usize,
) -> Option<(usize, usize, u16, Vec<u32>)> {
    use std::io::{Read, Seek, SeekFrom};
    let text = std::fs::read_to_string(dir.join(format!("{cache_name}.txt"))).ok()?;
    let manifest = parse_manifest(&text);
    if index >= manifest.len() || manifest[index].affiliate.is_some() {
        return None;
    }
    let mut variants: Vec<String> = Vec::new();
    for entry in std::fs::read_dir(dir).ok()? {
        let path = entry.ok()?.path();
        let fname = path.file_name()?.to_string_lossy().to_string();
        if let Some(v) = fname
            .strip_prefix(&format!("{cache_name}."))
            .and_then(|r| r.strip_suffix(".toc"))
        {
            variants.push(v.to_string());
        }
    }
    variants.sort();
    for variant in variants {
        let dat_path = dir.join(format!("{cache_name}.{variant}.dat"));
        let toc_bytes = std::fs::read(dir.join(format!("{cache_name}.{variant}.toc"))).ok()?;
        if toc_bytes.len() < 4 || (toc_bytes.len() - 4) / 4 < manifest.len() {
            continue;
        }
        let dat_len = std::fs::metadata(&dat_path).ok()?.len() as usize;
        if u32::from_le_bytes(toc_bytes[0..4].try_into().ok()?) as usize != dat_len {
            continue;
        }
        let offsets: Vec<i64> = (0..manifest.len())
            .map(|i| i32::from_le_bytes(toc_bytes[4 + i * 4..8 + i * 4].try_into().unwrap()) as i64)
            .collect();
        let off = offsets[index];
        if off < 0 {
            return None;
        }
        let start = off as usize;
        let end = offsets[index + 1..]
            .iter()
            .find(|&&o| o > off)
            .map(|&o| o as usize)
            .unwrap_or(dat_len);
        let mut file = std::fs::File::open(&dat_path).ok()?;
        file.seek(SeekFrom::Start(start as u64)).ok()?;
        let mut span = vec![0u8; end - start];
        file.read_exact(&mut span).ok()?;
        let h = &span[..16];
        let listing = Listing {
            hash: u16::from_le_bytes(h[0..2].try_into().ok()?),
            encoding: TexEncoding::from_u16(u16::from_le_bytes(h[2..4].try_into().ok()?)),
            encoding_raw: u16::from_le_bytes(h[2..4].try_into().ok()?),
            width: u16::from_le_bytes(h[4..6].try_into().ok()?) as usize,
            height: (u16::from_le_bytes(h[6..8].try_into().ok()?) & 0x7FFF) as usize,
            full_chain: u16::from_le_bytes(h[6..8].try_into().ok()?) & 0x8000 != 0,
            compressed_size: u32::from_le_bytes(h[8..12].try_into().ok()?),
            rle_indicator: u32::from_le_bytes(h[12..16].try_into().ok()?),
            span_start: 0,
            span_end: span.len(),
        };
        let expected = listing.expected_size()?;
        let payload = &span[16..];
        let decoded = if listing.rle_indicator == 0 {
            payload.to_vec()
        } else {
            rle_decode(payload, listing.encoding.segment_size()?, (listing.rle_indicator & 0xFF) as u8)?
        };
        if decoded.len() < expected {
            return None;
        }
        let raw = &decoded[..expected];
        let level0 = &raw[..listing.encoding.level_size(listing.width, listing.height)?];
        let pixels: Vec<u32> = match listing.encoding {
            TexEncoding::Dxt1 => crate::codec::dxt::decode_dxt1(level0, listing.width, listing.height),
            TexEncoding::Dxt5 => crate::codec::dxt::decode_dxt5(level0, listing.width, listing.height),
            TexEncoding::Rgb565 => level0
                .chunks_exact(2)
                .map(|c| {
                    let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                    let r = (((v >> 11) & 0x1F) * 255 + 15) / 31;
                    let g = (((v >> 5) & 0x3F) * 255 + 31) / 63;
                    let b = ((v & 0x1F) * 255 + 15) / 31;
                    0xFF00_0000 | (r << 16) | (g << 8) | b
                })
                .collect(),
            TexEncoding::Etc1 => crate::codec::etc1::decode_etc1(level0, listing.width, listing.height),
            TexEncoding::Rgba4444 => level0
                .chunks_exact(2)
                .map(|c| {
                    let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                    let r = ((v >> 12) & 0xF) * 17;
                    let g = ((v >> 8) & 0xF) * 17;
                    let b = ((v >> 4) & 0xF) * 17;
                    let a = (v & 0xF) * 17;
                    (a << 24) | (r << 16) | (g << 8) | b
                })
                .collect(),
            TexEncoding::Rgba32 => level0
                .chunks_exact(4)
                .map(|c| ((c[3] as u32) << 24) | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
                .collect(),
            _ => return None,
        };
        return Some((listing.width, listing.height, listing.encoding_raw, pixels));
    }
    None
}

/// One edit slot per original manifest entry: `None` deletes the entry,
/// `Some((line, listing))` keeps/replaces it (listing `None` = affiliate).
pub struct CacheEdit {
    pub entries: Vec<Option<(String, Option<Vec<u8>>)>>,
    pub appended: Vec<(String, Option<Vec<u8>>)>,
}

/// Applies a [CacheEdit]: rewrites the manifest (original structure kept,
/// texture lines substituted by ordinal, additions appended), .dat and .toc.
pub fn write_cache_edit(
    dir: &std::path::Path,
    cache_name: &str,
    cache: &TexdbCache,
    edit: &CacheEdit,
) -> Result<String, String> {
    let mut slots: Vec<Option<&[u8]>> = Vec::new();
    for slot in &edit.entries {
        if let Some((_, listing)) = slot {
            slots.push(listing.as_deref());
        }
    }
    for (_, listing) in &edit.appended {
        slots.push(listing.as_deref());
    }
    let (dat, toc) = rebuild_dat_toc(&slots);
    let mut out_lines: Vec<String> = Vec::new();
    let mut ordinal = 0usize;
    for raw_line in cache.raw_txt.lines() {
        let line = raw_line.trim_end();
        if line.trim_start().starts_with('"') {
            if let Some(Some((new_line, _))) = edit.entries.get(ordinal) {
                out_lines.push(new_line.clone());
            }
            ordinal += 1;
        } else {
            out_lines.push(line.to_string());
        }
    }
    for (line, _) in &edit.appended {
        out_lines.push(line.clone());
    }
    let mut txt = out_lines.join("\n");
    txt.push('\n');
    std::fs::write(dir.join(format!("{cache_name}.txt")), txt).map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{cache_name}.{}.dat", cache.variant)), &dat)
        .map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{cache_name}.{}.toc", cache.variant)), &toc)
        .map_err(|e| e.to_string())?;
    let notes = sync_after_write(dir, cache_name, cache);
    Ok(notes)
}

fn with_notes(base: String, notes: String) -> String {
    if notes.is_empty() {
        base
    } else {
        format!("{base};{notes}")
    }
}

/// Encodes one texture (level 0 + full mip chain when requested) into a
/// listing for an arbitrary target encoding. None when the encoding cannot
/// be produced (Unknown) or a level encodes to nothing.
fn encode_listing_from_pixels(
    name: &str,
    pixels: &[u32],
    width: usize,
    height: usize,
    encoding: TexEncoding,
    full_chain: bool,
    use_rle: bool,
) -> Option<Vec<u8>> {
    let mut levels: Vec<(Vec<u32>, usize, usize)> = vec![(pixels.to_vec(), width, height)];
    if full_chain {
        let src = crate::mipmap::PixelImage::new(pixels.to_vec(), width, height);
        for l in crate::mipmap::generate_chain(&src, crate::mipmap::MipFilter::Box) {
            levels.push((l.pixels, l.width, l.height));
        }
    }
    let encoded: Vec<Vec<u8>> = levels
        .iter()
        .map(|(px, w, h)| match encoding {
            TexEncoding::Dxt5 => crate::codec::dxt::encode_dxt5(px, *w, *h),
            TexEncoding::Dxt1 => crate::codec::dxt::encode_dxt1(px, *w, *h),
            TexEncoding::Etc1 => crate::codec::etc1::encode_etc1(px, *w, *h),
            TexEncoding::Rgb565 => rgb565_bytes(px),
            TexEncoding::Rgba4444 => rgba4444_bytes(px),
            TexEncoding::Rgba32 => rgba32_bytes(px),
            TexEncoding::Unknown(_) => Vec::new(),
        })
        .collect();
    if encoded.iter().any(|e| e.is_empty()) {
        return None;
    }
    let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
    build_listing(name, encoding, width, height, &refs, use_rle)
}

/// Box-average downscale of an ARGB image (also the average color at 1x1).
fn scale_box(px: &[u32], w: usize, h: usize, tw: usize, th: usize) -> Vec<u32> {
    if w == 0 || h == 0 || px.len() < w * h {
        return vec![0xFF00_0000; tw * th];
    }
    let mut out = Vec::with_capacity(tw * th);
    for ty in 0..th {
        let y0 = ty * h / th;
        let y1 = (((ty + 1) * h / th).max(y0 + 1)).min(h);
        for tx in 0..tw {
            let x0 = tx * w / tw;
            let x1 = (((tx + 1) * w / tw).max(x0 + 1)).min(w);
            let (mut a, mut r, mut g, mut b, mut n) = (0u64, 0u64, 0u64, 0u64, 0u64);
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = px[y * w + x] as u64;
                    a += (p >> 24) & 0xFF;
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                    n += 1;
                }
            }
            out.push((((a / n) as u32) << 24) | (((r / n) as u32) << 16) | (((g / n) as u32) << 8) | ((b / n) as u32));
        }
    }
    out
}

/// One .tmb thumbnail record: a listing whose payload is a tiny version of
/// the texture — 1x1 average color for RGBA32 textures, otherwise an 8x8
/// downscale (ETC1 kept only inside the etc variant's .tmb, DXT-family
/// thumbnails stored as RGB565), matching the shipped game caches.
fn thumb_record(
    name: &str,
    pixels: Option<(&[u32], usize, usize)>,
    encoding: TexEncoding,
    variant: &str,
) -> Vec<u8> {
    let (tw, th, enc_raw, payload): (usize, usize, u16, Vec<u8>) = match pixels {
        None => (1, 1, 0x1401, rgba32_bytes(&[0xFF00_0000])),
        Some((px, w, h)) => match encoding {
            TexEncoding::Rgba32 => (1, 1, 0x1401, rgba32_bytes(&scale_box(px, w, h, 1, 1))),
            TexEncoding::Etc1 if variant == "etc" => {
                let s = scale_box(px, w, h, 8, 8);
                (8, 8, 0x8D64, crate::codec::etc1::encode_etc1(&s, 8, 8))
            }
            TexEncoding::Rgba4444 => {
                let s = scale_box(px, w, h, 8, 8);
                (8, 8, 0x8034, rgba4444_bytes(&s))
            }
            _ => {
                let s = scale_box(px, w, h, 8, 8);
                (8, 8, 0x8033, rgb565_bytes(&s))
            }
        },
    };
    let mut rec = Vec::with_capacity(16 + payload.len());
    rec.extend_from_slice(&hash_name(name).to_le_bytes());
    rec.extend_from_slice(&enc_raw.to_le_bytes());
    rec.extend_from_slice(&(tw as u16).to_le_bytes());
    rec.extend_from_slice(&(th as u16).to_le_bytes());
    rec.extend_from_slice(&((payload.len() + 4) as u32).to_le_bytes());
    rec.extend_from_slice(&0u32.to_le_bytes());
    rec.extend_from_slice(&payload);
    rec
}

/// Walks a .tmb file as concatenated listings; None when the framing does
/// not consume the file exactly (e.g. the shipped broken .360.tmb).
fn parse_tmb_records(bytes: &[u8]) -> Option<Vec<(u16, Vec<u8>)>> {
    let mut out = Vec::new();
    let mut off = 0usize;
    while off < bytes.len() {
        if off + 16 > bytes.len() {
            return None;
        }
        let hash = u16::from_le_bytes(bytes[off..off + 2].try_into().ok()?);
        let csize = u32::from_le_bytes(bytes[off + 8..off + 12].try_into().ok()?) as usize;
        let span = csize.checked_add(12)?;
        if span < 16 || off + span > bytes.len() {
            return None;
        }
        out.push((hash, bytes[off..off + span].to_vec()));
        off += span;
    }
    Some(out)
}

/// Keeps the rest of a cache folder consistent after the active variant and
/// the manifest were rewritten. TXD Tool declares a cache corrupted unless,
/// for every variant family it can load: the variant's .toc count equals
/// the manifest count AND its .tmb thumbnail count + alias count equals the
/// manifest count. So: every other VALID variant receives the same edit at
/// listing level (untouched textures keep that variant's raw spans;
/// new/changed textures are re-encoded into that variant's encoding), and
/// every .tmb is regenerated with one record per non-affiliate texture
/// (old records reused byte-for-byte for unchanged textures). Variants or
/// .tmb files that fail to parse (the shipped broken .360 family) are left
/// alone, exactly like TXD Tool ignores them. Returns machine-style notes.
fn sync_after_write(dir: &std::path::Path, cache_name: &str, old: &TexdbCache) -> String {
    use std::collections::{BTreeSet, HashMap};
    let Ok(new) = TexdbCache::load(dir, cache_name) else {
        return String::new();
    };
    let mut notes: Vec<String> = Vec::new();
    let old_index: HashMap<String, usize> = old
        .manifest
        .iter()
        .enumerate()
        .map(|(i, e)| (e.name.clone(), i))
        .collect();
    let unchanged = |new_i: usize, name: &str| -> bool {
        old_index.get(name).map_or(false, |&oi| {
            old.manifest[oi].affiliate.is_none() && old.raw_listing(oi) == new.raw_listing(new_i)
        })
    };
    let mut disk_variants: Vec<String> = Vec::new();
    let mut disk_tmbs: BTreeSet<String> = BTreeSet::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let fname = entry.file_name().to_string_lossy().to_string();
            if let Some(rest) = fname.strip_prefix(&format!("{cache_name}.")) {
                if let Some(v) = rest.strip_suffix(".toc") {
                    disk_variants.push(v.to_string());
                } else if let Some(v) = rest.strip_suffix(".tmb") {
                    disk_tmbs.insert(v.to_string());
                }
            }
        }
    }
    disk_variants.sort();
    // ---- other variants ----
    let mut variant_enc: HashMap<String, HashMap<String, TexEncoding>> = HashMap::new();
    for v in &disk_variants {
        if *v == new.variant {
            continue;
        }
        let Ok(vold) =
            TexdbCache::load_variant(dir, cache_name, v, &old.manifest, &old.raw_txt)
        else {
            continue;
        };
        let vold_index: HashMap<String, usize> = vold
            .manifest
            .iter()
            .enumerate()
            .map(|(i, e)| (e.name.clone(), i))
            .collect();
        let mut slots: Vec<Option<Vec<u8>>> = Vec::new();
        let mut enc_map: HashMap<String, TexEncoding> = HashMap::new();
        let mut failed = false;
        for (ni, entry) in new.manifest.iter().enumerate() {
            if entry.affiliate.is_some() {
                slots.push(None);
                continue;
            }
            let lname = entry.name.clone();
            if unchanged(ni, &entry.name) {
                if let Some(&voi) = vold_index.get(&lname) {
                    slots.push(vold.raw_listing(voi).map(|b| b.to_vec()));
                    if let Some(l) = &vold.listings[voi] {
                        enc_map.insert(lname, l.encoding);
                    }
                    continue;
                }
            }
            let enc = vold_index
                .get(&lname)
                .and_then(|&voi| vold.listings[voi].as_ref().map(|l| l.encoding))
                .or_else(|| new.listings[ni].as_ref().map(|l| l.encoding))
                .unwrap_or(TexEncoding::Rgb565);
            if matches!(enc, TexEncoding::Unknown(_)) {
                failed = true;
                break;
            }
            let Some(px) = new.decode_pixels(ni) else {
                failed = true;
                break;
            };
            let full_chain = new.listings[ni].as_ref().map_or(false, |l| l.full_chain);
            let use_rle = new.listings[ni].as_ref().map_or(true, |l| l.rle_indicator != 0);
            match encode_listing_from_pixels(
                &entry.name,
                &px,
                entry.width,
                entry.height,
                enc,
                full_chain,
                use_rle,
            ) {
                Some(listing) => {
                    slots.push(Some(listing));
                    enc_map.insert(lname, enc);
                }
                None => {
                    failed = true;
                    break;
                }
            }
        }
        if failed {
            notes.push(format!("variant_skip={v}"));
            continue;
        }
        let refs: Vec<Option<&[u8]>> = slots.iter().map(|s| s.as_deref()).collect();
        let (dat, toc) = rebuild_dat_toc(&refs);
        if std::fs::write(dir.join(format!("{cache_name}.{v}.dat")), &dat).is_ok()
            && std::fs::write(dir.join(format!("{cache_name}.{v}.toc")), &toc).is_ok()
        {
            notes.push(format!("variant_sync={v}"));
            variant_enc.insert(v.clone(), enc_map);
        }
    }
    // ---- thumbnails ----
    let mut tmb_variants: BTreeSet<String> = disk_tmbs;
    tmb_variants.insert(new.variant.clone());
    for v in variant_enc.keys() {
        tmb_variants.insert(v.clone());
    }
    let old_real: Vec<&ManifestEntry> =
        old.manifest.iter().filter(|e| e.affiliate.is_none()).collect();
    for t in &tmb_variants {
        let path = dir.join(format!("{cache_name}.{t}.tmb"));
        let mut old_records: HashMap<String, Vec<u8>> = HashMap::new();
        if path.exists() {
            let Ok(bytes) = std::fs::read(&path) else { continue };
            let Some(recs) = parse_tmb_records(&bytes) else { continue };
            // Positional pairing first (records sit in manifest order);
            // fall back to hash matching when counts desynced (a deleted
            // texture shifts positions). Hash collisions (e.g. cd11c and
            // goldrim share 0xE023 in the shipped cache) make the pure
            // hash map hand a texture its namesake's thumbnail.
            let paired = recs.len().min(old_real.len());
            let positional_ok = (0..paired)
                .all(|i| recs[i].0 == hash_name(&old_real[i].name));
            if positional_ok {
                for (i, (_, rec)) in recs.into_iter().enumerate() {
                    if i < old_real.len() {
                        old_records.insert(old_real[i].name.clone(), rec);
                    }
                }
            } else {
                let mut by_hash: HashMap<u16, Vec<u8>> = HashMap::new();
                for (h, rec) in recs {
                    by_hash.entry(h).or_insert(rec);
                }
                for e in &old_real {
                    if let Some(rec) = by_hash.get(&hash_name(&e.name)) {
                        old_records.insert(e.name.clone(), rec.clone());
                    }
                }
            }
        }
        let mut out: Vec<u8> = Vec::new();
        for (ni, entry) in new.manifest.iter().enumerate() {
            if entry.affiliate.is_some() {
                continue;
            }
            let lname = entry.name.clone();
            if unchanged(ni, &entry.name) {
                if let Some(rec) = old_records.get(&lname) {
                    out.extend_from_slice(rec);
                    continue;
                }
            }
            let enc = variant_enc
                .get(t)
                .and_then(|m| m.get(&lname).copied())
                .or_else(|| {
                    if *t == new.variant {
                        new.listings[ni].as_ref().map(|l| l.encoding)
                    } else {
                        None
                    }
                })
                .or_else(|| new.listings[ni].as_ref().map(|l| l.encoding))
                .unwrap_or(TexEncoding::Rgb565);
            let px = new.decode_pixels(ni);
            let rec = thumb_record(
                &entry.name,
                px.as_ref().map(|v| (v.as_slice(), entry.width, entry.height)),
                enc,
                t,
            );
            out.extend_from_slice(&rec);
        }
        if std::fs::write(&path, &out).is_ok() {
            notes.push(format!("tmb_sync={t}"));
        }
    }
    notes.join(";")
}

fn current_edit(cache: &TexdbCache) -> CacheEdit {
    CacheEdit {
        entries: (0..cache.manifest.len())
            .map(|i| {
                Some((
                    cache.manifest[i].line.clone(),
                    cache.raw_listing(i).map(|b| b.to_vec()),
                ))
            })
            .collect(),
        appended: Vec::new(),
    }
}

/// Deletes textures by manifest index. Affiliates pointing at a deleted
/// texture are removed too (they would dangle). Returns a report line.
pub fn delete_textures(
    dir: &std::path::Path,
    cache_name: &str,
    indices: &[usize],
) -> Result<String, String> {
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    let mut edit = current_edit(&cache);
    let mut deleted_names: Vec<String> = Vec::new();
    for &i in indices {
        if i < edit.entries.len() && edit.entries[i].is_some() {
            deleted_names.push(cache.manifest[i].name.to_lowercase());
            edit.entries[i] = None;
        }
    }
    let mut affiliates_removed = 0usize;
    for (i, entry) in cache.manifest.iter().enumerate() {
        if edit.entries[i].is_none() {
            continue;
        }
        if let Some(target) = &entry.affiliate {
            if deleted_names.contains(&target.to_lowercase()) {
                edit.entries[i] = None;
                affiliates_removed += 1;
            }
        }
    }
    let notes = write_cache_edit(dir, cache_name, &cache, &edit)?;
    Ok(with_notes(
        format!(
            "deleted={};affiliates_removed={}",
            deleted_names.len(),
            affiliates_removed
        ),
        notes,
    ))
}

/// Renames one texture: manifest line, listing hash, and every affiliate
/// pointing at it are updated. Returns a report line.
pub fn rename_texture(
    dir: &std::path::Path,
    cache_name: &str,
    index: usize,
    new_name: &str,
) -> Result<String, String> {
    let new_name = new_name.trim();
    if new_name.is_empty() || new_name.contains('"') {
        return Err("nama baru tidak valid".to_string());
    }
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    if index >= cache.manifest.len() || cache.manifest[index].affiliate.is_some() {
        return Err("entri bukan tekstur asli".to_string());
    }
    if cache
        .manifest
        .iter()
        .enumerate()
        .any(|(i, e)| i != index && e.name.eq_ignore_ascii_case(new_name))
    {
        return Err(format!("nama '{new_name}' sudah dipakai"));
    }
    let old_name = cache.manifest[index].name.clone();
    let mut edit = current_edit(&cache);
    if let Some(Some((line, listing))) = edit.entries.get_mut(index) {
        *line = line.replacen(&format!("\"{old_name}\""), &format!("\"{new_name}\""), 1);
        if let Some(bytes) = listing {
            bytes[0..2].copy_from_slice(&hash_name(new_name).to_le_bytes());
        }
    }
    for (i, entry) in cache.manifest.iter().enumerate() {
        if entry.affiliate.as_deref() == Some(old_name.as_str()) {
            if let Some(Some((line, _))) = edit.entries.get_mut(i) {
                *line = line
                    .replace(
                        &format!("affiliate=\"{old_name}\""),
                        &format!("affiliate=\"{new_name}\""),
                    )
                    .replace(
                        &format!("\"affiliate={old_name}\""),
                        &format!("\"affiliate={new_name}\""),
                    );
            }
        }
    }
    let notes = write_cache_edit(dir, cache_name, &cache, &edit)?;
    Ok(with_notes(format!("renamed={old_name}->{new_name}"), notes))
}

/// Adds an affiliate (alias) entry pointing at the texture at `target_index`.
pub fn add_alias(
    dir: &std::path::Path,
    cache_name: &str,
    target_index: usize,
    alias_name: &str,
) -> Result<String, String> {
    let alias_name = alias_name.trim();
    if alias_name.is_empty() || alias_name.contains('"') {
        return Err("nama alias tidak valid".to_string());
    }
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    if target_index >= cache.manifest.len() || cache.manifest[target_index].affiliate.is_some() {
        return Err("target bukan tekstur asli".to_string());
    }
    if cache.manifest.iter().any(|e| e.name.eq_ignore_ascii_case(alias_name)) {
        return Err(format!("nama '{alias_name}' sudah dipakai"));
    }
    let target = &cache.manifest[target_index];
    let mut edit = current_edit(&cache);
    edit.appended.push((
        format!("\"{}\" \"affiliate={}\"", alias_name, target.name),
        None,
    ));
    let notes = write_cache_edit(dir, cache_name, &cache, &edit)?;
    Ok(with_notes(
        format!("alias={alias_name};target={}", target.name),
        notes,
    ))
}

/// Decodes every level of a texture to pixels (level 0 first).
fn texture_level_pixels(cache: &TexdbCache, index: usize) -> Option<Vec<(usize, usize, Vec<u32>)>> {
    let listing = cache.listings.get(index)?.as_ref()?;
    let raw = cache.decode_raw(index)?;
    let mut levels = Vec::new();
    let mut offset = 0usize;
    let (mut w, mut h) = (listing.width, listing.height);
    loop {
        let size = listing.encoding.level_size(w, h)?;
        if offset + size > raw.len() {
            return None;
        }
        let slice = &raw[offset..offset + size];
        let pixels = pixels_for_encoding(listing.encoding, slice, w, h)?;
        levels.push((w, h, pixels));
        offset += size;
        if !listing.full_chain || (w == 1 && h == 1) {
            break;
        }
        w = (w / 2).max(1);
        h = (h / 2).max(1);
    }
    Some(levels)
}

fn pixels_for_encoding(encoding: TexEncoding, data: &[u8], w: usize, h: usize) -> Option<Vec<u32>> {
    let px = match encoding {
        TexEncoding::Dxt1 => crate::codec::dxt::decode_dxt1(data, w, h),
        TexEncoding::Dxt5 => crate::codec::dxt::decode_dxt5(data, w, h),
        TexEncoding::Etc1 => crate::codec::etc1::decode_etc1(data, w, h),
        TexEncoding::Rgb565 => data
            .chunks_exact(2)
            .map(|c| {
                let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                let r = (((v >> 11) & 0x1F) * 255 + 15) / 31;
                let g = (((v >> 5) & 0x3F) * 255 + 31) / 63;
                let b = ((v & 0x1F) * 255 + 15) / 31;
                0xFF00_0000 | (r << 16) | (g << 8) | b
            })
            .collect(),
        TexEncoding::Rgba4444 => data
            .chunks_exact(2)
            .map(|c| {
                let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                let r = ((v >> 12) & 0xF) * 17;
                let g = ((v >> 8) & 0xF) * 17;
                let b = ((v >> 4) & 0xF) * 17;
                let a = (v & 0xF) * 17;
                (a << 24) | (r << 16) | (g << 8) | b
            })
            .collect(),
        TexEncoding::Rgba32 => data
            .chunks_exact(4)
            .map(|c| ((c[3] as u32) << 24) | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
            .collect(),
        TexEncoding::Unknown(_) => return None,
    };
    Some(px)
}

/// Repacks every texture of the cache into a target family:
/// 0 = DXT (DXT5 when the texture has alpha, else DXT1),
/// 1 = ETC1 (alpha dropped), 2 = raw (RGBA4444 with alpha, else RGB565).
/// Entries that cannot be decoded keep their original bytes.
pub fn repack_cache(
    dir: &std::path::Path,
    cache_name: &str,
    target: i32,
) -> Result<String, String> {
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    let mut edit = current_edit(&cache);
    let (mut converted, mut kept, mut alpha_dropped) = (0usize, 0usize, 0usize);
    for i in 0..cache.manifest.len() {
        if cache.manifest[i].affiliate.is_some() {
            continue;
        }
        let Some(levels) = texture_level_pixels(&cache, i) else {
            kept += 1;
            continue;
        };
        let has_alpha = levels[0].2.iter().any(|p| (p >> 24) & 0xFF < 255);
        let encoding = match target {
            1 => TexEncoding::Etc1,
            2 => {
                if has_alpha {
                    TexEncoding::Rgba4444
                } else {
                    TexEncoding::Rgb565
                }
            }
            _ => {
                if has_alpha {
                    TexEncoding::Dxt5
                } else {
                    TexEncoding::Dxt1
                }
            }
        };
        if target == 1 && has_alpha {
            alpha_dropped += 1;
        }
        let encoded: Vec<Vec<u8>> = levels
            .iter()
            .map(|(w, h, px)| match encoding {
                TexEncoding::Dxt5 => crate::codec::dxt::encode_dxt5(px, *w, *h),
                TexEncoding::Dxt1 => crate::codec::dxt::encode_dxt1(px, *w, *h),
                TexEncoding::Etc1 => crate::codec::etc1::encode_etc1(px, *w, *h),
                TexEncoding::Rgb565 => rgb565_bytes(px),
                TexEncoding::Rgba4444 => rgba4444_bytes(px),
                _ => Vec::new(),
            })
            .collect();
        let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
        let entry = &cache.manifest[i];
        let Some(listing) = build_listing(&entry.name, encoding, levels[0].0, levels[0].1, &refs, true)
        else {
            kept += 1;
            continue;
        };
        let line = import_manifest_line(
            &entry.name,
            levels[0].0,
            levels[0].1,
            crc32_ieee(&encoded[0]),
            crc32_ieee(&encoded[0]),
            encoding,
            has_alpha && target != 1,
            entry.line.contains("camnorm=1"),
            entry.line.contains("hassibling=1"),
        );
        edit.entries[i] = Some((line, Some(listing)));
        converted += 1;
    }
    let notes = write_cache_edit(dir, cache_name, &cache, &edit)?;
    Ok(with_notes(
        format!("converted={converted};kept={kept};alpha_dropped={alpha_dropped}"),
        notes,
    ))
}

/// Extracts texture names from a RenderWare DFF model by walking its chunk
/// tree and reading TEXTURE (0x06) struct payloads (name is a fixed 32-byte
/// field after filter/addressing bytes). Order-preserving, deduplicated.
pub fn dff_texture_names(data: &[u8]) -> Vec<String> {
    fn u32le(data: &[u8], at: usize) -> u32 {
        u32::from_le_bytes(data[at..at + 4].try_into().unwrap())
    }
    fn walk(data: &[u8], start: usize, end: usize, depth: usize, out: &mut Vec<String>) {
        if depth > 12 {
            return;
        }
        let mut pos = start;
        while pos + 12 <= end {
            let ty = u32le(data, pos);
            let size = u32le(data, pos + 4) as usize;
            let body = pos + 12;
            let chunk_end = body.saturating_add(size).min(end);
            if ty == 0x06 {
                // TEXTURE chunk: children are a small STRUCT (filter/addressing)
                // then STRING (0x02) chunks — the first string is the texture
                // name, the second its mask name. (Verified byte-level against
                // a real vehicle DFF; older fixed 32-byte name fields are not
                // used by RW 3.7+ streams like this one.)
                let mut child = body;
                while child + 12 <= chunk_end {
                    let cty = u32le(data, child);
                    let csize = u32le(data, child + 4) as usize;
                    let cbody = child + 12;
                    if cty == 0x02 {
                        let cend = cbody.saturating_add(csize).min(chunk_end);
                        let raw = &data[cbody..cend];
                        let name: String = raw
                            .iter()
                            .take_while(|&&b| b != 0)
                            .map(|&b| b as char)
                            .collect::<String>()
                            .trim()
                            .to_string();
                        if !name.is_empty() && !out.contains(&name) {
                            out.push(name);
                        }
                        break; // first STRING only; the next one is the mask
                    }
                    if csize == 0 {
                        break;
                    }
                    child = cbody + csize;
                }
            } else if ty != 0x01 && size > 0 {
                walk(data, body, chunk_end, depth + 1, out);
            }
            if size == 0 {
                break;
            }
            pos = body + size;
        }
    }
    let mut out = Vec::new();
    walk(data, 0, data.len(), 0, &mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_vectors_from_real_caches() {
        assert_eq!(hash_name("gunshell"), 0x126C);
        assert_eq!(hash_name("menu_bgmap"), 0x756B);
        assert_eq!(hash_name("splash_up1"), 0x897B);
    }

    #[test]
    fn quote_tokens_keeps_quoted_spans_whole() {
        assert_eq!(
            quote_tokens(" width=128 \"affiliate=crazy paving\" png=bd59"),
            vec!["width=128", "affiliate=crazy paving", "png=bd59"]
        );
        assert_eq!(quote_tokens(""), Vec::<String>::new());
        assert_eq!(quote_tokens("a=1  b=2"), vec!["a=1", "b=2"]);
    }

    #[test]
    fn affiliate_target_with_spaces_parses_whole() {
        // Verbatim lines from the real gta3 cache manifest (dataset
        // gta3.txt lines 3372 + 3575). The pre-fix parser truncated the
        // affiliate target to "crazy".
        let text = "\"crazy paving\" width=128 height=128 png=bd599eec img=86271d52\n\
                    \"crazy paving_lod\" \"affiliate=crazy paving\"\n";
        let entries = parse_manifest(text);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].name, "crazy paving");
        assert_eq!(entries[0].width, 128);
        assert_eq!(entries[0].height, 128);
        assert_eq!(entries[0].affiliate, None);
        assert_eq!(entries[1].name, "crazy paving_lod");
        assert_eq!(entries[1].affiliate.as_deref(), Some("crazy paving"));
    }

    #[test]
    fn affiliate_quoted_value_form_still_parses() {
        // The other real-world spelling: affiliate="target" (key=value with
        // a quoted value, no space) must keep working.
        let entries = parse_manifest("\"alpha\" width=4 height=4 affiliate=\"beta\"\n");
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].affiliate.as_deref(), Some("beta"));
    }

    #[test]
    fn rle_roundtrip_with_runs_and_indicator_collision() {
        let seg = 4;
        let mut data = Vec::new();
        data.extend_from_slice(&[0x09, 0x09, 0x09, 0x09]); // literal starting with indicator 0x09
        data.extend_from_slice(&[0xAA, 0xBB, 0xCC, 0xDD]); // plain literal
        for _ in 0..10 {
            data.extend_from_slice(&[0x01, 0x02, 0x03, 0x04]); // run of 10
        }
        let encoded = rle_encode(&data, seg, 0x09);
        let decoded = rle_decode(&encoded, seg, 0x09).expect("decode");
        assert_eq!(decoded, data);
    }

    #[test]
    fn rle_decode_game_style_stream() {
        // literal "ABCD", then repeat "WXYZ" x3, indicator 0xFF
        let mut stream = b"ABCD".to_vec();
        stream.extend_from_slice(&[0xFF, 3]);
        stream.extend_from_slice(b"WXYZ");
        let out = rle_decode(&stream, 4, 0xFF).expect("decode");
        assert_eq!(out, b"ABCDWXYZWXYZWXYZ".to_vec());
    }

    fn tiny_png() -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut out, 4, 4);
            enc.set_color(png::ColorType::Rgba);
            enc.set_depth(png::BitDepth::Eight);
            let mut writer = enc.write_header().unwrap();
            writer.write_image_data(&[255, 0, 0, 255].repeat(16)).unwrap();
        }
        out
    }

    #[test]
    fn import_overwrite_and_rebuild_through_files() {
        let level0: Vec<u8> = (0..8u8).collect();
        let listing = build_listing("beta", TexEncoding::Dxt1, 4, 4, &[&level0], true).unwrap();
        let (dat, toc) = rebuild_dat_toc(&[Some(&listing), None]);
        let dir = std::env::temp_dir().join(format!("texdb-import-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("beta.txt"),
            "cat=0 name=Default defaultformat=2\n\"beta\" width=4 height=4\n\"alpha\" width=4 height=4 affiliate=\"beta\"\n",
        )
        .unwrap();
        std::fs::write(dir.join("beta.etc.dat"), &dat).unwrap();
        std::fs::write(dir.join("beta.etc.toc"), &toc).unwrap();
        let opts = ImportOptions {
            format: ImportFormat::CompressedNoAlpha,
            conflict: ConflictPolicy::Overwrite,
            sibling_model: None,
            reduce_to: 0,
            camnorm: true,
            hash_check: true,
            correct_caps: false,
            use_rle: true,
            generate_mipmaps: true,
            mip_filter: 1,
        };
        let report = import_textures(&dir, "beta", &[("beta".to_string(), tiny_png())], &opts).unwrap();
        assert!(report.contains("replaced=1"), "report: {report}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        let (w, h, _enc, px) = decode_pixels_at(&dir, "beta", 0).unwrap();
        assert_eq!((w, h), (4, 4));
        assert!(px.iter().all(|&p| p == 0xFFFF_0000), "imported texture must decode red");
        assert!(cache.manifest[0].line.contains("camnorm=1"));
        let rebuilt = rebuild_cache(&dir, "beta").unwrap();
        assert!(rebuilt.contains("entries=2"), "rebuild: {rebuilt}");
        std::fs::remove_dir_all(&dir).ok();
    }

    fn make_synthetic_cache(dir: &std::path::Path) {
        let level0: Vec<u8> = (0..8u8).collect();
        let listing = build_listing("beta", TexEncoding::Dxt1, 4, 4, &[&level0], true).unwrap();
        let (dat, toc) = rebuild_dat_toc(&[Some(&listing), None]);
        std::fs::create_dir_all(dir).unwrap();
        std::fs::write(
            dir.join("beta.txt"),
            "cat=0 name=Default defaultformat=2\n\"beta\" width=4 height=4\n\"alpha\" width=4 height=4 affiliate=\"beta\"\n",
        )
        .unwrap();
        std::fs::write(dir.join("beta.etc.dat"), &dat).unwrap();
        std::fs::write(dir.join("beta.etc.toc"), &toc).unwrap();
    }

    #[test]
    fn rename_alias_delete_ops() {
        let dir = std::env::temp_dir().join(format!("texdb-ops-{}", std::process::id()));
        make_synthetic_cache(&dir);
        // rename beta -> gamma; affiliate line must follow
        let r = rename_texture(&dir, "beta", 0, "gamma").unwrap();
        assert!(r.contains("beta->gamma"), "{r}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.manifest[0].name, "gamma");
        assert_eq!(cache.manifest[1].affiliate.as_deref(), Some("gamma"));
        assert!(cache.decode_raw(0).is_some());
        // alias to gamma
        let r = add_alias(&dir, "beta", 0, "gamma_alias").unwrap();
        assert!(r.contains("alias=gamma_alias"), "{r}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.manifest.len(), 3);
        assert_eq!(cache.manifest[2].affiliate.as_deref(), Some("gamma"));
        // deleting gamma removes both affiliates too
        let r = delete_textures(&dir, "beta", &[0]).unwrap();
        assert!(r.contains("deleted=1"), "{r}");
        assert!(r.contains("affiliates_removed=2"), "{r}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.manifest.len(), 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn repack_to_etc_and_back() {
        let dir = std::env::temp_dir().join(format!("texdb-repack-{}", std::process::id()));
        make_synthetic_cache(&dir);
        let r = repack_cache(&dir, "beta", 1).unwrap();
        assert!(r.contains("converted=1"), "{r}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.listings[0].unwrap().encoding, TexEncoding::Etc1);
        assert!(cache.decode_pixels(0).is_some());
        let r = repack_cache(&dir, "beta", 0).unwrap();
        assert!(r.contains("converted=1"), "{r}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.listings[0].unwrap().encoding, TexEncoding::Dxt1);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn dff_texture_names_synthetic() {
        // TEXTURE chunk { STRUCT(4B), STRING(name), STRING(mask) } — RW 3.7 layout.
        fn chunk(ty: u32, body: &[u8]) -> Vec<u8> {
            let mut v = Vec::new();
            v.extend_from_slice(&ty.to_le_bytes());
            v.extend_from_slice(&(body.len() as u32).to_le_bytes());
            v.extend_from_slice(&0x1803_FFFFu32.to_le_bytes());
            v.extend_from_slice(body);
            v
        }
        let struct_chunk = chunk(0x01, &[0x06, 0x01, 0x00, 0x00]);
        let name_chunk = chunk(0x02, b"wheel\0\0\0");
        let mask_chunk = chunk(0x02, b"\0\0\0\0");
        let mut tex_body = Vec::new();
        tex_body.extend_from_slice(&struct_chunk);
        tex_body.extend_from_slice(&name_chunk);
        tex_body.extend_from_slice(&mask_chunk);
        let dff = chunk(0x10, &chunk(0x06, &tex_body));
        assert_eq!(dff_texture_names(&dff), vec!["wheel".to_string()]);
    }

    #[test]
    fn write_roundtrip_through_files() {
        // Manifest: one real texture + one affiliate.
        let level0: Vec<u8> = (0..8u8).collect();
        let listing = build_listing("beta", TexEncoding::Dxt1, 4, 4, &[&level0], true)
            .expect("listing builds");
        let (dat, toc) = rebuild_dat_toc(&[Some(&listing), None]);
        let dir = std::env::temp_dir().join(format!("texdb-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("beta.txt"),
            "cat=0 name=Default defaultformat=2\n\"beta\" width=4 height=4\n\"alpha\" width=4 height=4 affiliate=\"beta\"\n",
        )
        .unwrap();
        std::fs::write(dir.join("beta.etc.dat"), &dat).unwrap();
        std::fs::write(dir.join("beta.etc.toc"), &toc).unwrap();
        let cache = TexdbCache::load(&dir, "beta").expect("cache loads");
        assert_eq!(cache.variant, "etc");
        assert!(cache.listings[1].is_none(), "affiliate has no listing");
        assert_eq!(cache.decode_raw(0).unwrap(), level0);
        assert_eq!(cache.raw_listing(0).unwrap(), &listing[..]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn raw_size_models_match_real_textures() {
        // gunshell 32x32 full chain RGB565 = 2730; slidernub DXT5 32x32 full chain = 1392
        assert_eq!(TexEncoding::Rgb565.raw_size(32, 32, true), Some(2730));
        assert_eq!(TexEncoding::Dxt5.raw_size(32, 32, true), Some(1392));
        assert_eq!(TexEncoding::Dxt1.raw_size(1024, 1024, false), Some(524288));
    }
}

// ---------------------------------------------------------------------------
// Cache description for the browser UI (replaces the Kotlin TexdbCache
// parser; the facade in Kotlin only maps these values to its data classes).
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct TexdbListEntry {
    pub name: String,
    pub width: usize,
    pub height: usize,
    pub offset: usize,
    pub size: usize,
    /// The listing's first 4 bytes as u32 (hash u16 | encoding u16 << 16),
    /// matching what the old Kotlin parser exposed.
    pub hash: u32,
    pub has_mipmaps: bool,
    pub png_crc: Option<String>,
    pub img_crc: Option<String>,
    pub category: String,
    /// Manifest props minus width/height/png/img (affiliate included).
    pub flags: Vec<(String, String)>,
}

#[derive(Clone, Debug)]
pub struct CacheDescription {
    pub name: String,
    pub variant: String,
    pub variants: Vec<String>,
    pub dat_file_name: String,
    /// Props of the last `cat=` line (the old parser's "category defaults").
    pub defaults: Vec<(String, String)>,
    pub textures: Vec<TexdbListEntry>,
}

/// Resolves the cache name for a directory: the folder name when
/// `<folder>.txt` exists, otherwise the stem of the single `.txt` inside.
/// Mirrors the old Kotlin loader, including its error texts.
pub fn resolve_cache_name(dir: &std::path::Path) -> Result<String, String> {
    let folder = dir
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    if dir.join(format!("{folder}.txt")).exists() {
        return Ok(folder);
    }
    let mut txts: Vec<std::path::PathBuf> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let p = entry.path();
            if p.is_file()
                && p.extension()
                    .map(|e| e.to_string_lossy().eq_ignore_ascii_case("txt"))
                    .unwrap_or(false)
            {
                txts.push(p);
            }
        }
    }
    if txts.len() == 1 {
        Ok(txts[0]
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default())
    } else {
        Err(format!(
            "Manifes tidak ditemukan: {}. Pilih FOLDER cache-nya (berisi <nama>.txt + .toc + .dat), bukan berkas lain.",
            dir.join(format!("{folder}.txt")).display()
        ))
    }
}

fn parse_props(text: &str) -> Vec<(String, String)> {
    let mut props = Vec::new();
    for token in quote_tokens(text) {
        if let Some((k, v)) = token.split_once('=') {
            props.push((
                k.trim_matches('"').to_string(),
                v.trim_matches('"').to_string(),
            ));
        }
    }
    props
}

/// Per-entry categories + last `cat=` line props, walking the manifest in
/// the same order as `parse_manifest` (only `"`-lines produce entries).
fn manifest_categories(text: &str) -> (Vec<String>, Vec<(String, String)>) {
    let mut categories = Vec::new();
    let mut defaults: Vec<(String, String)> = Vec::new();
    let mut current = "Default".to_string();
    for raw in text.lines() {
        let line = raw.trim();
        if line.starts_with('"') {
            categories.push(current.clone());
        } else if line.starts_with("cat=") {
            defaults = parse_props(line);
            if let Some((_, v)) = defaults.iter().find(|(k, _)| k == "name") {
                current = v.clone();
            }
        }
    }
    (categories, defaults)
}

impl TexdbCache {
    /// Span (start, end) of a manifest index inside the .dat, resolving
    /// affiliates to their target's span.
    pub fn span_for(&self, index: usize) -> Option<(usize, usize)> {
        if let Some(Some(l)) = self.listings.get(index) {
            return Some((l.span_start, l.span_end));
        }
        let target = self.manifest.get(index)?.affiliate.as_ref()?;
        let j = self
            .manifest
            .iter()
            .position(|e| e.affiliate.is_none() && e.name.eq_ignore_ascii_case(target))?;
        let l = self.listings.get(j)?.as_ref()?;
        Some((l.span_start, l.span_end))
    }

    /// The raw .dat span bytes of a manifest index (affiliate-aware).
    pub fn raw_span_bytes(&self, index: usize) -> Option<Vec<u8>> {
        let (start, end) = self.span_for(index)?;
        Some(self.dat[start..end].to_vec())
    }
}

/// Full description of the cache in `dir` for UI listing.
pub fn describe_cache(dir: &std::path::Path) -> Result<CacheDescription, String> {
    let name = resolve_cache_name(dir)?;
    let text = std::fs::read_to_string(dir.join(format!("{name}.txt")))
        .map_err(|e| e.to_string())?;
    let (categories, defaults) = manifest_categories(&text);

    let mut toc_variants: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let fname = entry.file_name().to_string_lossy().into_owned();
            if let Some(rest) = fname
                .strip_prefix(&format!("{name}."))
                .and_then(|r| r.strip_suffix(".toc"))
            {
                toc_variants.push(rest.to_string());
            }
        }
    }
    toc_variants.sort();
    if toc_variants.is_empty() {
        return Err(format!("Tidak ada berkas .toc di {}", dir.display()));
    }

    let cache = TexdbCache::load(dir, &name).map_err(|_| {
        format!(
            "Tidak ada varian cache yang valid di {} (uji: ukuran .dat & jumlah entri)",
            dir.display()
        )
    })?;

    let mut textures = Vec::with_capacity(cache.manifest.len());
    for (i, entry) in cache.manifest.iter().enumerate() {
        let (offset, size, hash, mips) = match cache.span_for(i) {
            Some((start, end)) => {
                let l = cache
                    .listings
                    .get(i)
                    .and_then(|x| x.as_ref())
                    .or_else(|| {
                        entry.affiliate.as_ref().and_then(|t| {
                            cache
                                .manifest
                                .iter()
                                .position(|e| {
                                    e.affiliate.is_none() && e.name.eq_ignore_ascii_case(t)
                                })
                                .and_then(|j| cache.listings.get(j).and_then(|x| x.as_ref()))
                        })
                    });
                match l {
                    Some(l) => (
                        start,
                        end - start,
                        (l.hash as u32) | ((l.encoding_raw as u32) << 16),
                        l.full_chain,
                    ),
                    None => (start, end - start, 0, false),
                }
            }
            None => (0, 0, 0, false),
        };
        let flags: Vec<(String, String)> = entry
            .props
            .iter()
            .filter(|(k, _)| k != "width" && k != "height" && k != "png" && k != "img")
            .cloned()
            .collect();
        textures.push(TexdbListEntry {
            name: entry.name.clone(),
            width: entry.width,
            height: entry.height,
            offset,
            size,
            hash,
            has_mipmaps: mips,
            png_crc: entry.prop("png").map(|s| s.to_string()),
            img_crc: entry.prop("img").map(|s| s.to_string()),
            category: categories
                .get(i)
                .cloned()
                .unwrap_or_else(|| "Default".to_string()),
            flags,
        });
    }

    Ok(CacheDescription {
        dat_file_name: format!("{name}.{}.dat", cache.variant),
        name,
        variant: cache.variant.clone(),
        variants: toc_variants,
        defaults,
        textures,
    })
}

/// Writes the raw .dat span of manifest `index` in the cache at `dir` to
/// `dest` (affiliates resolve to their target, as the old loader did).
pub fn extract_raw_span(
    dir: &std::path::Path,
    index: usize,
    dest: &std::path::Path,
) -> Result<String, String> {
    let name = resolve_cache_name(dir)?;
    let cache = TexdbCache::load(dir, &name).map_err(|e| e.to_string())?;
    let bytes = cache
        .raw_span_bytes(index)
        .ok_or_else(|| "Entri tidak punya data mentah".to_string())?;
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(dest, &bytes).map_err(|e| e.to_string())?;
    Ok(format!("terekstrak {} byte", bytes.len()))
}
