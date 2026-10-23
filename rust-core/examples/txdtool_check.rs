use imgtxd_core::texdb::*;
use std::path::PathBuf;

/// Replicates TXD Tool's cache-corruption equations (recovered from its
/// loader): for every variant family it can load — toc count == manifest
/// count AND tmb count + alias count == manifest count. A tmb-only family
/// (unc) gets the tmb equation. Unloadable variants are ignored, exactly
/// like TXD Tool ignores the shipped broken .360 family.
fn main() {
    let dir = PathBuf::from(std::env::args().nth(1).expect("dir"));
    let name = std::env::args().nth(2).expect("name");
    let text = std::fs::read_to_string(dir.join(format!("{name}.txt"))).expect("txt");
    let manifest = parse_manifest_for_check(&text);
    let txt_count = manifest.len();
    let alias_count = manifest.iter().filter(|a| **a).count();
    let mut families: Vec<String> = Vec::new();
    for e in std::fs::read_dir(&dir).unwrap().flatten() {
        let f = e.file_name().to_string_lossy().to_string();
        if let Some(v) = f.strip_prefix(&format!("{name}.")).and_then(|r| r.strip_suffix(".toc")) {
            families.push(v.to_string());
        }
    }
    families.sort();
    let mut bad = 0;
    for v in &families {
        match TexdbCache::load(&dir, &name) {
            Ok(c) if c.variant == *v => {}
            _ => {
                // not loadable as this variant (or not the chosen one);
                // validate via toc count only when toc parses structurally
                let toc = std::fs::read(dir.join(format!("{name}.{v}.toc"))).unwrap_or_default();
                let dat_len = std::fs::metadata(dir.join(format!("{name}.{v}.dat"))).map(|m| m.len() as usize).unwrap_or(0);
                let stated = if toc.len() >= 4 { u32::from_le_bytes(toc[0..4].try_into().unwrap()) as usize } else { 0 };
                if stated != dat_len {
                    println!("family {v}: variant not loadable -> TXD Tool skips it");
                    continue;
                }
            }
        }
        let toc = std::fs::read(dir.join(format!("{name}.{v}.toc"))).unwrap();
        let toc_count = (toc.len() - 4) / 4;
        let tmb = std::fs::read(dir.join(format!("{name}.{v}.tmb"))).unwrap_or_default();
        let tmb_count = count_tmb(&tmb);
        let ok_toc = toc_count == txt_count;
        let ok_tmb = tmb_count.map_or(false, |c| c + alias_count == txt_count);
        println!("family {v}: toc={toc_count} (==txt {txt_count}: {ok_toc}) tmb={tmb_count:?} (+alias {alias_count} == txt: {ok_tmb})");
        if !ok_toc || !ok_tmb { bad += 1; }
    }
    // tmb-only families
    for e in std::fs::read_dir(&dir).unwrap().flatten() {
        let f = e.file_name().to_string_lossy().to_string();
        if let Some(v) = f.strip_prefix(&format!("{name}.")).and_then(|r| r.strip_suffix(".tmb")) {
            if families.iter().any(|x| x == v) { continue; }
            let tmb = std::fs::read(e.path()).unwrap_or_default();
            let tmb_count = count_tmb(&tmb);
            let ok = tmb_count.map_or(false, |c| c + alias_count == txt_count);
            println!("tmb-only {v}: tmb={tmb_count:?} (+alias {alias_count} == txt {txt_count}: {ok})");
            if !ok { bad += 1; }
        }
    }
    println!("TXDTOOL VERDICT: {}", if bad == 0 { "CLEAN".to_string() } else { format!("CORRUPTED ({bad} families)") });
}

fn parse_manifest_for_check(text: &str) -> Vec<bool> {
    // true = affiliate line
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with('"') {
            out.push(line.contains("affiliate="));
        }
    }
    out
}

fn count_tmb(bytes: &[u8]) -> Option<usize> {
    let mut off = 0usize;
    let mut n = 0usize;
    while off < bytes.len() {
        if off + 16 > bytes.len() { return None; }
        let csize = u32::from_le_bytes(bytes[off + 8..off + 12].try_into().ok()?) as usize;
        let span = csize.checked_add(12)?;
        if span < 16 || off + span > bytes.len() { return None; }
        off += span;
        n += 1;
    }
    Some(n)
}
