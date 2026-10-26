use imgtxd_core::texdb::*;
use std::path::PathBuf;

fn main() {
    let dir = PathBuf::from(std::env::args().nth(1).expect("dir"));
    let name = std::env::args().nth(2).expect("name");
    let cache = match TexdbCache::load(&dir, &name) {
        Ok(c) => c,
        Err(e) => {
            println!("LOAD FAIL: {e}");
            return;
        }
    };
    let mut problems = 0;
    let mut prev_end = 0usize;
    for (i, e) in cache.manifest.iter().enumerate() {
        let Some(listing) = &cache.listings[i] else {
            if e.affiliate.is_none() {
                println!("[{i}] {} NOT AFFILIATE but no listing", e.name);
                problems += 1;
            }
            continue;
        };
        if listing.hash != hash_name(&e.name) {
            println!(
                "[{i}] {} HASH mismatch: listing={:04x} name={:04x}",
                e.name,
                listing.hash,
                hash_name(&e.name)
            );
            problems += 1;
        }
        if listing.width != e.width || listing.height != e.height {
            println!(
                "[{i}] {} DIMS mismatch: listing={}x{} manifest={}x{}",
                e.name, listing.width, listing.height, e.width, e.height
            );
            problems += 1;
        }
        if listing.span_start != prev_end {
            println!(
                "[{i}] {} SPAN gap/order: start={} expected={}",
                e.name, listing.span_start, prev_end
            );
            problems += 1;
        }
        prev_end = listing.span_end;
        // csize field: bytes [8..12] of the raw span
        if let Some(raw) = cache.raw_listing(i) {
            let csize = u32::from_le_bytes([raw[8], raw[9], raw[10], raw[11]]) as usize;
            if csize != raw.len() - 12 {
                println!(
                    "[{i}] {} CSIZE mismatch: field={} span-12={}",
                    e.name,
                    csize,
                    raw.len() - 12
                );
                problems += 1;
            }
        }
    }
    // affiliate targets exist
    for (i, e) in cache.manifest.iter().enumerate() {
        if let Some(t) = &e.affiliate {
            if !cache
                .manifest
                .iter()
                .any(|x| x.name.eq_ignore_ascii_case(t))
            {
                println!("[{i}] {} AFFILIATE target missing: {}", e.name, t);
                problems += 1;
            }
        }
    }
    println!(
        "STRICT: {} problems across {} entries (variant {})",
        problems,
        cache.manifest.len(),
        cache.variant
    );
}
