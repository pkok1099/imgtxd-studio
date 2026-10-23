//! End-to-end probe of the exact `img.rs` calls the JNI layer makes for the
//! app's IMG screens: list -> add -> extract -> replace -> rename -> delete
//! -> rebuild, on a copy of a real dataset IMG. Verifies CRC stability of
//! untouched entries at every step and a SEHAT verdict after rebuild.
use imgtxd_core::img::*;
use imgtxd_core::texdb::crc32_ieee;
use std::path::PathBuf;

fn crcs(a: &ImgArchive) -> Vec<(String, u32)> {
    a.entries
        .iter()
        .map(|e| {
            let bytes = a.read_entry_bytes(e).expect("read entry");
            (e.clean_name().to_string(), crc32_ieee(&bytes))
        })
        .collect()
}

fn main() {
    let src = PathBuf::from(std::env::args().nth(1).expect("src img"));
    let work = PathBuf::from("/tmp/img_jni_probe");
    std::fs::create_dir_all(&work).unwrap();
    let img_path = work.join("probe.img");
    std::fs::copy(&src, &img_path).unwrap();
    // sidecar .dir for VER1 sources
    let dir_src = src.with_extension("dir");
    if dir_src.exists() {
        std::fs::copy(&dir_src, work.join("probe.dir")).unwrap();
    }

    let mut arc = ImgArchive::open(&img_path, None).expect("open");
    let n0 = arc.entries.len();
    let before = crcs(&arc);
    println!("OPEN {:?} entries={} total={}", arc.version, n0, arc.total_data_size());

    // ADD
    let new_file = work.join("probe_new.dff");
    std::fs::write(&new_file, vec![0x5Au8; 5000]).unwrap();
    arc.add(&new_file, Some("probe_new.dff")).expect("add");
    assert_eq!(arc.entries.len(), n0 + 1);
    let idx_new = arc.entries.len() - 1;
    let out = work.join("out_new.bin");
    arc.extract(&arc.entries[idx_new].clone(), &out).expect("extract new");
    let data = std::fs::read(&out).unwrap();
    assert_eq!(data.len(), 6144);
    assert!(data[..5000].iter().all(|&b| b == 0x5A));
    println!("ADD+EXTRACT ok (crc untouched stable: {})", crcs(&arc)[..n0] == before[..]);

    // REPLACE (grow entry 0)
    let repl = work.join("repl.bin");
    std::fs::write(&repl, vec![0x77u8; 9000]).unwrap();
    let victim_name = arc.entries[0].clean_name().to_string();
    arc.replace(0, &repl).expect("replace");
    let out0 = work.join("out0.bin");
    arc.extract(&arc.entries[0].clone(), &out0).expect("extract replaced");
    let d0 = std::fs::read(&out0).unwrap();
    assert!(d0[..9000].iter().all(|&b| b == 0x77));
    println!("REPLACE ok ({victim_name})");

    // RENAME
    arc.rename(0, "renamed_probe.bin").expect("rename");
    assert_eq!(arc.entries[0].clean_name(), "renamed_probe.bin");
    println!("RENAME ok");

    // REOPEN (listing path) + DELETE the added entry
    let mut arc = ImgArchive::open(&img_path, None).expect("reopen");
    assert_eq!(arc.entries.len(), n0 + 1);
    let idx_new = arc
        .entries
        .iter()
        .position(|e| e.clean_name() == "probe_new.dff")
        .expect("find new");
    arc.delete(&[idx_new]).expect("delete");
    assert_eq!(arc.entries.len(), n0);
    println!("DELETE ok");

    // REBUILD + health + CRC of surviving untouched entries (index 0 was replaced)
    arc.rebuild().expect("rebuild");
    let report = verify_archive(&img_path, None);
    assert!(report.contains("SEHAT"), "report:\n{report}");
    let after = crcs(&arc);
    let mut stable = 0;
    for (name, crc) in &before[1..] {
        if after.iter().any(|(n, c)| n == name && c == crc) {
            stable += 1;
        }
    }
    println!(
        "REBUILD ok SEHAT; untouched CRC stable {}/{} (entry 0 replaced+renamed by design)",
        stable,
        before.len() - 1
    );
    assert_eq!(stable, before.len() - 1);
    println!("IMG_JNI_PATH_PROBE_PASS");
}
