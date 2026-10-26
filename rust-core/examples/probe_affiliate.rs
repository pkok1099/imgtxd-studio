use imgtxd_core::texdb::*;
use std::path::Path;
fn main() {
    let arg = std::env::args().nth(1).expect("dir");
    let dir = Path::new(&arg);
    let c = TexdbCache::load(dir, "txd").unwrap();
    let aff: Vec<_> = c
        .manifest
        .iter()
        .filter(|m| m.affiliate.is_some())
        .collect();
    println!(
        "manifest: {} entries, affiliate parsed: {}",
        c.manifest.len(),
        aff.len()
    );
    for m in aff.iter().take(3) {
        println!("  {:?} -> affiliate {:?}", m.name, m.affiliate);
    }
    if let Some(m) = c.manifest.iter().find(|m| m.name == "smokeII_3") {
        println!(
            "smokeII_3: affiliate={:?} w={} h={} line={:?}",
            m.affiliate, m.width, m.height, m.line
        );
    }
    let none_listings = c.listings.iter().filter(|l| l.is_none()).count();
    println!("listings None (slots): {none_listings}");
}
