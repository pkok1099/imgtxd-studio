use imgtxd_core::texdb::*;
use std::path::{Path, PathBuf};

fn copy_dir(src: &Path, dst: &Path) {
    std::fs::create_dir_all(dst).unwrap();
    for e in std::fs::read_dir(src).unwrap() {
        let e = e.unwrap();
        std::fs::copy(e.path(), dst.join(e.file_name())).unwrap();
    }
}

fn opts(conflict: ConflictPolicy) -> ImportOptions {
    ImportOptions {
        format: ImportFormat::CompressedAlpha,
        conflict,
        sibling_model: None,
        reduce_to: 2048,
        camnorm: true,
        hash_check: true,
        correct_caps: false,
        use_rle: true,
        generate_mipmaps: true,
        mip_filter: 1,
        defragment: false,
    }
}

fn main() {
    let src = PathBuf::from(std::env::args().nth(1).expect("src dir"));
    let png = std::fs::read(std::env::args().nth(2).expect("png path")).unwrap();
    let work = PathBuf::from("/tmp/txd_bisect");
    let cases: Vec<(&str, Box<dyn Fn(&Path) -> String>)> = vec![
        ("import_add", Box::new(|d: &Path| {
            let jobs = vec![("zz_new_texture".to_string(), png.clone())];
            import_textures(d, "txd", &jobs, &opts(ConflictPolicy::JustAdd))
                .unwrap_or_else(|e| format!("ERR {e}"))
        })),
        ("import_overwrite", Box::new(|d: &Path| {
            let jobs = vec![("gunshell".to_string(), png.clone())];
            import_textures(d, "txd", &jobs, &opts(ConflictPolicy::Overwrite))
                .unwrap_or_else(|e| format!("ERR {e}"))
        })),
        ("rename", Box::new(|d: &Path| {
            rename_texture(d, "txd", 0, "zz_renamed").unwrap_or_else(|e| format!("ERR {e}"))
        })),
        ("alias", Box::new(|d: &Path| {
            add_alias(d, "txd", 0, "zz_alias").unwrap_or_else(|e| format!("ERR {e}"))
        })),
        ("delete", Box::new(|d: &Path| {
            delete_textures(d, "txd", &[0]).unwrap_or_else(|e| format!("ERR {e}"))
        })),
        ("repack_dxt", Box::new(|d: &Path| {
            repack_cache(d, "txd", 0).unwrap_or_else(|e| format!("ERR {e}"))
        })),
        ("repack_etc", Box::new(|d: &Path| {
            repack_cache(d, "txd", 1).unwrap_or_else(|e| format!("ERR {e}"))
        })),
    ];
    for (label, op) in cases {
        let _ = std::fs::remove_dir_all(&work);
        copy_dir(&src, &work);
        let result = op(&work);
        // strict re-validation inline (same checks as strict_check example)
        let verdict = match TexdbCache::load(&work, "txd") {
            Ok(cache) => {
                let mut problems = 0;
                let mut prev_end = 0usize;
                for (i, e) in cache.manifest.iter().enumerate() {
                    let Some(listing) = &cache.listings[i] else {
                        if e.affiliate.is_none() { problems += 1; println!("  [{i}] {} no listing", e.name); }
                        continue;
                    };
                    if listing.hash != hash_name(&e.name) { problems += 1; println!("  [{i}] {} HASH listing={:04x} name={:04x}", e.name, listing.hash, hash_name(&e.name)); }
                    if listing.width != e.width || listing.height != e.height { problems += 1; println!("  [{i}] {} DIMS listing={}x{} manifest={}x{}", e.name, listing.width, listing.height, e.width, e.height); }
                    if listing.span_start != prev_end { problems += 1; println!("  [{i}] {} SPAN start={} expected={}", e.name, listing.span_start, prev_end); }
                    prev_end = listing.span_end;
                    if let Some(raw) = cache.raw_listing(i) {
                        let csize = u32::from_le_bytes([raw[8], raw[9], raw[10], raw[11]]) as usize;
                        if csize != raw.len() - 12 { problems += 1; println!("  [{i}] {} CSIZE field={} span-12={}", e.name, csize, raw.len() - 12); }
                    }
                }
                format!("{problems} problems")
            }
            Err(e) => format!("LOAD FAIL {e}"),
        };
        println!("{label}: op=({result}) strict=({verdict})");
    }
}
