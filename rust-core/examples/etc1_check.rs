//! Decodes 'coronastar' from the real txd cache via the Rust ETC1 codec and
//! prints probe pixels (center should be ~white, corners ~black).
use imgtxd_core::texdb::decode_pixels_at;
fn main() {
    let arg = std::env::args().nth(1).expect("cache dir");
    let dir = std::path::Path::new(&arg);
    let text = std::fs::read_to_string(dir.join("txd.txt")).unwrap();
    let idx = text
        .lines()
        .filter(|l| l.trim_start().starts_with('"'))
        .position(|l| l.contains("\"coronastar\""))
        .expect("find coronastar");
    let (w, h, enc, px) = decode_pixels_at(dir, "txd", idx).expect("decode");
    println!("size {w}x{h} enc {enc:#x}");
    println!("corner: {:08x}", px[0]);
    println!("center: {:08x}", px[(h / 2) * w + w / 2]);
    let mean: u64 = px
        .iter()
        .map(|p| (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) as u64)
        .sum();
    println!("mean brightness: {}", mean / (px.len() as u64 * 3));
}
