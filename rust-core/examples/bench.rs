//! Performance harness for the hot user workflows. Run with:
//!   cargo run --release --example bench
//!
//! Measures (wall time, best of N):
//! 1. synthetic-cache build + load (the .dat parse + manifest hash checks)
//! 2. batch PNG import (decode → mip chain → encode → listing) — the app's
//!    heaviest operation
//! 3. per-entry pixel decode (the thumbnail/browse path)
//! 4. single large-texture encode per codec (parallelism sanity check)

use imgtxd_core::codec;
use imgtxd_core::mipmap::{MipFilter, PixelImage};
use imgtxd_core::texdb::{self, ImportFormat, ImportOptions};
use std::time::Instant;

fn make_png(w: u32, h: u32, seed: u8, alpha: bool) -> Vec<u8> {
    let mut buf = Vec::new();
    {
        let mut enc = png::Encoder::new(&mut buf, w, h);
        enc.set_color(if alpha {
            png::ColorType::Rgba
        } else {
            png::ColorType::Rgb
        });
        enc.set_depth(png::BitDepth::Eight);
        let mut writer = enc.write_header().unwrap();
        let bpp = if alpha { 4 } else { 3 };
        let mut data = vec![0u8; (w * h) as usize * bpp];
        let mut s = seed as u32 | 1;
        for c in data.chunks_mut(bpp) {
            s = s.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            for (i, b) in c.iter_mut().enumerate() {
                *b = ((s >> (i * 8)) & 0xFF) as u8;
            }
        }
        writer.write_image_data(&data).unwrap();
    }
    buf
}

fn bench<T>(name: &str, runs: u32, f: impl Fn() -> T) -> T {
    let mut best = f64::INFINITY;
    let mut out = None;
    for _ in 0..runs {
        let t = Instant::now();
        let r = f();
        let e = t.elapsed().as_secs_f64();
        if e < best {
            best = e;
            out = Some(r);
        }
    }
    println!("{name:<52} {best:>9.3} s");
    out.unwrap()
}

fn main() {
    let tmp = std::env::temp_dir().join("imgtxd_bench");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    // ---- 1. synthetic cache: 1024 entries 256x256 DXT5 --------------------
    let n_entries = 1024;
    let mut listings = Vec::with_capacity(n_entries);
    let lines: Vec<String> = (0..n_entries)
        .map(|i| {
            let px = vec![((i * 7) & 0xFF) as u32 * 0x010101; 256 * 256];
            let enc = codec::dxt::encode_dxt5(&px, 256, 256);
            listings.push(
                texdb::build_listing(
                    &format!("tex{i:04}"),
                    texdb::TexEncoding::Dxt5,
                    256,
                    256,
                    &[&enc],
                    false,
                )
                .unwrap(),
            );
            format!("\"tex{i:04}\" width=256 height=256")
        })
        .collect();
    // Write a real cache dir via import machinery on a fresh manifest.
    let txt = tmp.join("bench.txt");
    std::fs::write(&txt, lines.join("\n") + "\n").unwrap();
    // build .dat/.toc by writing listings sequentially
    let mut dat: Vec<u8> = Vec::new();
    let mut toc: Vec<i32> = Vec::with_capacity(n_entries);
    for l in &listings {
        toc.push(dat.len() as i32);
        dat.extend_from_slice(l);
    }
    std::fs::write(tmp.join("bench.0.dat"), &dat).unwrap();
    let mut toc_bytes = Vec::with_capacity(4 + n_entries * 4);
    toc_bytes.extend_from_slice(&(dat.len() as u32).to_le_bytes());
    for o in &toc {
        toc_bytes.extend_from_slice(&o.to_le_bytes());
    }
    std::fs::write(tmp.join("bench.0.toc"), &toc_bytes).unwrap();
    // A second variant family: every import now also exercises
    // sync_after_write's re-encode of the changed entries (the real-world
    // multi-family caches: etc + dxt + ...).
    std::fs::write(tmp.join("bench.dxt.dat"), &dat).unwrap();
    std::fs::write(tmp.join("bench.dxt.toc"), &toc_bytes).unwrap();
    println!(
        "cache: {} entries, {} MB dat",
        n_entries,
        dat.len() / 1_048_576
    );

    bench("cache load (1024 x 256x256 DXT5)", 5, || {
        texdb::TexdbCache::load(&tmp, "bench").unwrap()
    });

    // ---- 2. batch import ----------------------------------------------------
    // 64 PNGs: mixed 512x512 / 256x256 / 128x128, half with alpha.
    let mut images: Vec<(String, Vec<u8>)> = Vec::new();
    let sizes = [(512u32, 512u32), (256, 256), (128, 128)];
    for i in 0..64 {
        let (w, h) = sizes[i % 3];
        images.push((
            format!("import_{i:03}"),
            make_png(w, h, (i * 13) as u8, i % 2 == 0),
        ));
    }
    let opts = ImportOptions {
        format: ImportFormat::CompressedAlpha,
        generate_mipmaps: true,
        mip_filter: MipFilter::Box as i32,
        use_rle: true,
        reduce_to: 0,
        correct_caps: false,
        conflict: texdb::ConflictPolicy::Overwrite,
        sibling_model: None,
        hash_check: true,
        camnorm: false,
        defragment: false,
    };
    bench("batch import 64 PNGs -> DXT1/5 + mips + RLE", 3, || {
        texdb::import_textures(&tmp, "bench", &images, &opts).unwrap()
    });

    // ---- 2b. small-texture batch (below every internal band threshold) -----
    // 64x64 = 256 blocks: DXT/ETC1 encode and resample all run inline serial,
    // so per-image parallelism is the ONLY parallelism — this is the common
    // game texture size on the 8-core phones this app targets.
    let mut small: Vec<(String, Vec<u8>)> = Vec::new();
    for i in 0..96 {
        small.push((
            format!("small_{i:03}"),
            make_png(64, 64, (i * 29) as u8, i % 3 == 0),
        ));
    }
    bench("batch import 96 small PNGs (64x64)", 5, || {
        texdb::import_textures(&tmp, "bench", &small, &opts).unwrap()
    });

    // ---- 2c. pure per-image compute: serial vs parallel ---------------------
    // Isolates the prepare phase (decode+mips+encode) from the serial cache
    // load/write tail. This is what scales with CPU count on-device.
    bench("prepare 96 small images SERIALLY", 5, || {
        let mut n = 0usize;
        for (_, bytes) in &small {
            if let Some((pixels, w, h)) = texdb::decode_png_argb(bytes) {
                let np = pixels.len();
                let src = PixelImage::new(pixels, w, h);
                let mut chain = vec![src.clone()];
                chain.extend(imgtxd_core::mipmap::generate_chain(
                    &src,
                    MipFilter::from_i32(opts.mip_filter),
                ));
                let mut wasted = 0usize;
                for l in &chain {
                    wasted += codec::dxt::encode_dxt5(&l.pixels, l.width, l.height).len();
                }
                n += wasted ^ (np & 0xFF);
            }
        }
        n
    });
    bench("prepare 96 small images PARALLEL", 5, || {
        let workers = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(2);
        let mut total = 0usize;
        for batch in small.chunks(workers) {
            let sum: usize = std::thread::scope(|s| {
                let handles: Vec<_> = batch
                    .iter()
                    .map(|(_, bytes)| {
                        s.spawn(|| {
                            let Some((pixels, w, h)) = texdb::decode_png_argb(bytes) else {
                                return 0usize;
                            };
                            let np = pixels.len();
                            let src = PixelImage::new(pixels, w, h);
                            let mut chain = vec![src.clone()];
                            chain.extend(imgtxd_core::mipmap::generate_chain(
                                &src,
                                MipFilter::from_i32(opts.mip_filter),
                            ));
                            let mut wasted = 0usize;
                            for l in &chain {
                                wasted +=
                                    codec::dxt::encode_dxt5(&l.pixels, l.width, l.height).len();
                            }
                            wasted ^ (np & 0xFF)
                        })
                    })
                    .collect();
                handles.into_iter().map(|h| h.join().unwrap_or(0)).sum()
            });
            total += sum;
        }
        total
    });

    // ---- 3. thumbnail decode ------------------------------------------------
    let cache = texdb::TexdbCache::load(&tmp, "bench").unwrap();
    bench("decode 256 entries to pixels (thumb path)", 3, || {
        (0..256)
            .map(|i| cache.decode_pixels(i).map(|px| px.len()).unwrap_or(0))
            .sum::<usize>()
    });

    // ---- 4. single large encode per codec -----------------------------------
    let big = vec![0x3F7F_3F7Fu32; 1024 * 1024];
    bench("encode_dxt1 1024x1024", 3, || {
        codec::dxt::encode_dxt1(&big, 1024, 1024).len()
    });
    bench("encode_dxt5 1024x1024", 3, || {
        codec::dxt::encode_dxt5(&big, 1024, 1024).len()
    });
    bench("encode_etc1 1024x1024", 3, || {
        codec::etc1::encode_etc1(&big, 1024, 1024).len()
    });
    let img = PixelImage::new(big.clone(), 1024, 1024);
    bench("mip chain box 1024x1024 -> 1x1", 3, || {
        imgtxd_core::mipmap::generate_chain(&img, MipFilter::Box).len()
    });

    let _ = std::fs::remove_dir_all(&tmp);
}
