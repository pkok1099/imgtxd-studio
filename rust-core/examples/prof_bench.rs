//! Profiling harness for REMAINING hotspots (post-r3): decode paths, RLE,
//! CRC32, pixel->byte conversions, PNG-import pipeline.
//! Deterministic data (xorshift32) so sandbox and VM results are comparable.
//! Run: cargo run --release --example prof_bench
use imgtxd_core::codec::dxt::{decode_dxt1, decode_dxt5, encode_dxt1, encode_dxt5};
use imgtxd_core::codec::etc1::{decode_etc1, encode_etc1};
use imgtxd_core::mipmap::{generate_chain, MipFilter, PixelImage};
use imgtxd_core::texdb::{crc32_ieee, decode_png_argb, rle_decode, rle_encode};
use imgtxd_core::txd::decode_dxt3;
use std::io::Write as _;
use std::time::Instant;

struct Rng(u32);
impl Rng {
    fn next(&mut self) -> u32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        x
    }
}

fn fnv(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x1000_0000_01b3);
    }
    h
}

fn fnv_u32(data: &[u32]) -> u64 {
    let bytes: Vec<u8> = data.iter().flat_map(|v| v.to_le_bytes()).collect();
    fnv(&bytes)
}

/// Noisy ARGB texture (realistic worst case for encoders/decoders).
fn noisy(w: usize, h: usize, seed: u32) -> Vec<u32> {
    let mut r = Rng(seed);
    (0..w * h)
        .map(|_| 0xFF00_0000u32 | (r.next() & 0x00FF_FFFF))
        .collect()
}

fn noisy_bytes(len: usize, seed: u32) -> Vec<u8> {
    let mut r = Rng(seed);
    (0..len).map(|_| r.next() as u8).collect()
}

/// Run-heavy byte data: each 8-byte unit repeats 4..16 times (typical RLE win).
fn run_heavy(len: usize, seg: usize, seed: u32) -> Vec<u8> {
    let mut r = Rng(seed);
    let mut out = Vec::with_capacity(len + 128 * seg);
    while out.len() < len {
        let unit: Vec<u8> = (0..seg).map(|_| r.next() as u8).collect();
        let run = 4 + (r.next() % 12) as usize;
        for _ in 0..run {
            out.extend_from_slice(&unit);
        }
    }
    out.truncate(len);
    out
}

fn bench<F: FnMut() -> u64>(name: &str, min_ms: f64, max_it: usize, mut f: F) -> f64 {
    f(); // warmup (result discarded)
    let mut it = 0usize;
    let t = Instant::now();
    loop {
        let _ = f();
        it += 1;
        if t.elapsed().as_secs_f64() * 1000.0 >= min_ms || it >= max_it {
            break;
        }
    }
    let per = t.elapsed().as_secs_f64() * 1000.0 / it as f64;
    println!("{name:<46} {:>9.3} ms  ({it} it)", per);
    per
}

fn main() {
    println!("=== prof_bench: remaining hotspots (post-r3) ===");
    let mut check: u64 = 0;

    // ---------- A. single-texture decode (view path) ----------
    println!("--- A. decode single texture ---");
    for (sz, tag) in [(512usize, "512"), (1024, "1024")] {
        let px = noisy(sz, sz, 0xA1 + sz as u32);
        let d1 = encode_dxt1(&px, sz, sz);
        let d5 = encode_dxt5(&px, sz, sz);
        let et = encode_etc1(&px, sz, sz);
        let d3: Vec<u8> = {
            // DXT3 block = 8 alpha bytes + DXT1 block
            let b1 = encode_dxt1(&px, sz, sz);
            let mut out = Vec::with_capacity(b1.len() * 2);
            for blk in b1.chunks_exact(8) {
                let mut r = Rng(0xD3 ^ (blk[0] as u32));
                for i in 0..8 {
                    out.push(((r.next() & 0xFF) as u8) ^ (i as u8 * 17));
                }
                out.extend_from_slice(blk);
            }
            out
        };
        check ^= fnv_u32(&decode_dxt1(&d1, sz, sz));
        check ^= fnv_u32(&decode_dxt5(&d5, sz, sz));
        check ^= fnv_u32(&decode_dxt3(&d3, sz, sz));
        check ^= fnv_u32(&decode_etc1(&et, sz, sz));
        bench(&format!("decode_dxt1  {tag}x{tag}"), 120.0, 200, || {
            fnv_u32(&decode_dxt1(&d1, sz, sz))
        });
        bench(&format!("decode_dxt3  {tag}x{tag}"), 120.0, 200, || {
            fnv_u32(&decode_dxt3(&d3, sz, sz))
        });
        bench(&format!("decode_dxt5  {tag}x{tag}"), 120.0, 200, || {
            fnv_u32(&decode_dxt5(&d5, sz, sz))
        });
        bench(&format!("decode_etc1  {tag}x{tag}"), 120.0, 200, || {
            fnv_u32(&decode_etc1(&et, sz, sz))
        });
    }

    // ---------- B. batch decode 64 x 256x256 (gallery / export-all) ----------
    println!("--- B. batch decode 64 x 256x256 (serial) ---");
    for (name, enc) in [("dxt1", 0u8), ("dxt5", 1), ("etc1", 2)] {
        let px = noisy(256, 256, 0xB2);
        let data: Vec<Vec<u8>> = (0..64u32)
            .map(|i| {
                let mut v = match enc {
                    0 => encode_dxt1(&px, 256, 256),
                    1 => encode_dxt5(&px, 256, 256),
                    _ => encode_etc1(&px, 256, 256),
                };
                v[0] ^= i as u8; // per-texture variation
                v
            })
            .collect();
        let f = match enc {
            0 => decode_dxt1 as fn(&[u8], usize, usize) -> Vec<u32>,
            1 => decode_dxt5 as fn(&[u8], usize, usize) -> Vec<u32>,
            _ => decode_etc1 as fn(&[u8], usize, usize) -> Vec<u32>,
        };
        bench(&format!("batch64 decode_{name} 256x256"), 120.0, 20, || {
            let mut acc = 0u64;
            for d in &data {
                acc ^= fnv_u32(&f(d, 256, 256));
            }
            acc
        });
    }

    // ---------- C. RLE ----------
    println!("--- C. RLE (seg 8, 2 MiB raw) ---");
    let seg = 8;
    let raw_noise = noisy_bytes(2 * 1024 * 1024, 0xC3);
    let raw_runs = run_heavy(2 * 1024 * 1024, seg, 0xC4);
    let enc_noise = rle_encode(&raw_noise, seg, 0xFF);
    let enc_runs = rle_encode(&raw_runs, seg, 0xFF);
    println!(
        "  (enc_noise {} bytes, enc_runs {} bytes from 2097152)",
        enc_noise.len(),
        enc_runs.len()
    );
    // synthetic indicator-heavy stream: (0xFF, 255, unit) * N
    let unit = [0x5Au8; 8];
    let mut enc_ind = Vec::new();
    for _ in 0..1000 {
        enc_ind.push(0xFF);
        enc_ind.push(255);
        enc_ind.extend_from_slice(&unit);
    }
    check ^= fnv(&rle_decode(&enc_noise, seg, 0xFF).unwrap_or_default());
    check ^= fnv(&rle_decode(&enc_runs, seg, 0xFF).unwrap_or_default());
    bench("rle_encode noisy 2MiB (worst case)", 120.0, 50, || {
        fnv(&rle_encode(&raw_noise, seg, 0xFF))
    });
    bench("rle_encode run-heavy 2MiB", 120.0, 50, || {
        fnv(&rle_encode(&raw_runs, seg, 0xFF))
    });
    bench("rle_decode passthrough ~2MiB", 120.0, 50, || {
        fnv(&rle_decode(&enc_noise, seg, 0xFF).unwrap_or_default())
    });
    bench("rle_decode run-heavy -> 2MiB", 120.0, 50, || {
        fnv(&rle_decode(&enc_runs, seg, 0xFF).unwrap_or_default())
    });
    bench("rle_decode indicator-heavy -> 2.0MiB", 120.0, 50, || {
        fnv(&rle_decode(&enc_ind, seg, 0xFF).unwrap_or_default())
    });

    // ---------- D. CRC32 (informational; game never reads) ----------
    println!("--- D. CRC32 ---");
    bench("crc32_ieee 2MiB", 120.0, 50, || crc32_ieee(&raw_noise) as u64);

    // ---------- E. pixel -> byte conversions (import helpers) ----------
    println!("--- E. pixel-to-byte conversions 512x512 ---");
    let px512 = noisy(512, 512, 0xE5);
    let rgba32 = |px: &[u32]| -> u64 {
        let mut out = Vec::with_capacity(px.len() * 4);
        for &p in px {
            out.push(((p >> 16) & 0xFF) as u8);
            out.push(((p >> 8) & 0xFF) as u8);
            out.push((p & 0xFF) as u8);
            out.push((p >> 24) as u8);
        }
        fnv(&out)
    };
    let rgb565 = |px: &[u32]| -> u64 {
        let mut out = Vec::with_capacity(px.len() * 2);
        for &p in px {
            let v = ((((p >> 16) & 0xFF) >> 3) << 11)
                | ((((p >> 8) & 0xFF) >> 2) << 5)
                | ((p & 0xFF) >> 3);
            out.extend_from_slice(&(v as u16).to_le_bytes());
        }
        fnv(&out)
    };
    bench("rgba32_bytes-like 512x512", 120.0, 200, || rgba32(&px512));
    bench("rgb565_bytes-like 512x512", 120.0, 200, || rgb565(&px512));

    // ---------- F. PNG import pipeline (no disk) ----------
    println!("--- F. PNG import pipeline (512x512 RGBA, mipmaps, DXT5) ---");
    let png_bytes = make_png(512, 512, 0xF6);
    bench("decode_png_argb 512x512", 120.0, 100, || {
        let (px, w, h) = decode_png_argb(&png_bytes).expect("png");
        fnv_u32(&px) ^ (w as u64) ^ ((h as u64) << 32)
    });
    bench("generate_chain (bilinear) 512x512", 120.0, 100, || {
        let (px, w, h) = decode_png_argb(&png_bytes).expect("png");
        let src = PixelImage::new(px, w, h);
        let chain = generate_chain(&src, MipFilter::Bilinear);
        chain.iter().map(|l| l.pixels.len() as u64).sum::<u64>()
    });
    bench("import total: png+chain+encode_dxt5", 200.0, 50, || {
        let (px, w, h) = decode_png_argb(&png_bytes).expect("png");
        let src = PixelImage::new(px, w, h);
        let chain = generate_chain(&src, MipFilter::Bilinear);
        let mut acc = 0u64;
        for lvl in &chain {
            acc ^= fnv(&encode_dxt5(&lvl.pixels, lvl.width, lvl.height));
        }
        acc
    });

    println!("\nCHECKSUM fnv_xor = {check:016x} (must match across machines)");
    let _ = std::io::stdout().flush();
}

fn make_png(w: u32, h: u32, seed: u32) -> Vec<u8> {
    let mut r = Rng(seed);
    let mut data = Vec::with_capacity((w * h * 4) as usize);
    for _ in 0..w * h {
        data.push(r.next() as u8);
        data.push(r.next() as u8);
        data.push(r.next() as u8);
        data.push((r.next() & 0xFF) as u8); // varied alpha
    }
    let mut out = Vec::new();
    {
        let mut enc = png::Encoder::new(&mut out, w, h);
        enc.set_color(png::ColorType::Rgba);
        enc.set_depth(png::BitDepth::Eight);
        let mut writer = enc.write_header().expect("hdr");
        writer.write_image_data(&data).expect("px");
    }
    out
}
