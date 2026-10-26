//! Installs the S15 mod textures into a copy of the `txd` texdb cache,
//! TXD Tool style: PNG -> (reduce to fit) -> DXT1/DXT5 + full mip chain ->
//! RLE listing -> rebuilt .dat/.toc/.txt. Untouched textures stay
//! byte-identical; only the validated variant (etc) is rebuilt.
//! Usage: install_s15_texdb <src_cache_dir> <cache_name> <mod_dir> <out_dir> [max_size]
use std::collections::HashMap;
use std::path::{Path, PathBuf};

use imgtxd_core::codec::dxt::{encode_dxt1, encode_dxt5};
use imgtxd_core::mipmap::{generate_chain, MipFilter, PixelImage};
use imgtxd_core::texdb::{build_listing, crc32_ieee, rebuild_dat_toc, TexEncoding, TexdbCache};

fn decode_png(bytes: &[u8]) -> (Vec<u32>, usize, usize) {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder.read_info().expect("png info");
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).expect("png frame");
    let (w, h) = (info.width as usize, info.height as usize);
    let px: Vec<u32> = match info.color_type {
        png::ColorType::Rgba => buf[..info.buffer_size()]
            .as_chunks::<4>()
            .0
            .iter()
            .map(|c| {
                ((c[3] as u32) << 24) | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32
            })
            .collect(),
        png::ColorType::Rgb => buf[..info.buffer_size()]
            .as_chunks::<3>()
            .0
            .iter()
            .map(|c| 0xFF00_0000 | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
            .collect(),
        png::ColorType::Grayscale => buf[..info.buffer_size()]
            .iter()
            .map(|&v| 0xFF00_0000 | ((v as u32) << 16) | ((v as u32) << 8) | v as u32)
            .collect(),
        png::ColorType::GrayscaleAlpha => buf[..info.buffer_size()]
            .as_chunks::<2>()
            .0
            .iter()
            .map(|c| {
                ((c[1] as u32) << 24) | ((c[0] as u32) << 16) | ((c[0] as u32) << 8) | c[0] as u32
            })
            .collect(),
        other => panic!("unsupported png color type {other:?}"),
    };
    assert_eq!(px.len(), w * h);
    (px, w, h)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let src = Path::new(&args[1]);
    let cache_name = &args[2];
    let mod_dir = Path::new(&args[3]);
    let out_dir = Path::new(&args[4]);
    let max_size: usize = args.get(5).and_then(|s| s.parse().ok()).unwrap_or(512);

    std::fs::create_dir_all(out_dir).unwrap();
    for e in std::fs::read_dir(src).unwrap() {
        let p = e.unwrap().path();
        std::fs::copy(&p, out_dir.join(p.file_name().unwrap())).unwrap();
    }
    let cache = TexdbCache::load(src, cache_name).expect("load cache");
    println!("variant: {}", cache.variant);

    // Gather mod PNGs (both wizard folders).
    let mut jobs: Vec<(String, PathBuf)> = Vec::new();
    for folder in ["without alpha", "with alpha"] {
        let dir = mod_dir.join(folder);
        if !dir.exists() {
            continue;
        }
        let mut files: Vec<PathBuf> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().path())
            .filter(|p| p.extension().map(|x| x == "png").unwrap_or(false))
            .collect();
        files.sort();
        for f in files {
            jobs.push((f.file_stem().unwrap().to_string_lossy().to_string(), f));
        }
    }
    println!("mod textures: {}", jobs.len());

    let mut index_by_name: HashMap<String, usize> = HashMap::new();
    for (i, e) in cache.manifest.iter().enumerate() {
        if e.affiliate.is_none() {
            index_by_name.entry(e.name.to_lowercase()).or_insert(i);
        }
    }
    let mut new_listings: HashMap<usize, Vec<u8>> = HashMap::new();
    let mut new_lines: HashMap<usize, String> = HashMap::new();
    let mut appended: Vec<(Vec<u8>, String)> = Vec::new();
    let (mut replaced, mut added) = (0usize, 0usize);

    for (name, path) in &jobs {
        let png_bytes = std::fs::read(path).unwrap();
        let (pixels, w, h) = decode_png(&png_bytes);
        let src_img = PixelImage::new(pixels, w, h);
        let mut chain = vec![src_img.clone()];
        chain.extend(generate_chain(&src_img, MipFilter::Box));
        let base = chain
            .iter()
            .position(|l| l.width.max(l.height) <= max_size)
            .unwrap_or(chain.len() - 1);
        let levels = &chain[base..];
        let has_alpha = levels[0].pixels.iter().any(|p| (p >> 24) & 0xFF < 255);
        let encoding = if has_alpha {
            TexEncoding::Dxt5
        } else {
            TexEncoding::Dxt1
        };
        let encoded: Vec<Vec<u8>> = levels
            .iter()
            .map(|l| match encoding {
                TexEncoding::Dxt5 => encode_dxt5(&l.pixels, l.width, l.height),
                _ => encode_dxt1(&l.pixels, l.width, l.height),
            })
            .collect();
        let refs: Vec<&[u8]> = encoded.iter().map(|v| &v[..]).collect();
        let listing = build_listing(
            name,
            encoding,
            levels[0].width,
            levels[0].height,
            &refs,
            true,
        )
        .expect("build listing");
        let line = format!(
            "\"{}\" width={} height={} png={:08x} img={:08x} format=7 {}streammode=1",
            name,
            levels[0].width,
            levels[0].height,
            crc32_ieee(&png_bytes),
            crc32_ieee(&encoded[0]),
            if has_alpha { "alphamode=2 " } else { "" }
        );
        match index_by_name.get(&name.to_lowercase()) {
            Some(&i) => {
                new_listings.insert(i, listing);
                new_lines.insert(i, line);
                replaced += 1;
                println!(
                    "  overwrite {name} ({}x{} {:?})",
                    levels[0].width, levels[0].height, encoding
                );
            }
            None => {
                appended.push((listing, line));
                added += 1;
                println!(
                    "  add {name} ({}x{} {:?})",
                    levels[0].width, levels[0].height, encoding
                );
            }
        }
    }

    // Rebuild .dat/.toc over the full manifest (+ appended entries).
    let total = cache.manifest.len() + appended.len();
    let mut slots: Vec<Option<&[u8]>> = Vec::with_capacity(total);
    for i in 0..cache.manifest.len() {
        if cache.manifest[i].affiliate.is_some() {
            slots.push(None);
        } else if let Some(l) = new_listings.get(&i) {
            slots.push(Some(l));
        } else {
            slots.push(cache.raw_listing(i));
        }
    }
    for (listing, _) in &appended {
        slots.push(Some(listing));
    }
    let (dat, toc) = rebuild_dat_toc(&slots);

    // Rebuild .txt: original lines, replaced in place, additions at the end.
    let orig_txt = std::fs::read_to_string(src.join(format!("{cache_name}.txt"))).unwrap();
    let mut out_lines: Vec<String> = Vec::new();
    let mut entry_idx = 0usize;
    for raw_line in orig_txt.lines() {
        let line = raw_line.trim_end();
        if line.trim_start().starts_with('"') {
            if let Some(nl) = new_lines.get(&entry_idx) {
                out_lines.push(nl.clone());
            } else {
                out_lines.push(line.to_string());
            }
            entry_idx += 1;
        } else {
            out_lines.push(line.to_string());
        }
    }
    for (_, line) in &appended {
        out_lines.push(line.clone());
    }
    let mut txt = out_lines.join("\n");
    txt.push('\n');

    std::fs::write(out_dir.join(format!("{cache_name}.txt")), txt).unwrap();
    std::fs::write(
        out_dir.join(format!("{cache_name}.{}.dat", cache.variant)),
        &dat,
    )
    .unwrap();
    std::fs::write(
        out_dir.join(format!("{cache_name}.{}.toc", cache.variant)),
        &toc,
    )
    .unwrap();
    println!(
        "done: replaced={replaced} added={added} dat={} bytes",
        dat.len()
    );
}
