//! Golden-parity probes for the audio fixer and the texdb cache description.
//! Usage: audio_texdb_golden <GENRL.osw> <shipped GENRL.osw.idx> <texdb cache dir>
use imgtxd_core::audio;
use imgtxd_core::texdb;
use std::path::{Path, PathBuf};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let osw = PathBuf::from(&args[1]);
    let shipped_idx = PathBuf::from(&args[2]);
    let cache_dir = PathBuf::from(&args[3]);

    // --- audio: fix a scratch copy, compare bytes with the shipped .idx ---
    let work = Path::new("/tmp/audio_probe.osw");
    std::fs::copy(&osw, work).unwrap();
    let (count, idx_path) = audio::fix_file(work).expect("fix");
    let produced = std::fs::read(&idx_path).unwrap();
    let shipped = std::fs::read(&shipped_idx).unwrap();
    let entries = audio::compute_index(&std::fs::read(&osw).unwrap()).unwrap();
    println!("GOLDEN|osw_count|{count}");
    println!("GOLDEN|osw_match|{}", produced == shipped);
    println!(
        "GOLDEN|osw_first|IdxEntry(name={}, dataOffset={}, compressedSize={})",
        entries.first().unwrap().name,
        entries.first().unwrap().data_offset,
        entries.first().unwrap().compressed_size
    );
    println!(
        "GOLDEN|osw_last|IdxEntry(name={}, dataOffset={}, compressedSize={})",
        entries.last().unwrap().name,
        entries.last().unwrap().data_offset,
        entries.last().unwrap().compressed_size
    );

    // --- texdb describe in the Kotlin golden's line format ---
    let desc = texdb::describe_cache(&cache_dir).expect("describe");
    println!(
        "GOLDEN|texdb|{}|{}|{}|{}",
        desc.name,
        desc.variant,
        desc.variants.join(","),
        desc.dat_file_name
    );
    let mut defs = desc.defaults.clone();
    defs.sort();
    println!(
        "GOLDEN|texdb_defaults|{}",
        defs.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join(";")
    );
    for t in &desc.textures {
        let mut flags = t.flags.clone();
        flags.sort();
        println!(
            "GOLDEN|tex|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
            t.name,
            t.width,
            t.height,
            t.offset,
            t.size,
            t.hash as i32,
            t.has_mipmaps,
            t.png_crc.as_deref().unwrap_or("-"),
            t.img_crc.as_deref().unwrap_or("-"),
            t.category,
            flags.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join(";")
        );
    }
}
