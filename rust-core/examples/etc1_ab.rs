//! A/B verification for encode_etc1 rewrites: deterministic test images ->
//! FNV-1a hash of the encoded stream + roundtrip mean abs error (quality).
//! A correct optimization must produce IDENTICAL hashes vs the old encoder.
//! Run: cargo run --release --example etc1_ab
use imgtxd_core::codec::etc1::{decode_etc1, encode_etc1};

fn lcg_image(w: usize, h: usize, seed: u64, mode: u32) -> Vec<u32> {
    let mut s = seed;
    let mut px = Vec::with_capacity(w * h);
    for _ in 0..w * h {
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let r = (s >> 33) as u32 & 0xFF;
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let g = (s >> 33) as u32 & 0xFF;
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let b = (s >> 33) as u32 & 0xFF;
        let p = match mode {
            0 => 0xFF00_0000 | (r << 16) | (g << 8) | b, // full noise
            1 => 0xFF00_0000 | ((r & 0xF0) << 16) | ((g & 0xF0) << 8) | (b & 0xF0), // quantized
            _ => 0xFF00_0000 | (r << 16) | (r << 8) | r, // grayscale
        };
        px.push(p);
    }
    px
}

fn gradient(w: usize, h: usize) -> Vec<u32> {
    (0..w * h)
        .map(|i| {
            let x = (i % w) as u32;
            let y = (i / w) as u32;
            let (wf, hf) = (w.max(1) as u32, h.max(1) as u32);
            0xFF00_0000u32 | ((x * 255 / wf) << 16) | ((y * 255 / hf) << 8) | ((x + y) % 256)
        })
        .collect()
}

fn hash(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

fn quality(src: &[u32], enc: &[u8], w: usize, h: usize) -> f64 {
    let dec = decode_etc1(enc, w, h);
    let mut total = 0u64;
    for (a, b) in src.iter().zip(dec.iter()) {
        for shift in [16, 8, 0] {
            total += (((a >> shift) & 0xFF) as i64 - ((b >> shift) & 0xFF) as i64).unsigned_abs();
        }
    }
    total as f64 / (src.len() * 3) as f64
}

fn main() {
    let cases: Vec<(&str, Vec<u32>, usize, usize)> = vec![
        ("gradient512", gradient(512, 512), 512, 512),
        ("gradient1024", gradient(1024, 1024), 1024, 1024),
        ("noise256", lcg_image(256, 256, 0xDEAD_BEEF, 0), 256, 256),
        ("quant192", lcg_image(192, 192, 0x1234_5678, 1), 192, 192),
        ("gray160", lcg_image(160, 160, 0xCAFE_BABE, 2), 160, 160),
        ("solid", vec![0xFF35_689B; 64 * 64], 64, 64),
        ("odd5x3", gradient(5, 3), 5, 3),
        ("odd7x9", lcg_image(7, 9, 42, 0), 7, 9),
    ];
    println!("=== encode_etc1 A/B checksums ===");
    for (name, px, w, h) in &cases {
        let enc = encode_etc1(px, *w, *h);
        let q = quality(px, &enc, *w, *h);
        println!(
            "{name:<14} bytes={:<8} fnv1a={:016x} mean_abs_err={:.3}",
            enc.len(),
            hash(&enc),
            q
        );
    }
}
