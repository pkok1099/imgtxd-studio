//! A/B verification round 2: resample (all filters) + encode_dxt1/dxt5
//! (+ encode_etc1 regression). Deterministic inputs -> FNV-1a hashes of
//! outputs. A correct optimization must produce IDENTICAL hashes.
//! Run: cargo run --release --example opt2_ab
use imgtxd_core::codec::dxt::{encode_dxt1, encode_dxt5};
use imgtxd_core::codec::etc1::encode_etc1;
use imgtxd_core::mipmap::{resample, MipFilter, PixelImage};

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
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let a = (s >> 33) as u32 & 0xFF;
        let p = match mode {
            0 => 0xFF00_0000 | (r << 16) | (g << 8) | b,
            1 => 0xFF00_0000 | ((r & 0xF0) << 16) | ((g & 0xF0) << 8) | (b & 0xF0),
            2 => 0xFF00_0000 | (r << 16) | (r << 8) | r,
            _ => (a << 24) | (r << 16) | (g << 8) | b, // alpha noise incl. <128
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

fn hash_px(px: &[u32]) -> u64 {
    // SAFETY: `px` is a valid `&[u32]` produced by `lcg_image`/`gradient`/
    // `mipmap::downscale_half`, so its backing memory is properly aligned
    // for u32 (and thus u8). `px.len() * 4` is bounded by the test image
    // sizes (≤ 1024×1024×4 = 4 MiB), well below usize::MAX even on 32-bit.
    // The cast `*const u8` preserves provenance; u8 has no alignment req.
    let byte_len = px.len().saturating_mul(4);
    hash(unsafe { std::slice::from_raw_parts(px.as_ptr() as *const u8, byte_len) })
}

const FILTERS: &[(&str, MipFilter)] = &[
    ("nearest", MipFilter::Nearest),
    ("box", MipFilter::Box),
    ("bilinear", MipFilter::Bilinear),
    ("cubic", MipFilter::Cubic),
    ("gaussian", MipFilter::Gaussian),
    ("lanczos4", MipFilter::Lanczos4),
];

fn main() {
    println!("=== resample A/B checksums ===");
    let sizes: Vec<(&str, usize, usize, usize, usize)> = vec![
        ("1024->512", 1024, 1024, 512, 512),
        ("odd1001x733->333x244", 1001, 733, 333, 244),
        ("up256->512", 256, 256, 512, 512),
        ("640x480->320x240", 640, 480, 320, 240),
        ("8->4", 8, 8, 4, 4),
        ("5x3->2x1", 5, 3, 2, 1),
    ];
    for (fname, filter) in FILTERS {
        for (sname, sw, sh, dw, dh) in &sizes {
            let px = lcg_image(*sw, *sh, 0xABCD_1234 + *sw as u64, 0);
            let src = PixelImage::new(px, *sw, *sh);
            let out = resample(&src, *dw, *dh, *filter);
            assert_eq!((out.width, out.height), (*dw, *dh));
            println!(
                "{fname:<9} {sname:<22} fnv1a={:016x}",
                hash_px(&out.pixels)
            );
        }
    }
    // gradient source (smooth) exercises different code paths than noise
    for (fname, filter) in FILTERS {
        let src = PixelImage::new(gradient(300, 200), 300, 200);
        let out = resample(&src, 150, 100, *filter);
        println!("{fname:<9} grad300x200->150x100    fnv1a={:016x}", hash_px(&out.pixels));
    }

    println!("=== encode_dxt A/B checksums ===");
    let dxt_cases: Vec<(&str, Vec<u32>, usize, usize)> = vec![
        ("grad1024", gradient(1024, 1024), 1024, 1024),
        ("noise512", lcg_image(512, 512, 0xDEAD_BEEF, 0), 512, 512),
        ("alpha512", lcg_image(512, 512, 0x0123_4567_89AB_CDEF, 3), 512, 512),
        ("quant192", lcg_image(192, 192, 0x1234_5678, 1), 192, 192),
        ("solid64", vec![0xFF35_689B; 64 * 64], 64, 64),
        ("odd5x3", gradient(5, 3), 5, 3),
        ("odd7x9", lcg_image(7, 9, 42, 3), 7, 9),
    ];
    for (name, px, w, h) in &dxt_cases {
        let e1 = encode_dxt1(px, *w, *h);
        let e5 = encode_dxt5(px, *w, *h);
        println!(
            "{name:<10} dxt1={:016x} dxt5={:016x}",
            hash(&e1),
            hash(&e5)
        );
    }

    println!("=== encode_etc1 regression ===");
    let etc_cases: Vec<(&str, Vec<u32>, usize, usize)> = vec![
        ("grad512", gradient(512, 512), 512, 512),
        ("noise256", lcg_image(256, 256, 0xDEAD_BEEF, 0), 256, 256),
    ];
    for (name, px, w, h) in &etc_cases {
        let e = encode_etc1(px, *w, *h);
        println!("{name:<10} fnv1a={:016x}", hash(&e));
    }
    println!("done");
}
