//! Runs the new cache ops against a COPY of the real txd cache: renames a
//! texture, adds an alias, repacks to ETC and back, then deletes — verifying
//! the cache still parses after every step.
use imgtxd_core::texdb::*;
use std::path::Path;
fn names(dir: &Path) -> Vec<String> {
    TexdbCache::load(dir, "txd")
        .unwrap()
        .manifest
        .iter()
        .map(|m| m.name.clone())
        .collect()
}
fn main() {
    let arg = std::env::args().nth(1).expect("dir");
    let dir = Path::new(&arg);
    let before = names(dir);
    println!("entries before: {}", before.len());
    println!("rename: {}", rename_texture(dir, "txd", 0, &format!("{}_renamed", before[0])).unwrap());
    println!("alias: {}", add_alias(dir, "txd", 1, "zz_test_alias").unwrap());
    println!("count after alias: {}", names(dir).len());
    println!("repack ETC: {}", repack_cache(dir, "txd", 1).unwrap());
    let c = TexdbCache::load(dir, "txd").unwrap();
    println!("etc entries now: {}", c.listings.iter().flatten().filter(|l| l.encoding == TexEncoding::Etc1).count());
    println!("repack DXT: {}", repack_cache(dir, "txd", 0).unwrap());
    println!("delete: {}", delete_textures(dir, "txd", &[0]).unwrap());
    println!("entries after: {}", names(dir).len());
    println!("decode check: {}", TexdbCache::load(dir, "txd").unwrap().decode_pixels(0).map(|p| p.len()).unwrap_or(0));
}
