//! Profiling bench for the texdb import path (measurement only; not
//! production code).
//! Subcommands (dir must be a throwaway copy of a cache folder):
//!   phases <dir> <cache> <n>   replicate import_textures' phases in-process:
//!                              load, copy-all-listings, per-image CPU
//!                              (PNG decode + mip chain + DXT encode +
//!                              build_listing), rebuild_dat_toc, raw writes
//!   import <dir> <cache> <n>   the real import_textures() call (Overwrite,
//!                              wizard defaults from examples/one_op.rs)
//!   rebuildcache <dir> <cache> the real rebuild_cache() call
//!
//! Test PNGs are synthesized deterministically from the cache's own first N
//! non-affiliate manifest entries (same names + dimensions), opaque gradient
//! pixels, encoded with the `png` crate at default compression.

use imgtxd_core::codec::dxt::{encode_dxt1, encode_dxt5};
use imgtxd_core::mipmap::{generate_chain, MipFilter, PixelImage};
use imgtxd_core::texdb::*;
use std::path::{Path, PathBuf};
use std::time::Instant;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn wizard_opts() -> ImportOptions {
    ImportOptions {
        format: ImportFormat::CompressedAlpha,
        conflict: ConflictPolicy::Overwrite,
        sibling_model: None,
        reduce_to: 2048,
        camnorm: true,
        hash_check: true,
        correct_caps: false,
        use_rle: true,
        generate_mipmaps: true,
        mip_filter: 1,
        defragment: false,
    }
}

fn make_pngs(cache: &TexdbCache, n: usize) -> Vec<(String, Vec<u8>)> {
    let mut out = Vec::new();
    for e in cache.manifest.iter().filter(|e| e.affiliate.is_none()) {
        if out.len() >= n {
            break;
        }
        let (w, h) = (e.width.max(4), e.height.max(4));
        let mut px: Vec<u8> = Vec::with_capacity(w * h * 4);
        for y in 0..h {
            for x in 0..w {
                px.push((x * 255 / w) as u8);
                px.push((y * 255 / h) as u8);
                px.push(((x + y) * 255 / (w + h)) as u8);
                px.push(255);
            }
        }
        let mut buf: Vec<u8> = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut buf, w as u32, h as u32);
            enc.set_color(png::ColorType::Rgba);
            enc.set_depth(png::BitDepth::Eight);
            let mut wr = enc.write_header().unwrap();
            wr.write_image_data(&px).unwrap();
        }
        out.push((e.name.clone(), buf));
    }
    out
}

/// Per-image CPU of import_textures' loop body for the CompressedAlpha path
/// (opaque source => DXT1; mirrors the production sequence).
fn image_cpu(png_bytes: &[u8], name: &str, opts: &ImportOptions) -> usize {
    let (pixels, w, h) = decode_png_argb(png_bytes).expect("png decode");
    let src = PixelImage::new(pixels, w, h);
    let mut chain = vec![src.clone()];
    chain.extend(generate_chain(&src, MipFilter::from_i32(opts.mip_filter)));
    let levels = if opts.generate_mipmaps {
        &chain[..]
    } else {
        &chain[..1]
    };
    let has_alpha = levels[0].pixels.iter().any(|p| (p >> 24) & 0xFF < 255);
    let enc = if has_alpha {
        TexEncoding::Dxt5
    } else {
        TexEncoding::Dxt1
    };
    let encoded: Vec<Vec<u8>> = levels
        .iter()
        .map(|l| match enc {
            TexEncoding::Dxt5 => encode_dxt5(&l.pixels, l.width, l.height),
            _ => encode_dxt1(&l.pixels, l.width, l.height),
        })
        .collect();
    let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
    let listing = build_listing(name, enc, w, h, &refs, opts.use_rle).expect("listing");
    listing.len()
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("");
    let dir = PathBuf::from(&args[2]);
    let cache_name = args[3].clone();
    match mode {
        "phases" => {
            let n: usize = args[4].parse().unwrap();
            let t = Instant::now();
            let cache = TexdbCache::load(&dir, &cache_name).unwrap();
            let load_ms = ms(t);
            let dat_len =
                std::fs::metadata(dir.join(format!("{cache_name}.{}.dat", cache.variant)))
                    .unwrap()
                    .len();
            // Step 2 of import_textures: copy every raw listing into owned Vecs.
            let t = Instant::now();
            let mut listings: Vec<Option<Vec<u8>>> = (0..cache.manifest.len())
                .map(|i| cache.raw_listing(i).map(|b| b.to_vec()))
                .collect();
            let copy_ms = ms(t);
            let copied: usize = listings
                .iter()
                .map(|l| l.as_ref().map_or(0, |v| v.len()))
                .sum();
            // Per-image CPU.
            let pngs = make_pngs(&cache, n);
            let png_bytes: usize = pngs.iter().map(|(_, b)| b.len()).sum();
            let opts = wizard_opts();
            let t = Instant::now();
            for (name, bytes) in &pngs {
                let idx = cache
                    .manifest
                    .iter()
                    .position(|e| e.affiliate.is_none() && e.name.eq_ignore_ascii_case(name))
                    .unwrap();
                listings[idx] = Some(vec![0u8; image_cpu(bytes, name, &opts)]);
            }
            let cpu_ms = ms(t);
            // rebuild_dat_toc over the full cache.
            let total_listings: Vec<Option<&[u8]>> =
                listings.iter().map(|l| l.as_deref()).collect();
            let t = Instant::now();
            let (dat, toc) = rebuild_dat_toc(&total_listings);
            let rebuild_ms = ms(t);
            // Raw writes of the resulting buffers (to scratch files in dir).
            let t = Instant::now();
            std::fs::write(dir.join("bench_scratch.dat"), &dat).unwrap();
            std::fs::write(dir.join("bench_scratch.toc"), &toc).unwrap();
            let write_ms = ms(t);
            std::fs::remove_file(dir.join("bench_scratch.dat")).ok();
            std::fs::remove_file(dir.join("bench_scratch.toc")).ok();
            println!(
                "load: {:.1} ms | copy_listings: {:.1} ms ({} B) | image_cpu x{}: {:.1} ms (png {} B) | rebuild_dat_toc: {:.1} ms | write dat+toc: {:.1} ms ({} B) | sum_phases: {:.1} ms | dat={} B entries={} variant={}",
                load_ms,
                copy_ms,
                copied,
                n,
                cpu_ms,
                png_bytes,
                rebuild_ms,
                write_ms,
                dat.len() + toc.len(),
                load_ms + copy_ms + cpu_ms + rebuild_ms + write_ms,
                dat_len,
                cache.manifest.len(),
                cache.variant
            );
        }
        "import" => {
            let n: usize = args[4].parse().unwrap();
            let probe = TexdbCache::load(&dir, &cache_name).unwrap();
            let pngs = make_pngs(&probe, n);
            drop(probe);
            let t = Instant::now();
            let r = import_textures(&dir, &cache_name, &pngs, &wizard_opts()).unwrap();
            println!(
                "import_textures x{}: {:.1} ms | {}",
                n,
                ms(t),
                r.replace('\n', " ")
            );
        }
        "rebuildcache" => {
            let t = Instant::now();
            let r = rebuild_cache(&dir, &cache_name).unwrap();
            println!("rebuild_cache: {:.1} ms | {}", ms(t), r.replace('\n', " "));
        }
        _ => panic!("usage: bench_texdb phases|import|rebuildcache <dir> <cache> [n]"),
    }
    let _ = Path::new("");
}
