//! Phase profiler for the batch-import hot path. Run with:
//!   cargo run --release --example prof
//!
//! Splits `import_textures` into its constituent phases and times each on the
//! same workload as examples/bench.rs, so optimization targets are chosen on
//! measured ground truth instead of guesses. Uses only public API.

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

fn timed<T>(name: &str, runs: u32, f: impl Fn() -> T) -> T {
    let mut best = f64::INFINITY;
    let mut total = 0f64;
    let mut out = None;
    for _ in 0..runs {
        let t = Instant::now();
        let r = f();
        let e = t.elapsed().as_secs_f64();
        best = best.min(e);
        total += e;
        out = Some(r);
    }
    println!(
        "{name:<52} {best:>8.4} s  (mean {:.4})",
        total / runs as f64
    );
    out.unwrap()
}

/// prepare_import body via public API (decode → chain → encode → listing),
/// instrumented with per-sub-phase wall times accumulated into `st`.
fn prepare_one_timed(
    png_bytes: &[u8],
    name: &str,
    use_alpha_codec: bool,
    st: &std::sync::Mutex<SubTimes>,
) -> usize {
    let t = Instant::now();
    let Some((pixels, w, h)) = texdb::decode_png_argb(png_bytes) else {
        return 0;
    };
    let decode = t.elapsed().as_secs_f64();

    let t = Instant::now();
    let src = PixelImage::new(pixels, w, h);
    let mut chain = vec![src.clone()];
    chain.extend(imgtxd_core::mipmap::generate_chain(&src, MipFilter::Box));
    let chain_t = t.elapsed().as_secs_f64();

    let t = Instant::now();
    let mut encoded: Vec<Vec<u8>> = Vec::with_capacity(chain.len());
    for l in &chain {
        let enc = if use_alpha_codec {
            codec::dxt::encode_dxt5(&l.pixels, l.width, l.height)
        } else {
            codec::dxt::encode_dxt1(&l.pixels, l.width, l.height)
        };
        encoded.push(enc);
    }
    let encode_t = t.elapsed().as_secs_f64();

    let t = Instant::now();
    let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
    let listing = texdb::build_listing(
        name,
        texdb::TexEncoding::Dxt5,
        chain[0].width,
        chain[0].height,
        &refs,
        true,
    )
    .unwrap();
    let crc = texdb::crc32_ieee(png_bytes);
    let listing_t = t.elapsed().as_secs_f64();

    {
        let mut g = st.lock().unwrap();
        g.decode += decode;
        g.chain += chain_t;
        g.encode += encode_t;
        g.listing += listing_t + 0.0; // crc inside listing timing bucket
    }
    let _ = crc;
    listing.len() ^ encoded[0].len()
}

#[derive(Default)]
struct SubTimes {
    decode: f64,
    chain: f64,
    encode: f64,
    listing: f64,
}

fn print_sub(label: &str, st: &SubTimes, wall: f64) {
    println!(
        "  {label:<38} wall={wall:.4}  decode={:.4} chain={:.4} encode={:.4} listing+crc={:.4}",
        st.decode, st.chain, st.encode, st.listing
    );
}

fn stub() -> &'static std::sync::Mutex<SubTimes> {
    static S: std::sync::OnceLock<std::sync::Mutex<SubTimes>> = std::sync::OnceLock::new();
    S.get_or_init(|| std::sync::Mutex::new(SubTimes::default()))
}

fn main() {
    let tmp = std::env::temp_dir().join("imgtxd_prof");
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp).unwrap();

    // ---- same synthetic cache as bench ------------------------------------
    let n_entries = 1024;
    let listings: Vec<Vec<u8>> = (0..n_entries)
        .map(|i| {
            let px = vec![((i * 7) & 0xFF) as u32 * 0x010101; 256 * 256];
            let enc = codec::dxt::encode_dxt5(&px, 256, 256);
            texdb::build_listing(
                &format!("tex{i:04}"),
                texdb::TexEncoding::Dxt5,
                256,
                256,
                &[&enc],
                false,
            )
            .unwrap()
        })
        .collect();
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
    std::fs::write(tmp.join("bench.dxt.dat"), &dat).unwrap();
    std::fs::write(tmp.join("bench.dxt.toc"), &toc_bytes).unwrap();
    let lines: Vec<String> = (0..n_entries)
        .map(|i| format!("\"tex{i:04}\" width=256 height=256"))
        .collect();
    std::fs::write(tmp.join("bench.txt"), lines.join("\n") + "\n").unwrap();
    println!(
        "cache: {} entries, {:.0} MB dat",
        n_entries,
        dat.len() as f64 / 1_048_576.0
    );

    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2);
    println!("workers = {workers}");

    // ---- 64 mixed images, same distribution as bench -----------------------
    let mut images: Vec<(String, Vec<u8>)> = Vec::new();
    let sizes = [(512u32, 512u32), (256, 256), (128, 128)];
    let mut png_total = 0usize;
    for i in 0..64 {
        let (w, h) = sizes[i % 3];
        let png = make_png(w, h, (i * 13) as u8, i % 2 == 0);
        png_total += png.len();
        images.push((format!("import_{i:03}"), png));
    }
    println!("64 pngs total {:.1} MB", png_total as f64 / 1_048_576.0);

    // ---- phase A: cache load ------------------------------------------------
    let _cache = timed("A  cache load (TexdbCache::load)", 5, || {
        texdb::TexdbCache::load(&tmp, "bench").unwrap()
    });

    // ---- phase B: prepare 64 mixed, PARALLEL (workers) ----------------------
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
    let _ = &opts;
    let imgs = &images;
    timed("B  prepare 64 UNGUARDED (pre-fix behavior)", 5, || {
        let mut acc = 0usize;
        for batch in imgs.chunks(workers) {
            let sum: usize = std::thread::scope(|s| {
                let handles: Vec<_> = batch
                    .iter()
                    .map(|(name, bytes)| {
                        let name = name.clone();
                        s.spawn(move || prepare_one_timed(bytes, &name, true, stub()))
                    })
                    .collect();
                handles.into_iter().map(|h| h.join().unwrap_or(0)).sum()
            });
            acc ^= sum;
        }
        acc
    });

    // ---- phase C: prepare 64 mixed, SERIAL (instrumented) -------------------
    {
        let st = std::sync::Mutex::new(SubTimes::default());
        let wall = {
            let t = Instant::now();
            for (name, bytes) in imgs {
                prepare_one_timed(bytes, name, true, &st);
            }
            t.elapsed().as_secs_f64()
        };
        print_sub("C  SERIAL sub-phases", &st.into_inner().unwrap(), wall);
    }

    // ---- phase D2: single-alloc dat rebuild (new apply_write tail) ---------
    let appended: Vec<u8> = vec![0xAB; 13 * 1_048_576];
    timed("D2 dat with_capacity(64+13MB) + 2x copy", 5, || {
        let mut d = Vec::with_capacity(dat.len() + appended.len());
        d.extend_from_slice(&dat);
        d.extend_from_slice(&appended);
        d.len()
    });

    // ---- phase E: disk tail (append write + toc + txt) ----------------------
    let dat_path = tmp.join("bench.0.dat");
    timed("E  disk: write 13MB append + toc + txt", 5, || {
        use std::io::{Seek, SeekFrom, Write};
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .open(&dat_path)
            .unwrap();
        f.seek(SeekFrom::End(0)).unwrap();
        f.write_all(&appended).unwrap();
        std::fs::write(tmp.join("bench.0.toc"), &toc_bytes).unwrap();
        std::fs::write(tmp.join("bench.txt"), "x".repeat(24 * 1088)).unwrap();
    });

    // ---- phase F: full import on the populated cache (reference) ------------
    timed("F  full import on 1088-entry cache", 3, || {
        texdb::import_textures(&tmp, "bench", imgs, &opts)
            .unwrap()
            .len()
    });

    // ---- phase F2: full import into a fresh (empty) cache -------------------
    // Load cost ~= 0 and the apply tail has no 64 MB rebuild, so this isolates
    // the outer-parallel prepare + light append: the phase the OuterGuard fix
    // targets.
    std::fs::write(tmp.join("fresh.txt"), "").unwrap();
    std::fs::write(tmp.join("fresh.0.dat"), b"").unwrap();
    std::fs::write(tmp.join("fresh.0.toc"), 0u32.to_le_bytes()).unwrap();
    timed("F2 full import into FRESH cache", 5, || {
        texdb::import_textures(&tmp, "fresh", imgs, &opts)
            .unwrap()
            .len()
    });

    // ---- phase F3: idempotent re-import (identical bytes) -------------------
    // Second import of the SAME images: every entry compares equal, so
    // sync_after_write finds nothing to re-encode. F - F3 ~= variant
    // re-encode + thumbnail regeneration; F3 - F2 ~= load + apply-with-copy.
    texdb::import_textures(&tmp, "bench", imgs, &opts).unwrap();
    timed("F3 idempotent re-import (sync no-op)", 3, || {
        texdb::import_textures(&tmp, "bench", imgs, &opts)
            .unwrap()
            .len()
    });

    let _ = std::fs::remove_dir_all(&tmp);
}
