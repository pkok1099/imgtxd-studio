//! Read-only validation of the Rust IMG parser against real archives.
//! Usage: cargo run --release --example validate_dataset -- <file.img> [...]
//! Output lines are designed to diff 1:1 against an independent Python parser.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use imgtxd_core::img::{ImgArchive, ImgVersion};

fn crc32(data: &[u8], mut crc: u32) -> u32 {
    crc = !crc;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xEDB88320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

fn data_crc(path: &Path, offset: u64, len: u64) -> std::io::Result<u32> {
    let mut f = std::fs::File::open(path)?;
    f.seek(SeekFrom::Start(offset))?;
    let mut crc = 0u32;
    let mut remaining = len;
    let mut buf = vec![0u8; 1 << 20];
    while remaining > 0 {
        let take = remaining.min(buf.len() as u64) as usize;
        f.read_exact(&mut buf[..take])?;
        crc = crc32(&buf[..take], crc);
        remaining -= take as u64;
    }
    Ok(crc)
}

fn main() {
    for arg in std::env::args().skip(1) {
        let path = Path::new(&arg);
        let name = path.file_name().unwrap().to_string_lossy();
        match ImgArchive::open(path, None) {
            Ok(arc) => {
                let ver = match arc.version {
                    ImgVersion::Ver1 => "VER1",
                    ImgVersion::Ver2 => "VER2",
                };
                // Directory fingerprint: crc over "name|offset|size;" for every entry
                let mut dir_desc = String::new();
                for e in &arc.entries {
                    dir_desc.push_str(&format!(
                        "{}|{}|{};",
                        e.clean_name(),
                        e.offset_sectors,
                        e.size_sectors
                    ));
                }
                let dir_crc = crc32(dir_desc.as_bytes(), 0);
                let largest = arc.entries.iter().reduce(|a, b| {
                    if b.size_sectors > a.size_sectors {
                        b
                    } else {
                        a
                    }
                });
                let (largest_desc, largest_crc) = match largest {
                    Some(e) => {
                        let crc =
                            data_crc(path, e.offset_bytes(), e.size_bytes()).unwrap_or(0xDEAD_BEEF);
                        (format!("{}:{}", e.clean_name(), e.size_sectors), crc)
                    }
                    None => ("-".to_string(), 0),
                };
                println!(
                    "{name} {ver} count={} dircrc={:08x} largest={} datacrc={:08x}",
                    arc.entries.len(),
                    dir_crc,
                    largest_desc,
                    largest_crc
                );
            }
            Err(e) => println!("{name} ERROR {e}"),
        }
    }
}
