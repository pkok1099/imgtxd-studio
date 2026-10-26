//! Prints texture names extracted from a DFF (chunk-walk).
use imgtxd_core::texdb::dff_texture_names;
fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: dff_names <file.dff>");
    let bytes = std::fs::read(&path).expect("read dff");
    let names = dff_texture_names(&bytes);
    println!("{} textures", names.len());
    for n in &names {
        println!("{n}");
    }
}
