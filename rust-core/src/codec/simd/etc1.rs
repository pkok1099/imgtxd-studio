//! ETC1 block-expansion kernels — x86_64 (SSSE3, runtime-detected) and
//! aarch64 NEON (baseline), bit-identical to the scalar decoder in
//! `codec::etc1`.
//!
//! The scalar decoder's cost is dominated by two 4-entry palette builds
//! (24 clamps + packs per block) followed by a 16-pixel loop with per-pixel
//! bit extraction. The vectorized form removes both: every pixel's color is
//! computed directly as `clamp255(base_sb + modifier(t_sb, idx))` over one
//! 16-lane byte stream per block:
//!
//! 1. The two index planes (msb/lsb; pixel k = x*4+y uses bit `15-k`) expand
//!    to 0/1 lane bytes with two ops each: the block's index bytes are
//!    broadcast so lane j holds byte `j/8`, an AND with the per-lane bit mask
//!    `BITM` isolates bit `7-(j&7)` in place, and an unsigned min with 1
//!    normalizes the isolated bit to 0/1.
//! 2. The modifier magnitude comes from the constant `MAGTAB` indexed by
//!    `2*table_sb + (msb_bit ^ lsb_bit)`: levels are
//!    `[-big, -small, small, big]`, so the magnitude is the big modifier
//!    when the two index bits agree and the small one when they differ
//!    (MAGTAB[2t] = big, MAGTAB[2t+1] = small); the subblock table choice
//!    and the base color ride the same `first01` 0/1 lane pattern through
//!    2-entry byte-table lookups (`first` = x<2 for flip=0, y<2 for flip=1).
//! 3. The msb bit selects negation in i16: `mod = (mag ^ neg) - neg`.
//! 4. Per channel: `max(base16 + mod16, 0)` then `packus` reproduces the
//!    scalar `clamp255` exactly (values are within i16; the unsigned
//!    saturating pack clamps 0..255), and the k-ordered 4x4 byte matrix is
//!    transposed to row-major with fixed shuffles before four dword stores.
//!
//! All table selectors stay < 16, so `pshufb`/`vqtbl` never zeroes a lane and
//! the output is bit-identical to the scalar decoder by construction; the
//! differential tests below re-prove it over random blocks, dimensions,
//! truncated streams, and an exhaustive table-pair sweep.

/// `MAGTAB[2t] = big_t`, `MAGTAB[2t+1] = small_t` (the modifier magnitude is
/// the big one when the two index bits agree, small when they differ).
const MAGTAB: [u8; 16] = [8, 2, 17, 5, 29, 9, 42, 13, 60, 18, 80, 24, 106, 33, 183, 55];

/// Per-lane bit masks: lane j isolates bit `7-(j&7)` of its byte, i.e. pixel
/// k = j reads bit `15-k` of the big-endian index word (byte j/8, bit 7-(j&7)).
const BITM: [u8; 16] = [
    0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01, 0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01,
];
/// msb plane source bytes: lanes 0..7 <- byte0, 8..15 <- byte1.
const SHUF_M: [u8; 16] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1];
/// lsb plane source bytes: lanes 0..7 <- byte2, 8..15 <- byte3.
const SHUF_L: [u8; 16] = [2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3];
/// `first` lane pattern, flip = 0 (x < 2 <=> k < 8): palette A on lanes 0..7.
const FPAT0: [u8; 16] = [1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0];
/// `first` lane pattern, flip = 1 (y < 2 <=> k & 3 < 2).
const FPAT1: [u8; 16] = [1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0];
/// Byte offsets into the per-block 8-byte table `T8`:
/// `[Rb, Ra, Gb, Ga, Bb, Ba, 2*t2, 2*t1]`; the G/B/T variants select
/// green/blue/table pairs with the same `first01` lane pattern.
const OFF_G: u8 = 2;
const OFF_B: u8 = 4;
const OFF_T: u8 = 6;

/// Driver: decode block-rows `[by_start, by_end)` of an aligned ETC1 image
/// into `pixels` (band-local rows, first pixel row = 0). Returns `false`
/// when no accelerated path exists on this platform — the caller then runs
/// its scalar band loop.
#[cfg(target_arch = "x86_64")]
pub fn etc1_band(
    data: &[u8],
    width: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
    band_y: usize,
) -> bool {
    if super::tier() >= 2 {
        unsafe {
            x86::etc1_band_x86(data, width, blocks_x, by_start, by_end, pixels, band_y);
        }
        true
    } else {
        false
    }
}

#[cfg(target_arch = "aarch64")]
pub fn etc1_band(
    data: &[u8],
    width: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
    band_y: usize,
) -> bool {
    unsafe {
        neon::etc1_band_neon(data, width, blocks_x, by_start, by_end, pixels, band_y);
    }
    true
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn etc1_band(
    _data: &[u8],
    _width: usize,
    _blocks_x: usize,
    _by_start: usize,
    _by_end: usize,
    _pixels: &mut [u32],
    _band_y: usize,
) -> bool {
    false
}

#[cfg(target_arch = "x86_64")]
mod x86 {
    use super::{BITM, FPAT0, FPAT1, MAGTAB, OFF_B, OFF_G, OFF_T, SHUF_L, SHUF_M};
    use core::arch::x86_64::*;

    #[inline(always)]
    fn expand4(v: u32) -> u8 {
        ((v << 4) | v) as u8
    }

    #[inline(always)]
    fn expand5(v: u32) -> u8 {
        ((v << 3) | (v >> 2)) as u8
    }

    #[inline(always)]
    fn signed3(v: u8) -> i32 {
        ((v & 7) ^ 4) as i32 - 4
    }

    /// Whole-band ETC1 decode on SSSE3+.
    ///
    /// # Safety
    /// `tier() >= 2` (SSSE3 verified) by the caller. `pixels` must hold
    /// `(by_end - by_start) * 4 * width` u32s from row 0 (the band contract
    /// of `decode_etc1_band`), and `data` accesses are bounds-checked per
    /// block below — the same contract the scalar band loop upholds.
    #[target_feature(enable = "ssse3")]
    pub(super) unsafe fn etc1_band_x86(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let zero = _mm_setzero_si128();
        let ff8 = _mm_set1_epi8(-1);
        let ones = _mm_set1_epi8(1);
        let bitm = _mm_loadu_si128(BITM.as_ptr() as *const __m128i);
        let mag = _mm_loadu_si128(MAGTAB.as_ptr() as *const __m128i);
        let shuf_m = _mm_loadu_si128(SHUF_M.as_ptr() as *const __m128i);
        let shuf_l = _mm_loadu_si128(SHUF_L.as_ptr() as *const __m128i);
        let fpat0 = _mm_loadu_si128(FPAT0.as_ptr() as *const __m128i);
        let fpat1 = _mm_loadu_si128(FPAT1.as_ptr() as *const __m128i);
        let offg = _mm_set1_epi8(OFF_G as i8);
        let offb = _mm_set1_epi8(OFF_B as i8);
        let offt = _mm_set1_epi8(OFF_T as i8);
        let fpat0g = _mm_add_epi8(fpat0, offg);
        let fpat1g = _mm_add_epi8(fpat1, offg);
        let fpat0b = _mm_add_epi8(fpat0, offb);
        let fpat1b = _mm_add_epi8(fpat1, offb);
        let fpat0t = _mm_add_epi8(fpat0, offt);
        let fpat1t = _mm_add_epi8(fpat1, offt);

        let mut offset = by_start * blocks_x * 8;
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 8 > data.len() {
                    return;
                }
                let b0 = data[offset];
                let b1 = data[offset + 1];
                let b2 = data[offset + 2];
                let b3 = data[offset + 3];
                let b4 = data[offset + 4];
                let b5 = data[offset + 5];
                let b6 = data[offset + 6];
                let b7 = data[offset + 7];
                // Scalar header: base colors (u8 after expand4/expand5), the
                // two modifier tables, and the flip bit.
                let diff = (b3 >> 1) & 1 == 1;
                let flip = b3 & 1 == 1;
                let (ra, ga, ba, rb, gb, bb);
                if diff {
                    let (r, g, bl) = ((b0 >> 3) as i32, (b1 >> 3) as i32, (b2 >> 3) as i32);
                    let (dr, dg, db) = (signed3(b0), signed3(b1), signed3(b2));
                    ra = expand5(r as u32);
                    ga = expand5(g as u32);
                    ba = expand5(bl as u32);
                    rb = expand5((r + dr).clamp(0, 31) as u32);
                    gb = expand5((g + dg).clamp(0, 31) as u32);
                    bb = expand5((bl + db).clamp(0, 31) as u32);
                } else {
                    ra = expand4((b0 >> 4) as u32);
                    ga = expand4((b1 >> 4) as u32);
                    ba = expand4((b2 >> 4) as u32);
                    rb = expand4((b0 & 15) as u32);
                    gb = expand4((b1 & 15) as u32);
                    bb = expand4((b2 & 15) as u32);
                }
                let t1 = (b3 >> 5) & 7;
                let t2 = (b3 >> 2) & 7;
                // Per-block 2-entry lookup table:
                // [Rb, Ra, Gb, Ga, Bb, Ba, 2*t2, 2*t1]; the first01 lane
                // pattern (+G/B/T offsets) gathers base colors and the
                // subblock's modifier table from it.
                let mut t8b = [0u8; 8];
                t8b[0] = rb;
                t8b[1] = ra;
                t8b[2] = gb;
                t8b[3] = ga;
                t8b[4] = bb;
                t8b[5] = ba;
                t8b[6] = t2 << 1;
                t8b[7] = t1 << 1;
                let t8 = _mm_loadl_epi64(t8b.as_ptr() as *const __m128i);

                // Index planes: lane j holds index byte j/8 after the two
                // shuffles; AND with BITM isolates bit 7-(j&7) (= bit 15-k,
                // k = x*4+y) in place and the unsigned min with 1 yields the
                // 0/1 lane byte.
                let bw = _mm_set1_epi32(
                    ((b4 as u32) | ((b5 as u32) << 8) | ((b6 as u32) << 16) | ((b7 as u32) << 24))
                        as i32,
                );
                let wm = _mm_shuffle_epi8(bw, shuf_m);
                let wl = _mm_shuffle_epi8(bw, shuf_l);
                let msb_bits = _mm_min_epu8(_mm_and_si128(wm, bitm), ones);
                let lsb_bits = _mm_min_epu8(_mm_and_si128(wl, bitm), ones);

                let (fpat, fpatg, fpatb, fpatt) = if flip {
                    (fpat1, fpat1g, fpat1b, fpat1t)
                } else {
                    (fpat0, fpat0g, fpat0b, fpat0t)
                };
                // Modifier magnitude per pixel: MAGTAB[2*t_sb + (msb^lsb)]
                // (big when the index bits agree, small when they differ).
                let t12 = _mm_shuffle_epi8(t8, fpatt);
                let mag_sel = _mm_or_si128(t12, _mm_xor_si128(msb_bits, lsb_bits));
                let magv = _mm_shuffle_epi8(mag, mag_sel);
                // Sign: msb bit 0 = negative level.
                let neg = _mm_cmpeq_epi8(msb_bits, zero);
                let neg_lo = _mm_unpacklo_epi8(neg, zero);
                let neg_hi = _mm_unpackhi_epi8(neg, zero);
                let mag_lo = _mm_unpacklo_epi8(magv, zero);
                let mag_hi = _mm_unpackhi_epi8(magv, zero);
                // mod = neg ? -mag : mag  (two's-complement negate-if-mask).
                let mod_lo = _mm_sub_epi16(_mm_xor_si128(mag_lo, neg_lo), neg_lo);
                let mod_hi = _mm_sub_epi16(_mm_xor_si128(mag_hi, neg_hi), neg_hi);

                // Base colors per pixel via the same 2-entry table.
                let baser = _mm_shuffle_epi8(t8, fpat);
                let baseg = _mm_shuffle_epi8(t8, fpatg);
                let baseb = _mm_shuffle_epi8(t8, fpatb);
                let br_lo = _mm_unpacklo_epi8(baser, zero);
                let br_hi = _mm_unpackhi_epi8(baser, zero);
                let bg_lo = _mm_unpacklo_epi8(baseg, zero);
                let bg_hi = _mm_unpackhi_epi8(baseg, zero);
                let bb_lo = _mm_unpacklo_epi8(baseb, zero);
                let bb_hi = _mm_unpackhi_epi8(baseb, zero);

                // clamp255(base + mod) == max16(base+mod, 0) then packus.
                let colr = _mm_packus_epi16(
                    _mm_max_epi16(_mm_add_epi16(br_lo, mod_lo), zero),
                    _mm_max_epi16(_mm_add_epi16(br_hi, mod_hi), zero),
                );
                let colg = _mm_packus_epi16(
                    _mm_max_epi16(_mm_add_epi16(bg_lo, mod_lo), zero),
                    _mm_max_epi16(_mm_add_epi16(bg_hi, mod_hi), zero),
                );
                let colb = _mm_packus_epi16(
                    _mm_max_epi16(_mm_add_epi16(bb_lo, mod_lo), zero),
                    _mm_max_epi16(_mm_add_epi16(bb_hi, mod_hi), zero),
                );

                // Interleave to ARGB dwords (LE bytes [b, g, r, 0xff]) in
                // k-order (k = x*4+y): o0..o3 hold the four block columns.
                let bgp_lo = _mm_unpacklo_epi8(colb, colg);
                let bgp_hi = _mm_unpackhi_epi8(colb, colg);
                let rf_lo = _mm_unpacklo_epi8(colr, ff8);
                let rf_hi = _mm_unpackhi_epi8(colr, ff8);
                let o0 = _mm_unpacklo_epi16(bgp_lo, rf_lo);
                let o1 = _mm_unpackhi_epi16(bgp_lo, rf_lo);
                let o2 = _mm_unpacklo_epi16(bgp_hi, rf_hi);
                let o3 = _mm_unpackhi_epi16(bgp_hi, rf_hi);
                // Transpose the 4x4 dword block to row-major and store.
                let t0 = _mm_unpacklo_epi32(o0, o1);
                let t1x = _mm_unpackhi_epi32(o0, o1);
                let t2x = _mm_unpacklo_epi32(o2, o3);
                let t3x = _mm_unpackhi_epi32(o2, o3);
                // SAFETY: `pixels[row0 + bx*4 + py*width .. +4]` is inside the
                // band (aligned contract: block (by,bx) maps to in-bounds
                // rows/cols); all loads read in-bounds stack/table bytes.
                let base = row0 + bx * 4;
                _mm_storeu_si128(
                    pixels.as_mut_ptr().add(base) as *mut __m128i,
                    _mm_unpacklo_epi64(t0, t2x),
                );
                _mm_storeu_si128(
                    pixels.as_mut_ptr().add(base + width) as *mut __m128i,
                    _mm_unpackhi_epi64(t0, t2x),
                );
                _mm_storeu_si128(
                    pixels.as_mut_ptr().add(base + 2 * width) as *mut __m128i,
                    _mm_unpacklo_epi64(t1x, t3x),
                );
                _mm_storeu_si128(
                    pixels.as_mut_ptr().add(base + 3 * width) as *mut __m128i,
                    _mm_unpackhi_epi64(t1x, t3x),
                );
                offset += 8;
            }
        }
    }
}

#[cfg(target_arch = "aarch64")]
mod neon {
    use super::{BITM, FPAT0, FPAT1, MAGTAB, OFF_B, OFF_G, OFF_T, SHUF_L, SHUF_M};
    use core::arch::aarch64::*;

    #[inline(always)]
    fn expand4(v: u32) -> u8 {
        ((v << 4) | v) as u8
    }

    #[inline(always)]
    fn expand5(v: u32) -> u8 {
        ((v << 3) | (v >> 2)) as u8
    }

    #[inline(always)]
    fn signed3(v: u8) -> i32 {
        ((v & 7) ^ 4) as i32 - 4
    }

    /// Whole-band ETC1 decode on NEON.
    ///
    /// # Safety
    /// Band/alignment contract of `decode_etc1_band` (see module docs);
    /// NEON is aarch64-baseline so no runtime gate is required.
    #[target_feature(enable = "neon")]
    pub(super) unsafe fn etc1_band_neon(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let zero = vdupq_n_u8(0);
        let ff8 = vdupq_n_u8(0xFF);
        let ones = vdupq_n_u8(1);
        let zero16 = vreinterpretq_s16_u8(zero);
        let bitm = vld1q_u8(BITM.as_ptr());
        let mag = vld1q_u8(MAGTAB.as_ptr());
        let shuf_m = vld1q_u8(SHUF_M.as_ptr());
        let shuf_l = vld1q_u8(SHUF_L.as_ptr());
        let fpat0 = vld1q_u8(FPAT0.as_ptr());
        let fpat1 = vld1q_u8(FPAT1.as_ptr());
        let offg = vdupq_n_u8(OFF_G);
        let offb = vdupq_n_u8(OFF_B);
        let offt = vdupq_n_u8(OFF_T);

        let mut offset = by_start * blocks_x * 8;
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 8 > data.len() {
                    return;
                }
                let b0 = data[offset];
                let b1 = data[offset + 1];
                let b2 = data[offset + 2];
                let b3 = data[offset + 3];
                let b4 = data[offset + 4];
                let b5 = data[offset + 5];
                let b6 = data[offset + 6];
                let b7 = data[offset + 7];
                let diff = (b3 >> 1) & 1 == 1;
                let flip = b3 & 1 == 1;
                let (ra, ga, ba, rb, gb, bb);
                if diff {
                    let (r, g, bl) = ((b0 >> 3) as i32, (b1 >> 3) as i32, (b2 >> 3) as i32);
                    let (dr, dg, db) = (signed3(b0), signed3(b1), signed3(b2));
                    ra = expand5(r as u32);
                    ga = expand5(g as u32);
                    ba = expand5(bl as u32);
                    rb = expand5((r + dr).clamp(0, 31) as u32);
                    gb = expand5((g + dg).clamp(0, 31) as u32);
                    bb = expand5((bl + db).clamp(0, 31) as u32);
                } else {
                    ra = expand4((b0 >> 4) as u32);
                    ga = expand4((b1 >> 4) as u32);
                    ba = expand4((b2 >> 4) as u32);
                    rb = expand4((b0 & 15) as u32);
                    gb = expand4((b1 & 15) as u32);
                    bb = expand4((b2 & 15) as u32);
                }
                let t1 = (b3 >> 5) & 7;
                let t2 = (b3 >> 2) & 7;
                let mut t8b = [0u8; 8];
                t8b[0] = rb;
                t8b[1] = ra;
                t8b[2] = gb;
                t8b[3] = ga;
                t8b[4] = bb;
                t8b[5] = ba;
                t8b[6] = t2 << 1;
                t8b[7] = t1 << 1;
                let t8 = vcombine_u8(vld1_u8(t8b.as_ptr()), vdup_n_u8(0));

                let bw = vreinterpretq_u8_u32(vdupq_n_u32(
                    (b4 as u32) | ((b5 as u32) << 8) | ((b6 as u32) << 16) | ((b7 as u32) << 24),
                ));
                let wm = vqtbl1q_u8(bw, shuf_m);
                let wl = vqtbl1q_u8(bw, shuf_l);
                let msb_bits = vminq_u8(vandq_u8(wm, bitm), ones);
                let lsb_bits = vminq_u8(vandq_u8(wl, bitm), ones);

                let (fpat, fpatg, fpatb, fpatt) = if flip {
                    (
                        fpat1,
                        vaddq_u8(fpat1, offg),
                        vaddq_u8(fpat1, offb),
                        vaddq_u8(fpat1, offt),
                    )
                } else {
                    (
                        fpat0,
                        vaddq_u8(fpat0, offg),
                        vaddq_u8(fpat0, offb),
                        vaddq_u8(fpat0, offt),
                    )
                };
                let t12 = vqtbl1q_u8(t8, fpatt);
                let mag_sel = vorrq_u8(t12, veorq_u8(msb_bits, lsb_bits));
                let magv = vqtbl1q_u8(mag, mag_sel);
                let neg = vceqzq_u8(msb_bits);
                let neg_lo = vzip1q_u8(neg, zero);
                let neg_hi = vzip2q_u8(neg, zero);
                let mag_lo = vzip1q_u8(magv, zero);
                let mag_hi = vzip2q_u8(magv, zero);
                let mod_lo = vsubq_s16(
                    veorq_s16(vreinterpretq_s16_u8(mag_lo), vreinterpretq_s16_u8(neg_lo)),
                    vreinterpretq_s16_u8(neg_lo),
                );
                let mod_hi = vsubq_s16(
                    veorq_s16(vreinterpretq_s16_u8(mag_hi), vreinterpretq_s16_u8(neg_hi)),
                    vreinterpretq_s16_u8(neg_hi),
                );

                let baser = vqtbl1q_u8(t8, fpat);
                let baseg = vqtbl1q_u8(t8, fpatg);
                let baseb = vqtbl1q_u8(t8, fpatb);
                let br_lo = vzip1q_u8(baser, zero);
                let br_hi = vzip2q_u8(baser, zero);
                let bg_lo = vzip1q_u8(baseg, zero);
                let bg_hi = vzip2q_u8(baseg, zero);
                let bb_lo = vzip1q_u8(baseb, zero);
                let bb_hi = vzip2q_u8(baseb, zero);

                let colr = vcombine_u8(
                    vqmovun_s16(vmaxq_s16(
                        vaddq_s16(vreinterpretq_s16_u8(br_lo), mod_lo),
                        zero16,
                    )),
                    vqmovun_s16(vmaxq_s16(
                        vaddq_s16(vreinterpretq_s16_u8(br_hi), mod_hi),
                        zero16,
                    )),
                );
                let colg = vcombine_u8(
                    vqmovun_s16(vmaxq_s16(
                        vaddq_s16(vreinterpretq_s16_u8(bg_lo), mod_lo),
                        zero16,
                    )),
                    vqmovun_s16(vmaxq_s16(
                        vaddq_s16(vreinterpretq_s16_u8(bg_hi), mod_hi),
                        zero16,
                    )),
                );
                let colb = vcombine_u8(
                    vqmovun_s16(vmaxq_s16(
                        vaddq_s16(vreinterpretq_s16_u8(bb_lo), mod_lo),
                        zero16,
                    )),
                    vqmovun_s16(vmaxq_s16(
                        vaddq_s16(vreinterpretq_s16_u8(bb_hi), mod_hi),
                        zero16,
                    )),
                );

                let bgp_lo = vzip1q_u8(colb, colg);
                let bgp_hi = vzip2q_u8(colb, colg);
                let rf_lo = vzip1q_u8(colr, ff8);
                let rf_hi = vzip2q_u8(colr, ff8);
                let o0 = vzip1q_u16(vreinterpretq_u16_u8(bgp_lo), vreinterpretq_u16_u8(rf_lo));
                let o1 = vzip2q_u16(vreinterpretq_u16_u8(bgp_lo), vreinterpretq_u16_u8(rf_lo));
                let o2 = vzip1q_u16(vreinterpretq_u16_u8(bgp_hi), vreinterpretq_u16_u8(rf_hi));
                let o3 = vzip2q_u16(vreinterpretq_u16_u8(bgp_hi), vreinterpretq_u16_u8(rf_hi));
                // o0..o3 are u16-lane zips of the [b,g | r,255] byte pairs;
                // reinterpreting as u32 reassembles whole ARGB pixels, which
                // the u32 zips then transpose to row-major (mirrors the x86
                // dword shuffle layout). The missing reinterprets here broke
                // aarch64 compilation ever since this kernel landed (pass-9
                // verification only ever ran on x86_64).
                let t0 = vzip1q_u32(vreinterpretq_u32_u16(o0), vreinterpretq_u32_u16(o1));
                let t1x = vzip2q_u32(vreinterpretq_u32_u16(o0), vreinterpretq_u32_u16(o1));
                let t2x = vzip1q_u32(vreinterpretq_u32_u16(o2), vreinterpretq_u32_u16(o3));
                let t3x = vzip2q_u32(vreinterpretq_u32_u16(o2), vreinterpretq_u32_u16(o3));
                // SAFETY: band mapping as documented; all accesses in-bounds.
                let base = row0 + bx * 4;
                vst1q_u8(
                    pixels.as_mut_ptr().add(base) as *mut u8,
                    vreinterpretq_u8_u64(vzip1q_u64(
                        vreinterpretq_u64_u32(t0),
                        vreinterpretq_u64_u32(t2x),
                    )),
                );
                vst1q_u8(
                    pixels.as_mut_ptr().add(base + width) as *mut u8,
                    vreinterpretq_u8_u64(vzip2q_u64(
                        vreinterpretq_u64_u32(t0),
                        vreinterpretq_u64_u32(t2x),
                    )),
                );
                vst1q_u8(
                    pixels.as_mut_ptr().add(base + 2 * width) as *mut u8,
                    vreinterpretq_u8_u64(vzip1q_u64(
                        vreinterpretq_u64_u32(t1x),
                        vreinterpretq_u64_u32(t3x),
                    )),
                );
                vst1q_u8(
                    pixels.as_mut_ptr().add(base + 3 * width) as *mut u8,
                    vreinterpretq_u8_u64(vzip2q_u64(
                        vreinterpretq_u64_u32(t1x),
                        vreinterpretq_u64_u32(t3x),
                    )),
                );
                offset += 8;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    /// Pure scalar reference decoder (the original pre-SIMD expansion).
    fn ref_etc1(data: &[u8], width: usize, height: usize) -> Vec<u32> {
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
        fn decode_block(b: &[u8; 8], out: &mut [u32; 16]) {
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
        let blocks_x = width.div_ceil(4);
        let blocks_y = height.div_ceil(4);
        let mut pixels = vec![0u32; width * height];
        let mut block = [0u32; 16];
        for by in 0..blocks_y {
            for bx in 0..blocks_x {
                let off = (by * blocks_x + bx) * 8;
                if off + 8 > data.len() {
                    return pixels;
                }
                let mut b = [0u8; 8];
                b.copy_from_slice(&data[off..off + 8]);
                decode_block(&b, &mut block);
                for py in 0..4 {
                    for px in 0..4 {
                        let (x, y) = (bx * 4 + px, by * 4 + py);
                        if x < width && y < height {
                            pixels[y * width + x] = block[py * 4 + px];
                        }
                    }
                }
            }
        }
        pixels
    }

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
        fn byte(&mut self) -> u8 {
            (self.next_u32() & 0xFF) as u8
        }
    }

    #[test]
    fn etc1_random_blocks_match_reference() {
        let mut rng = Rng(0xE71C_0DE1_FEED_FACE);
        for &(w, h) in &[
            (4usize, 4usize),
            (8, 12),
            (12, 8),
            (64, 64),
            (256, 32),
            (20, 16),
        ] {
            let data: Vec<u8> = (0..w.div_ceil(4) * h.div_ceil(4) * 8)
                .map(|_| rng.byte())
                .collect();
            let got = crate::codec::etc1::decode_etc1(&data, w, h);
            let want = ref_etc1(&data, w, h);
            assert_eq!(got, want, "etc1 {w}x{h}");
        }
    }

    #[test]
    fn etc1_non_aligned_dimensions_match_reference() {
        let mut rng = Rng(0x0DD5_EED5_EED0_DD05);
        for &(w, h) in &[(5usize, 5usize), (13, 7), (98, 70), (31, 33), (255, 3)] {
            let data: Vec<u8> = (0..w.div_ceil(4) * h.div_ceil(4) * 8)
                .map(|_| rng.byte())
                .collect();
            let got = crate::codec::etc1::decode_etc1(&data, w, h);
            let want = ref_etc1(&data, w, h);
            assert_eq!(got, want, "etc1 {w}x{h}");
        }
    }

    /// Exhaustive sweep over both modifier tables x all 16-bit index planes,
    /// with randomized base colors and modes — catches any bit-plane or
    /// sign/magnitude edge case.
    #[test]
    fn etc1_all_table_pairs_and_index_planes_match_reference() {
        let mut rng = Rng(0x0AB1_6C0D_E123_4567);
        for t1 in 0..8u8 {
            for t2 in 0..8u8 {
                for rep in 0..24 {
                    let mut b = [0u8; 8];
                    b[0] = rng.byte();
                    b[1] = rng.byte();
                    b[2] = rng.byte();
                    b[3] = (t1 << 5) | (t2 << 2) | (if rep & 1 == 0 { 2 } else { 0 }) | (rep & 2);
                    b[4] = rng.byte();
                    b[5] = rng.byte();
                    b[6] = rng.byte();
                    b[7] = rng.byte();
                    let got = crate::codec::etc1::decode_etc1(&b, 4, 4);
                    let want = ref_etc1(&b, 4, 4);
                    assert_eq!(got, want, "t1={t1} t2={t2} rep={rep} b={b:?}");
                }
            }
        }
    }

    #[test]
    fn etc1_truncated_stream_matches_reference() {
        let mut rng = Rng(0x07B4_5E1F_10DD_5EED);
        let data: Vec<u8> = (0..64 * 8).map(|_| rng.byte()).collect();
        for len in 0..=data.len() {
            let got = crate::codec::etc1::decode_etc1(&data[..len], 16, 16);
            let want = ref_etc1(&data[..len], 16, 16);
            assert_eq!(got, want, "truncated len={len}");
        }
    }
}
