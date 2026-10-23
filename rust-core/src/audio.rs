//! GTA SA audio fixer (IMG Tool parity item I13) — faithful port of the
//! Kotlin `OswIdxFixer`.
//!
//! The `.osw` file is a ZIP container. This module recomputes every entry's
//! data offset and compressed size from the ZIP central directory and
//! writes a fresh `<name>.osw.idx`:
//!
//!   i32 entry count, then per entry: i32 data offset, i32 compressed
//!   size, i16 name length, name bytes (ISO-8859-1).
//!
//! Offsets follow the original tool's formula: entries are sequential,
//! each preceded by a 30-byte local header plus its file name. Validated
//! against the real GENRL.osw: the regenerated index is byte-identical
//! to the game's shipped GENRL.osw.idx (652 entries).

const LOCAL_HEADER_BASE_SIZE: i64 = 30;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IdxEntry {
    pub name: String,
    pub data_offset: i32,
    pub compressed_size: i32,
}

fn u16le(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes(b[off..off + 2].try_into().unwrap_or([0; 2]))
}

fn u32le(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes(b[off..off + 4].try_into().unwrap_or([0; 4]))
}

/// Computes the index entries from the ZIP central directory, in order,
/// without writing anything.
pub fn compute_index(osw_bytes: &[u8]) -> Result<Vec<IdxEntry>, String> {
    // Locate the End Of Central Directory record (22-byte base + comment).
    if osw_bytes.len() < 22 {
        return Err("Berkas .osw terlalu kecil".to_string());
    }
    let scan_start = osw_bytes.len().saturating_sub(65_557);
    let mut eocd = None;
    let mut i = osw_bytes.len() - 22;
    loop {
        if u32le(osw_bytes, i) == 0x0605_4b50 {
            eocd = Some(i);
            break;
        }
        if i == scan_start {
            break;
        }
        i -= 1;
    }
    let eocd = eocd.ok_or_else(|| "Struktur ZIP .osw tidak ditemukan".to_string())?;
    let total = u16le(osw_bytes, eocd + 10) as usize;
    let cd_offset = u32le(osw_bytes, eocd + 16) as usize;

    let mut entries = Vec::with_capacity(total);
    let mut position: i64 = 0;
    let mut p = cd_offset;
    for _ in 0..total {
        if p + 46 > osw_bytes.len() || u32le(osw_bytes, p) != 0x0201_4b50 {
            return Err("Direktori pusat ZIP .osw rusak".to_string());
        }
        let compressed_size = u32le(osw_bytes, p + 20) as i64;
        let name_len = u16le(osw_bytes, p + 28) as usize;
        let extra_len = u16le(osw_bytes, p + 30) as usize;
        let comment_len = u16le(osw_bytes, p + 32) as usize;
        let name_bytes = &osw_bytes[p + 46..(p + 46 + name_len).min(osw_bytes.len())];
        let name = String::from_utf8_lossy(name_bytes).into_owned();
        // Kotlin uses String.length (UTF-16 code units) for the offset
        // formula and the stored name length.
        let name_units = name.encode_utf16().count() as i64;
        let data_offset = position + LOCAL_HEADER_BASE_SIZE + name_units;
        if !name.ends_with('/') {
            entries.push(IdxEntry {
                name,
                data_offset: data_offset as i32,
                compressed_size: compressed_size as i32,
            });
        }
        position = data_offset + compressed_size;
        p += 46 + name_len + extra_len + comment_len;
    }
    Ok(entries)
}

/// Serializes index entries into `.idx` file bytes.
pub fn idx_bytes(entries: &[IdxEntry]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(entries.len() as i32).to_le_bytes());
    for e in entries {
        out.extend_from_slice(&e.data_offset.to_le_bytes());
        out.extend_from_slice(&e.compressed_size.to_le_bytes());
        out.extend_from_slice(&(e.name.encode_utf16().count() as i16).to_le_bytes());
        for ch in e.name.chars() {
            let v = ch as u32;
            out.push(if v <= 0xFF { v as u8 } else { b'?' });
        }
    }
    out
}

/// Recomputes the index for `osw_path` and writes `<osw_path>.idx`.
/// Returns (entry count, idx file path).
pub fn fix_file(osw_path: &std::path::Path) -> Result<(usize, std::path::PathBuf), String> {
    let bytes = std::fs::read(osw_path).map_err(|e| e.to_string())?;
    let entries = compute_index(&bytes)?;
    let idx_path = std::path::PathBuf::from(format!("{}.idx", osw_path.display()));
    std::fs::write(&idx_path, idx_bytes(&entries)).map_err(|e| e.to_string())?;
    Ok((entries.len(), idx_path))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn local_header(name: &str, size: u32) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        v.extend_from_slice(&20u16.to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes()); // flags
        v.extend_from_slice(&0u16.to_le_bytes()); // method STORED
        v.extend_from_slice(&0u32.to_le_bytes()); // time+date
        v.extend_from_slice(&0u32.to_le_bytes()); // crc (unused by the fixer)
        v.extend_from_slice(&size.to_le_bytes());
        v.extend_from_slice(&size.to_le_bytes());
        v.extend_from_slice(&(name.len() as u16).to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes());
        v.extend_from_slice(name.as_bytes());
        v
    }

    fn central_header(name: &str, size: u32, local_offset: u32) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        v.extend_from_slice(&20u16.to_le_bytes());
        v.extend_from_slice(&20u16.to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&size.to_le_bytes());
        v.extend_from_slice(&size.to_le_bytes());
        v.extend_from_slice(&(name.len() as u16).to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes()); // extra
        v.extend_from_slice(&0u16.to_le_bytes()); // comment
        v.extend_from_slice(&0u16.to_le_bytes()); // disk
        v.extend_from_slice(&0u16.to_le_bytes()); // int attr
        v.extend_from_slice(&0u32.to_le_bytes()); // ext attr
        v.extend_from_slice(&local_offset.to_le_bytes());
        v.extend_from_slice(name.as_bytes());
        v
    }

    #[test]
    fn compute_index_skips_directories_but_advances_position() {
        let mut zip = Vec::new();
        let off_a = zip.len() as u32;
        zip.extend_from_slice(&local_header("a.txt", 5));
        zip.extend_from_slice(b"hello");
        let off_dir = zip.len() as u32;
        zip.extend_from_slice(&local_header("dir/", 0));
        let off_b = zip.len() as u32;
        zip.extend_from_slice(&local_header("dir/b.bin", 3));
        zip.extend_from_slice(b"xyz");
        let cd_start = zip.len() as u32;
        let mut cd = Vec::new();
        cd.extend_from_slice(&central_header("a.txt", 5, off_a));
        cd.extend_from_slice(&central_header("dir/", 0, off_dir));
        cd.extend_from_slice(&central_header("dir/b.bin", 3, off_b));
        zip.extend_from_slice(&cd);
        zip.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&3u16.to_le_bytes());
        zip.extend_from_slice(&3u16.to_le_bytes());
        zip.extend_from_slice(&(cd.len() as u32).to_le_bytes());
        zip.extend_from_slice(&cd_start.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());

        let entries = compute_index(&zip).expect("index");
        assert_eq!(
            entries,
            vec![
                IdxEntry { name: "a.txt".into(), data_offset: 35, compressed_size: 5 },
                IdxEntry { name: "dir/b.bin".into(), data_offset: 113, compressed_size: 3 },
            ]
        );
        assert_eq!(idx_bytes(&entries).len(), 4 + 15 + 19);
    }
}
