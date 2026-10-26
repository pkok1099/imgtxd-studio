//! Large texture benchmark (4096 and 8192) for DXT1, DXT5, ETC1, and Mipmaps.
use imgtxd_core::codec::dxt::{decode_dxt1, decode_dxt5, encode_dxt1, encode_dxt5};
use imgtxd_core::codec::etc1::{decode_etc1, encode_etc1};
use imgtxd_core::mipmap::{generate_chain, MipFilter, PixelImage};
use std::time::Instant;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn bench<F: FnMut()>(name: &str, iters: usize, mut f: F) {
    // 1x warmup
    f();
    let t = Instant::now();
    for _ in 0..iters {
        f();
    }
    println!(
        "{name:<42} {:>9.2} ms/iter ({} it)",
        ms(t) / iters as f64,
        iters
    );
}

fn make_synthetic_pixels(w: usize, h: usize) -> Vec<u32> {
    let mut pixels = vec![0u32; w * h];
    for y in 0..h {
        let row_off = y * w;
        for x in 0..w {
            let r = ((x * 255) / w) as u32;
            let g = ((y * 255) / h) as u32;
            let b = (((x + y) * 255) / (w + h)) as u32;
            let a = 255u32;
            pixels[row_off + x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
    pixels
}

fn main() {
    println!("=== Large Texture Benchmark (4096 and 8192) ===");
    for &size in &[4096usize, 8192usize] {
        let ram_mb = (size * size * 4) / (1024 * 1024);
        println!("\n--- Testing {size}x{size} (~{ram_mb} MB raw ARGB) ---");

        println!("Generating synthetic {size}x{size} buffer...");
        let t0 = Instant::now();
        let pixels = make_synthetic_pixels(size, size);
        println!("Buffer generated in {:.2} ms", ms(t0));

        let iters = if size >= 8192 { 2 } else { 3 };

        // 1. DXT1
        bench(&format!("encode_dxt1 {size}x{size}"), iters, || {
            let enc = encode_dxt1(&pixels, size, size);
            std::hint::black_box(enc);
        });

        let dxt1_data = encode_dxt1(&pixels, size, size);
        bench(&format!("decode_dxt1 {size}x{size}"), iters, || {
            let dec = decode_dxt1(&dxt1_data, size, size);
            std::hint::black_box(dec);
        });

        // 2. DXT5
        bench(&format!("encode_dxt5 {size}x{size}"), iters, || {
            let enc = encode_dxt5(&pixels, size, size);
            std::hint::black_box(enc);
        });

        let dxt5_data = encode_dxt5(&pixels, size, size);
        bench(&format!("decode_dxt5 {size}x{size}"), iters, || {
            let dec = decode_dxt5(&dxt5_data, size, size);
            std::hint::black_box(dec);
        });

        // 3. ETC1 (ETC1 encode is heavier, test 4096 only or 1 it for 8192)
        if size <= 4096 {
            bench(&format!("encode_etc1 {size}x{size}"), 1, || {
                let enc = encode_etc1(&pixels, size, size);
                std::hint::black_box(enc);
            });
            let etc1_data = encode_etc1(&pixels, size, size);
            bench(&format!("decode_etc1 {size}x{size}"), iters, || {
                let dec = decode_etc1(&etc1_data, size, size);
                std::hint::black_box(dec);
            });
        }

        // 4. Mipmap chain (Box filter down to 1x1)
        let img = PixelImage::new(pixels, size, size);
        bench(
            &format!("mip chain Box {size}x{size} -> 1x1"),
            iters,
            || {
                let chain = generate_chain(&img, MipFilter::Box);
                std::hint::black_box(chain);
            },
        );
    }
    println!("\nLarge texture benchmark finished!");
}
