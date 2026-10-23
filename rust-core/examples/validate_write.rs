//! Write-path validation on a COPY of a real archive:
//! add one file, delete entry 0, rebuild, reopen, print the summary.
//! Usage: validate_write <src.img> <file-to-add> <entry-name> <work-dir>
use imgtxd_core::img::ImgArchive;
use std::path::Path;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (src, add, name, work) = (
        Path::new(&args[1]),
        Path::new(&args[2]),
        &args[3],
        Path::new(&args[4]),
    );
    std::fs::create_dir_all(work).unwrap();
    let copy = work.join("work.img");
    std::fs::copy(src, &copy).unwrap();
    let mut arc = ImgArchive::open(&copy, None).unwrap();
    let before = arc.entries.len();
    arc.add(add, Some(name)).unwrap();
    arc.delete(&[0]).unwrap();
    arc.rebuild().unwrap();
    let reopened = ImgArchive::open(&copy, None).unwrap();
    println!(
        "before={} after={} workimg={}",
        before,
        reopened.entries.len(),
        copy.display()
    );
    for e in &reopened.entries {
        println!("E {}|{}|{}", e.clean_name(), e.offset_sectors, e.size_sectors);
    }
}
