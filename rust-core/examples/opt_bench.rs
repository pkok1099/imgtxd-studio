//! Optimization A/B benchmark (self-contained, deterministic).
//! Run: cargo run --release --example opt_bench
use imgtxd_core::codec::dxt::{decode_dxt1, decode_dxt5, encode_dxt1, encode_dxt5};
use imgtxd_core::codec::etc1::{decode_etc1, encode_etc1};
use imgtxd_core::img::*;
use imgtxd_core::mipmap::{resample, MipFilter, PixelImage};
use imgtxd_core::texdb::*;
use imgtxd_core::txd;
use std::path::{Path, PathBuf};
use std::time::Instant;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn bench<F: FnMut()>(name: &str, iters: usize, mut f: F) {
    // warmup
    f();
    let t = Instant::now();
    for _ in 0..iters {
        f();
    }
    println!(
        "{name:<38} {:>9.2} ms/iter ({} it)",
        ms(t) / iters as f64,
        iters
    );
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

fn dxt1_1024() -> Vec<u8> {
    let px = gradient(1024, 1024);
    encode_dxt1(&px, 1024, 1024)
}

fn dxt5_512() -> Vec<u8> {
    let px = gradient(512, 512);
    encode_dxt5(&px, 512, 512)
}

fn synthetic_cache(dir: &Path, n: usize) -> PathBuf {
    std::fs::create_dir_all(dir).unwrap();
    let mut listings = Vec::new();
    let mut lines = Vec::new();
    for i in 0..n {
        let name = format!("tex_{i:05}");
        let level0 = vec![i as u8; 512 * 512 / 2]; // DXT1 512x512
        let l = build_listing(&name, TexEncoding::Dxt1, 512, 512, &[&level0], false).unwrap();
        lines.push(format!("\"{name}\" width=512 height=512"));
        listings.push(l);
    }
    let refs: Vec<&[u8]> = listings.iter().map(|l| &l[..]).collect();
    let (dat, toc) = rebuild_dat_toc(&refs.iter().map(|r| Some(*r)).collect::<Vec<_>>());
    std::fs::write(
        dir.join("bench.txt"),
        format!("cat=0 name=Default\n{}\n", lines.join("\n")),
    )
    .unwrap();
    std::fs::write(dir.join("bench.etc.dat"), &dat).unwrap();
    std::fs::write(dir.join("bench.etc.toc"), &toc).unwrap();
    dir.to_path_buf()
}

fn synthetic_img(dir: &Path) -> PathBuf {
    std::fs::create_dir_all(dir).unwrap();
    let img = dir.join("bench.img");
    let mut arc = ImgArchive::create_empty(img.as_path(), ImgVersion::Ver2, None).unwrap();
    let src = dir.join("payload.bin");
    std::fs::write(&src, vec![0x5Au8; 8 * 1024 * 1024]).unwrap();
    for i in 0..16 {
        arc.add(&src, Some(&format!("f{i:02}.bin"))).unwrap();
    }
    img
}

fn synthetic_txd(dir: &Path) -> PathBuf {
    std::fs::create_dir_all(dir).unwrap();
    let mut textures = Vec::new();
    for i in 0..40 {
        let e = txd::TxdTexture {
            name: format!("t{i:03}"),
            width: 256,
            height: 256,
            depth: 32,
            raster_format: txd::FORMAT_8888,
            raw_pixels: vec![i as u8; 256 * 256 * 4],
            ..txd::TxdTexture::default()
        };
        textures.push(e);
    }
    let bytes = txd::build_to_bytes(&textures, txd::RW_VERSION_SA);
    let p = dir.join("bench.txd");
    std::fs::write(&p, &bytes).unwrap();
    p
}

fn main() {
    let tmp = std::env::temp_dir().join("imgtxd-optbench");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    println!("=== resample (1024x1024 -> 512x512) ===");
    let px = gradient(1024, 1024);
    let src = PixelImage::new(px.clone(), 1024, 1024);
    bench("nearest", 10, || {
        let _ = resample(&src, 512, 512, MipFilter::Nearest);
    });
    bench("box", 10, || {
        let _ = resample(&src, 512, 512, MipFilter::Box);
    });
    bench("bilinear", 10, || {
        let _ = resample(&src, 512, 512, MipFilter::Bilinear);
    });
    bench("cubic", 5, || {
        let _ = resample(&src, 512, 512, MipFilter::Cubic);
    });
    bench("gaussian", 5, || {
        let _ = resample(&src, 512, 512, MipFilter::Gaussian);
    });
    bench("lanczos4", 5, || {
        let _ = resample(&src, 512, 512, MipFilter::Lanczos4);
    });

    println!("=== dxt (1024x1024 decode / 512x512 decode) ===");
    let d1 = dxt1_1024();
    bench("decode_dxt1 1024", 10, || {
        let _ = decode_dxt1(&d1, 1024, 1024);
    });
    let px1024 = gradient(1024, 1024);
    bench("encode_dxt1 1024", 5, || {
        let _ = encode_dxt1(&px1024, 1024, 1024);
    });
    let d5 = dxt5_512();
    bench("decode_dxt5 512", 10, || {
        let _ = decode_dxt5(&d5, 512, 512);
    });
    let px512 = gradient(512, 512);
    bench("encode_dxt5 512", 5, || {
        let _ = encode_dxt5(&px512, 512, 512);
    });
    let e1 = encode_etc1(&px512, 512, 512);
    bench("decode_etc1 512", 10, || {
        let _ = decode_etc1(&e1, 512, 512);
    });
    bench("encode_etc1 512", 3, || {
        let _ = encode_etc1(&px512, 512, 512);
    });

    println!("=== texdb ===");
    let cdir = synthetic_cache(&tmp.join("cache"), 300);
    bench("describe_cache (300x512px, ~10MB dat)", 5, || {
        let _ = describe_cache(&cdir).unwrap();
    });
    bench("decode_pixels_at #150", 20, || {
        let _ = decode_pixels_at(&cdir, "bench", 150).unwrap();
    });

    println!("=== img archive ===");
    let ipath = synthetic_img(&tmp.join("img"));
    bench("open (16x8MB entries)", 10, || {
        let _ = ImgArchive::open(&ipath, None).unwrap();
    });
    bench("rebuild 128MB archive", 3, || {
        let mut arc = ImgArchive::open(&ipath, None).unwrap();
        arc.rebuild().unwrap();
    });

    println!("=== txd ===");
    let tpath = synthetic_txd(&tmp.join("txd"));
    let size = std::fs::metadata(&tpath).unwrap().len();
    println!("txd size: {} MB", size / 1024 / 1024);
    bench("parse whole (40x256px rgba)", 10, || {
        let _ = txd::parse_file(&tpath).unwrap();
    });
    bench("view: parse whole + decode (old path)", 10, || {
        let t = txd::parse_file(&tpath).unwrap();
        let _ = txd::decode_pixels(&t.textures[20]).unwrap();
    });
    bench("view: decode_entry_pixels_at (new)", 50, || {
        let _ = txd::decode_entry_pixels_at(&tpath, 20).unwrap();
    });

    let _ = std::fs::remove_dir_all(&tmp);
    println!("done");
}
