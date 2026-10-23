//! Golden-parity probe for the TXD Rust port: replicates the construction
//! of the (removed) Kotlin GoldenCaptureTest and prints the same GOLDEN
//! lines, plus a direct byte comparison against the Kotlin-built sample.
use imgtxd_core::txd::*;
use std::path::PathBuf;

// --- minimal SHA-256 (probe tooling only) ---
const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

fn sha256(data: &[u8]) -> String {
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    let bit_len = (data.len() as u64) * 8;
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes(chunk[i * 4..i * 4 + 4].try_into().unwrap());
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh) =
            (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }
    h.iter().map(|v| format!("{v:08x}")).collect()
}

fn sha_ints(ints: &[u32]) -> String {
    let mut bytes = Vec::with_capacity(ints.len() * 4);
    for v in ints {
        bytes.extend_from_slice(&v.to_le_bytes());
    }
    sha256(&bytes)
}

fn pattern(w: usize, h: usize, alpha_mode: u8) -> Vec<u32> {
    let mut px = vec![0u32; w * h];
    for y in 0..h {
        for x in 0..w {
            let r = (x * 255) / (w - 1).max(1);
            let g = (y * 255) / (h - 1).max(1);
            let b = (x * 7 + y * 13) % 256;
            let a = match alpha_mode {
                0 => 255,
                1 => (x * 255) / (w - 1).max(1),
                _ => {
                    if (x + y) % 3 == 0 { 0 } else { 255 }
                }
            };
            px[y * w + x] =
                ((a as u32) << 24) | ((r as u32) << 16) | ((g as u32) << 8) | b as u32;
        }
    }
    px
}

fn crafted(size: usize, seed: usize) -> Vec<u8> {
    (0..size).map(|i| ((i * 7 + seed * 31 + 3) % 251) as u8).collect()
}

fn manual(
    name: &str, w: usize, h: usize, depth: u32, raster: u32, d3d: u32, raw: Vec<u8>,
) -> TxdTexture {
    TxdTexture {
        name: name.to_string(),
        width: w,
        height: h,
        depth,
        raster_format: raster,
        d3d_format: d3d,
        raw_pixels: raw,
        ..TxdTexture::default()
    }
}

fn main() {
    let golden_path = PathBuf::from(std::env::args().nth(1).expect("golden_sample.txd path"));
    let mut entries: Vec<TxdTexture> = Vec::new();

    entries.push(create_entry(
        "alpha_opaque",
        &pattern(16, 16, 0),
        16,
        16,
        None,
        &ImportOptions { generate_mipmaps: true, mip_filter: MipFilter::Box, max_dimension: None },
    ));
    entries.push(create_entry(
        "smooth_alpha",
        &pattern(16, 16, 1),
        16,
        16,
        None,
        &ImportOptions {
            generate_mipmaps: true,
            mip_filter: MipFilter::Bilinear,
            max_dimension: None,
        },
    ));
    entries.push(create_entry(
        "reduced",
        &pattern(64, 32, 0),
        64,
        32,
        None,
        &ImportOptions {
            generate_mipmaps: true,
            mip_filter: MipFilter::Nearest,
            max_dimension: Some(16),
        },
    ));
    entries.push(manual("manual_dxt5", 8, 8, 16, FORMAT_565, FOURCC_DXT5, crafted(64, 5)));
    entries.push(manual("manual_dxt3", 8, 8, 16, FORMAT_565, FOURCC_DXT3, crafted(64, 9)));
    let mut dxt1a = vec![0u8; 8];
    dxt1a[2] = 0xFF;
    dxt1a[3] = 0xFF;
    dxt1a[4] = 0xE4;
    dxt1a[5] = 0xE4;
    dxt1a[6] = 0xE4;
    dxt1a[7] = 0xE4;
    entries.push(manual("manual_dxt1a", 4, 4, 16, FORMAT_565, FOURCC_DXT1, dxt1a));
    let mut pal = manual("manual_pal8", 4, 4, 8, FORMAT_PAL8, 0, (0..16).map(|i| i as u8).collect());
    pal.palette = Some(crafted(1024, 2));
    entries.push(pal);
    entries.push(manual("manual_565", 4, 2, 16, FORMAT_565, 0, crafted(16, 3)));
    entries.push(manual("manual_1555", 4, 2, 16, FORMAT_1555, 0, crafted(16, 4)));
    entries.push(manual("manual_4444", 4, 2, 16, FORMAT_4444, 0, crafted(16, 6)));
    entries.push(manual("manual_888", 4, 2, 24, FORMAT_888, 0, crafted(24, 7)));
    entries.push(manual("manual_8888", 4, 2, 32, FORMAT_8888, 0, crafted(32, 8)));
    entries.push(manual("manual_etc1", 8, 8, 16, FORMAT_565, FOURCC_ETC1, crafted(32, 11)));
    let mut mips = manual("manual_mips", 8, 8, 32, FORMAT_8888, 0, crafted(256, 12));
    mips.mask_name = "maskx".to_string();
    mips.filter_flags = 0x2222;
    mips.platform_id = 0x06;
    mips.mipmap_count = 3;
    mips.mip_levels = vec![crafted(64, 13), crafted(16, 14)];
    entries.push(mips);

    let bytes = build_to_bytes(&entries, RW_VERSION_SA);
    println!("GOLDEN|build_sha|{}", sha256(&bytes));
    println!("GOLDEN|build_size|{}", bytes.len());
    let golden_bytes = std::fs::read(&golden_path).expect("read golden sample");
    println!("GOLDEN|byte_equal|{}", bytes == golden_bytes);

    let parsed = parse(&bytes).expect("parse");
    println!("GOLDEN|rwversion|{}", parsed.rw_version);
    for e in &parsed.textures {
        let mips_joined: Vec<u8> = e.mip_levels.iter().flatten().copied().collect();
        println!(
            "GOLDEN|entry|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
            e.name,
            e.mask_name,
            e.width,
            e.height,
            e.depth,
            e.raster_format,
            e.d3d_format,
            e.filter_flags,
            e.platform_id,
            e.mipmap_count,
            e.mip_levels.len(),
            sha256(&e.raw_pixels),
            sha256(&mips_joined),
            e.palette.as_ref().map(|p| sha256(p)).unwrap_or_else(|| "-".to_string())
        );
    }

    let get = |n: &str| parsed.textures.iter().find(|e| e.name == n).unwrap();
    println!(
        "GOLDEN|decode_dxt1|{}",
        sha_ints(&decode_pixels(get("alpha_opaque")).unwrap())
    );
    println!(
        "GOLDEN|decode_dxt5|{}",
        sha_ints(&decode_pixels(get("manual_dxt5")).unwrap())
    );
    println!(
        "GOLDEN|decode_dxt3|{}",
        sha_ints(&decode_pixels(get("manual_dxt3")).unwrap())
    );
    println!(
        "GOLDEN|decode_dxt1a|{}",
        sha_ints(&decode_pixels(get("manual_dxt1a")).unwrap())
    );

    let mut textures = parsed.textures.clone();
    let target = textures.iter_mut().find(|e| e.name == "manual_565").unwrap();
    replace_texture(
        target,
        &pattern(8, 8, 1),
        8,
        8,
        Some(false),
        &ImportOptions {
            generate_mipmaps: true,
            mip_filter: MipFilter::Nearest,
            max_dimension: None,
        },
    );
    let rebuilt = build_to_bytes(&textures, parsed.rw_version);
    println!("GOLDEN|replace_sha|{}", sha256(&rebuilt));
    println!("GOLDEN|replace_size|{}", rebuilt.len());
}
