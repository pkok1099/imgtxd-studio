//! Profiling of the app's real read paths against actual GTA data
//! (measurement only; not production code). Everything is deterministic:
//! sampling uses xorshift32, output integrity uses FNV-1a 64 checksums.
//!
//! Usage: real_prof <bench_dir>
//!   <bench_dir> must contain gta3.img and the gta3/ cache folder
//!   (gta3.txt + gta3.<variant>.toc + gta3.<variant>.dat).

use imgtxd_core::img::{verify_archive, ImgArchive};
use imgtxd_core::texdb::{decode_pixels_at, describe_cache, resolve_cache_name, TexdbCache};
use imgtxd_core::txd;
use std::path::{Path, PathBuf};
use std::time::Instant;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn fnv(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

fn fnv_u32(data: &[u32]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for &p in data {
        h ^= p as u64;
        h = h.wrapping_mul(0x100000001b3);
        h ^= (p >> 8) as u64 & 0xFF;
        h = h.wrapping_mul(0x100000001b3);
        h ^= (p >> 16) as u64 & 0xFF;
        h = h.wrapping_mul(0x100000001b3);
        h ^= (p >> 24) as u64 & 0xFF;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

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
    fn below(&mut self, n: usize) -> usize {
        (self.next() as usize) % n.max(1)
    }
}

fn fmt_bytes(b: usize) -> String {
    if b >= 1 << 20 {
        format!("{:.1} MiB", b as f64 / (1 << 20) as f64)
    } else if b >= 1 << 10 {
        format!("{:.1} KiB", b as f64 / (1 << 10) as f64)
    } else {
        format!("{b} B")
    }
}

/// Local replica of texdb::quote_tokens (measurement only).
fn bench_quote_tokens(rest: &str) -> Vec<String> {
    let b = rest.as_bytes();
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < b.len() {
        while i < b.len() && (b[i] as char).is_whitespace() {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        if b[i] == b'"' {
            let start = i + 1;
            let mut j = start;
            while j < b.len() && b[j] != b'"' {
                j += 1;
            }
            out.push(rest[start..j].to_string());
            i = (j + 1).min(b.len());
        } else {
            let start = i;
            while i < b.len() && !(b[i] as char).is_whitespace() {
                i += 1;
            }
            out.push(rest[start..i].to_string());
        }
    }
    out
}

fn main() {
    let dir = PathBuf::from(std::env::args().nth(1).expect("usage: real_prof <bench_dir>"));
    println!("=== real_prof: {}", dir.display());

    // ---------- 1. IMG archive ----------
    let img_path = dir.join("gta3.img");
    println!("\n[IMG] {} ({})", img_path.display(), fmt_bytes(std::fs::metadata(&img_path).map(|m| m.len() as usize).unwrap_or(0)));
    let t = Instant::now();
    let archive = ImgArchive::open(&img_path, None).expect("open gta3.img");
    println!(
        "open ({:?}): {:.1} ms | entries: {}",
        archive.version,
        ms(t),
        archive.entries.len()
    );
    let n_txd = archive.entries.iter().filter(|e| e.clean_name().ends_with(".txd")).count();
    let n_dff = archive.entries.iter().filter(|e| e.clean_name().ends_with(".dff")).count();
    let txd_idx: Vec<usize> = archive
        .entries
        .iter()
        .enumerate()
        .filter(|(_, e)| e.clean_name().ends_with(".txd"))
        .map(|(i, _)| i)
        .collect();
    let dff_idx: Vec<usize> = archive
        .entries
        .iter()
        .enumerate()
        .filter(|(_, e)| e.clean_name().ends_with(".dff"))
        .map(|(i, _)| i)
        .collect();
    println!("txd entries: {n_txd} | dff entries: {n_dff}");

    // Read 8 TXD + 8 DFF + 16 random entries; hash the bytes.
    let mut rng = Rng(0x12345678);
    let mut read_targets: Vec<usize> = Vec::new();
    read_targets.extend(txd_idx.iter().take(8).copied());
    read_targets.extend(dff_idx.iter().take(8).copied());
    for _ in 0..16 {
        read_targets.push(rng.below(archive.entries.len()));
    }
    let t = Instant::now();
    let mut bytes_read = 0usize;
    let mut img_hash: u64 = 0xcbf29ce484222325;
    let mut txd_buffers: Vec<(String, Vec<u8>)> = Vec::new();
    for &i in &read_targets {
        let e = &archive.entries[i];
        let buf = archive.read_entry_bytes(e).expect("read entry");
        img_hash = fnv(&buf) ^ img_hash.rotate_left(1);
        bytes_read += buf.len();
        if e.clean_name().ends_with(".txd") && txd_buffers.len() < 8 {
            txd_buffers.push((e.clean_name().to_string(), buf));
        }
    }
    println!(
        "read {} entries ({}): {:.1} ms | hash {:016x}",
        read_targets.len(),
        fmt_bytes(bytes_read),
        ms(t),
        img_hash
    );

    let t = Instant::now();
    let report = verify_archive(&img_path, None);
    println!("verify_archive (full scan): {:.1} ms | {}", ms(t), report.replace('\n', " "));

    // ---------- 2. TXD parse + decode ----------
    println!("\n[TXD] parse + decode of {} real TXD from the img", txd_buffers.len());
    let t = Instant::now();
    let mut parsed = 0usize;
    let mut tex_count = 0usize;
    let mut txd_hash: u64 = 0xcbf29ce484222325;
    let mut decode_hash: u64 = 0xcbf29ce484222325;
    for (name, buf) in &txd_buffers {
        if let Ok(f) = txd::parse(buf) {
            parsed += 1;
            tex_count += f.textures.len();
            txd_hash = fnv(&buf[..buf.len().min(4096)]) ^ txd_hash.rotate_left(3);
            if let Some(tex) = f.textures.first() {
                if let Some(px) = txd::decode_pixels(tex) {
                    decode_hash = fnv_u32(&px) ^ decode_hash.rotate_left(5);
                    let _ = name;
                }
            }
        }
    }
    println!(
        "parse x{}: {:.1} ms (parsed {parsed}, {tex_count} textures) | hash {txd_hash:016x} | decode-first-hash {decode_hash:016x}",
        txd_buffers.len(),
        ms(t)
    );

    // ---------- 3. TexDB (mobile cache) ----------
    let cache_dir = dir.join("gta3");
    println!("\n[TEXDB] {}", cache_dir.display());
    let t = Instant::now();
    let cname = resolve_cache_name(&cache_dir).expect("cache name");
    println!("resolve_cache_name: {:.1} ms | {}", ms(t), cname);
    let t = Instant::now();
    let cache = TexdbCache::load(&cache_dir, &cname).expect("load cache");
    println!(
        "TexdbCache::load (185MB dat + manifest): {:.1} ms | variant {} | entries {} | dat {}",
        ms(t),
        cache.variant,
        cache.manifest.len(),
        fmt_bytes(cache.raw_listing(cache.manifest.len() / 2).map_or(0, |b| b.len()))
    );
    let t = Instant::now();
    let desc = describe_cache(&cache_dir).expect("describe");
    println!(
        "describe_cache: {:.1} ms | textures listed {} | variants {:?}",
        ms(t),
        desc.textures.len(),
        desc.variants
    );

    // Deterministic sample of 64 non-affiliate entries with data.
    let usable: Vec<usize> = (0..cache.manifest.len())
        .filter(|&i| cache.listings[i].is_some())
        .collect();
    let mut rng = Rng(0x9e3779b9);
    let mut sample: Vec<usize> = Vec::new();
    for _ in 0..64 {
        sample.push(usable[rng.below(usable.len())]);
    }
    let t = Instant::now();
    let mut raw_hash: u64 = 0xcbf29ce484222325;
    let mut raw_bytes = 0usize;
    for &i in &sample {
        if let Some(b) = cache.decode_raw(i) {
            raw_hash = fnv(&b) ^ raw_hash.rotate_left(1);
            raw_bytes += b.len();
        }
    }
    println!(
        "decode_raw x64: {:.1} ms ({} decoded) | hash {raw_hash:016x}",
        ms(t),
        fmt_bytes(raw_bytes)
    );
    let t = Instant::now();
    let mut px_hash: u64 = 0xcbf29ce484222325;
    let mut px_count = 0usize;
    let mut px_bytes = 0usize;
    for &i in &sample {
        if let Some(px) = cache.decode_pixels(i) {
            px_hash = fnv_u32(&px) ^ px_hash.rotate_left(1);
            px_count += px.len();
            px_bytes += px.len() * 4;
        }
    }
    println!(
        "decode_pixels x64: {:.1} ms ({} px, {}) | hash {px_hash:016x}",
        ms(t),
        px_count,
        fmt_bytes(px_bytes)
    );

    // Per-encoding breakdown of the sample (what mix does the real cache hold?).
    let mut enc_mix: Vec<(String, usize)> = Vec::new();
    for &i in &sample {
        if let Some(l) = cache.listings[i].as_ref() {
            let key = format!("{:?} rle={} {}x{}", l.encoding, l.rle_indicator > 0, l.width, l.height);
            match enc_mix.iter_mut().find(|(k, _)| *k == key) {
                Some((_, c)) => *c += 1,
                None => enc_mix.push((key, 1)),
            }
        }
    }
    enc_mix.sort_by_key(|e| std::cmp::Reverse(e.1));
    for (k, c) in enc_mix.iter().take(12) {
        println!("  sample mix: {c}x {k}");
    }

    // decode_pixels_at on 32 entries: one-shot helper (fresh partial load per
    // call) used by export flows — measures the no-persistent-cache path.
    let t = Instant::now();
    let mut at_hash: u64 = 0xcbf29ce484222325;
    for &i in sample.iter().take(32) {
        if let Some((_, _, _, px)) = decode_pixels_at(&cache_dir, &cname, i) {
            at_hash = fnv_u32(&px) ^ at_hash.rotate_left(2);
        }
    }
    println!(
        "decode_pixels_at x32 (fresh load each call): {:.1} ms | hash {at_hash:016x}",
        ms(t)
    );

    // ---------- 4. Breakdown of the per-call view path ----------
    // Where does one decode_pixels_at call spend its time? Replicate its
    // stages with public I/O + a line walk that mirrors parse_manifest's
    // entry rule (trimmed line starts with '"' and has a closing quote).
    let txt_path = cache_dir.join(format!("{cname}.txt"));
    let t = Instant::now();
    let text = std::fs::read_to_string(&txt_path).expect("read manifest");
    println!("\n[BREAKDOWN] decode_pixels_at per-call stages (cache {:?}):", cname);
    println!("  read_to_string manifest ({}): {:.1} ms", fmt_bytes(text.len()), ms(t));
    let t = Instant::now();
    let mut count = 0usize;
    let mut target_line: Option<&str> = None;
    let probe_idx = sample[0];
    for line in text.lines() {
        let line = line.trim();
        if !line.starts_with('"') {
            continue;
        }
        if line[1..].find('"').is_none() {
            continue;
        }
        if count == probe_idx {
            target_line = Some(line);
        }
        count += 1;
    }
    println!(
        "  targeted line walk ({} entries, probe #{probe_idx} = {:?}): {:.1} ms",
        count,
        target_line.map(|l| &l[1..l[1..].find('"').unwrap() + 1]),
        ms(t)
    );
    let t = Instant::now();
    let toc_bytes = std::fs::read(cache_dir.join(format!("{cname}.{}.toc", cache.variant))).expect("toc");
    let _ = toc_bytes.len();
    println!("  read toc ({}): {:.1} ms", fmt_bytes(toc_bytes.len()), ms(t));
    // Current path cost estimate: manifest full parse = read + walk + parse.
    // Time parse-equivalent by re-walking with a local quote_tokens replica
    // (mirrors texdb::quote_tokens; measurement only).
    let t = Instant::now();
    let mut parse_cost = 0usize;
    for line in text.lines() {
        let line = line.trim();
        if !line.starts_with('"') {
            continue;
        }
        let Some(end) = line[1..].find('"') else { continue };
        parse_cost += line.len();
        for token in bench_quote_tokens(&line[1 + end + 1..]) {
            if token.contains('=') {
                parse_cost += token.len();
            }
        }
    }
    println!(
        "  full-parse-equivalent walk ({parse_cost} B touched): {:.1} ms (allocating parse is what the current path pays per call)",
        ms(t)
    );

    println!("\nDONE");
    let _ = Path::new("");
}
