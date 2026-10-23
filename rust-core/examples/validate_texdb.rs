//! Validates the Rust texdb decoder against real game caches.
//! Usage: validate_texdb <texdb-root-dir>
//! For every cache + variant: loads via TexdbCache internals per variant and
//! reports how many listings decode to exactly their expected size.
use std::path::Path;

use imgtxd_core::texdb::{TexEncoding, TexdbCache};

fn main() {
    let root_arg = std::env::args().nth(1).expect("usage: validate_texdb <root>");
    let root = Path::new(&root_arg);
    let mut caches: Vec<String> = Vec::new();
    for e in std::fs::read_dir(root).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            caches.push(p.file_name().unwrap().to_string_lossy().to_string());
        }
    }
    caches.sort();
    for cache in &caches {
        let dir = root.join(cache);
        match TexdbCache::load(&dir, cache) {
            Ok(c) => {
                let mut exact = 0;
                let mut aff = 0;
                let mut fail = 0;
                let mut unknown = 0;
                for (i, l) in c.listings.iter().enumerate() {
                    match l {
                        None => aff += 1,
                        Some(listing) => {
                            if listing.encoding == TexEncoding::Unknown(listing.encoding_raw) {
                                unknown += 1;
                                continue;
                            }
                            match c.decode_raw(i) {
                                Some(_) => exact += 1,
                                None => fail += 1,
                            }
                        }
                    }
                }
                println!(
                    "{cache} ({}): entries={} decoded_ok={} affiliates={} unknown_enc={} failed={}",
                    c.variant,
                    c.manifest.len(),
                    exact,
                    aff,
                    unknown,
                    fail
                );
            }
            Err(e) => println!("{cache}: LOAD ERROR {e}"),
        }
    }
}
