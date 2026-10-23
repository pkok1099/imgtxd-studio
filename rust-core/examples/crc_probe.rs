use imgtxd_core::texdb::*;
use std::path::PathBuf;

fn hex(s: Option<&str>) -> u32 {
    s.and_then(|v| u32::from_str_radix(v, 16).ok()).unwrap_or(0)
}

fn main() {
    let dir = PathBuf::from(std::env::args().nth(1).expect("dir"));
    let name = std::env::args().nth(2).expect("name");
    let cache = TexdbCache::load(&dir, &name).expect("load");
    let mut shown = 0;
    for (i, e) in cache.manifest.iter().enumerate() {
        if e.affiliate.is_some() { continue; }
        let png = hex(e.prop("png"));
        let img = hex(e.prop("img"));
        let raw = cache.decode_raw(i);
        let span = cache.raw_listing(i).map(|b| b.to_vec());
        let (cand_full, cand_span) = match (&raw, &span) {
            (Some(r), Some(s)) => (crc32_ieee(r), crc32_ieee(s)),
            _ => (0, 0),
        };
        let cand_payload = span.as_ref().map(|s| crc32_ieee(&s[16..])).unwrap_or(0);
        let cand_pixels = cache.decode_pixels(i)
            .map(|px| {
                let mut bytes = Vec::with_capacity(px.len() * 4);
                for p in &px { bytes.extend_from_slice(&p.to_le_bytes()); }
                crc32_ieee(&bytes)
            })
            .unwrap_or(0);
        let lvl0 = raw.as_ref().and_then(|r| {
            cache.listings[i].as_ref().and_then(|l| l.expected_size())
                .map(|exp| crc32_ieee(&r[..exp.min(r.len())]))
        }).unwrap_or(0);
        println!(
            "{} png={:08x} img={:08x} | full={:08x} lvl0={:08x} payload={:08x} span={:08x} pixels={:08x} | img=={} png=={}",
            e.name, png, img, cand_full, lvl0, cand_payload, cand_span, cand_pixels,
            [("full", cand_full), ("lvl0", lvl0), ("payload", cand_payload), ("span", cand_span), ("pixels", cand_pixels)]
                .iter().find(|(_, c)| *c == img).map(|(n, _)| *n).unwrap_or("-"),
            [("full", cand_full), ("lvl0", lvl0), ("payload", cand_payload), ("span", cand_span), ("pixels", cand_pixels)]
                .iter().find(|(_, c)| *c == png).map(|(n, _)| *n).unwrap_or("-"),
        );
        shown += 1;
        if shown >= 10 { break; }
    }
}
