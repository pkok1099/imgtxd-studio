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

/// Unpacked per-block state shared by the scalar and SIMD pixel selection
/// loops: the two 4-color subblock palettes, the 16-bit per-pixel modifier
/// index bit planes (msb = high bit, lsb = low bit, pixel k = x*4 + y at bit
/// 15-k) and the subblock split flag.
struct BlockColors {
    pal_a: [u32; 4],
    pal_b: [u32; 4],
    msb: u16,
    lsb: u16,
    flip: bool,
}

/// Color unpack half of `decode_block`, identical math to the previous
/// monolithic implementation (diff/individual modes, packed palettes).
fn block_colors(b: &[u8; 8]) -> BlockColors {
    let diff = (b[3] >> 1) & 1 == 1;
    let flip = b[3] & 1 == 1;
    let (c1, c2);
    if diff {
        let r = (b[0] >> 3) as i32;
        let g = (b[1] >> 3) as i32;
        let bl = (b[2] >> 3) as i32;
        let (dr, dg, db) = (
            signed3(b[0] as u32),
            signed3(b[1] as u32),
            signed3(b[2] as u32),
        );
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
    BlockColors {
        pal_a: packed_palette(c1, t1),
        pal_b: packed_palette(c2, t2),
        msb: ((b[4] as u16) << 8) | b[5] as u16,
        lsb: ((b[6] as u16) << 8) | b[7] as u16,
        flip,
    }
}

/// Byte i of each entry is bit (7-i) of the byte, so the entries for a u16
/// bit plane's high and low byte concatenated yield, at byte k, the plane's
/// bit 15-k (k-order pixel index bit) as a 0/1 byte. Compile-time built.
/// x86-only: the SSSE3 decode kernel is the only consumer (kept out of the
/// aarch64 build so the table does not trip dead-code warnings there).
#[cfg(target_arch = "x86_64")]
const IDX01: [u64; 256] = {
    let mut t = [0u64; 256];
    let mut b = 0usize;
    while b < 256 {
        let mut v = 0u64;
        let mut i = 0usize;
        while i < 8 {
            v |= (((b as u64) >> (7 - i)) & 1) << (8 * i);
            i += 1;
        }
        t[b] = v;
        b += 1;
    }
    t
};

/// pshufb selector mapping k-order index bytes (byte k = pixel x*4 + y) to
/// row-major output order (byte p = pixel y*4 + x): byte p reads byte
/// k(p) = (p % 4) * 4 + p / 4. x86-only (see `IDX01`).
#[cfg(target_arch = "x86_64")]
const PSHUF_K2P: [u8; 16] = [0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15];

/// Subblock-palette offset per row-major output pixel p (0 = subblock A,
/// 4 = B) for the scalar combined-palette lookup; flip=0 splits columns
/// (first = x < 2), flip=1 splits rows (first = y < 2).
const SEL_A_FLIP0: [u8; 16] = [0, 0, 4, 4, 0, 0, 4, 4, 0, 0, 4, 4, 0, 0, 4, 4];
const SEL_A_FLIP1: [u8; 16] = [0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 4, 4, 4, 4, 4, 4];

/// pshufb zero masks (high bit set) selecting which of the two subblock
/// palettes contributes each pixel: subblock A's lookup uses ZERO_A_* (so
/// B-only pixels read 0) and subblock B's uses ZERO_B_*; the OR of the two
/// results is exactly the selected palette color. x86-only (see `IDX01`).
#[cfg(target_arch = "x86_64")]
const ZERO_A_FLIP0: [u8; 16] = [
    0, 0, 0x80, 0x80, 0, 0, 0x80, 0x80, 0, 0, 0x80, 0x80, 0, 0, 0x80, 0x80,
];
#[cfg(target_arch = "x86_64")]
const ZERO_B_FLIP0: [u8; 16] = [
    0x80, 0x80, 0, 0, 0x80, 0x80, 0, 0, 0x80, 0x80, 0, 0, 0x80, 0x80, 0, 0,
];
#[cfg(target_arch = "x86_64")]
const ZERO_A_FLIP1: [u8; 16] = [
    0, 0, 0, 0, 0, 0, 0, 0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
];
#[cfg(target_arch = "x86_64")]
const ZERO_B_FLIP1: [u8; 16] = [
    0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0, 0, 0, 0, 0, 0, 0, 0,
];

/// Decodes one block via the best available pixel-selection kernel.
/// `simd` must equal `codec::simd::tier() >= 2` on x86_64 (callers hoist the
/// feature check out of the block loops); it is ignored on other targets.
#[inline]
#[cfg_attr(not(target_arch = "x86_64"), allow(unused_variables))]
fn decode_block_selected(simd: bool, b: &[u8; 8], out: &mut [u32; 16]) {
    #[cfg(target_arch = "x86_64")]
    {
        if simd {
            // SAFETY: SSSE3 runtime-detected (`tier() >= 2`); register-only
            // kernel over the caller's 8-byte block, writing `out` in full.
            unsafe { decode_block_ssse3(b, out) };
            return;
        }
    }
    decode_block_scalar(b, out)
}

fn decode_block(b: &[u8; 8], out: &mut [u32; 16]) {
    #[cfg(target_arch = "x86_64")]
    let simd = crate::codec::simd::tier() >= 2;
    #[cfg(not(target_arch = "x86_64"))]
    let simd = false;
    decode_block_selected(simd, b, out)
}

/// Portable branchless pixel selection: one combined 8-entry palette plus a
/// constant per-pixel subblock offset replaces the previous per-pixel branch
/// and palette-pointer select. Bit-identical output by construction (same
/// `block_colors` unpack, same index extraction, same palette math).
fn decode_block_scalar(b: &[u8; 8], out: &mut [u32; 16]) {
    let c = block_colors(b);
    let mut pal8 = [0u32; 8];
    pal8[..4].copy_from_slice(&c.pal_a);
    pal8[4..].copy_from_slice(&c.pal_b);
    let sel8: &[u8; 16] = if c.flip { &SEL_A_FLIP1 } else { &SEL_A_FLIP0 };
    let (msb, lsb) = (c.msb, c.lsb);
    for p in 0..16 {
        let x = p & 3;
        let y = p >> 2;
        let bit = 15 - (x * 4 + y);
        let idx = ((((msb >> bit) & 1) << 1) | ((lsb >> bit) & 1)) as usize;
        out[p] = pal8[idx | sel8[p] as usize];
    }
}

/// SSSE3 pixel-selection kernel for one ETC1 block, bit-identical to
/// `decode_block_scalar`: the 16 per-pixel palette lookups become four
/// `pshufb` lane selections against the two subblock palettes, with the
/// index bit planes expanded via the compile-time `IDX01` table and
/// transposed to output order by `PSHUF_K2P`. Differential-tested in
/// `decode_block_simd_matches_scalar` over random and adversarial blocks.
///
/// # Safety
/// SSSE3 must be runtime-detected (`tier() >= 2`) by the caller. All math is
/// register-only; loads (`loadu`) touch only the caller's 8-byte block,
/// read-only constant arrays and the 16-byte stack palettes; stores
/// (`storeu`) write exactly the 64 bytes of `out: [u32; 16]`.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "ssse3")]
unsafe fn decode_block_ssse3(b: &[u8; 8], out: &mut [u32; 16]) {
    use core::arch::x86_64::*;
    let c = block_colors(b);
    let msb = c.msb as u32;
    let lsb = c.lsb as u32;
    // Index bit planes as 0/1 bytes in k order (high byte entry feeds k=0..7).
    let mv = _mm_set_epi64x(
        IDX01[(msb & 0xFF) as usize] as i64,
        IDX01[(msb >> 8) as usize] as i64,
    );
    let lv = _mm_set_epi64x(
        IDX01[(lsb & 0xFF) as usize] as i64,
        IDX01[(lsb >> 8) as usize] as i64,
    );
    // idx(k) = 2 * msb_bit + lsb_bit; bytes stay within 0..3 (no carry).
    let idx_k = _mm_add_epi8(_mm_add_epi8(mv, mv), lv);
    // Transpose to row-major p order, then scale to 4-byte palette offsets
    // (idx <= 3, so the 16-bit left shift never spills across bytes).
    let k2p = _mm_loadu_si128(PSHUF_K2P.as_ptr().cast());
    let base = _mm_slli_epi16(_mm_shuffle_epi8(idx_k, k2p), 2);
    let pa = _mm_loadu_si128(c.pal_a.as_ptr().cast());
    let pb = _mm_loadu_si128(c.pal_b.as_ptr().cast());
    let (zero_a, zero_b) = if c.flip {
        (
            _mm_loadu_si128(ZERO_A_FLIP1.as_ptr().cast()),
            _mm_loadu_si128(ZERO_B_FLIP1.as_ptr().cast()),
        )
    } else {
        (
            _mm_loadu_si128(ZERO_A_FLIP0.as_ptr().cast()),
            _mm_loadu_si128(ZERO_B_FLIP0.as_ptr().cast()),
        )
    };
    // Byte j of every pixel = palette byte (idx*4 + j); exactly one of the
    // two pshufb lookups survives each selector (the other is zeroed by the
    // 0x80 mask), so the OR merges the selected subblock palette.
    macro_rules! lane {
        ($j:expr) => {{
            let jb = _mm_set1_epi8($j);
            let sa = _mm_or_si128(_mm_or_si128(base, jb), zero_a);
            let sb = _mm_or_si128(_mm_or_si128(base, jb), zero_b);
            _mm_or_si128(_mm_shuffle_epi8(pa, sa), _mm_shuffle_epi8(pb, sb))
        }};
    }
    let (o0, o1, o2, o3) = (lane!(0), lane!(1), lane!(2), lane!(3));
    // Interleave the four per-byte lanes into row-major u32 pixels:
    // pixels 0..3 = output row 0, ..., pixels 12..15 = output row 3.
    let ab_lo = _mm_unpacklo_epi8(o0, o1);
    let ab_hi = _mm_unpackhi_epi8(o0, o1);
    let cd_lo = _mm_unpacklo_epi8(o2, o3);
    let cd_hi = _mm_unpackhi_epi8(o2, o3);
    let ptr = out.as_mut_ptr().cast::<__m128i>();
    _mm_storeu_si128(ptr, _mm_unpacklo_epi16(ab_lo, cd_lo));
    _mm_storeu_si128(ptr.add(1), _mm_unpackhi_epi16(ab_lo, cd_lo));
    _mm_storeu_si128(ptr.add(2), _mm_unpacklo_epi16(ab_hi, cd_hi));
    _mm_storeu_si128(ptr.add(3), _mm_unpackhi_epi16(ab_hi, cd_hi));
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
    let workers = crate::parallel::inner_workers(4);
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
    // Whole-band vectorized path (pass-9): one SSSE3/NEON kernel decodes
    // every block of the band directly to the band's pixel rows, skipping
    // the per-block palette build entirely. Bit-identical to the per-block
    // paths below (differential-tested over random blocks, dimensions,
    // truncated streams, and exhaustive table pairs); falls through to the
    // per-block fast path when no accelerated kernel exists on this platform.
    if width.is_multiple_of(4)
        && height.is_multiple_of(4)
        && crate::codec::simd::etc1::etc1_band(
            data,
            width,
            blocks_x,
            by_start,
            by_end,
            pixels,
            y0 * width,
        )
    {
        return;
    }
    // Aligned fast path: every block covers a full 4x4 pixel group, block
    // bytes are consumed in order without truncation, and each decoded block
    // row is one contiguous 4-pixel store. Bit-identical to the general path
    // below (same per-block decoder, same write positions) — only the
    // per-pixel bounds checks and per-block byte copies disappear, which is
    // where most of the decode wall-clock used to live.
    if width.is_multiple_of(4)
        && height.is_multiple_of(4)
        && data.len() >= blocks_x * height.div_ceil(4) * 8
    {
        let blocks: &[[u8; 8]] = data.as_chunks::<8>().0;
        #[cfg(target_arch = "x86_64")]
        let use_simd = crate::codec::simd::tier() >= 2;
        #[cfg(not(target_arch = "x86_64"))]
        let use_simd = false;
        for by in by_start..by_end {
            let local_y = (by - by_start) * 4;
            for bx in 0..blocks_x {
                decode_block_selected(use_simd, &blocks[by * blocks_x + bx], &mut block);
                let base = local_y * width + bx * 4;
                let row = &mut pixels[base..base + 4];
                row.copy_from_slice(&block[0..4]);
                let row = &mut pixels[base + width..base + width + 4];
                row.copy_from_slice(&block[4..8]);
                let row = &mut pixels[base + 2 * width..base + 2 * width + 4];
                row.copy_from_slice(&block[8..12]);
                let row = &mut pixels[base + 3 * width..base + 3 * width + 4];
                row.copy_from_slice(&block[12..16]);
            }
        }
        return;
    }
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
/// Dispatches to the NEON vectorized per-pixel distance search on aarch64
/// (the 4 candidate levels ride in i32x4 lanes; the argmin over the extracted
/// lane values replays the scalar loop's exact order and strict-< tie-break,
/// so the chosen table and indices are bit-identical). x86_64 keeps the
/// scalar form: on typical desktop Xeons `pmulld` is slower than the scalar
/// `imul` chain LLVM already schedules well (measured, see bench).
fn encode_subblock(px: &[(u32, u32, u32); 8]) -> SubblockEnc {
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: NEON baseline; register-only math over the 8-pixel input.
        unsafe {
            return encode_subblock_neon(px);
        }
    }
    // Not `any(test, ...)`: on aarch64 the NEON branch above diverges, so
    // compiling this line in test builds too made it unreachable code (the
    // only pre-existing aarch64-test warning). The scalar form stays
    // available to tests on aarch64 via its own cfg below.
    #[cfg(not(target_arch = "aarch64"))]
    encode_subblock_scalar(px)
}

#[cfg(any(test, not(target_arch = "aarch64")))]
fn encode_subblock_scalar(px: &[(u32, u32, u32); 8]) -> SubblockEnc {
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

/// NEON variant of `encode_subblock_scalar` (i32x4 lanes, same ordering and
/// tie-breaks as the scalar loop — see `encode_subblock_avx2`).
///
/// # Safety
/// NEON is aarch64-baseline. Register-only math over the caller's slice.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn encode_subblock_neon(px: &[(u32, u32, u32); 8]) -> SubblockEnc {
    use core::arch::aarch64::*;
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
        let lc_r = [lc[0].0, lc[1].0, lc[2].0, lc[3].0];
        let lc_g = [lc[0].1, lc[1].1, lc[2].1, lc[3].1];
        let lc_b = [lc[0].2, lc[1].2, lc[2].2, lc[3].2];
        let vr = vld1q_s32(lc_r.as_ptr());
        let vg = vld1q_s32(lc_g.as_ptr());
        let vb = vld1q_s32(lc_b.as_ptr());
        let mut err = 0u32;
        let mut indices = [0u8; 8];
        for (i, &p) in px.iter().enumerate() {
            let pr = vdupq_n_s32(p.0 as i32);
            let pg = vdupq_n_s32(p.1 as i32);
            let pb = vdupq_n_s32(p.2 as i32);
            let dr = vsubq_s32(vr, pr);
            let dg = vsubq_s32(vg, pg);
            let db = vsubq_s32(vb, pb);
            let e = vaddq_s32(
                vaddq_s32(vmulq_s32(dr, dr), vmulq_s32(dg, dg)),
                vmulq_s32(db, db),
            );
            let mut es = [0i32; 4];
            vst1q_s32(es.as_mut_ptr(), e);
            let mut best_e = i32::MAX;
            let mut best_li = 0usize;
            for (li, &ev) in es.iter().enumerate() {
                if ev < best_e {
                    best_e = ev;
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
            // Both orientations are always scored; the lower-variance one
            // wins and ties keep the unflipped split (strict <, same as the
            // original encoder). The losing orientation's subblock encodings
            // are never used, so the flipped half is only encoded when its
            // score actually wins — no Option/unwrap needed.
            let mut best = {
                let h = halves(false);
                FlipBest {
                    score: var(&h.a) + var(&h.b),
                    flip: 0,
                    ea: encode_subblock(&h.a),
                    eb: encode_subblock(&h.b),
                    pa: h.pa,
                    pb: h.pb,
                }
            };
            {
                let h = halves(true);
                let score = var(&h.a) + var(&h.b);
                if score < best.score {
                    best = FlipBest {
                        score,
                        flip: 1,
                        ea: encode_subblock(&h.a),
                        eb: encode_subblock(&h.b),
                        pa: h.pa,
                        pb: h.pb,
                    };
                }
            }
            let FlipBest {
                flip,
                ea,
                eb,
                pa,
                pb,
                ..
            } = best;
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
    let workers = crate::parallel::inner_workers(4);
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
                total +=
                    (((a >> shift) & 0xFF) as i64 - ((b >> shift) & 0xFF) as i64).unsigned_abs();
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

    /// The vectorized encode path must pick the same table and indices as
    /// the scalar reference for every random subblock (bit-identical bytes).
    #[test]
    fn encode_subblock_simd_matches_scalar() {
        struct Rng(u64);
        impl Rng {
            fn next_u32(&mut self) -> u32 {
                let mut x = self.0;
                x ^= x >> 12;
                x ^= x << 25;
                x ^= x >> 27;
                self.0 = x;
                (x.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 32) as u32
            }
        }
        let mut rng = Rng(0xE71_C0DE_FEED_FACE);
        for _ in 0..20_000 {
            let px: [(u32, u32, u32); 8] = core::array::from_fn(|_| {
                (
                    rng.next_u32() & 0xFF,
                    rng.next_u32() & 0xFF,
                    rng.next_u32() & 0xFF,
                )
            });
            let want = encode_subblock_scalar(&px);
            let got = encode_subblock(&px);
            assert_eq!(got.base4, want.base4);
            assert_eq!(got.table, want.table, "table for {px:?}");
            assert_eq!(got.indices, want.indices, "indices for {px:?}");
        }
    }

    /// The SSSE3 decode path must be bit-identical to the scalar reference
    /// for random blocks plus every diff/individual x flip x table-index
    /// combination (the pixel-selection kernel replaces only the 16 lookups;
    /// the shared `block_colors` unpack is exercised by both paths).
    #[test]
    fn decode_block_simd_matches_scalar() {
        struct Rng(u64);
        impl Rng {
            fn next_u32(&mut self) -> u32 {
                let mut x = self.0;
                x ^= x >> 12;
                x ^= x << 25;
                x ^= x >> 27;
                self.0 = x;
                (x.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 32) as u32
            }
        }
        let mut rng = Rng(0xDE_C0_DE_5E_ED_F0_0D);
        let mut cases: Vec<[u8; 8]> = Vec::new();
        for diff in 0..2u8 {
            for flip in 0..2u8 {
                for t in 0..8u8 {
                    let mut b = [0u8; 8];
                    b[3] = (t << 5) | (t << 2) | (diff << 1) | flip;
                    b[4] = 0xFF; // msb all ones
                    b[6] = 0x55; // lsb alternating
                    cases.push(b);
                    let mut c = b;
                    c[4] = 0x00; // msb all zeros
                    c[6] = 0xAA;
                    cases.push(c);
                }
            }
        }
        while cases.len() < 21_000 {
            let mut b = [0u8; 8];
            for slot in b.iter_mut() {
                *slot = rng.next_u32() as u8;
            }
            cases.push(b);
        }
        for b in &cases {
            let mut want = [0u32; 16];
            decode_block_scalar(b, &mut want);
            let mut got = [0u32; 16];
            decode_block(b, &mut got);
            assert_eq!(got, want, "decode mismatch for block {b:02x?}");
        }
    }

    /// Pins the orientation selection of `encode_band`: the split with the
    /// lower total variance wins, ties keep the unflipped encoding.
    #[test]
    fn flip_selection_prefers_lower_variance_orientation() {
        // Left columns constant blue / right columns constant red-ish: the
        // column split yields two zero-variance halves and must win (flip=0).
        let mut px = Vec::new();
        for _y in 0..4 {
            for x in 0..4 {
                px.push(if x < 2 { 0xFF0000FF } else { 0xFF000080 });
            }
        }
        let enc = encode_etc1(&px, 4, 4);
        assert_eq!(
            enc[3] & 1,
            0,
            "column split must win, flip bit {:b}",
            enc[3]
        );

        // Same colors stacked by rows: the row split wins, flip bit = 1.
        let mut px = Vec::new();
        for y in 0..4 {
            for _x in 0..4 {
                px.push(if y < 2 { 0xFF0000FF } else { 0xFF000080 });
            }
        }
        let enc = encode_etc1(&px, 4, 4);
        assert_eq!(enc[3] & 1, 1, "row split must win, flip bit {:b}", enc[3]);
    }
}
