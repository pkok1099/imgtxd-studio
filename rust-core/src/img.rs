//! GTA .img archive (VER1 + VER2) — faithful port of the Kotlin `ImgArchive`
//! and `ImgEntry`.
//!
//! Layout: 2048-byte sectors; 32-byte directory entries
//! (u32 offset sectors, u32 size sectors, 24-byte ISO-8859-1 name).
//! VER2 embeds the directory after an 8-byte header ("VER2" + count);
//! VER1 keeps it in a sidecar .dir file. The Kotlin `AppIntegrity` guard is an
//! app-level concern and intentionally not part of this engine port.

use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

pub const SECTOR_SIZE: u64 = 2048;
pub const ENTRY_SIZE: usize = 32;
pub const NAME_SIZE: usize = 24;
pub const VER2_MAGIC: &[u8; 4] = b"VER2";
const MAX_VER2_ENTRIES: u32 = 200_000;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ImgVersion {
    Ver1,
    Ver2,
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ImgEntry {
    pub offset_sectors: u32,
    pub size_sectors: u32,
    /// Raw name as stored (may contain NUL padding right after parsing).
    pub name: String,
}

impl ImgEntry {
    pub fn new(offset_sectors: u32, size_sectors: u32, name: &str) -> Self {
        Self {
            offset_sectors,
            size_sectors,
            name: name.to_string(),
        }
    }

    /// Name up to the first NUL byte (Kotlin `computeCleanName`).
    pub fn clean_name(&self) -> &str {
        match self.name.find('\0') {
            Some(idx) => &self.name[..idx],
            None => &self.name,
        }
    }

    pub fn offset_bytes(&self) -> u64 {
        self.offset_sectors as u64 * SECTOR_SIZE
    }

    pub fn size_bytes(&self) -> u64 {
        self.size_sectors as u64 * SECTOR_SIZE
    }
}

fn sectors_for(len: u64) -> u32 {
    len.div_ceil(SECTOR_SIZE) as u32
}

/// Encodes an IMG entry name into the fixed 24-byte directory field.
///
/// Encoding is one char → one byte (ISO-8859-1; chars > 0xFF become '?', the
/// same replacement the old Kotlin charset encoder produced), so truncating
/// by **chars** (`take(NAME_SIZE)`) is also truncating by output **bytes**:
/// the field can never overflow, and a multibyte UTF-8 input still fills all
/// 24 slots instead of wasting two bytes per char. Names in practice are
/// ASCII, where this is a plain byte copy.
fn encode_name(name: &str, out: &mut [u8; NAME_SIZE]) {
    for (i, ch) in name.chars().take(NAME_SIZE).enumerate() {
        let c = ch as u32;
        out[i] = if c <= 0xFF { c as u8 } else { b'?' };
    }
}

fn decode_name(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

fn parse_entries(buf: &[u8], count: usize) -> Vec<ImgEntry> {
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        let base = i * ENTRY_SIZE;
        let offset = u32::from_le_bytes(buf[base..base + 4].try_into().unwrap());
        let size = u32::from_le_bytes(buf[base + 4..base + 8].try_into().unwrap());
        let name = decode_name(&buf[base + 8..base + 8 + NAME_SIZE]);
        entries.push(ImgEntry {
            offset_sectors: offset,
            size_sectors: size,
            name,
        });
    }
    entries
}

fn entries_to_bytes(entries: &[ImgEntry]) -> Vec<u8> {
    let mut buf = vec![0u8; entries.len() * ENTRY_SIZE];
    for (i, e) in entries.iter().enumerate() {
        let base = i * ENTRY_SIZE;
        buf[base..base + 4].copy_from_slice(&e.offset_sectors.to_le_bytes());
        buf[base + 4..base + 8].copy_from_slice(&e.size_sectors.to_le_bytes());
        let mut name = [0u8; NAME_SIZE];
        encode_name(e.clean_name(), &mut name);
        buf[base + 8..base + 8 + NAME_SIZE].copy_from_slice(&name);
    }
    buf
}

pub fn is_ver2_file(path: &Path) -> bool {
    let mut f = match File::open(path) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let mut magic = [0u8; 4];
    matches!(f.read_exact(&mut magic), Ok(())) && &magic == VER2_MAGIC
}

fn default_dir_path(img_path: &Path) -> Option<PathBuf> {
    let stem = img_path.file_stem()?.to_str()?;
    let parent = img_path.parent()?;
    let lower = parent.join(format!("{stem}.dir"));
    if lower.exists() {
        return Some(lower);
    }
    let upper = parent.join(format!("{stem}.DIR"));
    if upper.exists() {
        return Some(upper);
    }
    None
}

pub struct ImgArchive {
    pub img_file: PathBuf,
    pub dir_file: Option<PathBuf>,
    pub version: ImgVersion,
    pub entries: Vec<ImgEntry>,
}

impl ImgArchive {
    pub fn open(img_file: &Path, dir_file: Option<&Path>) -> io::Result<Self> {
        if !img_file.exists() {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("IMG archive file not found: {}", img_file.display()),
            ));
        }
        if is_ver2_file(img_file) {
            // Directory-only read: header (8 B) + count * 32 B of entries.
            // Payloads are read lazily by extract/rebuild, never here — the
            // whole-file read this replaces cost ~1 s on a 283 MB archive.
            let mut f = File::open(img_file)?;
            let file_len = f.metadata()?.len();
            let mut header = [0u8; 8];
            f.read_exact(&mut header).map_err(|e| {
                if e.kind() == io::ErrorKind::UnexpectedEof {
                    io::Error::new(io::ErrorKind::InvalidData, "VER2 header truncated")
                } else {
                    e
                }
            })?;
            let count = u32::from_le_bytes(header[4..8].try_into().unwrap());
            if count > MAX_VER2_ENTRIES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("Invalid entry count in VER2 archive: {count}"),
                ));
            }
            let need = 8 + count as usize * ENTRY_SIZE;
            if file_len < need as u64 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "VER2 directory truncated",
                ));
            }
            let mut dir_buf = vec![0u8; count as usize * ENTRY_SIZE];
            f.read_exact(&mut dir_buf).map_err(|e| {
                if e.kind() == io::ErrorKind::UnexpectedEof {
                    io::Error::new(io::ErrorKind::InvalidData, "VER2 directory truncated")
                } else {
                    e
                }
            })?;
            let entries = parse_entries(&dir_buf, count as usize);
            Ok(Self {
                img_file: img_file.to_path_buf(),
                dir_file: None,
                version: ImgVersion::Ver2,
                entries,
            })
        } else {
            let resolved = match dir_file {
                Some(d) => d.to_path_buf(),
                None => default_dir_path(img_file).ok_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::NotFound,
                        "VER1 archive requires corresponding .dir file",
                    )
                })?,
            };
            let data = std::fs::read(&resolved)?;
            let count = data.len() / ENTRY_SIZE;
            let entries = parse_entries(&data[..count * ENTRY_SIZE], count);
            Ok(Self {
                img_file: img_file.to_path_buf(),
                dir_file: Some(resolved),
                version: ImgVersion::Ver1,
                entries,
            })
        }
    }

    pub fn create_empty(
        img_file: &Path,
        version: ImgVersion,
        dir_file: Option<&Path>,
    ) -> io::Result<Self> {
        match version {
            ImgVersion::Ver2 => {
                let mut f = OpenOptions::new()
                    .read(true)
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .open(img_file)?;
                f.write_all(VER2_MAGIC)?;
                f.write_all(&0u32.to_le_bytes())?;
                Ok(Self {
                    img_file: img_file.to_path_buf(),
                    dir_file: None,
                    version,
                    entries: Vec::new(),
                })
            }
            ImgVersion::Ver1 => {
                let resolved = match dir_file {
                    Some(d) => d.to_path_buf(),
                    None => {
                        let stem = img_file
                            .file_stem()
                            .and_then(|s| s.to_str())
                            .unwrap_or_default();
                        img_file
                            .parent()
                            .unwrap_or(Path::new("."))
                            .join(format!("{stem}.dir"))
                    }
                };
                OpenOptions::new()
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .open(img_file)?;
                OpenOptions::new()
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .open(&resolved)?;
                Ok(Self {
                    img_file: img_file.to_path_buf(),
                    dir_file: Some(resolved),
                    version,
                    entries: Vec::new(),
                })
            }
        }
    }

    fn ver2_header_size(&self) -> u64 {
        8 + self.entries.len() as u64 * ENTRY_SIZE as u64
    }

    pub fn total_data_size(&self) -> u64 {
        let mut total: u64 = self.entries.iter().map(|e| e.size_bytes()).sum();
        if self.version == ImgVersion::Ver2 {
            total += self.ver2_header_size();
        }
        total
    }

    fn write_dir(&mut self) -> io::Result<()> {
        match self.version {
            ImgVersion::Ver2 => {
                let header_size = self.ver2_header_size();
                // If the grown header overlaps the first entry, move that entry to the end.
                if let Some(first_idx) = self
                    .entries
                    .iter()
                    .enumerate()
                    .min_by_key(|(_, e)| e.offset_sectors)
                    .map(|(i, _)| i)
                {
                    if self.entries[first_idx].offset_bytes() < header_size {
                        self.move_entry_to_end(first_idx)?;
                    }
                }
                let mut f = OpenOptions::new()
                    .read(true)
                    .write(true)
                    .open(&self.img_file)?;
                f.seek(SeekFrom::Start(0))?;
                f.write_all(VER2_MAGIC)?;
                f.write_all(&(self.entries.len() as u32).to_le_bytes())?;
                f.write_all(&entries_to_bytes(&self.entries))?;
                Ok(())
            }
            ImgVersion::Ver1 => {
                let dir = self.dir_file.as_ref().ok_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::NotFound,
                        "Missing .dir file for VER1 archive",
                    )
                })?;
                // Atomic: a torn .dir write loses the index of the whole
                // archive; temp+rename keeps the previous (still valid) copy.
                crate::fsutil::write_atomic(dir, &entries_to_bytes(&self.entries))
            }
        }
    }

    fn move_entry_to_end(&mut self, idx: usize) -> io::Result<()> {
        let (offset, size) = (
            self.entries[idx].offset_bytes(),
            self.entries[idx].size_bytes(),
        );
        let mut f = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&self.img_file)?;
        let file_len = f.metadata()?.len();
        // Cap the read at the actual file length to prevent a malicious
        // directory entry (size_sectors up to u32::MAX → ~2^53 bytes) from
        // triggering an allocation-DoS abort. extract() uses take() and is
        // already safe; this fixes the in-memory move path.
        let size = size.min(file_len.saturating_sub(offset));
        let mut data = vec![0u8; size as usize];
        f.seek(SeekFrom::Start(offset))?;
        f.read_exact(&mut data)?;
        let end = file_len.div_ceil(SECTOR_SIZE) * SECTOR_SIZE;
        f.seek(SeekFrom::Start(end))?;
        f.write_all(&data)?;
        self.entries[idx].offset_sectors = (end / SECTOR_SIZE) as u32;
        Ok(())
    }

    /// Reads an entry's (sector-padded) bytes into memory.
    pub fn read_entry_bytes(&self, entry: &ImgEntry) -> io::Result<Vec<u8>> {
        let mut src = File::open(&self.img_file)?;
        let offset = entry.offset_bytes();
        // Cap the read at the actual file length to prevent allocation-DoS
        // from a malicious directory entry whose size_sectors is u32::MAX
        // (would request ~2^53 bytes on 64-bit and abort the process).
        let file_len = src.metadata()?.len();
        let size = entry.size_bytes().min(file_len.saturating_sub(offset));
        src.seek(SeekFrom::Start(offset))?;
        let mut buf = vec![0u8; size as usize];
        src.read_exact(&mut buf)?;
        Ok(buf)
    }

    pub fn extract(&self, entry: &ImgEntry, dest: &Path) -> io::Result<()> {
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut src = File::open(&self.img_file)?;
        src.seek(SeekFrom::Start(entry.offset_bytes()))?;
        let mut out = File::create(dest)?;
        // 1 MiB buffered copy: std::io::copy's internal 8 KiB buffer makes
        // multi-megabyte entries syscall-bound.
        let mut reader = io::BufReader::with_capacity(
            1024 * 1024,
            Read::by_ref(&mut src).take(entry.size_bytes()),
        );
        io::copy(&mut reader, &mut out)?;
        Ok(())
    }

    /// Copies up to `size` bytes src -> dst through a large scratch buffer
    /// (same EOF-tolerant semantics as `io::copy(take(size))`).
    fn copy_up_to(src: &mut File, dst: &mut File, mut size: u64, buf: &mut [u8]) -> io::Result<()> {
        while size > 0 {
            let want = (size as usize).min(buf.len());
            let n = src.read(&mut buf[..want])?;
            if n == 0 {
                break;
            }
            dst.write_all(&buf[..n])?;
            size -= n as u64;
        }
        Ok(())
    }

    fn write_file_padded(f: &mut File, src_path: &Path, sectors: u32) -> io::Result<()> {
        let mut src = File::open(src_path)?;
        // Large read/write chunks (1 MiB) instead of one 2 KiB sector read
        // per loop — order-of-magnitude fewer syscalls on multi-MB files.
        const CHUNK: usize = 1024 * 1024;
        let mut buf = vec![0u8; CHUNK];
        let mut remaining = sectors as u64 * SECTOR_SIZE;
        while remaining > 0 {
            let want = (remaining as usize).min(CHUNK);
            let mut filled = 0usize;
            while filled < want {
                let n = src.read(&mut buf[filled..want])?;
                if n == 0 {
                    break;
                }
                filled += n;
            }
            if filled < want {
                buf[filled..want].fill(0);
            }
            f.write_all(&buf[..want])?;
            remaining -= want as u64;
        }
        Ok(())
    }

    pub fn add(&mut self, file_to_add: &Path, entry_name: Option<&str>) -> io::Result<()> {
        let target = entry_name.map(|s| s.to_string()).unwrap_or_else(|| {
            file_to_add
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned()
        });
        let clean = target.split('\0').next().unwrap_or("").to_string();
        if self
            .entries
            .iter()
            .any(|e| e.clean_name().eq_ignore_ascii_case(&clean))
        {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                format!("Entry already exists in archive: {clean}"),
            ));
        }
        let file_len = std::fs::metadata(file_to_add)?.len();
        let sectors = sectors_for(file_len);
        let img_len = std::fs::metadata(&self.img_file)?.len();
        let append_sector = sectors_for(img_len);
        {
            let mut f = OpenOptions::new().write(true).open(&self.img_file)?;
            f.seek(SeekFrom::Start(append_sector as u64 * SECTOR_SIZE))?;
            Self::write_file_padded(&mut f, file_to_add, sectors)?;
        }
        self.entries
            .push(ImgEntry::new(append_sector, sectors, &clean));
        self.write_dir()
    }

    pub fn replace(&mut self, entry_idx: usize, replacement: &Path) -> io::Result<()> {
        let file_len = std::fs::metadata(replacement)?.len();
        let sectors = sectors_for(file_len);
        let write_sector = if sectors <= self.entries[entry_idx].size_sectors {
            self.entries[entry_idx].offset_sectors
        } else {
            let img_len = std::fs::metadata(&self.img_file)?.len();
            sectors_for(img_len)
        };
        {
            let mut f = OpenOptions::new().write(true).open(&self.img_file)?;
            f.seek(SeekFrom::Start(write_sector as u64 * SECTOR_SIZE))?;
            Self::write_file_padded(&mut f, replacement, sectors)?;
        }
        self.entries[entry_idx].offset_sectors = write_sector;
        self.entries[entry_idx].size_sectors = sectors;
        self.write_dir()
    }

    pub fn delete(&mut self, indices: &[usize]) -> io::Result<()> {
        let mut sorted: Vec<usize> = indices.to_vec();
        sorted.sort_unstable_by(|a, b| b.cmp(a));
        for i in sorted {
            if i < self.entries.len() {
                self.entries.remove(i);
            }
        }
        self.write_dir()
    }

    pub fn rename(&mut self, entry_idx: usize, new_name: &str) -> io::Result<()> {
        let clean = new_name.split('\0').next().unwrap_or("").to_string();
        self.entries[entry_idx].name = clean;
        self.write_dir()
    }

    /// Repacks all entries contiguously into a temp file, swaps it in, rewrites the directory.
    pub fn rebuild(&mut self) -> io::Result<()> {
        let tmp = self.img_file.with_file_name(format!(
            "{}_tmp",
            self.img_file
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
        ));
        if tmp.exists() {
            std::fs::remove_file(&tmp)?;
        }
        let header_sectors = if self.version == ImgVersion::Ver2 {
            self.ver2_header_size().div_ceil(SECTOR_SIZE)
        } else {
            0
        };
        let mut current_sector = header_sectors;
        {
            let mut src = File::open(&self.img_file)?;
            let mut dst = OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .open(&tmp)?;
            if self.version == ImgVersion::Ver2 {
                dst.write_all(VER2_MAGIC)?;
                let pad = header_sectors * SECTOR_SIZE - 4;
                dst.write_all(&vec![0u8; pad as usize])?;
            }
            let mut copy_buf = vec![0u8; 1024 * 1024];
            for i in 0..self.entries.len() {
                let (off, size) = (self.entries[i].offset_bytes(), self.entries[i].size_bytes());
                src.seek(SeekFrom::Start(off))?;
                dst.seek(SeekFrom::Start(current_sector * SECTOR_SIZE))?;
                Self::copy_up_to(&mut src, &mut dst, size, &mut copy_buf)?;
                // The offset of THIS entry is the pre-add cursor; it fits
                // u32 because the check below caps the cursor at u32::MAX
                // and header_sectors is bounded by MAX_VER2_ENTRIES.
                self.entries[i].offset_sectors = current_sector as u32;
                // Accumulate in u64 and refuse layouts beyond the u32 sector
                // fields on disk: claimed spans from a malformed directory can
                // otherwise wrap silently and produce a corrupt rebuilt
                // archive (8 TiB of claimed sectors = 2³² × 2 KiB).
                let next = current_sector.checked_add(self.entries[i].size_sectors as u64);
                match next {
                    Some(s) if s <= u32::MAX as u64 => current_sector = s,
                    _ => {
                        return Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            "archive layout exceeds u32 sector range",
                        ));
                    }
                }
            }
            dst.sync_all()?;
        }
        // fs::rename atomically replaces the destination on Linux/Android.
        // The previous remove_file() here opened a window in which the
        // archive did not exist at all (kill/power loss between the two
        // syscalls); rename alone is both atomic and sufficient.
        std::fs::rename(&tmp, &self.img_file)?;
        self.write_dir()
    }
}

/// Structural health report for an archive (Indonesian): duplicate names,
/// overlapping spans, entries past EOF, zero-size entries, reclaimable gaps.
pub fn verify_archive(img_file: &Path, dir_file: Option<&Path>) -> String {
    let archive = match ImgArchive::open(img_file, dir_file) {
        Ok(a) => a,
        Err(e) => return format!("Arsip tidak bisa dibuka: {e}"),
    };
    let file_len = std::fs::metadata(img_file).map(|m| m.len()).unwrap_or(0);
    let mut out = String::new();
    out.push_str(&format!("Berkas: {}\n", img_file.display()));
    out.push_str(&format!(
        "Versi: {:?} · {} entri · ukuran berkas {} byte\n",
        archive.version,
        archive.entries.len(),
        file_len
    ));
    // Duplicates (case-insensitive)
    let mut counts: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
    for e in &archive.entries {
        *counts.entry(e.clean_name().to_lowercase()).or_default() += 1;
    }
    let dups: Vec<(&String, &usize)> = counts.iter().filter(|(_, c)| **c > 1).collect();
    out.push_str(&format!("Nama duplikat: {}\n", dups.len()));
    for (name, c) in dups.iter().take(10) {
        out.push_str(&format!("  - {name} ×{c}\n"));
    }
    // Spans
    let mut spans: Vec<(u64, u64, &str)> = archive
        .entries
        .iter()
        .map(|e| {
            (
                e.offset_bytes(),
                e.offset_bytes() + e.size_bytes(),
                e.clean_name(),
            )
        })
        .collect();
    spans.sort();
    let zero = archive
        .entries
        .iter()
        .filter(|e| e.size_bytes() == 0)
        .count();
    out.push_str(&format!("Entri ukuran nol: {zero}\n"));
    let past_eof = spans.iter().filter(|(_, end, _)| *end > file_len).count();
    out.push_str(&format!("Entri melewati akhir berkas: {past_eof}\n"));
    let mut overlaps = 0usize;
    let mut reclaimable: u64 = 0;
    let mut cursor: Option<u64> = None;
    for (start, end, _) in &spans {
        if let Some(c) = cursor {
            if *start < c {
                overlaps += 1;
            } else {
                reclaimable += start - c;
            }
        }
        cursor = Some(cursor.map(|c| c.max(*end)).unwrap_or(*end));
    }
    if let Some(c) = cursor {
        if file_len > c {
            reclaimable += file_len - c;
        }
    }
    out.push_str(&format!("Rentang tumpang tindih: {overlaps}\n"));
    out.push_str(&format!(
        "Ruang bisa direklamasi (celah + ekor): {} byte (~{} KB)\n",
        reclaimable,
        reclaimable / 1024
    ));
    if dups.is_empty() && past_eof == 0 && overlaps == 0 {
        out.push_str("Hasil: SEHAT — tidak ada masalah struktural.\n");
    } else {
        out.push_str(
            "Hasil: ADA MASALAH — rebuild arsip biasanya memperbaiki tumpang tindih dan celah.\n",
        );
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    fn test_dir(tag: &str) -> PathBuf {
        let n = COUNTER.fetch_add(1, Ordering::SeqCst);
        let dir =
            std::env::temp_dir().join(format!("imgtxd-img-test-{}-{n}-{tag}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_src(dir: &Path, name: &str, size: usize, fill: u8) -> PathBuf {
        let p = dir.join(name);
        std::fs::write(&p, vec![fill; size]).unwrap();
        p
    }

    #[test]
    fn encode_name_truncates_at_24_bytes_with_latin1_mapping() {
        // 26 ASCII chars: truncated to exactly the first 24 bytes.
        let mut out = [0u8; NAME_SIZE];
        encode_name("abcdefghijklmnopqrstuvwxyz", &mut out);
        assert_eq!(&out, b"abcdefghijklmnopqrstuvwx");

        // One char → one byte: a 24-char name mixing in-field (é → 0xE9) and
        // out-of-field (中 → '?') chars fills the field exactly, no overflow.
        let mut out = [0u8; NAME_SIZE];
        encode_name("ééé中中中ééé中中中ééé中中中ééé中中中", &mut out);
        assert_eq!(out.len(), NAME_SIZE);
        assert!(
            out.iter().all(|&b| b == b'?' || b == 0xE9),
            "unexpected bytes: {out:?}"
        );

        // 30 multibyte chars: truncated at the 24-byte field boundary.
        let mut out = [0u8; NAME_SIZE];
        encode_name(&"中".repeat(30), &mut out);
        assert_eq!(out, [b'?'; NAME_SIZE]);
    }

    #[test]
    fn ver2_roundtrip_add_open_extract() {
        let dir = test_dir("v2");
        let img = dir.join("test.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver2, None).unwrap();
        let a = write_src(&dir, "a.dff", 3000, 0xAA);
        let b = write_src(&dir, "b.txd", 100, 0xBB);
        arc.add(&a, None).unwrap();
        arc.add(&b, None).unwrap();

        let reopened = ImgArchive::open(&img, None).unwrap();
        assert_eq!(reopened.version, ImgVersion::Ver2);
        assert_eq!(reopened.entries.len(), 2);
        assert_eq!(reopened.entries[0].clean_name(), "a.dff");
        assert_eq!(reopened.entries[0].size_sectors, 2);
        assert_eq!(reopened.entries[1].clean_name(), "b.txd");
        assert_eq!(reopened.entries[1].offset_sectors, 3);
        assert_eq!(reopened.total_data_size(), 8 + 64 + 3 * 2048);

        let out = dir.join("out.txd");
        reopened.extract(&reopened.entries[1], &out).unwrap();
        let data = std::fs::read(&out).unwrap();
        assert_eq!(data.len(), 2048);
        assert!(data[..100].iter().all(|&x| x == 0xBB));
        assert!(data[100..].iter().all(|&x| x == 0));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn ver2_header_growth_moves_first_entry() {
        let dir = test_dir("grow");
        let img = dir.join("big.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver2, None).unwrap();
        let src = write_src(&dir, "f.bin", 10, 0x42);
        for i in 0..65 {
            arc.add(&src, Some(&format!("e{i:03}.bin"))).unwrap();
        }
        let reopened = ImgArchive::open(&img, None).unwrap();
        assert_eq!(reopened.entries.len(), 65);
        let header = 8 + 65 * 32;
        for e in &reopened.entries {
            assert!(
                e.offset_bytes() >= header as u64,
                "entry {} overlaps header",
                e.clean_name()
            );
        }
        let out = dir.join("first.bin");
        reopened.extract(&reopened.entries[0], &out).unwrap();
        let data = std::fs::read(&out).unwrap();
        assert!(data[..10].iter().all(|&x| x == 0x42));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn ver1_roundtrip_and_delete() {
        let dir = test_dir("v1");
        let img = dir.join("old.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver1, None).unwrap();
        assert!(dir.join("old.dir").exists());
        let a = write_src(&dir, "x.dff", 500, 0x11);
        arc.add(&a, None).unwrap();

        let mut reopened = ImgArchive::open(&img, None).unwrap();
        assert_eq!(reopened.version, ImgVersion::Ver1);
        assert_eq!(reopened.entries.len(), 1);
        reopened.delete(&[0]).unwrap();
        let again = ImgArchive::open(&img, None).unwrap();
        assert_eq!(again.entries.len(), 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn replace_grows_to_end_then_shrinks_in_place() {
        let dir = test_dir("rep");
        let img = dir.join("r.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver2, None).unwrap();
        let small = write_src(&dir, "s.bin", 100, 0x01);
        arc.add(&small, Some("item.bin")).unwrap();
        let first_offset = arc.entries[0].offset_sectors;

        let big = write_src(&dir, "big.bin", 5000, 0x02);
        arc.replace(0, &big).unwrap();
        assert_eq!(arc.entries[0].size_sectors, 3);
        assert!(arc.entries[0].offset_sectors > first_offset);
        let grown_offset = arc.entries[0].offset_sectors;

        let tiny = write_src(&dir, "tiny.bin", 10, 0x03);
        arc.replace(0, &tiny).unwrap();
        assert_eq!(arc.entries[0].offset_sectors, grown_offset);
        assert_eq!(arc.entries[0].size_sectors, 1);

        let reopened = ImgArchive::open(&img, None).unwrap();
        let out = dir.join("o.bin");
        reopened.extract(&reopened.entries[0], &out).unwrap();
        let data = std::fs::read(&out).unwrap();
        assert!(data[..10].iter().all(|&x| x == 0x03));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn rebuild_reclaims_deleted_space() {
        let dir = test_dir("reb");
        let img = dir.join("rb.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver2, None).unwrap();
        let a = write_src(&dir, "a.bin", 2048, 0x0A);
        let b = write_src(&dir, "b.bin", 2048, 0x0B);
        arc.add(&a, None).unwrap();
        arc.add(&b, None).unwrap();
        arc.delete(&[0]).unwrap();
        arc.rebuild().unwrap();
        // header (1 sector) + 1 surviving sector
        assert_eq!(std::fs::metadata(&img).unwrap().len(), 2 * 2048);
        let reopened = ImgArchive::open(&img, None).unwrap();
        assert_eq!(reopened.entries.len(), 1);
        assert_eq!(reopened.entries[0].clean_name(), "b.bin");
        let out = dir.join("surv.bin");
        reopened.extract(&reopened.entries[0], &out).unwrap();
        assert!(std::fs::read(&out).unwrap().iter().all(|&x| x == 0x0B));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn duplicate_name_rejected_case_insensitive() {
        let dir = test_dir("dup");
        let img = dir.join("d.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver2, None).unwrap();
        let a = write_src(&dir, "a.bin", 10, 0x01);
        arc.add(&a, Some("Same.DFF")).unwrap();
        assert!(arc.add(&a, Some("same.dff")).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn clean_name_stops_at_nul() {
        let e = ImgEntry::new(0, 1, "abc\0\0\0");
        assert_eq!(e.clean_name(), "abc");
    }

    #[test]
    fn rebuild_rejects_u32_sector_overflow() {
        // Review L3: claimed spans from a malformed directory must not wrap
        // the u32 sector accumulator — rebuild fails cleanly instead of
        // writing a corrupt archive layout (previously 2 entries claiming
        // u32::MAX sectors wrapped back to small offsets on disk).
        let dir = test_dir("overflow");
        let img = dir.join("ovf.img");
        let d = dir.join("ovf.dir");
        std::fs::write(&img, vec![0u8; 4096]).unwrap();
        std::fs::write(&d, []).unwrap();
        let mut arc = ImgArchive::open(&img, Some(&d)).unwrap();
        arc.entries.push(ImgEntry::new(0, u32::MAX, "a.dff"));
        arc.entries.push(ImgEntry::new(0, 1, "b.dff"));
        let res = arc.rebuild();
        assert!(
            res.is_err(),
            "rebuild must refuse a layout beyond u32 sectors"
        );
        // The original archive on disk must be untouched (rename happens
        // only after the whole layout is validated).
        assert!(img.exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn rebuild_swaps_archive_without_remove_window() {
        // Review M4: rebuild must keep the archive present at all times —
        // the swap is a single atomic rename, no remove_file before it.
        let dir = test_dir("swap");
        let img = dir.join("s.img");
        let mut arc = ImgArchive::create_empty(&img, ImgVersion::Ver2, None).unwrap();
        let a = write_src(&dir, "a.dff", 3000, 0xAA);
        arc.add(&a, None).unwrap();
        arc.rebuild().unwrap();
        let reopened = ImgArchive::open(&img, None).unwrap();
        assert_eq!(reopened.entries.len(), 1);
        assert_eq!(reopened.entries[0].clean_name(), "a.dff");
        assert!(
            !dir.join("s_tmp").exists(),
            "temp file must be gone after rename"
        );
        std::fs::remove_dir_all(&dir).ok();
    }
}
