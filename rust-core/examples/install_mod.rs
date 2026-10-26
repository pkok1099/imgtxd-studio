//! Installs a mod DFF into a COPY of a real IMG archive using the Rust engine.
//! Usage: install_mod <src.img> <work.img> <entry-name> <mod-file>
use imgtxd_core::img::ImgArchive;
use std::path::Path;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (src, work, entry_name, mod_file) = (
        Path::new(&args[1]),
        Path::new(&args[2]),
        &args[3],
        Path::new(&args[4]),
    );
    std::fs::copy(src, work).unwrap();
    let mut arc = ImgArchive::open(work, None).unwrap();
    let idx = arc
        .entries
        .iter()
        .position(|e| e.clean_name().eq_ignore_ascii_case(entry_name))
        .expect("entry not found");
    let before = arc.entries[idx].clone();
    arc.replace(idx, mod_file).unwrap();
    let after = arc.entries[idx].clone();
    println!(
        "replaced {}: offset {} -> {}, sectors {} -> {}",
        after.clean_name(),
        before.offset_sectors,
        after.offset_sectors,
        before.size_sectors,
        after.size_sectors
    );
    let reopened = ImgArchive::open(work, None).unwrap();
    println!(
        "reopened ok: {} entries, file size {}",
        reopened.entries.len(),
        std::fs::metadata(work).unwrap().len()
    );
}
