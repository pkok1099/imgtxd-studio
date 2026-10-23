//! ETC1 (Ericsson Texture Compression 1) codec — the format behind the
//! GL_ETC1_RGB8_OES listings in mobile texdb caches.
//!
//! Decoder layout verified against real game data on 2026-10-23: the
//! 'coronastar' texture from txd.etc decodes to a clean radial glow.
//! The encoder always emits individual mode (never differential); decoders
//! must accept both, encoders need not produce both.

/// Intensity modifier pairs per codeword table: (small, big).
const MODIFIERS: [(i32, i32); 8] = [
    (2, 8),
    (5, 17),
    (9, 29),
    (13, 42),
    (18, 60),
    (24, 80),
    (33, 106),
    (55, 183),
];

fn expand4(v: u32) -> u32 {
    (v << 4) | v
}

fn expand5(v: u32) -> u32 {
    (v << 3) | (v >> 2)
}

fn signed3(v: u32) -> i32 {
    ((v & 7) ^ 4) as i32 - 4
}

fn clamp255(v: i32) -> u32 {
    v.clamp(0, 255) as u32
}

fn decode_block(b: &[u8; 8], out: &mut [u32; 16]) {
    let diff = (b[3] >> 1) & 1 == 1;
    let flip = b[3] & 1 == 1;
    let (c1, c2);
    if diff {
        let r = (b[0] >> 3) as i32;
        let g = (b[1] >> 3) as i32;
        let bl = (b[2] >> 3) as i32;
        let (dr, dg, db) = (signed3(b[0] as u32), signed3(b[1] as u32), signed3(b[2] as u32));
        c1 = (expand5(r as u32), expand5(g as u32), expand5(bl as u32));
        c2 = (
            expand5((r + dr).clamp(0, 31) as u32),
            expand5((g + dg).clamp(0, 31) as u32),
            expand5((bl + db).clamp(0, 31) as u32),
        );
    } else {
        c1 = (
            expand4(((b[0] >> 4) & 15) as u32),
            expand4(((b[1] >> 4) & 15) as u32),
            expand4(((b[2] >> 4) & 15) as u32),
        );
        c2 = (
            expand4((b[0] & 15) as u32),
            expand4((b[1] & 15) as u32),
            expand4((b[2] & 15) as u32),
        );
    }
    let t1 = ((b[3] >> 5) & 7) as usize;
    let t2 = ((b[3] >> 2) & 7) as usize;
    // Precompute the four packed output colors per subblock (same math as the
    // per-pixel form: clamp255(base + modifier), same packing). Pixels then do
    // one table lookup instead of 3 clamps + packing each.
    let pal_a = packed_palette(c1, t1);
    let pal_b = packed_palette(c2, t2);
    let msb = ((b[4] as u16) << 8) | b[5] as u16;
    let lsb = ((b[6] as u16) << 8) | b[7] as u16;
    for x in 0..4 {
        for y in 0..4 {
            let k = x * 4 + y;
            let idx = ((((msb >> (15 - k)) & 1) << 1) | ((lsb >> (15 - k)) & 1)) as usize;
            let first = if flip { y < 2 } else { x < 2 };
            let pal = if first { &pal_a } else { &pal_b };
            out[y * 4 + x] = pal[idx];
        }
    }
}

/// The four packed ARGB outputs of one subblock: modifier table `t` applied to
/// `base`, clamped and packed exactly as the per-pixel decoder did.
#[inline]
fn packed_palette(base: (u32, u32, u32), t: usize) -> [u32; 4] {
    let (small, big) = MODIFIERS[t];
    let mut pal = [0u32; 4];
    for (i, &m) in [-big, -small, small, big].iter().enumerate() {
        let r = clamp255(base.0 as i32 + m);
        let g = clamp255(base.1 as i32 + m);
        let bl = clamp255(base.2 as i32 + m);
        pal[i] = 0xFF00_0000 | (r << 16) | (g << 8) | bl;
    }
    pal
}

/// Decodes ETC1 block data to ARGB pixels (alpha = 255).
/// Large textures split into horizontal block-row bands decoded on worker
/// threads (deterministic: every band writes a disjoint row range; identical
/// output to the serial path). Decode work per block is small, so the
/// parallel threshold (16384 blocks = 512x512) sits well above the encoder's.
pub fn decode_etc1(data: &[u8], width: usize, height: usize) -> Vec<u32> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut pixels = vec![0u32; width * height];
    if blocks_x * blocks_y < 16384 {
        decode_etc1_band(data, width, height, blocks_x, 0, blocks_y, &mut pixels);
        return pixels;
    }
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .min(16);
    // At least 4 block-rows per band so per-thread work stays >> spawn cost.
    let rows_per_band = blocks_y.div_ceil(workers).max(4);
    std::thread::scope(|s| {
        let mut rest: &mut [u32] = &mut pixels;
        let mut by_start = 0usize;
        while by_start < blocks_y {
            let by_end = (by_start + rows_per_band).min(blocks_y);
            let y0 = (by_start * 4).min(height);
            let y1 = (by_end * 4).min(height);
            let (cur, tail) = rest.split_at_mut((y1 - y0) * width);
            rest = tail;
            s.spawn(move || {
                decode_etc1_band(data, width, height, blocks_x, by_start, by_end, cur);
            });
            by_start = by_end;
        }
    });
    pixels
}

/// Decodes block-rows [by_start, by_end) into `pixels`, which covers pixel
/// rows starting at `y0` (= min(by_start * 4, height)). Indexing into `data`
/// stays absolute; only pixel writes are band-relative.
fn decode_etc1_band(
    data: &[u8],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
) {
    let y0 = (by_start * 4).min(height);
    let mut block = [0u32; 16];
    for by in by_start..by_end {
        for bx in 0..blocks_x {
            let off = (by * blocks_x + bx) * 8;
            if off + 8 > data.len() {
                return;
            }
            let mut b = [0u8; 8];
            b.copy_from_slice(&data[off..off + 8]);
            decode_block(&b, &mut block);
            for py in 0..4 {
                for px in 0..4 {
                    let (x, y) = (bx * 4 + px, by * 4 + py);
                    if x < width && y < height {
                        pixels[(y - y0) * width + x] = block[py * 4 + px];
                    }
                }
            }
        }
    }
}

/// One subblock encode result: quantized base color (4 bits per channel),
/// chosen modifier table, and per-pixel level indices in gather order.
struct SubblockEnc {
    base4: (u32, u32, u32),
    table: u32,
    indices: [u8; 8],
}

/// Best flip candidate for one macroblock: score, flip bit, both subblock
/// encodings and their packed per-pixel modifiers.
struct FlipBest {
    score: u64,
    flip: u8,
    ea: SubblockEnc,
    eb: SubblockEnc,
    pa: [(u8, u8); 8],
    pb: [(u8, u8); 8],
}

/// One block split into two 8-pixel halves (subblocks) for a given
/// orientation; each pixel slot keeps its source (x, y) so index bits can be
/// packed back to absolute positions.
struct Halves {
    a: [(u32, u32, u32); 8],
    b: [(u32, u32, u32); 8],
    pa: [(u8, u8); 8],
    pb: [(u8, u8); 8],
}

/// Encodes one subblock (exactly 8 pixels): quantizes the mean color to 4
/// bits per channel, then picks the modifier table minimizing squared error.
///
/// Per-pixel error math is i32 against pre-clamped level colors: for a fixed
/// base color each level's `clamp(base + m)` is a constant, so the clamp is
/// hoisted out of the pixel loop (12 clamps per table instead of 96). Values
/// fit i32 (max per pixel 3*255^2 = 195075; sum over 8 pixels < 2^32). The
/// pixel-outer / level-inner structure keeps all state in registers —
/// measured faster than a level-outer "SIMD-friendly" layout on both x86_64
/// and typical aarch64 pipelines. Selection order and tie-breaks (strict `<`,
/// first level/table wins) match the original i64 version, so output is
/// bit-identical.
fn encode_subblock(px: &[(u32, u32, u32); 8]) -> SubblockEnc {
    let mean = (
        px.iter().map(|p| p.0).sum::<u32>() >> 3,
        px.iter().map(|p| p.1).sum::<u32>() >> 3,
        px.iter().map(|p| p.2).sum::<u32>() >> 3,
    );
    let base4 = (mean.0 >> 4, mean.1 >> 4, mean.2 >> 4);
    let base = (
        expand4(base4.0) as i32,
        expand4(base4.1) as i32,
        expand4(base4.2) as i32,
    );
    let mut best_err = u32::MAX;
    let mut best_t = 0u32;
    let mut best_indices = [0u8; 8];
    for (t, &(small, big)) in MODIFIERS.iter().enumerate() {
        let levels = [-big, -small, small, big];
        let mut lc = [(0i32, 0i32, 0i32); 4];
        for (li, &m) in levels.iter().enumerate() {
            lc[li] = (
                (base.0 + m).clamp(0, 255),
                (base.1 + m).clamp(0, 255),
                (base.2 + m).clamp(0, 255),
            );
        }
        let mut err = 0u32;
        let mut indices = [0u8; 8];
        for (i, &p) in px.iter().enumerate() {
            let (pr, pg, pb) = (p.0 as i32, p.1 as i32, p.2 as i32);
            let mut best_e = i32::MAX;
            let mut best_li = 0usize;
            for (li, &(lr, lg, lb)) in lc.iter().enumerate() {
                let dr = pr - lr;
                let dg = pg - lg;
                let db = pb - lb;
                let e = dr * dr + dg * dg + db * db;
                if e < best_e {
                    best_e = e;
                    best_li = li;
                }
            }
            indices[i] = best_li as u8;
            err += best_e as u32;
        }
        if err < best_err {
            best_err = err;
            best_t = t as u32;
            best_indices = indices;
        }
    }
    SubblockEnc {
        base4,
        table: best_t,
        indices: best_indices,
    }
}

/// Encodes a horizontal band of block-rows `[by_start, by_end)` into
/// `out[0..(by_end - by_start) * blocks_x * 8]`. Pure function of the shared
/// pixel buffer, so bands are safe to encode concurrently on disjoint slices.
fn encode_band(
    pixels: &[u32],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    out: &mut [u8],
) {
    let mut o = 0usize;
    for by in by_start..by_end {
        for bx in 0..blocks_x {
            // Gather the block's pixels (edge-clamped), as (r,g,b).
            let mut block = [(0u32, 0u32, 0u32); 16];
            for py in 0..4 {
                for px in 0..4 {
                    let x = (bx * 4 + px).min(width - 1);
                    let y = (by * 4 + py).min(height - 1);
                    let p = pixels[y * width + x];
                    block[py * 4 + px] = ((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
                }
            }
            // Split into two 8-pixel halves per orientation; each slot keeps
            // its source (x, y) so index bits can be packed directly.
            let halves = |flip: bool| -> Halves {
                let (mut a, mut bset) = ([(0, 0, 0); 8], [(0, 0, 0); 8]);
                let (mut pa, mut pb) = ([(0u8, 0u8); 8], [(0u8, 0u8); 8]);
                let (mut ia, mut ib) = (0usize, 0usize);
                for py in 0..4u8 {
                    for px in 0..4u8 {
                        let first = if flip { py < 2 } else { px < 2 };
                        if first {
                            a[ia] = block[py as usize * 4 + px as usize];
                            pa[ia] = (px, py);
                            ia += 1;
                        } else {
                            bset[ib] = block[py as usize * 4 + px as usize];
                            pb[ib] = (px, py);
                            ib += 1;
                        }
                    }
                }
                Halves { a, b: bset, pa, pb }
            };
            // Variance proxy preserved verbatim from the original encoder so
            // the flip choice (and thus the encoded bytes) stays identical.
            let var = |set: &[(u32, u32, u32); 8]| -> u64 {
                let m = (
                    set.iter().map(|p| p.0).sum::<u32>() / 8,
                    set.iter().map(|p| p.1).sum::<u32>() / 8,
                    set.iter().map(|p| p.2).sum::<u32>() / 8,
                );
                set.iter()
                    .map(|p| {
                        ((p.0 as i64 - m.0 as i64).pow(2)
                            + (p.1 as i64 - m.1 as i64).pow(2)
                            + (p.2 as i64 - m.2 as i64).pow(2)) as u64
                    })
                    .sum()
            };
            let mut best: Option<FlipBest> = None;
            for flip in [false, true] {
                let h = halves(flip);
                let ea = encode_subblock(&h.a);
                let eb = encode_subblock(&h.b);
                let score = var(&h.a) + var(&h.b);
                let better = best.as_ref().is_none_or(|b| score < b.score);
                if better {
                    best = Some(FlipBest {
                        score,
                        flip: flip as u8,
                        ea,
                        eb,
                        pa: h.pa,
                        pb: h.pb,
                    });
                }
            }
            let FlipBest { flip, ea, eb, pa, pb, .. } = best.unwrap();
            out[o] = (((ea.base4.0 << 4) | eb.base4.0) & 0xFF) as u8;
            out[o + 1] = (((ea.base4.1 << 4) | eb.base4.1) & 0xFF) as u8;
            out[o + 2] = (((ea.base4.2 << 4) | eb.base4.2) & 0xFF) as u8;
            out[o + 3] = ((ea.table as u8) << 5) | ((eb.table as u8) << 2) | flip;
            // Pack pixel index bits straight from the winning subblock
            // encodings: for a fixed base color + table this equals the old
            // per-pixel re-search (same math, same tie-break), bit k = x*4+y.
            let (mut msb, mut lsb) = (0u16, 0u16);
            for (&idx, &(x, y)) in ea.indices.iter().zip(pa.iter()) {
                let k = x as usize * 4 + y as usize;
                if idx & 2 != 0 {
                    msb |= 1 << (15 - k);
                }
                if idx & 1 != 0 {
                    lsb |= 1 << (15 - k);
                }
            }
            for (&idx, &(x, y)) in eb.indices.iter().zip(pb.iter()) {
                let k = x as usize * 4 + y as usize;
                if idx & 2 != 0 {
                    msb |= 1 << (15 - k);
                }
                if idx & 1 != 0 {
                    lsb |= 1 << (15 - k);
                }
            }
            out[o + 4] = ((msb >> 8) & 0xFF) as u8;
            out[o + 5] = (msb & 0xFF) as u8;
            out[o + 6] = ((lsb >> 8) & 0xFF) as u8;
            out[o + 7] = (lsb & 0xFF) as u8;
            o += 8;
        }
    }
}

/// Encodes ARGB pixels to ETC1 (individual mode; alpha ignored).
///
/// The per-pixel level search runs exactly once per subblock inside
/// `encode_subblock`; the winning orientation's indices are reused directly
/// for bit packing (the old encoder re-ran the whole search a second time).
/// Blocks are independent, so large images are split into horizontal block-row
/// bands encoded on worker threads (deterministic: every band writes a
/// disjoint, contiguous slice; results are identical to the serial path).
/// The flip choice still uses the original variance proxy and all tie-breaks
/// are preserved, so encoded output is bit-identical to the previous version.
pub fn encode_etc1(pixels: &[u32], width: usize, height: usize) -> Vec<u8> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut out = vec![0u8; blocks_x * blocks_y * 8];
    // Small textures: thread spawn overhead dominates, encode inline.
    if blocks_x * blocks_y < 1024 {
        encode_band(pixels, width, height, blocks_x, 0, blocks_y, &mut out);
        return out;
    }
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .min(16);
    // At least 4 block-rows per band so per-thread work stays >> spawn cost.
    let rows_per_band = blocks_y.div_ceil(workers).max(4);
    let chunk = rows_per_band * blocks_x * 8;
    std::thread::scope(|s| {
        for (band, chunk_out) in out.chunks_mut(chunk).enumerate() {
            let by_start = band * rows_per_band;
            let by_end = (by_start + rows_per_band).min(blocks_y);
            s.spawn(move || {
                encode_band(pixels, width, height, blocks_x, by_start, by_end, chunk_out);
            });
        }
    });
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn solid_color_roundtrip_exact() {
        // Chosen to be exactly representable: per channel it equals
        // expand4(base4) + 2 (the +small modifier of table 0):
        // 53 = 51+2, 104 = 102+2, 155 = 153+2.
        let px = vec![0xFF35_689Bu32; 16];
        let enc = encode_etc1(&px, 4, 4);
        assert_eq!(enc.len(), 8);
        let dec = decode_etc1(&enc, 4, 4);
        assert!(dec.iter().all(|&p| p == 0xFF35_689B), "got {:08x}", dec[0]);
    }

    #[test]
    fn gradient_roundtrip_close() {
        // Smooth horizontal gradient 0..255 in red, constant green/blue.
        let mut px = Vec::new();
        for _y in 0..8 {
            for x in 0..8 {
                px.push(0xFF00_0000 | (((x * 36) as u32) << 16) | 0x8080);
            }
        }
        let enc = encode_etc1(&px, 8, 8);
        let dec = decode_etc1(&enc, 8, 8);
        let mut total = 0u64;
        for (a, b) in px.iter().zip(dec.iter()) {
            for shift in [16, 8, 0] {
                total += (((a >> shift) & 0xFF) as i64 - ((b >> shift) & 0xFF) as i64).unsigned_abs();
            }
        }
        let mean = total as f64 / (px.len() * 3) as f64;
        assert!(mean < 14.0, "mean abs err {mean}");
    }

    #[test]
    fn decode_differential_block_known() {
        // Hand-built differential block: base (31,0,0) delta (+0,0,0),
        // tables 0/0, flip=0, all indices 2 (+small = +2): expect ~(255,2,2).
        let mut b = [0u8; 8];
        b[0] = 31 << 3; // r=31, dr=0 (3-bit signed raw 0)
        b[1] = 0; // g=0, dg=0
        b[2] = 0; // b=0, db=0
        b[3] = 0b0000_0010; // t1=0,t2=0, diff=1, flip=0
        // indices all '2' => msb bits all 1, lsb all 0
        b[4] = 0xFF;
        b[5] = 0xFF;
        let px = decode_etc1(&b, 4, 4);
        assert!(px.iter().all(|&p| p == 0xFFFF_0202), "got {:08x}", px[0]);
    }
}
