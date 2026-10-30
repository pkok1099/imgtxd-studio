//! Whole-game texture audit: cross-references every texture a game's DFF
//! models need against everything its texdb caches provide, and reports
//! what is missing (white/invisible models in game) and what is unused.
//!
//! Scope note: textures provided by TXD archives inside IMGs are not
//! counted yet (mobile games resolve vehicle/ped textures from texdb
//! caches, which is where missing-texture bugs actually bite).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::Path;

use crate::dff;
use crate::img::ImgArchive;
use crate::texdb::TexdbCache;

/// Audits a game `files/` folder (the one containing `texdb/` and the .img
/// archives). Returns an Indonesian report.
pub fn audit_game_folder(files_dir: &Path) -> Result<String, String> {
    if !files_dir.is_dir() {
        return Err(format!("bukan folder: {}", files_dir.display()));
    }
    // 1. Provided textures from every texdb cache: real names + affiliate
    // names (an affiliate provides its target under another name).
    let mut provided: BTreeSet<String> = BTreeSet::new();
    let mut real_textures: BTreeSet<String> = BTreeSet::new();
    let mut affiliate_targets: HashMap<String, String> = HashMap::new();
    let texdb_dir = files_dir.join("texdb");
    let mut cache_count = 0usize;
    if texdb_dir.is_dir() {
        for entry in std::fs::read_dir(&texdb_dir).map_err(|e| e.to_string())? {
            let path = entry.map_err(|e| e.to_string())?.path();
            if !path.is_dir() {
                continue;
            }
            let name = path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned();
            if !path.join(format!("{name}.txt")).exists() {
                continue;
            }
            if let Ok(cache) = TexdbCache::load(&path, &name) {
                cache_count += 1;
                for m in &cache.manifest {
                    let key = m.name.to_lowercase();
                    provided.insert(key.clone());
                    match &m.affiliate {
                        Some(target) => {
                            affiliate_targets.insert(key, target.to_lowercase());
                        }
                        None => {
                            real_textures.insert(key);
                        }
                    }
                }
            }
        }
    }

    // 2. Referenced textures from every DFF inside every IMG. Android SA
    // keeps its model archives inside texdb/ and anim/, so scan recursively
    // (depth-limited) rather than only the root.
    let mut referenced: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    let mut img_count = 0usize;
    let mut dff_count = 0usize;
    let mut img_paths: Vec<std::path::PathBuf> = Vec::new();
    fn collect_imgs(dir: &Path, depth: usize, out: &mut Vec<std::path::PathBuf>) {
        if depth > 4 {
            return;
        }
        let Ok(rd) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in rd.flatten() {
            let path = entry.path();
            if path.is_dir() {
                collect_imgs(&path, depth + 1, out);
            } else if path
                .extension()
                .map(|x| x.eq_ignore_ascii_case("img"))
                .unwrap_or(false)
            {
                out.push(path);
            }
        }
    }
    collect_imgs(files_dir, 0, &mut img_paths);
    for path in &img_paths {
        let path = path.as_path();
        // VER1 needs its .dir next to the .img.
        let dir_path = path.with_extension("dir");
        let dir_ref = if dir_path.exists() {
            Some(dir_path.as_path())
        } else {
            None
        };
        let archive = match ImgArchive::open(path, dir_ref) {
            Ok(a) => a,
            Err(_) => continue,
        };
        img_count += 1;
        for e in &archive.entries {
            if !e.clean_name().to_lowercase().ends_with(".dff") {
                continue;
            }
            let Ok(bytes) = archive.read_entry_bytes(e) else {
                continue;
            };
            dff_count += 1;
            for name in dff::texture_names(&bytes) {
                referenced
                    .entry(name.to_lowercase())
                    .or_default()
                    .insert(e.clean_name().to_string());
            }
        }
    }

    // 3. Diff. A referenced name is satisfied when provided directly or via
    // an affiliate whose target is referenced... affiliates provide their
    // own name, so direct set membership is the test. A real texture counts
    // as used when referenced by name, or when one of its affiliates is.
    let missing: Vec<(&String, &BTreeSet<String>)> = referenced
        .iter()
        .filter(|(name, _)| !provided.contains(*name))
        .collect();
    let mut used: BTreeSet<String> = BTreeSet::new();
    for name in referenced.keys() {
        if real_textures.contains(name) {
            used.insert(name.clone());
        }
        if let Some(target) = affiliate_targets.get(name) {
            used.insert(target.clone());
        }
    }
    let unused: Vec<&String> = real_textures
        .iter()
        .filter(|n| !used.contains(*n))
        .collect();

    let mut out = String::new();
    out.push_str(&format!("Folder: {}\n", files_dir.display()));
    out.push_str(&format!(
        "Cache texdb terbaca: {cache_count} · arsip IMG: {img_count} · DFF diperiksa: {dff_count}\n"
    ));
    out.push_str(&format!(
        "Tekstur disediakan cache: {} ({} asli + {} alias)\n",
        provided.len(),
        real_textures.len(),
        affiliate_targets.len()
    ));
    out.push_str(&format!("Tekstur dirujuk DFF: {}\n\n", referenced.len()));
    out.push_str(&format!(
        "TEKSTUR HILANG ({}): dirujuk model tapi tidak ada di cache mana pun — penyebab klasik model putih/tak terlihat.\n",
        missing.len()
    ));
    for (name, dffs) in missing.iter().take(100) {
        out.push_str(&format!(
            "  - {name} (dipakai {} DFF, mis. {})\n",
            dffs.len(),
            dffs.iter().next().cloned().unwrap_or_default()
        ));
    }
    if missing.len() > 100 {
        out.push_str(&format!("  … dan {} lainnya\n", missing.len() - 100));
    }
    out.push_str(&format!(
        "\nTEKSTUR TAK TERPAKAI ({}): ada di cache tapi tidak dirujuk DFF mana pun (kandidat aman untuk dihapus demi ukuran).\n",
        unused.len()
    ));
    for name in unused.iter().take(50) {
        out.push_str(&format!("  - {name}\n"));
    }
    if unused.len() > 50 {
        out.push_str(&format!("  … dan {} lainnya\n", unused.len() - 50));
    }
    out.push_str(
        "\nCatatan: tekstur dari arsip TXD di dalam IMG belum dihitung; tekstur bawaan mesin (font, corona, dsb.) bisa tampak 'hilang' padahal disediakan TXD internal game.\n",
    );
    Ok(out)
}
