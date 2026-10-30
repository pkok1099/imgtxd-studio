//! mobile texture database (texdb) — `.txt` manifest + `.toc` offsets +
//! `.dat` listings, with the RLE payload codec.
//!
//! Format reverse-engineered from the the community modding wiki ("Mobile textures (SA/VC)")
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

/// Upper bound for texture dimensions parsed from texdb listings and PNG
/// imports. Real game caches are ≤ 2048 (mobile packs ≤ 4096); 8192 matches
/// the cap in `txd::parse`. A faked listing header claiming e.g.
/// 65535×32767 would inflate `expected_size()` and the RLE output cap into
/// multi-GiB allocations → allocation failure aborts the whole process
/// (panic=abort). Listings above the cap are treated as corrupt (no data).
pub const MAX_TEXDB_DIM: usize = 8192;

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
    Dxt1,     // 0x83F0
    Dxt5,     // 0x83F3
    Etc1,     // 0x8D64 GL_ETC1_RGB8_OES
    Rgb565,   // 0x8033 (render-verified) and 0x8363 (same family)
    Rgba4444, // 0x8034 (probable layout, same storage family)
    Rgba32,   // 0x1401 GL_UNSIGNED_BYTE (RGBA bytes)
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
            Self::Dxt1 | Self::Etc1 => Some(width.div_ceil(4) * height.div_ceil(4) * 8),
            Self::Dxt5 => Some(width.div_ceil(4) * height.div_ceil(4) * 16),
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
///
/// `max_out` caps the decoded output size — every repeat/literal group is
/// checked against this bound before extending. Without it, a malicious
/// payload of N bytes can RLE-decode to N×255 bytes (e.g. a 100 MB `.dat`
/// span would allocate ~25 GB before the post-decode `len < expected`
/// check could reject it).
pub fn rle_decode(payload: &[u8], seg: usize, indicator: u8, max_out: usize) -> Option<Vec<u8>> {
    if seg == 0 {
        return None;
    }
    if seg == 8 {
        return rle_decode_seg8(payload, indicator, max_out);
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
            // Bomb guard: count × seg must fit in max_out (and not overflow).
            let added = count.checked_mul(seg)?;
            if out.len() + added > max_out {
                return None;
            }
            for _ in 0..count {
                out.extend_from_slice(segment);
            }
        } else {
            if i + seg - 1 > payload.len() {
                return None;
            }
            if out.len() + seg > max_out {
                return None;
            }
            out.push(b);
            out.extend_from_slice(&payload[i..i + seg - 1]);
            i += seg - 1;
        }
    }
    Some(out)
}

/// Bit-identical `rle_decode` fast path for seg = 8 (the dominant on-disk
/// segment size): literal spans — consecutive units whose first byte differs
/// from the indicator — are located by checking one byte per unit start and
/// copied as one bulk memcpy per span, replacing the per-unit bounds checks,
/// byte `push` and 7-byte tail copies that dominated wall-clock on
/// incompressible payloads. Group parsing, bomb guards (`max_out`) and every
/// `None` condition match the generic decoder exactly; differential-tested
/// in `rle_decode_seg8_matches_generic` (encoded roundtrips + random soup).
fn rle_decode_seg8(payload: &[u8], indicator: u8, max_out: usize) -> Option<Vec<u8>> {
    let len = payload.len();
    let mut out = Vec::new();
    let mut u = 0usize;
    while u < len {
        if payload[u] == indicator {
            // Repeat group: indicator, count, one 8-byte unit.
            if u + 2 + 8 > len {
                return None;
            }
            let count = payload[u + 1] as usize;
            let segment = &payload[u + 2..u + 2 + 8];
            u += 2 + 8;
            // Bomb guard: count × seg must fit in max_out; count <= 255 and
            // seg = 8 cannot overflow.
            let added = count * 8;
            if out.len() + added > max_out {
                return None;
            }
            for _ in 0..count {
                out.extend_from_slice(segment);
            }
        } else {
            // Literal span: full 8-byte units whose first byte is never the
            // indicator decode to the very same payload bytes, so the whole
            // span can be copied verbatim once the scan ends.
            let start = u;
            while u < len && payload[u] != indicator {
                if u + 8 > len {
                    return None;
                }
                u += 8;
            }
            let span = &payload[start..u];
            if out.len() + span.len() > max_out {
                return None;
            }
            out.extend_from_slice(span);
        }
    }
    Some(out)
}

/// Encodes `data` as an RLE stream the game decoder accepts.
/// Runs of identical `seg`-sized units become repeat groups; a literal unit
/// that would start with the indicator byte is emitted as a count-1 repeat
/// group so it can never be misread.
pub fn rle_encode(data: &[u8], seg: usize, indicator: u8) -> Vec<u8> {
    assert!(seg > 0 && data.len().is_multiple_of(seg));
    if seg == 8 {
        rle_encode_seg8(data, indicator)
    } else {
        rle_encode_generic(data, seg, indicator)
    }
}

/// Bit-identical `rle_encode` fast path for seg = 8 (the dominant on-disk
/// segment size): run detection compares whole units as single `u64` loads
/// (`u64::from_le_bytes` on a `[u8; 8]` compiles to one unaligned mov on
/// x86_64/aarch64) instead of per-unit slice `memcmp` with bounds checks —
/// the dominant cost on incompressible data. Emission order and bytes are
/// unchanged (differential-tested in `rle_encode_seg8_matches_generic`).
fn rle_encode_seg8(data: &[u8], indicator: u8) -> Vec<u8> {
    let (chunks, _) = data.as_chunks::<8>();
    let units = chunks.len();
    let mut out = Vec::new();
    let mut u = 0usize;
    while u < units {
        let unit: &[u8; 8] = &chunks[u];
        let unit_word = u64::from_le_bytes(*unit);
        // Count the run of identical units (cap 255 per group).
        let mut run = 1usize;
        while u + run < units && run < 255 && u64::from_le_bytes(chunks[u + run]) == unit_word {
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

/// Reference `rle_encode` for seg != 8, unchanged from the original
/// implementation; also the differential-test oracle for the seg-8 fast path.
fn rle_encode_generic(data: &[u8], seg: usize, indicator: u8) -> Vec<u8> {
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
///
/// Slicing-by-8: the classic T0 table processes one byte per step, while the
/// additional T1..T7 tables fold 8 bytes per iteration with the same
/// polynomial — the result is identical to the byte-at-a-time version by
/// construction (each Tn[i] is the byte algorithm applied to i followed by n
/// zero bytes). A byte-at-a-time tail handles the last `len % 8` bytes.
pub fn crc32_ieee(data: &[u8]) -> u32 {
    const fn next(c: u32) -> u32 {
        if c & 1 != 0 {
            0xEDB8_8320 ^ (c >> 1)
        } else {
            c >> 1
        }
    }
    const TABLE: [u32; 256] = {
        let mut table = [0u32; 256];
        let mut i = 0;
        while i < 256 {
            let (mut c, mut k) = (i as u32, 0);
            while k < 8 {
                c = next(c);
                k += 1;
            }
            table[i] = c;
            i += 1;
        }
        table
    };
    const T: [[u32; 256]; 8] = {
        let mut tables = [[0u32; 256]; 8];
        tables[0] = TABLE;
        let mut n = 1;
        while n < 8 {
            let mut i = 0;
            while i < 256 {
                let prev = tables[n - 1][i];
                tables[n][i] = (prev >> 8) ^ TABLE[(prev & 0xFF) as usize];
                i += 1;
            }
            n += 1;
        }
        tables
    };
    let mut crc = 0xFFFF_FFFFu32;
    let (chunks, tail) = data.as_chunks::<8>();
    for c in chunks {
        let lo = crc
            ^ ((c[0] as u32)
                | ((c[1] as u32) << 8)
                | ((c[2] as u32) << 16)
                | ((c[3] as u32) << 24));
        crc = T[7][(lo & 0xFF) as usize]
            ^ T[6][((lo >> 8) & 0xFF) as usize]
            ^ T[5][((lo >> 16) & 0xFF) as usize]
            ^ T[4][(lo >> 24) as usize]
            ^ T[3][c[4] as usize]
            ^ T[2][c[5] as usize]
            ^ T[1][c[6] as usize]
            ^ T[0][c[7] as usize];
    }
    for &b in tail {
        crc = T[0][((crc ^ b as u32) & 0xFF) as usize] ^ (crc >> 8);
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
    if encoding.raw_size(width, height, full_chain)?
        != levels.iter().map(|l| l.len()).sum::<usize>()
    {
        return None;
    }
    let raw: Vec<u8> = levels.iter().flat_map(|l| l.iter().copied()).collect();
    let seg = encoding.segment_size()?;
    let (payload, indicator): (Vec<u8>, u32) = if use_rle && raw.len().is_multiple_of(seg) {
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
        self.encoding
            .raw_size(self.width, self.height, self.full_chain)
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
        let Some(end) = line[1..].find('"') else {
            continue;
        };
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

/// Affiliate lines carry no dimensions in real manifests; inherit them
/// from the target texture so UIs can show real sizes.
fn inherit_affiliate_dims(manifest: &mut [ManifestEntry]) {
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
        inherit_affiliate_dims(&mut manifest);
        let mut variants: Vec<String> = Vec::new();
        for entry in std::fs::read_dir(dir)? {
            let path = entry?.path();
            let fname = path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .to_string();
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
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "toc too short",
            ));
        }
        let stated = u32::from_le_bytes(toc_bytes[0..4].try_into().unwrap()) as usize;
        if stated != dat.len() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "toc/dat size mismatch",
            ));
        }
        let offsets: Vec<i64> = (0..manifest.len())
            .map(|i| i32::from_le_bytes(toc_bytes[4 + i * 4..8 + i * 4].try_into().unwrap()) as i64)
            .collect();
        let mut listings: Vec<Option<Listing>> = Vec::with_capacity(manifest.len());
        for (i, entry) in manifest.iter().enumerate() {
            let off = offsets[i];
            if off < 0 {
                listings.push(None);
                continue;
            }
            let start = off as usize;
            if start + 16 > dat.len() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "listing out of range",
                ));
            }
            let h = &dat[start..start + 16];
            let hash = u16::from_le_bytes(h[0..2].try_into().unwrap());
            let encoding_raw = u16::from_le_bytes(h[2..4].try_into().unwrap());
            let width = u16::from_le_bytes(h[4..6].try_into().unwrap()) as usize;
            let hm = u16::from_le_bytes(h[6..8].try_into().unwrap());
            let compressed_size = u32::from_le_bytes(h[8..12].try_into().unwrap());
            let rle_indicator = u32::from_le_bytes(h[12..16].try_into().unwrap());
            // The span comes from the listing's own header (span = csize + 12),
            // NOT from the next toc offset: in-place writes append changed
            // listings at the end of the .dat and leave dead spans behind, so
            // offsets are no longer packed in manifest order. In shipped
            // (packed) caches both definitions coincide — the game's own
            // sequential fallback walks exactly this csize chain.
            let end = start + compressed_size as usize + 12;
            if end > dat.len() || end < start + 16 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "listing out of range",
                ));
            }
            if hash != hash_name(&entry.name) {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("listing hash mismatch for {}", entry.name),
                ));
            }
            // Dimension cap (see MAX_TEXDB_DIM): an inflated header would
            // push huge "expected" sizes and RLE caps into every consumer
            // of this listing. Degrade to "no data" for this one texture
            // instead of failing the whole cache or aborting later.
            if width > MAX_TEXDB_DIM || (hm & 0x7FFF) as usize > MAX_TEXDB_DIM {
                listings.push(None);
                continue;
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
    /// The uncompressed path borrows the .dat buffer directly (single copy
    /// into the result instead of payload + result).
    pub fn decode_raw(&self, index: usize) -> Option<Vec<u8>> {
        let listing = self.listings.get(index)?.as_ref()?;
        let expected = listing.expected_size()?;
        let payload = &self.dat[listing.payload_range()];
        if listing.rle_indicator == 0 {
            if payload.len() < expected {
                return None;
            }
            return Some(payload[..expected].to_vec());
        }
        let decoded = rle_decode(
            payload,
            listing.encoding.segment_size()?,
            (listing.rle_indicator & 0xFF) as u8,
            // Cap: 12.5% slop + 4 KiB fixed buffer covers the "few trailing
            // bytes" slop the comment allows on real caches while still
            // stopping a 255× decompression bomb on malicious payloads.
            expected + (expected / 8) + 4096,
        )?;
        if decoded.len() < expected {
            return None;
        }
        Some(decoded[..expected].to_vec())
    }

    /// Level-0 pixels as ARGB u32 for encodings with a pixel decoder
    /// (DXT1/DXT5 via the codec module, RGB565, RGBA4444, RGBA32).
    /// Decodes straight from the borrowed .dat span when the payload is
    /// stored raw (no intermediate copy of the whole texture).
    pub fn decode_pixels(&self, index: usize) -> Option<Vec<u32>> {
        let listing = self.listings.get(index)?.as_ref()?;
        let expected = listing.expected_size()?;
        let payload = &self.dat[listing.payload_range()];
        let decoded_storage;
        let raw: &[u8] = if listing.rle_indicator == 0 {
            if payload.len() < expected {
                return None;
            }
            &payload[..expected]
        } else {
            decoded_storage = rle_decode(
                payload,
                listing.encoding.segment_size()?,
                (listing.rle_indicator & 0xFF) as u8,
                expected + (expected / 8) + 4096,
            )?;
            if decoded_storage.len() < expected {
                return None;
            }
            &decoded_storage[..expected]
        };
        let (w, h) = (listing.width, listing.height);
        let level0_size = listing.encoding.level_size(w, h)?;
        let level0 = &raw[..level0_size.min(raw.len())];
        pixels_for_encoding(listing.encoding, level0, w, h)
    }
}

/// Decodes PNG bytes to ARGB pixels (the `png` crate, EXPAND transform).
pub fn decode_png_argb(bytes: &[u8]) -> Option<(Vec<u32>, usize, usize)> {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    // Cap the decoder's own allocations (row buffers etc.). The png 0.17
    // `Limits` struct has only `bytes` — it can NOT bound the output buffer,
    // which WE allocate below from the attacker-chosen header.
    decoder.set_limits(png::Limits { bytes: 64 << 20 });
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder.read_info().ok()?;
    // Gate the header dimensions BEFORE `output_buffer_size()`: a faked
    // 100000×100000 RGBA header would otherwise request a ~40 GB output
    // allocation and abort the process (panic=abort). Oversized images fail
    // here and the import wizard counts them as skipped.
    {
        let info = reader.info();
        if info.width as usize > MAX_TEXDB_DIM || info.height as usize > MAX_TEXDB_DIM {
            return None;
        }
    }
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).ok()?;
    let (w, h) = (info.width as usize, info.height as usize);
    let data = &buf[..info.buffer_size()];
    let px: Vec<u32> = match info.color_type {
        png::ColorType::Rgba => crate::codec::raw::rgba32_to_argb(data),
        png::ColorType::Rgb => crate::codec::raw::rgb24_to_argb(data),
        png::ColorType::Grayscale => crate::codec::raw::gray8_to_argb(data),
        png::ColorType::GrayscaleAlpha => crate::codec::raw::gray_alpha8_to_argb(data),
        _ => return None,
    };
    if px.len() != w * h {
        return None;
    }
    Some((px, w, h))
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
    /// TXD Tool "fast overwrite": write the result in a clean, defragmented
    /// layout (full .dat rebuild) within this same operation — one sync
    /// total, instead of a separate rebuild_cache pass afterwards.
    pub defragment: bool,
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

/// All fields needed to render one manifest listing line.
struct ManifestTex<'a> {
    name: &'a str,
    width: usize,
    height: usize,
    png_crc: u32,
    img_crc: u32,
    encoding: TexEncoding,
    has_alpha: bool,
    camnorm: bool,
    sibling: bool,
}

fn import_manifest_line(t: &ManifestTex<'_>) -> String {
    format!(
        "\"{}\" width={} height={} png={:08x} img={:08x}{}{}{}{}streammode=1",
        t.name,
        t.width,
        t.height,
        t.png_crc,
        t.img_crc,
        if matches!(t.encoding, TexEncoding::Dxt1 | TexEncoding::Dxt5) {
            " format=7"
        } else {
            ""
        },
        if t.has_alpha { " alphamode=2" } else { "" },
        if t.camnorm { " camnorm=1" } else { "" },
        if t.sibling { " hassibling=1" } else { "" },
    )
}

/// Parses a listing header from raw listing bytes placed at `span_start`.
fn listing_from_bytes(bytes: &[u8], span_start: usize) -> Option<Listing> {
    if bytes.len() < 16 {
        return None;
    }
    let h = &bytes[..16];
    let encoding_raw = u16::from_le_bytes(h[2..4].try_into().ok()?);
    let hm = u16::from_le_bytes(h[6..8].try_into().ok()?);
    Some(Listing {
        hash: u16::from_le_bytes(h[0..2].try_into().ok()?),
        encoding: TexEncoding::from_u16(encoding_raw),
        encoding_raw,
        width: u16::from_le_bytes(h[4..6].try_into().ok()?) as usize,
        height: (hm & 0x7FFF) as usize,
        full_chain: hm & 0x8000 != 0,
        compressed_size: u32::from_le_bytes(h[8..12].try_into().ok()?),
        rle_indicator: u32::from_le_bytes(h[12..16].try_into().ok()?),
        span_start,
        span_end: span_start + bytes.len(),
    })
}

/// One slot of a post-write manifest, in final manifest order.
enum FinalSlot {
    /// Affiliate or data-less entry: toc offset -1.
    NoData,
    /// Keep the old cache's entry byte-identical at its existing offset.
    Unchanged(usize),
    /// New listing bytes (appended to the .dat, or laid out by a rebuild).
    New(Vec<u8>),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum WriteMode {
    /// Append new listings at the end of the .dat; fall back to a full
    /// rebuild layout when the appended bytes exceed half the current .dat
    /// (a rewrite is then cheaper than the dead space it would leave).
    Auto,
    /// Always lay the whole .dat out fresh (explicit Rebuild Cache,
    /// fast-overwrite imports): defragment and reclaim all dead spans.
    ForceRebuild,
}

/// Applies a manifest-level write to the ACTIVE variant and returns the
/// (pre-write, post-write) cache states built in memory — nothing is
/// re-read from disk afterwards; the incremental sync consumes both.
///
/// Append mode touches only: the appended payload bytes at the end of the
/// .dat, the small .toc, and the manifest. Untouched entries keep their
/// offsets and bytes; removed/overwritten spans become dead space that an
/// explicit Rebuild Cache reclaims. Invariants after every call: the .toc
/// holds one offset per manifest entry (-1 for affiliates) and toc[0]
/// equals the .dat length.
fn apply_write(
    dir: &std::path::Path,
    cache_name: &str,
    old: TexdbCache,
    slots: &[FinalSlot],
    new_txt: &str,
    mode: WriteMode,
) -> Result<(TexdbCache, TexdbCache), String> {
    let variant = old.variant.clone();
    let dat_path = dir.join(format!("{cache_name}.{variant}.dat"));
    let toc_path = dir.join(format!("{cache_name}.{variant}.toc"));
    let old_dat_len = old.dat.len();
    let appended_len: usize = slots
        .iter()
        .map(|s| match s {
            FinalSlot::New(b) => b.len(),
            _ => 0,
        })
        .sum();
    let rebuild =
        mode == WriteMode::ForceRebuild || (old_dat_len > 0 && appended_len * 2 > old_dat_len);

    let (new_dat, new_listings): (Vec<u8>, Vec<Option<Listing>>) = if rebuild {
        let refs: Vec<Option<&[u8]>> = slots
            .iter()
            .map(|s| match s {
                FinalSlot::NoData => None,
                FinalSlot::Unchanged(oi) => old.raw_listing(*oi),
                FinalSlot::New(b) => Some(&b[..]),
            })
            .collect();
        let (dat, toc) = rebuild_dat_toc(&refs);
        std::fs::write(&dat_path, &dat).map_err(|e| e.to_string())?;
        std::fs::write(&toc_path, &toc).map_err(|e| e.to_string())?;
        let mut listings = Vec::with_capacity(slots.len());
        let mut cursor = 0usize;
        for r in &refs {
            match r {
                None => listings.push(None),
                Some(b) => {
                    listings.push(listing_from_bytes(b, cursor));
                    cursor += b.len();
                }
            }
        }
        (dat, listings)
    } else {
        let mut offsets: Vec<i64> = Vec::with_capacity(slots.len());
        let mut appended: Vec<u8> = Vec::new();
        let mut cursor = old_dat_len;
        for s in slots {
            match s {
                FinalSlot::NoData => offsets.push(-1),
                FinalSlot::Unchanged(oi) => {
                    let l = old
                        .listings
                        .get(*oi)
                        .and_then(|l| l.as_ref())
                        .ok_or("slot Unchanged tanpa listing")?;
                    offsets.push(l.span_start as i64);
                }
                FinalSlot::New(b) => {
                    offsets.push(cursor as i64);
                    appended.extend_from_slice(b);
                    cursor += b.len();
                }
            }
        }
        let mut new_listings: Vec<Option<Listing>> = Vec::with_capacity(slots.len());
        for (s, off) in slots.iter().zip(offsets.iter()) {
            new_listings.push(match s {
                FinalSlot::NoData => None,
                FinalSlot::Unchanged(oi) => old.listings.get(*oi).and_then(|l| *l),
                FinalSlot::New(b) => listing_from_bytes(b, *off as usize),
            });
        }
        if !appended.is_empty() {
            use std::io::{Seek, SeekFrom, Write};
            let mut f = std::fs::OpenOptions::new()
                .write(true)
                .open(&dat_path)
                .map_err(|e| e.to_string())?;
            f.seek(SeekFrom::End(0)).map_err(|e| e.to_string())?;
            f.write_all(&appended).map_err(|e| e.to_string())?;
        }
        let mut toc = Vec::with_capacity(4 + offsets.len() * 4);
        toc.extend_from_slice(&(cursor as u32).to_le_bytes());
        for o in &offsets {
            toc.extend_from_slice(&(*o as i32).to_le_bytes());
        }
        std::fs::write(&toc_path, &toc).map_err(|e| e.to_string())?;
        // One flat clone for the post-write state; the pre-write state
        // keeps the original buffer (its spans must stay pre-write).
        let mut dat = old.dat.clone();
        dat.extend_from_slice(&appended);
        (dat, new_listings)
    };

    std::fs::write(dir.join(format!("{cache_name}.txt")), new_txt).map_err(|e| e.to_string())?;
    let mut manifest = parse_manifest(new_txt);
    inherit_affiliate_dims(&mut manifest);
    let new_cache = TexdbCache {
        manifest,
        raw_txt: new_txt.to_string(),
        listings: new_listings,
        variant: variant.clone(),
        dat: new_dat,
    };
    let old_cache = TexdbCache {
        manifest: old.manifest,
        raw_txt: old.raw_txt,
        listings: old.listings,
        variant,
        dat: old.dat,
    };
    Ok((old_cache, new_cache))
}

/// Metadata-only view of one variant family: listing headers read via
/// seeks (no whole-.dat load). Enough for the incremental sync to keep
/// unchanged entries at their existing offsets. `None` mirrors
/// [TexdbCache::load_variant] failing (family not loadable — ignored).
struct VariantMeta {
    listings: Vec<Option<Listing>>,
    dat_len: usize,
}

fn load_variant_meta(
    dir: &std::path::Path,
    cache_name: &str,
    variant: &str,
    manifest: &[ManifestEntry],
) -> Option<VariantMeta> {
    use std::io::{Read, Seek, SeekFrom};
    let toc_bytes = std::fs::read(dir.join(format!("{cache_name}.{variant}.toc"))).ok()?;
    if toc_bytes.len() < 4 || (toc_bytes.len() - 4) / 4 < manifest.len() {
        return None;
    }
    let dat_path = dir.join(format!("{cache_name}.{variant}.dat"));
    let dat_len = std::fs::metadata(&dat_path).ok()?.len() as usize;
    if u32::from_le_bytes(toc_bytes[0..4].try_into().ok()?) as usize != dat_len {
        return None;
    }
    let mut f = std::fs::File::open(&dat_path).ok()?;
    let mut listings = Vec::with_capacity(manifest.len());
    for (i, entry) in manifest.iter().enumerate() {
        let off = i32::from_le_bytes(toc_bytes[4 + i * 4..8 + i * 4].try_into().ok()?) as i64;
        if off < 0 {
            listings.push(None);
            continue;
        }
        let start = off as usize;
        if start + 16 > dat_len {
            return None;
        }
        f.seek(SeekFrom::Start(off as u64)).ok()?;
        let mut h = [0u8; 16];
        f.read_exact(&mut h).ok()?;
        let hash = u16::from_le_bytes(h[0..2].try_into().ok()?);
        if hash != hash_name(&entry.name) {
            return None;
        }
        let encoding_raw = u16::from_le_bytes(h[2..4].try_into().ok()?);
        let hm = u16::from_le_bytes(h[6..8].try_into().ok()?);
        let compressed_size = u32::from_le_bytes(h[8..12].try_into().ok()?);
        let span_end = start + compressed_size as usize + 12;
        if span_end > dat_len || span_end < start + 16 {
            return None;
        }
        let width = u16::from_le_bytes(h[4..6].try_into().ok()?) as usize;
        let height = (hm & 0x7FFF) as usize;
        // Dimension cap (see MAX_TEXDB_DIM) — same rationale as load_variant.
        if width > MAX_TEXDB_DIM || height > MAX_TEXDB_DIM {
            listings.push(None);
            continue;
        }
        listings.push(Some(Listing {
            hash,
            encoding: TexEncoding::from_u16(encoding_raw),
            encoding_raw,
            width,
            height,
            full_chain: hm & 0x8000 != 0,
            compressed_size,
            rle_indicator: u32::from_le_bytes(h[12..16].try_into().ok()?),
            span_start: start,
            span_end,
        }));
    }
    Some(VariantMeta { listings, dat_len })
}

/// Per-image import work product: everything computed before the conflict
/// policy mutates cache state. `hash_ok` folds the overwrite hash-check
/// (`true` when no check applies) so the apply step never touches pixel data
/// or the borrowed cache.
struct PreparedImport {
    twin: Option<usize>,
    name: String,
    line: String,
    listing: Vec<u8>,
    hash_ok: bool,
}

/// Pure per-image pipeline: PNG decode → mip chain → per-level encode →
/// listing build. Reads only immutable cache state (manifest + listings),
/// writes nothing, so any number of these can run concurrently. Results are
/// applied to the cache strictly in input order by `import_textures`, which
/// keeps every manifest line, listing byte and report counter identical to
/// the previous serial implementation (duplicate names: last write wins,
/// exactly as before).
fn prepare_import(
    raw_name: &str,
    png_bytes: &[u8],
    manifest: &[ManifestEntry],
    listings: &[Option<Listing>],
    opts: &ImportOptions,
) -> Option<PreparedImport> {
    let (pixels, w, h) = decode_png_argb(png_bytes)?;
    let twin = manifest
        .iter()
        .position(|e| e.affiliate.is_none() && e.name.eq_ignore_ascii_case(raw_name));
    let name = if opts.correct_caps {
        twin.map(|i| manifest[i].name.clone())
            .unwrap_or_else(|| raw_name.to_string())
    } else {
        raw_name.to_string()
    };
    let src = crate::mipmap::PixelImage::new(pixels, w, h);
    let rest =
        crate::mipmap::generate_chain(&src, crate::mipmap::MipFilter::from_i32(opts.mip_filter));
    // Level 0 is moved (not cloned) in front of the generated chain: the
    // old `vec![src.clone()]` duplicated the full-resolution image (3 MB
    // at 1024x768) once per imported image — and once per concurrent
    // worker — while a move is free. Chain content and every downstream
    // byte are unchanged (levels[0] is the same pixels either way).
    let mut chain = Vec::with_capacity(rest.len() + 1);
    chain.push(src);
    chain.extend(rest);
    let base = if opts.reduce_to > 0 {
        chain
            .iter()
            .position(|l| l.width.max(l.height) <= opts.reduce_to)
            .unwrap_or(chain.len() - 1)
    } else {
        0
    };
    let levels = if opts.generate_mipmaps {
        &chain[base..]
    } else {
        &chain[base..base + 1]
    };
    let has_alpha = levels[0].pixels.iter().any(|p| (p >> 24) & 0xFF < 255);
    let encoding = match opts.format {
        ImportFormat::CompressedAlpha => {
            if has_alpha {
                TexEncoding::Dxt5
            } else {
                TexEncoding::Dxt1
            }
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
            TexEncoding::Rgb565 => crate::codec::raw::argb_to_rgb565_bytes(&l.pixels),
            TexEncoding::Rgba4444 => crate::codec::raw::argb_to_rgba4444_bytes(&l.pixels),
            TexEncoding::Rgba32 => crate::codec::raw::argb_to_rgba32_bytes(&l.pixels),
            _ => Vec::new(),
        })
        .collect();
    let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
    let listing = build_listing(
        &name,
        encoding,
        levels[0].width,
        levels[0].height,
        &refs,
        opts.use_rle,
    )?;
    let is_sibling = opts.conflict == ConflictPolicy::CreateSibling;
    let line = import_manifest_line(&ManifestTex {
        name: &name,
        width: levels[0].width,
        height: levels[0].height,
        png_crc: crc32_ieee(png_bytes),
        img_crc: crc32_ieee(&encoded[0]),
        encoding,
        has_alpha,
        camnorm: opts.camnorm,
        sibling: is_sibling,
    });
    // Overwrite hash-check folded here: `cache.listings` is immutable during
    // the whole import, so reading it in the worker is race-free, and the
    // apply step reduces to pure state mutation on already-computed data.
    let hash_ok = match twin {
        Some(i) if opts.hash_check => !listings
            .get(i)
            .and_then(|l| l.as_ref())
            .is_some_and(|existing| existing.hash != hash_name(&manifest[i].name)),
        _ => true,
    };
    Some(PreparedImport {
        twin,
        name,
        line,
        listing,
        hash_ok,
    })
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
    // Clone-on-replace: only slots an image actually replaces carry an
    // owned line; untouched entries are borrowed from the cache at write
    // time. The old code cloned every manifest line upfront and cloned it
    // a second time during the rebuild — 2N String allocations per import
    // call for N entries (~12k on the gta3 cache).
    let mut changed_lines: Vec<Option<String>> = vec![None; cache.manifest.len()];
    // Only slots an image actually replaces carry owned bytes — untouched
    // entries are never copied (the old code cloned every listing in the
    // cache, ~185 MB of copies on the gta3 cache, before writing anything).
    let mut changed: Vec<Option<Vec<u8>>> = vec![None; cache.manifest.len()];
    let mut appended_lines: Vec<String> = Vec::new();
    let mut appended_listings: Vec<Option<Vec<u8>>> = Vec::new();
    let mut report = ImportReport::default();

    // Per-image work (PNG decode → mip chain → encode → listing) is pure with
    // respect to cache state, so it runs concurrently across the batch; the
    // results are then applied to the cache strictly in input order, which
    // keeps every manifest line, listing byte and report counter identical to
    // the serial implementation (duplicate names: last write wins, as before).
    // Batches are sized to the worker count so peak memory stays proportional
    // to CPU count rather than batch size, and the codecs' internal band
    // splitters are oversubscribed by at most a small constant factor.
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2)
        .clamp(1, 16);
    let mut prepared: Vec<Option<PreparedImport>> = Vec::with_capacity(images.len());
    for batch in images.chunks(workers) {
        // The outer spawn only pays off when at least two images overlap;
        // a single-image batch runs the identical pipeline inline on the
        // calling thread WITHOUT the outer guard, so the per-image kernels
        // keep their internal band parallelism (a lone 1024x1024 import
        // measures ~2x effective cores from banding alone).
        let computed: Vec<Option<PreparedImport>> = if workers >= 3 && batch.len() > 1 {
            std::thread::scope(|s| {
                let handles: Vec<_> = batch
                    .iter()
                    .map(|(raw_name, png_bytes)| {
                        s.spawn(|| {
                            // Outer concurrent worker: hold the guard so the
                            // per-image kernels (chain generation, codec
                            // banding) run inline instead of multiplying
                            // threads past the core count.
                            let _outer = crate::parallel::OuterGuard::acquire();
                            prepare_import(
                                raw_name,
                                png_bytes,
                                &cache.manifest,
                                &cache.listings,
                                opts,
                            )
                        })
                    })
                    .collect();
                handles
                    .into_iter()
                    .map(|h| match h.join() {
                        Ok(p) => p,
                        // Propagate worker panics exactly like the serial
                        // loop did (release builds abort before this runs).
                        Err(e) => std::panic::resume_unwind(e),
                    })
                    .collect()
            })
        } else {
            // One or two cores, or a single-image batch: thread spawn/scope
            // overhead cancels the compute win (measured), so run the
            // identical pipeline inline.
            batch
                .iter()
                .map(|(raw_name, png_bytes)| {
                    prepare_import(raw_name, png_bytes, &cache.manifest, &cache.listings, opts)
                })
                .collect()
        };
        prepared.extend(computed);
    }

    // CreateSibling needs a quoted-name needle per applied image; opts is
    // constant for the whole call, so build it once instead of once per
    // image (a format! allocation inside the apply loop).
    let sibling_ctx: Option<(&str, String)> = opts
        .sibling_model
        .as_deref()
        .map(str::trim)
        .filter(|m| !m.is_empty())
        .map(|m| (m, format!("\"{m}\"")));

    for prepared in prepared {
        let Some(p) = prepared else {
            report.skipped += 1;
            continue;
        };
        match opts.conflict {
            ConflictPolicy::Overwrite => {
                if let Some(i) = p.twin {
                    // Raster-hash check: never overwrite an entry whose stored
                    // hash does not match its manifest name.
                    if !p.hash_ok {
                        report.skipped += 1;
                        continue;
                    }
                    changed[i] = Some(p.listing);
                    changed_lines[i] = Some(p.line);
                    report.replaced += 1;
                } else {
                    appended_listings.push(Some(p.listing));
                    appended_lines.push(p.line);
                    report.added += 1;
                }
            }
            ConflictPolicy::JustAdd => {
                appended_listings.push(Some(p.listing));
                appended_lines.push(p.line);
                report.added += 1;
            }
            ConflictPolicy::CreateSibling => {
                appended_listings.push(Some(p.listing));
                appended_lines.push(p.line);
                report.siblings += 1;
                if let Some((model, needle)) = &sibling_ctx {
                    if !cache
                        .manifest
                        .iter()
                        .any(|e| e.name.eq_ignore_ascii_case(model))
                        && !appended_lines.iter().any(|l| l.contains(needle.as_str()))
                    {
                        appended_listings.push(None);
                        appended_lines.push(format!("\"{}\" \"affiliate={}\"", model, p.name));
                    }
                }
            }
        }
    }

    // Final slots in manifest order: replaced entries carry their new
    // bytes; everything else stays byte-identical at its existing offset.
    let mut slots: Vec<FinalSlot> =
        Vec::with_capacity(cache.manifest.len() + appended_listings.len());
    for (i, slot) in changed.into_iter().enumerate() {
        slots.push(match slot {
            Some(bytes) => FinalSlot::New(bytes),
            None => {
                if cache.listings.get(i).and_then(|l| l.as_ref()).is_some() {
                    FinalSlot::Unchanged(i)
                } else {
                    FinalSlot::NoData
                }
            }
        });
    }
    for listing in appended_listings {
        slots.push(match listing {
            Some(bytes) => FinalSlot::New(bytes),
            None => FinalSlot::NoData,
        });
    }

    // Rewrite the manifest preserving its original structure: texture lines
    // are substituted by ordinal, additions go last. Unchanged entries and
    // raw non-texture lines are borrowed (Cow) — zero per-line allocation;
    // replaced entries move the prepared line; appends move theirs.
    let mut out_lines: Vec<std::borrow::Cow<'_, str>> = Vec::new();
    let mut ordinal = 0usize;
    for raw_line in cache.raw_txt.lines() {
        let line = raw_line.trim_end();
        if line.trim_start().starts_with('"') {
            if ordinal < cache.manifest.len() {
                out_lines.push(match changed_lines[ordinal].take() {
                    Some(new) => std::borrow::Cow::Owned(new),
                    None => std::borrow::Cow::Borrowed(cache.manifest[ordinal].line.as_str()),
                });
            }
            ordinal += 1;
        } else {
            out_lines.push(std::borrow::Cow::Borrowed(line));
        }
    }
    out_lines.extend(appended_lines.into_iter().map(std::borrow::Cow::Owned));
    let mut txt = out_lines.join("\n");
    txt.push('\n');

    let mode = if opts.defragment {
        WriteMode::ForceRebuild
    } else {
        WriteMode::Auto
    };
    let (old_cache, new_cache) = apply_write(dir, cache_name, cache, &slots, &txt, mode)?;
    let notes = sync_after_write(dir, cache_name, &old_cache, &new_cache);
    Ok(with_notes(report.to_line(), notes))
}

/// Rebuilds the cache's .dat/.toc from its current listings (defragment and
/// repair), leaving every texture's bytes untouched.
pub fn rebuild_cache(dir: &std::path::Path, cache_name: &str) -> Result<String, String> {
    let cache = TexdbCache::load(dir, cache_name).map_err(|e| e.to_string())?;
    let entries = cache.manifest.len();
    let raw_txt = cache.raw_txt.clone();
    let slots: Vec<FinalSlot> = (0..entries)
        .map(|i| {
            if cache.manifest[i].affiliate.is_some() {
                FinalSlot::NoData
            } else if cache.listings.get(i).and_then(|l| l.as_ref()).is_some() {
                FinalSlot::Unchanged(i)
            } else {
                FinalSlot::NoData
            }
        })
        .collect();
    let (old_cache, new_cache) = apply_write(
        dir,
        cache_name,
        cache,
        &slots,
        &raw_txt,
        WriteMode::ForceRebuild,
    )?;
    let bytes = new_cache.dat.len();
    let notes = sync_after_write(dir, cache_name, &old_cache, &new_cache);
    Ok(with_notes(
        format!("entries={entries};bytes={bytes}"),
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
    // Targeted manifest scan instead of a full parse: this helper is the
    // per-texture view path, and a 12k-entry manifest costs ~10 ms to parse
    // (name + line + props String allocations) against ~1 ms for a line
    // walk. The walk replicates parse_manifest's exact entry rule (trimmed
    // line starts with '"' and has a closing quote) and the exact affiliate
    // rule (a token whose key, after quote-trimming, is `affiliate`);
    // nothing else in a manifest line affects this function's behavior.
    let mut count = 0usize;
    let mut affiliate = false;
    for line in text.lines() {
        let line = line.trim();
        if !line.starts_with('"') {
            continue;
        }
        let Some(end) = line[1..].find('"') else {
            continue;
        };
        if count == index {
            affiliate = quote_tokens(&line[1 + end + 1..])
                .iter()
                .filter_map(|t| t.split_once('='))
                .any(|(k, _)| k.trim_matches('"') == "affiliate");
        }
        count += 1;
    }
    if index >= count || affiliate {
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
        if toc_bytes.len() < 4 || (toc_bytes.len() - 4) / 4 < count {
            continue;
        }
        let dat_len = std::fs::metadata(&dat_path).ok()?.len() as usize;
        if u32::from_le_bytes(toc_bytes[0..4].try_into().ok()?) as usize != dat_len {
            continue;
        }
        let off =
            i32::from_le_bytes(toc_bytes[4 + index * 4..8 + index * 4].try_into().ok()?) as i64;
        if off < 0 {
            return None;
        }
        let start = off as usize;
        let mut file = std::fs::File::open(&dat_path).ok()?;
        file.seek(SeekFrom::Start(start as u64)).ok()?;
        // Span from the listing's own header (csize + 12): toc neighbours no
        // longer bound a listing once in-place writes leave dead spans.
        let mut header = [0u8; 16];
        file.read_exact(&mut header).ok()?;
        let span_len = u32::from_le_bytes(header[8..12].try_into().ok()?) as usize + 12;
        if span_len < 16 || start + span_len > dat_len {
            return None;
        }
        let mut span = Vec::with_capacity(span_len);
        span.extend_from_slice(&header);
        span.resize(span_len, 0);
        file.read_exact(&mut span[16..]).ok()?;
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
        // Dimension cap (see MAX_TEXDB_DIM) — a faked header here would
        // inflate `expected_size()` and the RLE output cap below.
        if listing.width > MAX_TEXDB_DIM || listing.height > MAX_TEXDB_DIM {
            return None;
        }
        let expected = listing.expected_size()?;
        let payload = &span[16..];
        let decoded_storage;
        let raw: &[u8] = if listing.rle_indicator == 0 {
            if payload.len() < expected {
                return None;
            }
            &payload[..expected]
        } else {
            decoded_storage = rle_decode(
                payload,
                listing.encoding.segment_size()?,
                (listing.rle_indicator & 0xFF) as u8,
                expected + (expected / 8) + 4096,
            )?;
            if decoded_storage.len() < expected {
                return None;
            }
            &decoded_storage[..expected]
        };
        let level0_size = listing.encoding.level_size(listing.width, listing.height)?;
        let level0 = &raw[..level0_size.min(raw.len())];
        let pixels = pixels_for_encoding(listing.encoding, level0, listing.width, listing.height)?;
        return Some((listing.width, listing.height, listing.encoding_raw, pixels));
    }
    None
}

/// Listing payload for one kept edit slot.
pub enum EditListing {
    /// Reuse the entry's current bytes at their current offset — the edit
    /// engine never copies an untouched listing.
    Keep,
    /// Replacement listing bytes.
    Bytes(Vec<u8>),
}

/// One edit slot per original manifest entry: `None` deletes the entry,
/// `Some((line, listing))` keeps/replaces it (listing `None` = affiliate).
pub struct CacheEdit {
    pub entries: Vec<Option<(String, Option<EditListing>)>>,
    pub appended: Vec<(String, Option<Vec<u8>>)>,
}

/// Applies a [CacheEdit]: rewrites the manifest (original structure kept,
/// texture lines substituted by ordinal, additions appended) and writes
/// the active variant in place (see [apply_write]).
pub fn write_cache_edit(
    dir: &std::path::Path,
    cache_name: &str,
    cache: TexdbCache,
    edit: CacheEdit,
) -> Result<String, String> {
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

    let mut slots: Vec<FinalSlot> = Vec::with_capacity(edit.entries.len() + edit.appended.len());
    for (oi, slot) in edit.entries.into_iter().enumerate() {
        if let Some((_, listing)) = slot {
            slots.push(match listing {
                None => FinalSlot::NoData,
                Some(EditListing::Keep) => FinalSlot::Unchanged(oi),
                Some(EditListing::Bytes(b)) => {
                    if cache.raw_listing(oi) == Some(&b[..]) {
                        FinalSlot::Unchanged(oi)
                    } else {
                        FinalSlot::New(b)
                    }
                }
            });
        }
    }
    for (_, listing) in edit.appended {
        slots.push(match listing {
            None => FinalSlot::NoData,
            Some(b) => FinalSlot::New(b),
        });
    }
    let (old_cache, new_cache) =
        apply_write(dir, cache_name, cache, &slots, &txt, WriteMode::Auto)?;
    Ok(sync_after_write(dir, cache_name, &old_cache, &new_cache))
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
    // Defensive: PixelImage::new (mip chain) and the encoders index
    // `pixels[y * width + x]` and assert on inconsistent input — under
    // panic=abort that aborts the whole app. Any caller passing mismatched
    // dimensions must get None (the slot is skipped), never a panic.
    if width == 0 || height == 0 || pixels.len() != width.checked_mul(height)? {
        return None;
    }
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
            TexEncoding::Rgb565 => crate::codec::raw::argb_to_rgb565_bytes(px),
            TexEncoding::Rgba4444 => crate::codec::raw::argb_to_rgba4444_bytes(px),
            TexEncoding::Rgba32 => crate::codec::raw::argb_to_rgba32_bytes(px),
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
            out.push(
                (((a / n) as u32) << 24)
                    | (((r / n) as u32) << 16)
                    | (((g / n) as u32) << 8)
                    | ((b / n) as u32),
            );
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
        None => (
            1,
            1,
            0x1401,
            crate::codec::raw::argb_to_rgba32_bytes(&[0xFF00_0000]),
        ),
        Some((px, w, h)) => match encoding {
            TexEncoding::Rgba32 => (
                1,
                1,
                0x1401,
                crate::codec::raw::argb_to_rgba32_bytes(&scale_box(px, w, h, 1, 1)),
            ),
            TexEncoding::Etc1 if variant == "etc" => {
                let s = scale_box(px, w, h, 8, 8);
                (8, 8, 0x8D64, crate::codec::etc1::encode_etc1(&s, 8, 8))
            }
            TexEncoding::Rgba4444 => {
                let s = scale_box(px, w, h, 8, 8);
                (8, 8, 0x8034, crate::codec::raw::argb_to_rgba4444_bytes(&s))
            }
            _ => {
                let s = scale_box(px, w, h, 8, 8);
                (8, 8, 0x8033, crate::codec::raw::argb_to_rgb565_bytes(&s))
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
/// listing level (untouched textures keep that variant's offsets and bytes;
/// new/changed textures are re-encoded into that variant's encoding and
/// APPENDED to its .dat, only its small .toc rewritten), and every .tmb is
/// regenerated with one record per non-affiliate texture (old records
/// reused byte-for-byte for unchanged textures; the file is only written
/// when its content actually changes). Variants or .tmb files that fail to
/// parse (the shipped broken .360 family) are left alone, exactly like TXD
/// Tool ignores them. Both cache states are passed in by the caller — the
/// sync never re-reads the active cache from disk. Returns machine notes.
fn sync_after_write(
    dir: &std::path::Path,
    cache_name: &str,
    old: &TexdbCache,
    new: &TexdbCache,
) -> String {
    use std::collections::{BTreeSet, HashMap};
    // One variant re-encode job: everything needed to rebuild a changed
    // entry's listing in another variant family, as a pure function of the
    // post-write cache. `ni` indexes `new`; the rest mirror the listing
    // header that the decoded pixels come from.
    struct VariantJob {
        ni: usize,
        name: String,
        enc: TexEncoding,
        lw: usize,
        lh: usize,
        full_chain: bool,
        use_rle: bool,
    }
    let mut notes: Vec<String> = Vec::new();
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2)
        .clamp(1, 16);
    let old_index: HashMap<String, usize> = old
        .manifest
        .iter()
        .enumerate()
        .map(|(i, e)| (e.name.clone(), i))
        .collect();
    let unchanged = |new_i: usize, name: &str| -> bool {
        old_index.get(name).is_some_and(|&oi| {
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
        let Some(meta) = load_variant_meta(dir, cache_name, v, &old.manifest) else {
            continue;
        };
        let vold_index: HashMap<String, usize> = old
            .manifest
            .iter()
            .enumerate()
            .map(|(i, e)| (e.name.clone(), i))
            .collect();
        // Plan first (all-or-nothing per variant, like the old full
        // rewrite): unchanged entries keep their offsets; changed/new
        // entries are re-encoded from the new listing's pixels. The
        // re-encode jobs are pure functions of (new, meta) — they run
        // concurrently in worker-sized windows, while the append
        // bookkeeping (offsets/cursor/enc_map) stays serial in manifest
        // order, so the on-disk result is byte-identical to the serial
        // loop this replaces.
        enum VariantSlot {
            NoData,
            Keep(i64, TexEncoding),
            Encode(VariantJob),
        }
        let mut plan: Vec<VariantSlot> = Vec::with_capacity(new.manifest.len());
        let mut failed = false;
        for (ni, entry) in new.manifest.iter().enumerate() {
            if entry.affiliate.is_some() {
                plan.push(VariantSlot::NoData);
                continue;
            }
            let lname = entry.name.clone();
            if unchanged(ni, &entry.name) {
                if let Some(&voi) = vold_index.get(&lname) {
                    match &meta.listings[voi] {
                        Some(l) => plan.push(VariantSlot::Keep(l.span_start as i64, l.encoding)),
                        None => plan.push(VariantSlot::NoData),
                    }
                    continue;
                }
            }
            let enc = vold_index
                .get(&lname)
                .and_then(|&voi| meta.listings[voi].as_ref().map(|l| l.encoding))
                .or_else(|| new.listings[ni].as_ref().map(|l| l.encoding))
                .unwrap_or(TexEncoding::Rgb565);
            if matches!(enc, TexEncoding::Unknown(_)) {
                failed = true;
                break;
            }
            // Dimensions must come from the SAME source as the decoded
            // pixels (the listing header). Mixing in the manifest's
            // width/height invites a PixelImage::new assert (panic=abort)
            // on a hand-edited or corrupted manifest, mid-way through a
            // cache write.
            let (lw, lh) = new.listings[ni]
                .as_ref()
                .map(|l| (l.width, l.height))
                .unwrap_or((entry.width, entry.height));
            let full_chain = new.listings[ni].as_ref().is_some_and(|l| l.full_chain);
            let use_rle = new.listings[ni]
                .as_ref()
                .is_none_or(|l| l.rle_indicator != 0);
            plan.push(VariantSlot::Encode(VariantJob {
                ni,
                name: lname,
                enc,
                lw,
                lh,
                full_chain,
                use_rle,
            }));
        }

        let mut offsets: Vec<i64> = Vec::with_capacity(new.manifest.len());
        let mut appends: Vec<u8> = Vec::new();
        let mut cursor = meta.dat_len;
        let mut enc_map: HashMap<String, TexEncoding> = HashMap::new();
        'windows: for (widx, window) in plan.chunks_mut(workers).enumerate() {
            // Spawn only when at least two encode jobs overlap; a lone job
            // runs inline WITHOUT the outer guard so the decode/encode
            // kernels keep their internal band parallelism.
            let encode_jobs = window
                .iter()
                .filter(|slot| matches!(slot, VariantSlot::Encode(_)))
                .count();
            let results: Vec<Option<Vec<u8>>> = if workers >= 3 && encode_jobs > 1 {
                std::thread::scope(|s| {
                    let handles: Vec<_> = window
                        .iter_mut()
                        .filter_map(|slot| match slot {
                            VariantSlot::Encode(job) => Some(job),
                            _ => None,
                        })
                        .map(|job| {
                            s.spawn(move || {
                                let _outer = crate::parallel::OuterGuard::acquire();
                                new.decode_pixels(job.ni).and_then(|px| {
                                    encode_listing_from_pixels(
                                        &job.name,
                                        &px,
                                        job.lw,
                                        job.lh,
                                        job.enc,
                                        job.full_chain,
                                        job.use_rle,
                                    )
                                })
                            })
                        })
                        .collect();
                    handles
                        .into_iter()
                        .map(|h| match h.join() {
                            Ok(r) => r,
                            // Propagate worker panics exactly like the serial
                            // loop did (release builds abort before this runs).
                            Err(e) => std::panic::resume_unwind(e),
                        })
                        .collect()
                })
            } else {
                // One or two cores, or a single-job window: identical work
                // inline, no spawn overhead, inner banding left active.
                window
                    .iter_mut()
                    .filter_map(|slot| match slot {
                        VariantSlot::Encode(job) => Some(job),
                        _ => None,
                    })
                    .map(|job| {
                        new.decode_pixels(job.ni).and_then(|px| {
                            encode_listing_from_pixels(
                                &job.name,
                                &px,
                                job.lw,
                                job.lh,
                                job.enc,
                                job.full_chain,
                                job.use_rle,
                            )
                        })
                    })
                    .collect()
            };
            let mut results = results.into_iter();
            for (wi, slot) in window.iter().enumerate() {
                let ni = widx * workers + wi;
                match slot {
                    VariantSlot::NoData => offsets.push(-1),
                    VariantSlot::Keep(off, enc) => {
                        offsets.push(*off);
                        enc_map.insert(new.manifest[ni].name.clone(), *enc);
                    }
                    VariantSlot::Encode(job) => {
                        // `Some(None)` = encode failed (same as the serial
                        // loop's None branch); `None` here would mean result
                        // misalignment, which cannot happen structurally —
                        // both map to a full variant skip, never a bad write.
                        match results.next() {
                            Some(Some(listing)) => {
                                offsets.push(cursor as i64);
                                cursor += listing.len();
                                appends.extend_from_slice(&listing);
                                enc_map.insert(new.manifest[ni].name.clone(), job.enc);
                            }
                            _ => {
                                failed = true;
                                break 'windows;
                            }
                        }
                    }
                }
            }
        }
        if failed {
            notes.push(format!("variant_skip={v}"));
            continue;
        }
        // Already in the target state (toc would be byte-identical)?
        let already_synced = appends.is_empty()
            && offsets.len() == meta.listings.len()
            && offsets.iter().enumerate().all(|(i, &o)| {
                o == meta.listings[i]
                    .as_ref()
                    .map_or(-1, |l| l.span_start as i64)
            });
        let wrote = if already_synced {
            true
        } else {
            (|| -> std::io::Result<()> {
                use std::io::{Seek, SeekFrom, Write};
                if !appends.is_empty() {
                    let mut f = std::fs::OpenOptions::new()
                        .write(true)
                        .open(dir.join(format!("{cache_name}.{v}.dat")))?;
                    f.seek(SeekFrom::End(0))?;
                    f.write_all(&appends)?;
                }
                let mut toc = Vec::with_capacity(4 + offsets.len() * 4);
                toc.extend_from_slice(&(cursor as u32).to_le_bytes());
                for o in &offsets {
                    toc.extend_from_slice(&(*o as i32).to_le_bytes());
                }
                std::fs::write(dir.join(format!("{cache_name}.{v}.toc")), &toc)
            })()
            .is_ok()
        };
        if wrote {
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
    let old_real: Vec<&ManifestEntry> = old
        .manifest
        .iter()
        .filter(|e| e.affiliate.is_none())
        .collect();
    for t in &tmb_variants {
        let path = dir.join(format!("{cache_name}.{t}.tmb"));
        let mut old_records: HashMap<String, Vec<u8>> = HashMap::new();
        if path.exists() {
            let Ok(bytes) = std::fs::read(&path) else {
                continue;
            };
            let Some(recs) = parse_tmb_records(&bytes) else {
                continue;
            };
            // Positional pairing first (records sit in manifest order);
            // fall back to hash matching when counts desynced (a deleted
            // texture shifts positions). Hash collisions (e.g. cd11c and
            // goldrim share 0xE023 in the shipped cache) make the pure
            // hash map hand a texture its namesake's thumbnail.
            let paired = recs.len().min(old_real.len());
            let positional_ok = (0..paired).all(|i| recs[i].0 == hash_name(&old_real[i].name));
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
        // Changed entries need their pixels decoded for a fresh thumbnail;
        // unchanged ones reuse the stored record byte-for-byte. Decodes are
        // pure reads of `new`, so they run concurrently in worker-sized
        // windows; the record assembly stays serial so the .tmb content is
        // byte-identical to the serial loop this replaces.
        enum ThumbSlot<'a> {
            Reuse(&'a [u8]),
            Need { ni: usize, enc: TexEncoding },
        }
        let mut plan: Vec<ThumbSlot<'_>> = new
            .manifest
            .iter()
            .enumerate()
            .filter(|(_, e)| e.affiliate.is_none())
            .map(|(ni, entry)| {
                let lname = &entry.name;
                if unchanged(ni, lname) {
                    if let Some(rec) = old_records.get(lname) {
                        return ThumbSlot::Reuse(rec.as_slice());
                    }
                }
                let enc = variant_enc
                    .get(t)
                    .and_then(|m| m.get(lname).copied())
                    .or_else(|| {
                        if *t == new.variant {
                            new.listings[ni].as_ref().map(|l| l.encoding)
                        } else {
                            None
                        }
                    })
                    .or_else(|| new.listings[ni].as_ref().map(|l| l.encoding))
                    .unwrap_or(TexEncoding::Rgb565);
                ThumbSlot::Need { ni, enc }
            })
            .collect();
        for window in plan.chunks_mut(workers) {
            let need_nis: Vec<usize> = window
                .iter()
                .filter_map(|slot| match slot {
                    ThumbSlot::Need { ni, .. } => Some(*ni),
                    _ => None,
                })
                .collect();
            let decoded: Vec<Option<Vec<u32>>> = if need_nis.is_empty() {
                Vec::new()
            } else if workers >= 3 && need_nis.len() > 1 {
                // >= 2 decodes overlap: spawn guarded workers (inner kernels
                // run inline; a lone decode stays inline-with-banding below).
                std::thread::scope(|s| {
                    let handles: Vec<_> = need_nis
                        .iter()
                        .map(|&ni| {
                            s.spawn(move || {
                                let _outer = crate::parallel::OuterGuard::acquire();
                                new.decode_pixels(ni)
                            })
                        })
                        .collect();
                    handles
                        .into_iter()
                        .map(|h| match h.join() {
                            Ok(r) => r,
                            Err(e) => std::panic::resume_unwind(e),
                        })
                        .collect()
                })
            } else {
                need_nis.iter().map(|&ni| new.decode_pixels(ni)).collect()
            };
            let mut decoded = decoded.into_iter();
            for slot in window.iter() {
                match slot {
                    ThumbSlot::Reuse(rec) => out.extend_from_slice(rec),
                    ThumbSlot::Need { ni, enc } => {
                        // Misalignment (None) cannot happen structurally;
                        // both it and a failed decode fall back to the
                        // placeholder record, exactly like the serial loop
                        // passing `None` pixels to thumb_record.
                        let px = decoded.next().unwrap_or(None);
                        let entry = &new.manifest[*ni];
                        let rec = thumb_record(
                            &entry.name,
                            px.as_ref()
                                .map(|v| (v.as_slice(), entry.width, entry.height)),
                            *enc,
                            t,
                        );
                        out.extend_from_slice(&rec);
                    }
                }
            }
        }
        // The .tmb is small and most records are reused byte-for-byte, so
        // after a no-op or single-texture edit the regenerated content is
        // often identical to what's on disk — only write when it differs.
        let already = std::fs::read(&path).is_ok_and(|cur| cur == out);
        if already || std::fs::write(&path, &out).is_ok() {
            notes.push(format!("tmb_sync={t}"));
        }
    }
    notes.join(";")
}

fn current_edit(cache: &TexdbCache) -> CacheEdit {
    CacheEdit {
        entries: (0..cache.manifest.len())
            .map(|i| {
                let listing = if cache.listings.get(i).and_then(|l| l.as_ref()).is_some() {
                    Some(EditListing::Keep)
                } else {
                    None
                };
                Some((cache.manifest[i].line.clone(), listing))
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
    let notes = write_cache_edit(dir, cache_name, cache, edit)?;
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
        if let Some(raw) = cache.raw_listing(index) {
            let mut bytes = raw.to_vec();
            bytes[0..2].copy_from_slice(&hash_name(new_name).to_le_bytes());
            *listing = Some(EditListing::Bytes(bytes));
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
    let notes = write_cache_edit(dir, cache_name, cache, edit)?;
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
    if cache
        .manifest
        .iter()
        .any(|e| e.name.eq_ignore_ascii_case(alias_name))
    {
        return Err(format!("nama '{alias_name}' sudah dipakai"));
    }
    let target_name = cache.manifest[target_index].name.clone();
    let mut edit = current_edit(&cache);
    edit.appended.push((
        format!("\"{}\" \"affiliate={}\"", alias_name, target_name),
        None,
    ));
    let notes = write_cache_edit(dir, cache_name, cache, edit)?;
    Ok(with_notes(
        format!("alias={alias_name};target={target_name}"),
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
        TexEncoding::Rgb565 => crate::codec::raw::rgb565_to_argb(data),
        TexEncoding::Rgba4444 => crate::codec::raw::rgba4444_to_argb(data),
        TexEncoding::Rgba32 => crate::codec::raw::rgba32_to_argb(data),
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
                TexEncoding::Rgb565 => crate::codec::raw::argb_to_rgb565_bytes(px),
                TexEncoding::Rgba4444 => crate::codec::raw::argb_to_rgba4444_bytes(px),
                _ => Vec::new(),
            })
            .collect();
        let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
        let entry = &cache.manifest[i];
        let Some(listing) =
            build_listing(&entry.name, encoding, levels[0].0, levels[0].1, &refs, true)
        else {
            kept += 1;
            continue;
        };
        let line = import_manifest_line(&ManifestTex {
            name: &entry.name,
            width: levels[0].0,
            height: levels[0].1,
            png_crc: crc32_ieee(&encoded[0]),
            img_crc: crc32_ieee(&encoded[0]),
            encoding,
            has_alpha: has_alpha && target != 1,
            camnorm: entry.line.contains("camnorm=1"),
            sibling: entry.line.contains("hassibling=1"),
        });
        edit.entries[i] = Some((line, Some(EditListing::Bytes(listing))));
        converted += 1;
    }
    let notes = write_cache_edit(dir, cache_name, cache, edit)?;
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
        let decoded = rle_decode(&encoded, seg, 0x09, usize::MAX).expect("decode");
        assert_eq!(decoded, data);
    }

    #[test]
    fn rle_decode_game_style_stream() {
        // literal "ABCD", then repeat "WXYZ" x3, indicator 0xFF
        let mut stream = b"ABCD".to_vec();
        stream.extend_from_slice(&[0xFF, 3]);
        stream.extend_from_slice(b"WXYZ");
        let out = rle_decode(&stream, 4, 0xFF, usize::MAX).expect("decode");
        assert_eq!(out, b"ABCDWXYZWXYZWXYZ".to_vec());
    }

    /// The seg-8 fast path must be byte-identical to the generic reference
    /// across noise (no runs), run-heavy data (runs crossing the 255 cap),
    /// all-same, empty, single-unit, and indicator-collision inputs.
    #[test]
    fn rle_encode_seg8_matches_generic() {
        struct Rng(u32);
        impl Rng {
            fn next(&mut self) -> u32 {
                let mut x = self.0;
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                self.0 = x;
                x
            }
        }
        let mut rng = Rng(0x9E37_79B9);
        let mut cases: Vec<Vec<u8>> = vec![
            Vec::new(),
            vec![0x11; 8],
            vec![0x09; 8 * 3],   // short run, indicator 0x09 first byte
            vec![0xFF; 8 * 300], // run crossing the 255 cap
            (0u8..8).collect(),  // single unit
        ];
        // Incompressible noise.
        let noise: Vec<u8> = (0..64 * 1024).map(|_| rng.next() as u8).collect();
        cases.push(noise);
        // Run-heavy: each unit repeats 4..16 times.
        let mut run_heavy = Vec::new();
        while run_heavy.len() < 64 * 1024 {
            let unit: Vec<u8> = (0..8).map(|_| rng.next() as u8).collect();
            for _ in 0..(4 + (rng.next() % 12) as usize) {
                run_heavy.extend_from_slice(&unit);
            }
        }
        cases.push(run_heavy);
        for data in &cases {
            let fast = rle_encode_seg8(data, 0x09);
            let want = rle_encode_generic(data, 8, 0x09);
            assert_eq!(fast, want, "seg8 fast path diverged (len {})", data.len());
            // Roundtrip through the real dispatcher + decoder.
            let enc = rle_encode(data, 8, 0x09);
            let dec = rle_decode(&enc, 8, 0x09, usize::MAX).expect("decode");
            assert_eq!(dec, *data);
        }
    }

    /// The seg-8 decode fast path must agree with the generic decoder on
    /// every Some/None outcome and output byte: valid encoded roundtrips
    /// (noise, run-heavy, runs crossing the 255 cap, indicator collisions),
    /// truncated payloads, bomb-guard rejections (small max_out), and raw
    /// random byte soup that may parse to anything as long as both agree.
    #[test]
    fn rle_decode_seg8_matches_generic() {
        struct Rng(u32);
        impl Rng {
            fn next(&mut self) -> u32 {
                let mut x = self.0;
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                self.0 = x;
                x
            }
        }
        let mut rng = Rng(0x1234_ABCD);
        let mut payloads: Vec<Vec<u8>> = Vec::new();
        for len in [32 * 1024usize, 8, 0] {
            let noise: Vec<u8> = (0..len).map(|_| rng.next() as u8).collect();
            payloads.push(rle_encode_generic(&noise, 8, 0x09));
            let mut run_heavy = Vec::new();
            while run_heavy.len() < len {
                let unit: Vec<u8> = (0..8).map(|_| rng.next() as u8).collect();
                for _ in 0..(1 + (rng.next() % 300) as usize) {
                    run_heavy.extend_from_slice(&unit);
                }
            }
            payloads.push(rle_encode_generic(&run_heavy, 8, 0x09));
        }
        // Corruptions: truncated headers/units, and raw random soup.
        let valid = payloads[0].clone();
        for cut in [0usize, 1, 2, 5, 9, 10, 11, 17] {
            if cut <= valid.len() {
                payloads.push(valid[..valid.len() - cut].to_vec());
            }
        }
        for _ in 0..64 {
            let soup_len = 1 + (rng.next() % 512) as usize;
            payloads.push((0..soup_len).map(|_| rng.next() as u8).collect());
        }
        for payload in &payloads {
            for max_out in [usize::MAX, 4096, 64] {
                let fast = rle_decode_seg8(payload, 0x09, max_out);
                let want = rle_decode(payload, 8, 0x09, max_out);
                assert_eq!(fast, want, "decode divergence len {}", payload.len());
            }
        }
    }

    fn tiny_png() -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut out, 4, 4);
            enc.set_color(png::ColorType::Rgba);
            enc.set_depth(png::BitDepth::Eight);
            let mut writer = enc.write_header().unwrap();
            writer
                .write_image_data(&[255, 0, 0, 255].repeat(16))
                .unwrap();
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
            defragment: false,
        };
        let report =
            import_textures(&dir, "beta", &[("beta".to_string(), tiny_png())], &opts).unwrap();
        assert!(report.contains("replaced=1"), "report: {report}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        let (w, h, _enc, px) = decode_pixels_at(&dir, "beta", 0).unwrap();
        assert_eq!((w, h), (4, 4));
        assert!(
            px.iter().all(|&p| p == 0xFFFF_0000),
            "imported texture must decode red"
        );
        assert!(cache.manifest[0].line.contains("camnorm=1"));
        let rebuilt = rebuild_cache(&dir, "beta").unwrap();
        assert!(rebuilt.contains("entries=2"), "rebuild: {rebuilt}");
        std::fs::remove_dir_all(&dir).ok();
    }

    fn tiny_png_color(r: u8, g: u8, b: u8) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut out, 4, 4);
            enc.set_color(png::ColorType::Rgb);
            enc.set_depth(png::BitDepth::Eight);
            let mut writer = enc.write_header().unwrap();
            let px = [r, g, b];
            let mut data = Vec::with_capacity(16 * 3);
            for _ in 0..16 {
                data.extend_from_slice(&px);
            }
            writer.write_image_data(&data).unwrap();
        }
        out
    }

    #[test]
    fn import_parallel_preserves_input_order_semantics() {
        // The parallel prepare / serial apply refactor must keep the serial
        // semantics: corrupt PNGs are skipped, duplicate NEW names append one
        // entry each in input order, and every result is applied in the order
        // the images were given (batching over workers must not reorder).
        let dir = std::env::temp_dir().join(format!("parallel-order-{}", std::process::id()));
        make_synthetic_cache(&dir);
        let opts = ImportOptions {
            format: ImportFormat::CompressedNoAlpha,
            conflict: ConflictPolicy::Overwrite,
            sibling_model: None,
            reduce_to: 0,
            camnorm: false,
            hash_check: true,
            correct_caps: false,
            use_rle: true,
            generate_mipmaps: true,
            mip_filter: 1,
            defragment: false,
        };
        // With workers=2 (CI hosts) this splits as [beta,dup1] [corrupt,dup2]
        // [new1] — the duplicate "dup" images land in different batches, so a
        // result-order bug (instead of input-order apply) would flip them.
        let images: Vec<(String, Vec<u8>)> = vec![
            ("beta".to_string(), tiny_png_color(255, 0, 0)),
            ("dup".to_string(), tiny_png_color(0, 255, 0)),
            ("corrupt".to_string(), b"definitely not a png".to_vec()),
            ("dup".to_string(), tiny_png_color(0, 0, 255)),
            ("new1".to_string(), tiny_png_color(255, 255, 0)),
        ];
        let report = import_textures(&dir, "beta", &images, &opts).unwrap();
        assert!(
            report.contains("replaced=1")
                && report.contains("added=3")
                && report.contains("skipped=1"),
            "report: {report}"
        );
        // Slot 0 = "beta" replaced with red; slot 1 = untouched affiliate.
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.manifest.len(), 5, "2 beta + alpha + dup x2 + new1");
        let (_, _, _, px) = decode_pixels_at(&dir, "beta", 0).unwrap();
        assert!(px.iter().all(|&p| p == 0xFFFF_0000), "beta must be red");
        // Appended order preserved: dup(green), dup(blue), new1(yellow).
        let (_, _, _, d1) = decode_pixels_at(&dir, "beta", 2).unwrap();
        assert!(d1.iter().all(|&p| p == 0xFF00_FF00), "first dup green");
        let (_, _, _, d2) = decode_pixels_at(&dir, "beta", 3).unwrap();
        assert!(d2.iter().all(|&p| p == 0xFF00_00FF), "second dup blue");
        let (_, _, _, n1) = decode_pixels_at(&dir, "beta", 4).unwrap();
        assert!(n1.iter().all(|&p| p == 0xFFFF_FF00), "new1 yellow");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn prepare_import_is_deterministic() {
        // Concurrency contract: the same (input, cache-state) pair always
        // yields the same work product, regardless of which worker runs it.
        let manifest = parse_manifest(
            "\"beta\" width=4 height=4\n\"alpha\" width=4 height=4 affiliate=\"beta\"\n",
        );
        let listings: Vec<Option<Listing>> = vec![None, None];
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
            defragment: false,
        };
        let png = tiny_png_color(255, 0, 0);
        let a = prepare_import("beta", &png, &manifest, &listings, &opts).unwrap();
        let b = prepare_import("beta", &png, &manifest, &listings, &opts).unwrap();
        assert_eq!(a.twin, b.twin);
        assert_eq!(a.name, b.name);
        assert_eq!(a.line, b.line);
        assert_eq!(a.listing, b.listing);
        assert_eq!(a.hash_ok, b.hash_ok);
        assert_eq!(
            a.twin,
            Some(0),
            "twin resolved against the original manifest"
        );
        assert!(a.hash_ok, "no stored listing -> no hash rejection");
        assert!(prepare_import("x", b"not a png", &manifest, &listings, &opts).is_none());
    }

    #[test]
    fn import_create_sibling_with_model_appends_affiliate() {
        // Locks the CreateSibling apply arm including the hoisted quoted-name
        // needle (pass-12): the trimmed sibling model must be appended as an
        // affiliate-less manifest line referencing the imported texture name,
        // and a model already present in the manifest (case-insensitive) must
        // not be duplicated.
        let dir = std::env::temp_dir().join(format!("sibling-model-{}", std::process::id()));
        make_synthetic_cache(&dir);
        let opts = ImportOptions {
            format: ImportFormat::CompressedNoAlpha,
            conflict: ConflictPolicy::CreateSibling,
            // Whitespace around the model must be trimmed once (hoisted).
            sibling_model: Some("  wheel  ".to_string()),
            reduce_to: 0,
            camnorm: false,
            hash_check: true,
            correct_caps: false,
            use_rle: true,
            generate_mipmaps: true,
            mip_filter: 1,
            defragment: false,
        };
        let report =
            import_textures(&dir, "beta", &[("newtex".to_string(), tiny_png())], &opts).unwrap();
        assert!(report.contains("siblings=1"), "report: {report}");
        // CreateSibling counts the image under `siblings` only (added=0) —
        // established report semantics, unchanged by pass-12.
        assert!(report.contains("added=0"), "report: {report}");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert!(
            cache
                .manifest
                .iter()
                .any(|e| e.name == "wheel" && e.affiliate.as_deref() == Some("newtex")),
            "model sibling missing: {:?}",
            cache
                .manifest
                .iter()
                .map(|e| e.line.clone())
                .collect::<Vec<_>>()
        );
        // Re-importing the same texture must not append a second "wheel".
        let report2 =
            import_textures(&dir, "beta", &[("newtex2".to_string(), tiny_png())], &opts).unwrap();
        assert!(report2.contains("siblings=1"), "report2: {report2}");
        let cache2 = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(
            cache2.manifest.iter().filter(|e| e.name == "wheel").count(),
            1,
            "duplicate wheel sibling appended"
        );
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

    fn toc_offsets(dir: &std::path::Path, file: &str) -> Vec<i32> {
        let toc = std::fs::read(dir.join(file)).unwrap();
        let n = (toc.len() - 4) / 4;
        (0..n)
            .map(|i| i32::from_le_bytes(toc[4 + i * 4..8 + i * 4].try_into().unwrap()))
            .collect()
    }

    fn toc_stated_len(dir: &std::path::Path, file: &str) -> usize {
        let toc = std::fs::read(dir.join(file)).unwrap();
        u32::from_le_bytes(toc[0..4].try_into().unwrap()) as usize
    }

    #[test]
    fn import_overwrite_appends_and_keeps_unchanged_offsets() {
        // Three real textures; overwriting the middle one must not move
        // the other two (the old engine rewrote the whole .dat every time).
        let mk = |name: &str, fill: u8| {
            build_listing(name, TexEncoding::Dxt1, 4, 4, &[&[fill; 8]], true).unwrap()
        };
        let (l1, l2, l3) = (mk("t1", 1), mk("t2", 2), mk("t3", 3));
        let (dat, toc) = rebuild_dat_toc(&[Some(&l1), Some(&l2), Some(&l3)]);
        let dir = std::env::temp_dir().join(format!("texdb-append-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("beta.txt"),
            "\"t1\" width=4 height=4\n\"t2\" width=4 height=4\n\"t3\" width=4 height=4\n",
        )
        .unwrap();
        std::fs::write(dir.join("beta.etc.dat"), &dat).unwrap();
        std::fs::write(dir.join("beta.etc.toc"), &toc).unwrap();
        let old_dat_len = dat.len();
        let before = toc_offsets(&dir, "beta.etc.toc");
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
            defragment: false,
        };
        let report =
            import_textures(&dir, "beta", &[("t2".to_string(), tiny_png())], &opts).unwrap();
        assert!(report.contains("replaced=1"), "report: {report}");
        let after = toc_offsets(&dir, "beta.etc.toc");
        assert_eq!(after.len(), 3, "one toc offset per manifest entry");
        assert_eq!(after[0], before[0], "t1 must keep its offset");
        assert_eq!(after[2], before[2], "t3 must keep its offset");
        assert_eq!(
            after[1] as usize, old_dat_len,
            "t2's new listing is appended at the old EOF"
        );
        let new_len = std::fs::metadata(dir.join("beta.etc.dat")).unwrap().len() as usize;
        assert_eq!(
            toc_stated_len(&dir, "beta.etc.toc"),
            new_len,
            "toc[0] == .dat length"
        );
        assert!(new_len > old_dat_len, "dat grew by append only");
        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(cache.raw_listing(0).unwrap(), &l1[..], "t1 bytes untouched");
        assert_eq!(cache.raw_listing(2).unwrap(), &l3[..], "t3 bytes untouched");
        assert!(cache.decode_raw(1).is_some(), "t2 still decodes");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn second_variant_syncs_by_append_only() {
        let mk = |name: &str, fill: u8| {
            build_listing(name, TexEncoding::Dxt1, 4, 4, &[&[fill; 8]], true).unwrap()
        };
        let (la, lb) = (mk("aa", 7), mk("bb", 9));
        // A third, larger texture keeps the cache big enough that the
        // append heuristic (not the full-rebuild fallback) is exercised.
        let lc = build_listing("cc", TexEncoding::Dxt1, 16, 16, &[&[5u8; 128]], true).unwrap();
        let (dat, toc) = rebuild_dat_toc(&[Some(&la), Some(&lb), Some(&lc)]);
        let dir = std::env::temp_dir().join(format!("texdb-variants-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("multi.txt"),
            "\"aa\" width=4 height=4\n\"bb\" width=4 height=4\n\"cc\" width=16 height=16\n",
        )
        .unwrap();
        for v in ["etc", "dxt"] {
            std::fs::write(dir.join(format!("multi.{v}.dat")), &dat).unwrap();
            std::fs::write(dir.join(format!("multi.{v}.toc")), &toc).unwrap();
            // Correct .tmb files (one record per non-affiliate texture).
            let mut tmb = thumb_record("aa", None, TexEncoding::Dxt1, v);
            tmb.extend_from_slice(&thumb_record("bb", None, TexEncoding::Dxt1, v));
            tmb.extend_from_slice(&thumb_record("cc", None, TexEncoding::Dxt1, v));
            std::fs::write(dir.join(format!("multi.{v}.tmb")), &tmb).unwrap();
        }
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
            defragment: false,
        };
        import_textures(&dir, "multi", &[("aa".to_string(), tiny_png())], &opts).unwrap();
        // In BOTH families (the directly written one and the synced one):
        // bb/cc keep their offsets, aa's replacement sits at the old EOF —
        // neither .dat was rewritten wholesale.
        for v in ["etc", "dxt"] {
            let offs = toc_offsets(&dir, &format!("multi.{v}.toc"));
            assert_eq!(offs.len(), 3, "{v}: toc count == manifest count");
            assert_eq!(offs[1], la.len() as i32, "{v}: bb kept its original offset");
            assert_eq!(
                offs[2],
                (la.len() + lb.len()) as i32,
                "{v}: cc kept its original offset"
            );
            assert_eq!(
                offs[0] as usize,
                dat.len(),
                "{v}: aa appended at the old EOF"
            );
            let dat_len = std::fs::metadata(dir.join(format!("multi.{v}.dat")))
                .unwrap()
                .len() as usize;
            assert_eq!(
                toc_stated_len(&dir, &format!("multi.{v}.toc")),
                dat_len,
                "{v}: toc[0] == .dat len"
            );
            let tmb = std::fs::read(dir.join(format!("multi.{v}.tmb"))).unwrap();
            assert_eq!(
                parse_tmb_records(&tmb).map(|r| r.len()),
                Some(3),
                "{v} .tmb record count + 0 aliases == manifest count"
            );
        }
        let cache = TexdbCache::load(&dir, "multi").unwrap();
        assert!(cache.decode_raw(0).is_some() && cache.decode_raw(1).is_some());
        std::fs::remove_dir_all(&dir).ok();
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

    #[test]
    fn decode_pixels_at_targeted_scan_matches_full_parse() {
        // The targeted scan in decode_pixels_at must agree with the full
        // parse path (TexdbCache::load + decode_pixels) for every index,
        // including affiliates, out-of-bounds, indented entries, non-entry
        // lines (cat=) and a quote line without a closing quote.
        let beta_level0: Vec<u8> = (0..8u8).collect();
        let listing_beta =
            build_listing("beta", TexEncoding::Dxt1, 4, 4, &[&beta_level0], true).unwrap();
        let gamma_level0: Vec<u8> = (100..108u8).collect();
        let listing_gamma =
            build_listing("gamma", TexEncoding::Dxt1, 4, 4, &[&gamma_level0], true).unwrap();
        let (dat, toc) = rebuild_dat_toc(&[Some(&listing_beta), None, Some(&listing_gamma)]);
        let dir = std::env::temp_dir().join(format!("texdb-at-scan-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("beta.txt"),
            concat!(
                "cat=0 name=Default defaultformat=2\n",
                "\"beta\" width=4 height=4\n",
                "\"beta_lod\" width=4 height=4 affiliate=\"beta\"\n",
                "\"unclosed no closing quote\n",
                "  \"gamma\" width=4 height=4\n",
            ),
        )
        .unwrap();
        std::fs::write(dir.join("beta.etc.dat"), &dat).unwrap();
        std::fs::write(dir.join("beta.etc.toc"), &toc).unwrap();

        let cache = TexdbCache::load(&dir, "beta").unwrap();
        assert_eq!(
            cache.manifest.len(),
            3,
            "manifest entries: beta, beta_lod, gamma"
        );
        let enc0 = cache.listings[0].as_ref().unwrap().encoding_raw;
        let enc2 = cache.listings[2].as_ref().unwrap().encoding_raw;

        for i in 0..6usize {
            let via_at = decode_pixels_at(&dir, "beta", i);
            let via_cache = cache
                .decode_pixels(i)
                .map(|px| (cache.manifest[i].width, cache.manifest[i].height, px));
            match (via_at, via_cache) {
                (None, None) => {}
                (Some((w, h, enc, px)), Some((cw, ch, cpx))) => {
                    assert_eq!((w, h, enc), (cw, ch, if i == 0 { enc0 } else { enc2 }));
                    assert_eq!(px, cpx, "pixels differ at index {i}");
                }
                (a, b) => panic!("index {i}: decode_pixels_at={a:?} vs cache={}", b.is_some()),
            }
        }
        // Explicit edge expectations.
        assert!(decode_pixels_at(&dir, "beta", 1).is_none(), "affiliate");
        assert!(decode_pixels_at(&dir, "beta", 3).is_none(), "out of bounds");
        let (w, h, _, px) = decode_pixels_at(&dir, "beta", 2).unwrap();
        assert_eq!((w, h), (4, 4));
        assert_ne!(
            px,
            cache.decode_pixels(0).unwrap(),
            "gamma decodes its own pixels"
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn oversized_listing_is_treated_as_missing() {
        // A faked listing header (65535×32767) would inflate expected_size()
        // and the RLE output cap into multi-GiB allocations (review H1). It
        // must surface as "no data", never as a decode attempt. The header
        // is hand-rolled (an attacker does not use build_listing either).
        let mut listing = Vec::new();
        listing.extend_from_slice(&hash_name("huge").to_le_bytes());
        listing.extend_from_slice(&0x83F0u16.to_le_bytes()); // DXT1
        listing.extend_from_slice(&65535u16.to_le_bytes()); // width
        listing.extend_from_slice(&32767u16.to_le_bytes()); // height, level 0 only
        listing.extend_from_slice(&12u32.to_le_bytes()); // csize = payload(8) + 4
        listing.extend_from_slice(&0u32.to_le_bytes()); // stored raw
        listing.extend_from_slice(&[0u8; 8]);
        let (dat, toc) = rebuild_dat_toc(&[Some(&listing)]);
        let dir = std::env::temp_dir().join(format!("texdb-huge-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("huge.txt"), "\"huge\" width=65535 height=32767\n").unwrap();
        std::fs::write(dir.join("huge.etc.dat"), &dat).unwrap();
        std::fs::write(dir.join("huge.etc.toc"), &toc).unwrap();
        let cache = TexdbCache::load(&dir, "huge").unwrap();
        assert!(
            cache.listings[0].is_none(),
            "oversized listing must be dropped at load"
        );
        assert!(cache.decode_pixels(0).is_none());
        assert!(decode_pixels_at(&dir, "huge", 0).is_none());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn png_decode_rejects_oversized_header_and_accepts_small() {
        // IHDR claiming 65535×65535 RGBA (~17 GiB output buffer) must be
        // rejected by the dimension gate BEFORE the output allocation
        // (review M1). The trailing IDAT makes read_info succeed, so a
        // regression that checks too late OOMs instead of asserting.
        let mut png = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        let mut ihdr = Vec::new();
        ihdr.extend_from_slice(&65535u32.to_be_bytes());
        ihdr.extend_from_slice(&65535u32.to_be_bytes());
        ihdr.extend_from_slice(&[8, 6, 0, 0, 0]); // depth 8, RGBA, deflate/adaptive/none
        png.extend_from_slice(&13u32.to_be_bytes());
        png.extend_from_slice(b"IHDR");
        png.extend_from_slice(&ihdr);
        png.extend_from_slice(&crc32_ieee(&[b"IHDR".as_slice(), &ihdr].concat()).to_be_bytes());
        png.extend_from_slice(&0u32.to_be_bytes()); // empty IDAT
        png.extend_from_slice(b"IDAT");
        png.extend_from_slice(&crc32_ieee(b"IDAT").to_be_bytes());
        assert!(
            decode_png_argb(&png).is_none(),
            "oversized PNG header must be rejected"
        );

        // Sanity: a small valid PNG still decodes (and stays red).
        let (px, w, h) = decode_png_argb(&tiny_png()).unwrap();
        assert_eq!((w, h), (4, 4));
        assert_eq!(px.len(), 16);
        assert!(px.iter().all(|&p| p == 0xFFFF_0000));
    }

    #[test]
    fn encode_listing_rejects_inconsistent_dims() {
        // Review M3 backstop: a (pixels, w, h) mismatch must return None
        // instead of reaching PixelImage::new's assert (panic=abort).
        let px4 = vec![0xFF00_0000u32; 4];
        assert!(
            encode_listing_from_pixels("x", &px4, 0, 4, TexEncoding::Rgb565, false, true).is_none()
        );
        assert!(
            encode_listing_from_pixels("x", &px4, 4, 0, TexEncoding::Rgb565, false, true).is_none()
        );
        assert!(
            encode_listing_from_pixels("x", &px4, 2, 2, TexEncoding::Rgb565, false, true).is_some()
        );
        assert!(
            encode_listing_from_pixels("x", &px4, 4, 4, TexEncoding::Rgb565, false, true).is_none()
        );
        assert!(
            encode_listing_from_pixels("x", &px4, 4, 4, TexEncoding::Rgb565, true, true).is_none()
        );
        // 2×2 with the full mip chain (down to 1×1) still encodes.
        assert!(
            encode_listing_from_pixels("x", &px4, 2, 2, TexEncoding::Rgb565, true, true).is_some()
        );
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
/// Metadata-only: reads the manifest, the .toc, and 16-byte listing headers
/// via seeks — the (potentially hundreds of MB) .dat payload is never loaded.
pub fn describe_cache(dir: &std::path::Path) -> Result<CacheDescription, String> {
    let name = resolve_cache_name(dir)?;
    let text =
        std::fs::read_to_string(dir.join(format!("{name}.txt"))).map_err(|e| e.to_string())?;
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

    let mut manifest = parse_manifest(&text);
    inherit_affiliate_dims(&mut manifest);
    let mut active: Option<(String, VariantMeta)> = None;
    for v in &toc_variants {
        if let Some(meta) = load_variant_meta(dir, &name, v, &manifest) {
            active = Some((v.clone(), meta));
            break;
        }
    }
    let (variant, meta) = active.ok_or_else(|| {
        format!(
            "Tidak ada varian cache yang valid di {} (uji: ukuran .dat & jumlah entri)",
            dir.display()
        )
    })?;

    // Listing lookup for an index, with affiliate resolution to its target's
    // listing (same semantics as the previous full-load implementation).
    let listing_at =
        |j: usize| -> Option<&Listing> { meta.listings.get(j).and_then(|x| x.as_ref()) };
    let mut textures = Vec::with_capacity(manifest.len());
    for (i, entry) in manifest.iter().enumerate() {
        let direct = listing_at(i);
        let resolved = direct.or_else(|| {
            entry.affiliate.as_ref().and_then(|t| {
                manifest
                    .iter()
                    .position(|e| e.affiliate.is_none() && e.name.eq_ignore_ascii_case(t))
                    .and_then(listing_at)
            })
        });
        let (offset, size, hash, mips) = match (direct, resolved) {
            (Some(l), _) | (None, Some(l)) => {
                let (start, end) = (l.span_start, l.span_end);
                (
                    start,
                    end - start,
                    (l.hash as u32) | ((l.encoding_raw as u32) << 16),
                    l.full_chain,
                )
            }
            (None, None) => (0, 0, 0, false),
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
        dat_file_name: format!("{name}.{variant}.dat"),
        name,
        variant,
        variants: toc_variants,
        defaults,
        textures,
    })
}

/// Writes the raw .dat span of manifest `index` in the cache at `dir` to
/// `dest` (affiliates resolve to their target, as the old loader did).
/// Reads only the manifest, the .toc, and the target span — never the
/// whole .dat.
pub fn extract_raw_span(
    dir: &std::path::Path,
    index: usize,
    dest: &std::path::Path,
) -> Result<String, String> {
    use std::io::{Read, Seek, SeekFrom};
    let name = resolve_cache_name(dir)?;
    let text =
        std::fs::read_to_string(dir.join(format!("{name}.txt"))).map_err(|e| e.to_string())?;
    let mut manifest = parse_manifest(&text);
    inherit_affiliate_dims(&mut manifest);
    if index >= manifest.len() {
        return Err("Entri tidak punya data mentah".to_string());
    }
    let mut variants: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let fname = entry.file_name().to_string_lossy().into_owned();
            if let Some(rest) = fname
                .strip_prefix(&format!("{name}."))
                .and_then(|r| r.strip_suffix(".toc"))
            {
                variants.push(rest.to_string());
            }
        }
    }
    variants.sort();
    let mut found: Option<(String, usize, usize)> = None;
    for v in &variants {
        let Some(meta) = load_variant_meta(dir, &name, v, &manifest) else {
            continue;
        };
        // Resolve the span (affiliate-aware, same as span_for).
        let resolved = meta
            .listings
            .get(index)
            .and_then(|x| x.as_ref())
            .or_else(|| {
                manifest[index].affiliate.as_ref().and_then(|t| {
                    manifest
                        .iter()
                        .position(|e| e.affiliate.is_none() && e.name.eq_ignore_ascii_case(t))
                        .and_then(|j| meta.listings.get(j).and_then(|x| x.as_ref()))
                })
            });
        if let Some(l) = resolved {
            found = Some((v.clone(), l.span_start, l.span_end));
            break;
        }
        // A loadable variant without a span for this entry means the entry
        // has no data in this family (affiliate with missing target).
        return Err("Entri tidak punya data mentah".to_string());
    }
    let Some((variant, start, end)) = found else {
        return Err("Entri tidak punya data mentah".to_string());
    };
    let dat_path = dir.join(format!("{name}.{variant}.dat"));
    let mut f = std::fs::File::open(&dat_path).map_err(|e| e.to_string())?;
    f.seek(SeekFrom::Start(start as u64))
        .map_err(|e| e.to_string())?;
    let mut bytes = vec![0u8; end - start];
    f.read_exact(&mut bytes).map_err(|e| e.to_string())?;
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(dest, &bytes).map_err(|e| e.to_string())?;
    Ok(format!("terekstrak {} byte", bytes.len()))
}
