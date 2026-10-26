//! DXT1/DXT5 block-expansion kernels.
//!
//! The scalar decoder computes each pixel's palette index with shifts and a
//! 4-entry table lookup. Vectorized, a 4×4 block becomes four 16-byte
//! shuffles: each lookup byte expands to four 2-bit indices, scaled ×4 into
//! `pshufb`/`vqtbl` selectors that gather the ARGB palette entries directly.
//! The 256-entry selector tables below are pure functions of the lookup
//! byte, so the SIMD output is bit-identical to the scalar path by
//! construction (the differential tests re-prove it over random blocks).
//!
//! DXT5 alpha: each pixel row consumes 12 alpha-index bits. A static 4096-entry
//! table (`ASEL4`, palette-independent) maps the row directly to its four 3-bit
//! palette codes as the low 4 selector bytes, so one shuffle gathers the alpha
//! bytes from the 8-entry palette and a second fixed shuffle lifts them to the
//! top byte of each output dword while the color shuffle uses a table variant
//! that zeroes byte 3 (0x80 selector flag), leaving `(alpha << 24) | rgb` after
//! one OR. (The gather selector's bytes 4..15 stay zero — they select palette
//! entry 0, which the lift never reads.)
//! DXT3 alpha: explicit 4-bit per pixel — 8 alpha bytes expand to 16 nibbles
//! (even/odd nibble split + byte interleave), each scaled by exactly 17
//! (`n * 255 / 15 == n * 17`), giving the 16 alpha bytes in pixel order; a
//! per-row fixed lift (`LIFT3`) places them at the top byte of each dword.
//!
//! Safety: all vector stores in this module target `pixels[base .. base+4]`
//! row offsets that the caller's `aligned` invariant (width/height multiples
//! of 4, band ranges) already bounds — the band driver re-checks with
//! `debug_assert!` and the test suite covers randomized dimensions.

/// Selector table for DXT1 color expansion: `SEL_C1[b][j]` gathers palette
/// byte `((b >> (2*(j%4))) & 3) * 4 + j%4`.
const SEL_C1: [[u8; 16]; 256] = build_color_selectors(false);
/// DXT5 variant: same gather, but the alpha byte (j%4 == 3) is zeroed.
const SEL_C5: [[u8; 16]; 256] = build_color_selectors(true);
/// Fixed lift: input bytes 0..3 → top byte of each output dword.
const ALPHA_TOP: [u8; 16] = [
    0x80, 0x80, 0x80, 0, 0x80, 0x80, 0x80, 1, 0x80, 0x80, 0x80, 2, 0x80, 0x80, 0x80, 3,
];

/// 12-bit DXT5 alpha-index row → four 3-bit palette codes as selector bytes
/// 0..3 (pure function of the row, independent of the alpha palette).
const fn build_alpha_selectors() -> [[u8; 4]; 4096] {
    let mut t = [[0u8; 4]; 4096];
    let mut w = 0;
    while w < 4096 {
        t[w] = [
            (w & 7) as u8,
            ((w >> 3) & 7) as u8,
            ((w >> 6) & 7) as u8,
            ((w >> 9) & 7) as u8,
        ];
        w += 1;
    }
    t
}
const ASEL4: [[u8; 4]; 4096] = build_alpha_selectors();

/// DXT3 lift: alpha bytes 4*py..4*py+3 (pixel order) → top byte of each output
/// dword (row py). `ALPHA_TOP` with the source indices shifted by 4*py.
/// Unused positions must be 0x80 (pshufb zero flag / NEON out-of-range → 0) —
/// a plain 0 would copy nibble 0 into the color bytes instead of zeroing.
const fn build_lift3() -> [[u8; 16]; 4] {
    let mut t = [[0x80u8; 16]; 4];
    let mut py = 0;
    while py < 4 {
        let mut k = 0;
        while k < 4 {
            t[py][k * 4 + 3] = (py * 4 + k) as u8;
            k += 1;
        }
        py += 1;
    }
    t
}
const LIFT3: [[u8; 16]; 4] = build_lift3();

/// Nibble×17 LUT for the DXT3 alpha expansion (`n * 255 / 15 == n * 17`
/// exactly). Applied as a byte-gather with the raw nibbles as selector.
const MUL17: [u8; 16] = [
    0, 17, 34, 51, 68, 85, 102, 119, 136, 153, 170, 187, 204, 221, 238, 255,
];

const fn build_color_selectors(zero_alpha: bool) -> [[u8; 16]; 256] {
    let mut t = [[0u8; 16]; 256];
    let mut b = 0;
    while b < 256 {
        let mut j = 0;
        while j < 16 {
            // Output byte j sits in pixel j/4 at byte j%4; the pixel's 2-bit
            // palette index lives at bits 2*(j/4) of the lookup byte.
            let idx = (b >> (2 * (j / 4))) & 3;
            t[b][j] = if zero_alpha && j & 3 == 3 {
                0x80 // pshufb/vqtbl: high bit set → output byte 0
            } else {
                (idx * 4 + (j & 3)) as u8
            };
            j += 1;
        }
        b += 1;
    }
    t
}

/// Driver: decode block-rows `[by_start, by_end)` of an aligned DXT1 image
/// into `pixels` (band-local rows, first pixel row = 0). Returns `false`
/// when no accelerated path exists on this platform — the caller then runs
/// its scalar band loop.
#[cfg(target_arch = "x86_64")]
pub fn dxt1_band(
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
            x86::dxt1_band_x86(data, width, blocks_x, by_start, by_end, pixels, band_y);
        }
        true
    } else {
        false
    }
}

#[cfg(target_arch = "aarch64")]
pub fn dxt1_band(
    data: &[u8],
    width: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
    band_y: usize,
) -> bool {
    unsafe {
        neon::dxt1_band_neon(data, width, blocks_x, by_start, by_end, pixels, band_y);
    }
    true
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn dxt1_band(
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

/// DXT3 variant (explicit 4-bit alpha + always-4-color block). Same return
/// contract as `dxt1_band`/`dxt5_band`.
#[cfg(target_arch = "x86_64")]
pub fn dxt3_band(
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
            x86::dxt3_band_x86(data, width, blocks_x, by_start, by_end, pixels, band_y);
        }
        true
    } else {
        false
    }
}

#[cfg(target_arch = "aarch64")]
pub fn dxt3_band(
    data: &[u8],
    width: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
    band_y: usize,
) -> bool {
    unsafe {
        neon::dxt3_band_neon(data, width, blocks_x, by_start, by_end, pixels, band_y);
    }
    true
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn dxt3_band(
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

/// DXT5 variant (interpolated alpha + 4-color block). Same return contract.
#[cfg(target_arch = "x86_64")]
pub fn dxt5_band(
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
            x86::dxt5_band_x86(data, width, blocks_x, by_start, by_end, pixels, band_y);
        }
        true
    } else {
        false
    }
}

#[cfg(target_arch = "aarch64")]
pub fn dxt5_band(
    data: &[u8],
    width: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
    band_y: usize,
) -> bool {
    unsafe {
        neon::dxt5_band_neon(data, width, blocks_x, by_start, by_end, pixels, band_y);
    }
    true
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn dxt5_band(
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
    use super::{ALPHA_TOP, ASEL4, LIFT3, MUL17, SEL_C1, SEL_C5};
    use core::arch::x86_64::*;

    #[inline(always)]
    fn palette(c0: u32, c1: u32, dxt5_mode: bool) -> [u32; 4] {
        let p0 = crate::codec::dxt::rgb565_to_argb(c0, 255);
        let p1 = crate::codec::dxt::rgb565_to_argb(c1, 255);
        if dxt5_mode || c0 > c1 {
            [
                p0,
                p1,
                crate::codec::dxt::interpolate_color(p0, p1, 2, 1, 3),
                crate::codec::dxt::interpolate_color(p0, p1, 1, 2, 3),
            ]
        } else {
            [
                p0,
                p1,
                crate::codec::dxt::interpolate_color(p0, p1, 1, 1, 2),
                0x0000_0000,
            ]
        }
    }

    /// Whole-band DXT1 decode on SSSE3+.
    ///
    /// Pass-8 note: a two-blocks-per-iteration ILP variant of this kernel
    /// was measured on the 8-core target and consistently REGRESSED ~10%
    /// (batch64 4.57 -> 5.05 ms, 3 interleaved A/B rounds, checksums
    /// identical) — the extra register pressure beat the added overlap.
    /// Reverted to the one-block body; the experiment is documented in
    /// REVIEW.md section 11.
    ///
    /// # Safety
    /// `tier() >= 2` (SSSE3 verified) by the caller. `pixels` must hold
    /// `(by_end - by_start) * 4 * width` u32s from row 0 (the band contract
    /// of `decode_dxt1_band`), and `data` accesses are bounds-checked per
    /// block below — the same contract the scalar band loop upholds.
    #[target_feature(enable = "ssse3")]
    pub(super) unsafe fn dxt1_band_x86(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let mut offset = by_start * blocks_x * 8;
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 8 > data.len() {
                    return;
                }
                let c0 = u16::from_le_bytes([data[offset], data[offset + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[offset + 2], data[offset + 3]]) as u32;
                let lookup = u32::from_le_bytes([
                    data[offset + 4],
                    data[offset + 5],
                    data[offset + 6],
                    data[offset + 7],
                ]);
                let pal = palette(c0, c1, false);
                // SAFETY: `pixels[row0 + bx*4 + py*width .. +4]` is inside the
                // band (aligned contract: block (by,bx) maps to in-bounds
                // rows/cols), all loads read in-bounds stack/table bytes.
                let palv = _mm_loadu_si128(pal.as_ptr() as *const __m128i);
                let base = row0 + bx * 4;
                for py in 0..4u32 {
                    let sel =
                        _mm_loadu_si128(SEL_C1[((lookup >> (py * 8)) & 0xFF) as usize].as_ptr()
                            as *const __m128i);
                    let px = _mm_shuffle_epi8(palv, sel);
                    _mm_storeu_si128(
                        pixels.as_mut_ptr().add(base + (py as usize) * width) as *mut __m128i,
                        px,
                    );
                }
                offset += 8;
            }
        }
    }

    /// Whole-band DXT5 decode on SSSE3+.
    ///
    /// Pass-10: the per-block 8-entry alpha palette is built in SIMD. The
    /// scalar version spent six runtime-divisor integer divisions per block
    /// (`(w0*a0 + w1*a1)/7` / `/5`); here the six interpolated lanes are
    /// computed as 16-bit vectors and divided by a multiply-high reciprocal
    /// (`q = (num * M) >> 17`, M = 18725 for /7 and 26215 for /5). The
    /// reciprocal is exact for every representable numerator (num ≤ 1785 resp.
    /// 1275; the per-lane over/under-estimate is ≪ 1/7 / 1/5, and the
    /// exhaustive test covers all 65536 endpoint pairs). Endpoint lanes 0..1
    /// are OR-ed in afterwards; the `a0 <= a1` path writes its fixed 0/255
    /// lanes via one insert.
    ///
    /// # Safety
    /// Same band/alignment contract as `dxt1_band_x86` (16 bytes per block).
    #[target_feature(enable = "ssse3")]
    pub(super) unsafe fn dxt5_band_x86(
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
        let magic7 = _mm_set1_epi16(18_725);
        let magic5 = _mm_set1_epi16(26_215);
        // Lane 0..7 weights: lanes 0..1 unused (OR-ed endpoints), lanes 2..7
        // hold (w0, w1) for palette entries 2..7. All lanes stay non-negative
        // so the unsigned multiply-high reciprocal is valid.
        let w07 = _mm_set_epi16(1, 2, 3, 4, 5, 6, 0, 0);
        let w17 = _mm_set_epi16(6, 5, 4, 3, 2, 1, 0, 0);
        let w05 = _mm_set_epi16(0, 0, 1, 2, 3, 4, 0, 0);
        let w15 = _mm_set_epi16(0, 0, 4, 3, 2, 1, 0, 0);
        let mut offset = by_start * blocks_x * 16;
        let alpha_top = _mm_loadu_si128(ALPHA_TOP.as_ptr() as *const __m128i);
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 16 > data.len() {
                    return;
                }
                let a0 = data[offset];
                let a1 = data[offset + 1];
                let (w0t, w1t, mg) = if a0 > a1 {
                    (w07, w17, magic7)
                } else {
                    (w05, w15, magic5)
                };
                let num = _mm_add_epi16(
                    _mm_mullo_epi16(w0t, _mm_set1_epi16(a0 as i16)),
                    _mm_mullo_epi16(w1t, _mm_set1_epi16(a1 as i16)),
                );
                let q = _mm_srli_epi16(_mm_mulhi_epu16(num, mg), 1);
                let q8 = _mm_packus_epi16(q, q);
                let endpoints = _mm_unpacklo_epi64(
                    _mm_cvtsi32_si128(((a0 as u32) | ((a1 as u32) << 8)) as i32),
                    zero,
                );
                let mut apal = _mm_or_si128(q8, endpoints);
                if a0 <= a1 {
                    // Word lane 3 = bytes 6..7: palette[6] = 0, palette[7] =
                    // 255 (LE word 0xFF00). Lane 7 would be bytes 14..15 —
                    // caught by the exhaustive endpoint test.
                    apal = _mm_insert_epi16::<3>(apal, 0xFF00);
                }
                let alpha_lookup = u64::from_le_bytes([
                    data[offset + 2],
                    data[offset + 3],
                    data[offset + 4],
                    data[offset + 5],
                    data[offset + 6],
                    data[offset + 7],
                    0,
                    0,
                ]);
                let co = offset + 8;
                let c0 = u16::from_le_bytes([data[co], data[co + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[co + 2], data[co + 3]]) as u32;
                let lookup =
                    u32::from_le_bytes([data[co + 4], data[co + 5], data[co + 6], data[co + 7]]);
                let pal = palette(c0, c1, true);
                // SAFETY: band mapping as in dxt1_band_x86; palette/selector
                // loads are in-bounds stack and const-table reads.
                let palv = _mm_loadu_si128(pal.as_ptr() as *const __m128i);
                let base = row0 + bx * 4;
                for py in 0..4u32 {
                    let color_sel =
                        _mm_loadu_si128(SEL_C5[((lookup >> (py * 8)) & 0xFF) as usize].as_ptr()
                            as *const __m128i);
                    let px = _mm_shuffle_epi8(palv, color_sel);
                    let w12 = ((alpha_lookup >> (py * 12)) & 0xFFF) as usize;
                    // ASEL4[w12] gives the four 3-bit alpha codes as the low
                    // 4 selector bytes (bytes 4..15 stay zero → entry 0, read
                    // but discarded by the lift below).
                    let asel = _mm_cvtsi32_si128(i32::from_le_bytes(ASEL4[w12]));
                    // Gather the alpha bytes from the 8-entry palette, lift to
                    // the top byte of each output dword, then OR into the
                    // color (byte 3 was zeroed by SEL_C5).
                    let av1 = _mm_shuffle_epi8(apal, asel);
                    let av = _mm_shuffle_epi8(av1, alpha_top);
                    _mm_storeu_si128(
                        pixels.as_mut_ptr().add(base + (py as usize) * width) as *mut __m128i,
                        _mm_or_si128(px, av),
                    );
                }
                offset += 16;
            }
        }
    }

    /// Whole-band DXT3 decode on SSSE3+ (explicit 4-bit alpha + 4-color block).
    ///
    /// # Safety
    /// Same band/alignment contract as `dxt1_band_x86` (16 bytes per block).
    #[target_feature(enable = "ssse3")]
    pub(super) unsafe fn dxt3_band_x86(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let mul17 = _mm_loadu_si128(MUL17.as_ptr() as *const __m128i);
        let mut offset = by_start * blocks_x * 16;
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 16 > data.len() {
                    return;
                }
                let co = offset + 8;
                let c0 = u16::from_le_bytes([data[co], data[co + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[co + 2], data[co + 3]]) as u32;
                let lookup =
                    u32::from_le_bytes([data[co + 4], data[co + 5], data[co + 6], data[co + 7]]);
                // DXT3 always uses the 4-color palette (dxt5_mode variant).
                let pal = palette(c0, c1, true);
                // SAFETY: band mapping as in dxt1_band_x86; palette/selector
                // loads are in-bounds stack and const-table reads.
                let palv = _mm_loadu_si128(pal.as_ptr() as *const __m128i);
                // Alpha: 8 explicit bytes → 16 nibbles in pixel order → ×17.
                // unpacklo interleaves the low 8 bytes of each operand, so the
                // even/odd nibble halves (one nibble per byte) already yield
                // all 16 nibbles at once; the ×17 scale is a pshufb LUT gather
                // (a packus_epi16 variant would saturate signed i16 lanes
                // ≥ 0x80 — wrong for nibbles ≥ 8).
                let raw = _mm_loadl_epi64(data.as_ptr().add(offset) as *const __m128i);
                let evens = _mm_and_si128(raw, _mm_set1_epi8(0x0F));
                let odds = _mm_and_si128(_mm_srli_epi16(raw, 4), _mm_set1_epi8(0x0F));
                let nibbles = _mm_shuffle_epi8(mul17, _mm_unpacklo_epi8(evens, odds));
                let base = row0 + bx * 4;
                for py in 0..4u32 {
                    let color_sel =
                        _mm_loadu_si128(SEL_C5[((lookup >> (py * 8)) & 0xFF) as usize].as_ptr()
                            as *const __m128i);
                    let px = _mm_shuffle_epi8(palv, color_sel);
                    let av = _mm_shuffle_epi8(
                        nibbles,
                        _mm_loadu_si128(LIFT3[py as usize].as_ptr() as *const __m128i),
                    );
                    _mm_storeu_si128(
                        pixels.as_mut_ptr().add(base + (py as usize) * width) as *mut __m128i,
                        _mm_or_si128(px, av),
                    );
                }
                offset += 16;
            }
        }
    }
}

#[cfg(target_arch = "aarch64")]
mod neon {
    use super::{ALPHA_TOP, ASEL4, LIFT3, MUL17, SEL_C1, SEL_C5};
    use core::arch::aarch64::*;

    /// Lane weights for the SIMD alpha-palette build in `dxt5_band_neon`
    /// (lanes 0..1 unused, lanes 2..7 = (w0, w1) for palette entries 2..7;
    /// the /7 and /5 interpolated-endpoint variants). Mirrors the x86
    /// `_mm_set_epi16` constants.
    const W07: [u16; 8] = [0, 0, 6, 5, 4, 3, 2, 1];
    const W17: [u16; 8] = [0, 0, 1, 2, 3, 4, 5, 6];
    const W05: [u16; 8] = [0, 0, 4, 3, 2, 1, 0, 0];
    const W15: [u16; 8] = [0, 0, 1, 2, 3, 4, 0, 0];

    #[inline(always)]
    fn palette(c0: u32, c1: u32, dxt5_mode: bool) -> [u32; 4] {
        let p0 = crate::codec::dxt::rgb565_to_argb(c0, 255);
        let p1 = crate::codec::dxt::rgb565_to_argb(c1, 255);
        if dxt5_mode || c0 > c1 {
            [
                p0,
                p1,
                crate::codec::dxt::interpolate_color(p0, p1, 2, 1, 3),
                crate::codec::dxt::interpolate_color(p0, p1, 1, 2, 3),
            ]
        } else {
            [
                p0,
                p1,
                crate::codec::dxt::interpolate_color(p0, p1, 1, 1, 2),
                0x0000_0000,
            ]
        }
    }

    /// Whole-band DXT1 decode on NEON.
    ///
    /// # Safety
    /// Band/alignment contract of `decode_dxt1_band` (see module docs);
    /// NEON is aarch64-baseline so no runtime gate is required.
    #[target_feature(enable = "neon")]
    pub(super) unsafe fn dxt1_band_neon(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let mut offset = by_start * blocks_x * 8;
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 8 > data.len() {
                    return;
                }
                let c0 = u16::from_le_bytes([data[offset], data[offset + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[offset + 2], data[offset + 3]]) as u32;
                let lookup = u32::from_le_bytes([
                    data[offset + 4],
                    data[offset + 5],
                    data[offset + 6],
                    data[offset + 7],
                ]);
                let pal = palette(c0, c1, false);
                // SAFETY: band mapping as documented; all accesses in-bounds.
                let palv = vld1q_u8(pal.as_ptr() as *const u8);
                let base = row0 + bx * 4;
                for py in 0..4usize {
                    let sel = vld1q_u8(SEL_C1[((lookup >> (py * 8)) & 0xFF) as usize].as_ptr());
                    let px = vqtbl1q_u8(palv, sel);
                    vst1q_u8(pixels.as_mut_ptr().add(base + py * width) as *mut u8, px);
                }
                offset += 8;
            }
        }
    }

    /// Whole-band DXT5 decode on NEON.
    ///
    /// Pass-10: the per-block alpha palette is built in SIMD exactly like the
    /// x86 kernel (multiply-high reciprocal for the /7 and /5 interpolations,
    /// endpoint lanes OR-ed in, one `vsetq_lane` for the fixed 255 on the
    /// `a0 <= a1` path) — replaces the scalar six-division loop.
    ///
    /// # Safety
    /// Same band/alignment contract as `dxt1_band_neon` (16 bytes per block).
    #[target_feature(enable = "neon")]
    pub(super) unsafe fn dxt5_band_neon(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let w07 = vld1q_u16(W07.as_ptr());
        let w17 = vld1q_u16(W17.as_ptr());
        let w05 = vld1q_u16(W05.as_ptr());
        let w15 = vld1q_u16(W15.as_ptr());
        let magic7 = vdupq_n_u16(18_725);
        let magic5 = vdupq_n_u16(26_215);
        let mut offset = by_start * blocks_x * 16;
        let alpha_top = vld1q_u8(ALPHA_TOP.as_ptr());
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 16 > data.len() {
                    return;
                }
                let a0 = data[offset];
                let a1 = data[offset + 1];
                let (w0v, w1v, mv) = if a0 > a1 {
                    (w07, w17, magic7)
                } else {
                    (w05, w15, magic5)
                };
                let num = vaddq_u16(
                    vmulq_u16(w0v, vdupq_n_u16(a0 as u16)),
                    vmulq_u16(w1v, vdupq_n_u16(a1 as u16)),
                );
                // q = (num * magic) >> 17 via 32-bit widening multiply.
                let q = vcombine_u16(
                    vmovn_u32(vshrq_n_u32(
                        vmull_u16(vget_low_u16(num), vget_low_u16(mv)),
                        17,
                    )),
                    vmovn_u32(vshrq_n_u32(vmull_high_u16(num, mv), 17)),
                );
                let q8 = vmovn_u16(q);
                let ep = vset_lane_u8(a1, vset_lane_u8(a0, vdup_n_u8(0), 0), 1);
                let apal = vcombine_u8(vorr_u8(q8, ep), vdup_n_u8(0));
                let apal = if a0 <= a1 {
                    vsetq_lane_u8(255, apal, 7)
                } else {
                    apal
                };
                let alpha_lookup = u64::from_le_bytes([
                    data[offset + 2],
                    data[offset + 3],
                    data[offset + 4],
                    data[offset + 5],
                    data[offset + 6],
                    data[offset + 7],
                    0,
                    0,
                ]);
                let co = offset + 8;
                let c0 = u16::from_le_bytes([data[co], data[co + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[co + 2], data[co + 3]]) as u32;
                let lookup =
                    u32::from_le_bytes([data[co + 4], data[co + 5], data[co + 6], data[co + 7]]);
                let pal = palette(c0, c1, true);
                // SAFETY: band mapping as in dxt1_band_neon.
                let palv = vld1q_u8(pal.as_ptr() as *const u8);
                let base = row0 + bx * 4;
                for py in 0..4usize {
                    let color_sel =
                        vld1q_u8(SEL_C5[((lookup >> (py * 8)) & 0xFF) as usize].as_ptr());
                    let px = vqtbl1q_u8(palv, color_sel);
                    let w12 = ((alpha_lookup >> (py * 12)) & 0xFFF) as usize;
                    // ASEL4[w12] gives the four 3-bit alpha codes as the low
                    // 4 selector bytes (upper bytes zero → entry 0, read but
                    // discarded by the lift below).
                    let sel_lo = vld1_u8(ASEL4[w12].as_ptr());
                    let sel = vcombine_u8(sel_lo, vdup_n_u8(0));
                    // Gather the alpha bytes from the palette, then lift to
                    // the top byte of each output dword.
                    let av1 = vqtbl1q_u8(apal, sel);
                    let av = vqtbl1q_u8(av1, alpha_top);
                    vst1q_u8(
                        pixels.as_mut_ptr().add(base + py * width) as *mut u8,
                        vorrq_u8(px, av),
                    );
                }
                offset += 16;
            }
        }
    }

    /// Whole-band DXT3 decode on NEON (explicit 4-bit alpha + 4-color block).
    ///
    /// # Safety
    /// Same band/alignment contract as `dxt1_band_neon` (16 bytes per block).
    #[target_feature(enable = "neon")]
    pub(super) unsafe fn dxt3_band_neon(
        data: &[u8],
        width: usize,
        blocks_x: usize,
        by_start: usize,
        by_end: usize,
        pixels: &mut [u32],
        _band_y: usize,
    ) {
        debug_assert!(pixels.len() >= (by_end - by_start) * 4 * width);
        let mul17 = vld1q_u8(MUL17.as_ptr());
        let mut offset = by_start * blocks_x * 16;
        for by in by_start..by_end {
            let row0 = (by - by_start) * width * 4;
            for bx in 0..blocks_x {
                if offset + 16 > data.len() {
                    return;
                }
                let co = offset + 8;
                let c0 = u16::from_le_bytes([data[co], data[co + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[co + 2], data[co + 3]]) as u32;
                let lookup =
                    u32::from_le_bytes([data[co + 4], data[co + 5], data[co + 6], data[co + 7]]);
                // DXT3 always uses the 4-color palette (dxt5_mode variant).
                let pal = palette(c0, c1, true);
                // SAFETY: band mapping as in dxt1_band_neon.
                let palv = vld1q_u8(pal.as_ptr() as *const u8);
                // Alpha: 8 explicit bytes → 16 nibbles in pixel order → ×17.
                // zip1/zip2 of the even/odd nibble halves (one nibble per
                // byte) yield all 16 nibbles in pixel order; the ×17 scale is
                // a vtbl LUT gather.
                let raw = vld1_u8(data.as_ptr().add(offset));
                let evens = vand_u8(raw, vdup_n_u8(0x0F));
                let odds = vshr_n_u8(raw, 4);
                let nibbles = vqtbl1q_u8(
                    mul17,
                    vcombine_u8(vzip1_u8(evens, odds), vzip2_u8(evens, odds)),
                );
                let base = row0 + bx * 4;
                for py in 0..4usize {
                    let color_sel =
                        vld1q_u8(SEL_C5[((lookup >> (py * 8)) & 0xFF) as usize].as_ptr());
                    let px = vqtbl1q_u8(palv, color_sel);
                    let av = vqtbl1q_u8(nibbles, vld1q_u8(LIFT3[py].as_ptr()));
                    vst1q_u8(
                        pixels.as_mut_ptr().add(base + py * width) as *mut u8,
                        vorrq_u8(px, av),
                    );
                }
                offset += 16;
            }
        }
    }
}

#[cfg(test)]
mod tests {
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

    /// Pure scalar reference decoder (the original pre-SIMD expansion).
    fn ref_dxt1(data: &[u8], width: usize, height: usize) -> Vec<u32> {
        let blocks_x = width.div_ceil(4);
        let blocks_y = height.div_ceil(4);
        let mut pixels = vec![0u32; width * height];
        let mut offset = 0usize;
        let mut palette = [0u32; 4];
        for by in 0..blocks_y {
            for bx in 0..blocks_x {
                if offset + 8 > data.len() {
                    return pixels;
                }
                let c0 = u16::from_le_bytes([data[offset], data[offset + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[offset + 2], data[offset + 3]]) as u32;
                palette[0] = crate::codec::dxt::rgb565_to_argb(c0, 255);
                palette[1] = crate::codec::dxt::rgb565_to_argb(c1, 255);
                if c0 > c1 {
                    palette[2] =
                        crate::codec::dxt::interpolate_color(palette[0], palette[1], 2, 1, 3);
                    palette[3] =
                        crate::codec::dxt::interpolate_color(palette[0], palette[1], 1, 2, 3);
                } else {
                    palette[2] =
                        crate::codec::dxt::interpolate_color(palette[0], palette[1], 1, 1, 2);
                    palette[3] = 0x0000_0000;
                }
                let lookup = u32::from_le_bytes([
                    data[offset + 4],
                    data[offset + 5],
                    data[offset + 6],
                    data[offset + 7],
                ]);
                for py in 0..4 {
                    for px in 0..4 {
                        let x = bx * 4 + px;
                        let y = by * 4 + py;
                        if x < width && y < height {
                            let shift = (py * 4 + px) * 2;
                            let code = ((lookup >> shift) & 0x03) as usize;
                            pixels[y * width + x] = palette[code];
                        }
                    }
                }
                offset += 8;
            }
        }
        pixels
    }

    fn ref_dxt5(data: &[u8], width: usize, height: usize) -> Vec<u32> {
        let blocks_x = width.div_ceil(4);
        let blocks_y = height.div_ceil(4);
        let mut pixels = vec![0u32; width * height];
        let mut offset = 0usize;
        for by in 0..blocks_y {
            for bx in 0..blocks_x {
                if offset + 16 > data.len() {
                    return pixels;
                }
                let a0 = data[offset] as u32;
                let a1 = data[offset + 1] as u32;
                let mut alpha_palette = [0u32; 8];
                alpha_palette[0] = a0;
                alpha_palette[1] = a1;
                if a0 > a1 {
                    for (i, (w0, w1)) in [(6, 1), (5, 2), (4, 3), (3, 4), (2, 5), (1, 6)]
                        .iter()
                        .enumerate()
                    {
                        alpha_palette[2 + i] = (w0 * a0 + w1 * a1) / 7;
                    }
                } else {
                    for (i, (w0, w1)) in [(4, 1), (3, 2), (2, 3), (1, 4)].iter().enumerate() {
                        alpha_palette[2 + i] = (w0 * a0 + w1 * a1) / 5;
                    }
                    alpha_palette[6] = 0;
                    alpha_palette[7] = 255;
                }
                let mut alpha_lookup: u64 = 0;
                for i in 0..6 {
                    alpha_lookup |= (data[offset + 2 + i] as u64) << (8 * i);
                }
                let co = offset + 8;
                let c0 = u16::from_le_bytes([data[co], data[co + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[co + 2], data[co + 3]]) as u32;
                let mut color_palette = [0u32; 4];
                color_palette[0] = crate::codec::dxt::rgb565_to_argb(c0, 255);
                color_palette[1] = crate::codec::dxt::rgb565_to_argb(c1, 255);
                color_palette[2] = crate::codec::dxt::interpolate_color(
                    color_palette[0],
                    color_palette[1],
                    2,
                    1,
                    3,
                );
                color_palette[3] = crate::codec::dxt::interpolate_color(
                    color_palette[0],
                    color_palette[1],
                    1,
                    2,
                    3,
                );
                let color_lookup =
                    u32::from_le_bytes([data[co + 4], data[co + 5], data[co + 6], data[co + 7]]);
                for py in 0..4 {
                    for px in 0..4 {
                        let x = bx * 4 + px;
                        let y = by * 4 + py;
                        if x < width && y < height {
                            let idx = py * 4 + px;
                            let a_code = ((alpha_lookup >> (idx * 3)) & 0x07) as usize;
                            let alpha = alpha_palette[a_code];
                            let c_code = ((color_lookup >> (idx * 2)) & 0x03) as usize;
                            let rgb = color_palette[c_code] & 0x00FF_FFFF;
                            pixels[y * width + x] = (alpha << 24) | rgb;
                        }
                    }
                }
                offset += 16;
            }
        }
        pixels
    }

    #[test]
    fn dxt1_random_blocks_match_reference() {
        let mut rng = Rng(0xDEAD_BEEF_1234_5678);
        // (12, 8) and (20, 16) have an odd block-column count: they pin the
        // per-block bounds walk of the x86 kernel for non-multiple-of-2
        // block rows (guards any future paired/unrolled variant).
        for &(w, h) in &[
            (4usize, 4usize),
            (8, 12),
            (12, 8),
            (20, 16),
            (64, 64),
            (256, 32),
        ] {
            let data: Vec<u8> = (0..w.div_ceil(4) * h.div_ceil(4) * 8)
                .map(|_| rng.byte())
                .collect();
            let got = crate::codec::dxt::decode_dxt1(&data, w, h);
            let want = ref_dxt1(&data, w, h);
            assert_eq!(got, want, "dxt1 {w}x{h}");
        }
    }

    /// A `data` stream shorter than the aligned image must decode exactly
    /// the blocks the scalar reference decodes (per-block bounds check) and
    /// leave the remaining pixels zero — guards the x86 kernel's walk
    /// against skipping or double-decoding a block on short streams.
    #[test]
    fn dxt1_truncated_stream_matches_reference() {
        let mut rng = Rng(0x7BED_DA7A_0000_00FF);
        let (w, h) = (12usize, 8usize); // 3 x 2 blocks = 48 bytes when complete
        let data: Vec<u8> = (0..48).map(|_| rng.byte()).collect();
        for len in [0usize, 1, 7, 8, 9, 15, 16, 17, 23, 24, 25, 31, 40, 47, 48] {
            let got = crate::codec::dxt::decode_dxt1(&data[..len], w, h);
            let want = ref_dxt1(&data[..len], w, h);
            assert_eq!(got, want, "dxt1 truncated len={len}");
        }
    }

    /// Pure scalar reference decoder for DXT3 (explicit 4-bit alpha; mirrors
    /// the `txd::decode_dxt3_band` loop this SIMD path replaces).
    fn ref_dxt3(data: &[u8], width: usize, height: usize) -> Vec<u32> {
        let blocks_x = width.div_ceil(4);
        let blocks_y = height.div_ceil(4);
        let mut pixels = vec![0u32; width * height];
        let mut offset = 0usize;
        for by in 0..blocks_y {
            for bx in 0..blocks_x {
                if offset + 16 > data.len() {
                    return pixels;
                }
                let co = offset + 8;
                let c0 = u16::from_le_bytes([data[co], data[co + 1]]) as u32;
                let c1 = u16::from_le_bytes([data[co + 2], data[co + 3]]) as u32;
                let mut palette = [0u32; 4];
                palette[0] = crate::codec::dxt::rgb565_to_argb(c0, 255);
                palette[1] = crate::codec::dxt::rgb565_to_argb(c1, 255);
                palette[2] = crate::codec::dxt::interpolate_color(palette[0], palette[1], 2, 1, 3);
                palette[3] = crate::codec::dxt::interpolate_color(palette[0], palette[1], 1, 2, 3);
                let lookup =
                    u32::from_le_bytes([data[co + 4], data[co + 5], data[co + 6], data[co + 7]]);
                for py in 0..4 {
                    for px in 0..4 {
                        let x = bx * 4 + px;
                        let y = by * 4 + py;
                        if x < width && y < height {
                            let pixel_idx = py * 4 + px;
                            let alpha_byte = data[offset + pixel_idx / 2];
                            let a4 = if pixel_idx % 2 == 0 {
                                alpha_byte & 0x0F
                            } else {
                                (alpha_byte >> 4) & 0x0F
                            };
                            let alpha = (a4 as u32 * 255) / 15;
                            let code = ((lookup >> (pixel_idx * 2)) & 0x03) as usize;
                            let rgb = palette[code] & 0x00FF_FFFF;
                            pixels[y * width + x] = (alpha << 24) | rgb;
                        }
                    }
                }
                offset += 16;
            }
        }
        pixels
    }

    #[test]
    fn dxt3_random_blocks_match_reference() {
        let mut rng = Rng(0xD3D3_D3D3_1234_5678);
        for &(w, h) in &[(4usize, 4usize), (8, 12), (64, 64), (256, 32)] {
            let data: Vec<u8> = (0..w.div_ceil(4) * h.div_ceil(4) * 16)
                .map(|_| rng.byte())
                .collect();
            let got = crate::txd::decode_dxt3(&data, w, h);
            let want = ref_dxt3(&data, w, h);
            assert_eq!(got, want, "dxt3 {w}x{h}");
        }
    }

    /// DXT3 alpha extremes: both nibble orders and the 0/15 endpoints must
    /// expand to exactly 0/255 through the ×17 path, and the color bytes must
    /// stay untouched (the lift must zero, not copy nibble 0).
    #[test]
    fn dxt3_alpha_endpoints_expand_exactly() {
        // alpha bytes alternate 0x0F (nibble pair 15,0) and 0xF0 (0,15);
        // color block: c0=0xF800 (red), c1=0x001F, lookup all-0 -> red.
        let mut data = vec![0x0Fu8, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0];
        data.extend_from_slice(&[0x00, 0xF8, 0x1F, 0x00, 0, 0, 0, 0]);
        let px = crate::txd::decode_dxt3(&data, 4, 4);
        let want_alpha = [
            255u32, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255,
        ];
        for (i, &p) in px.iter().enumerate() {
            assert_eq!(p, (want_alpha[i] << 24) | 0x00FF_0000, "full pixel {i}");
        }
    }

    /// Known-block golden check: alpha bytes 01 23 45 67 89 AB CD EF hold the
    /// nibble sequence i^1 (1,0,3,2,…,15,14); each must decode to exactly
    /// n*17 in the alpha byte with the red color intact.
    #[test]
    fn dxt3_ascending_nibbles_golden() {
        let mut data = vec![0x01u8, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
        data.extend_from_slice(&[0x00, 0xF8, 0x1F, 0x00, 0, 0, 0, 0]);
        let px = crate::txd::decode_dxt3(&data, 4, 4);
        for (i, &p) in px.iter().enumerate() {
            let n = (i as u32) ^ 1;
            assert_eq!(p, ((n * 17) << 24) | 0x00FF_0000, "full pixel {i}");
        }
    }

    #[test]
    fn dxt5_random_blocks_match_reference() {
        let mut rng = Rng(0xC0FF_EE12_3456_789A);
        for &(w, h) in &[
            (4usize, 4usize),
            (8, 12),
            (12, 8),
            (20, 16),
            (64, 64),
            (128, 96),
        ] {
            let data: Vec<u8> = (0..w.div_ceil(4) * h.div_ceil(4) * 16)
                .map(|_| rng.byte())
                .collect();
            let got = crate::codec::dxt::decode_dxt5(&data, w, h);
            let want = ref_dxt5(&data, w, h);
            assert_eq!(got, want, "dxt5 {w}x{h}");
        }
    }

    /// Every (a0, a1) endpoint pair x every palette code: the decoded alpha
    /// byte must equal the scalar interpolation formula exactly (this pins
    /// the pass-10 multiply-high reciprocal used by the SIMD palette build —
    /// 65536 pairs x 8 codes). Color stays red to catch any lift/select
    /// corruption while the palette build changed.
    #[test]
    fn dxt5_alpha_palette_exhaustive_matches_scalar_formula() {
        // Color block: c0 = 0xF800 (red), c1 = 0x001F, lookup all-0 -> red.
        let mut data = vec![0u8; 16];
        data[8] = 0x00;
        data[9] = 0xF8;
        data[10] = 0x1F;
        // Alpha selector bytes: pixel i uses code i % 8 (codes 0..7 covered
        // twice per block); build the 48-bit LE stream once.
        let mut lookup: u64 = 0;
        for i in 0..16u64 {
            lookup |= (i % 8) << (i * 3);
        }
        for i in 0..6 {
            data[2 + i] = ((lookup >> (8 * i)) & 0xFF) as u8;
        }
        for a0 in 0..=255u8 {
            for a1 in 0..=255u8 {
                data[0] = a0;
                data[1] = a1;
                // Scalar reference palette (same formula as ref_dxt5).
                let (a0, a1) = (a0 as u32, a1 as u32);
                let mut pal = [a0, a1, 0, 0, 0, 0, 0, 0];
                if a0 > a1 {
                    for (i, (w0, w1)) in [(6, 1), (5, 2), (4, 3), (3, 4), (2, 5), (1, 6)]
                        .iter()
                        .enumerate()
                    {
                        pal[2 + i] = (w0 * a0 + w1 * a1) / 7;
                    }
                } else {
                    for (i, (w0, w1)) in [(4, 1), (3, 2), (2, 3), (1, 4)].iter().enumerate() {
                        pal[2 + i] = (w0 * a0 + w1 * a1) / 5;
                    }
                    pal[6] = 0;
                    pal[7] = 255;
                }
                let px = crate::codec::dxt::decode_dxt5(&data, 4, 4);
                for (i, &p) in px.iter().enumerate() {
                    let want_a = pal[i % 8];
                    assert_eq!((p >> 24) & 0xFF, want_a, "alpha a0={a0} a1={a1} px={i}");
                    assert_eq!(p & 0x00FF_FFFF, 0x00FF_0000, "color a0={a0} a1={a1} px={i}");
                }
            }
        }
    }

    /// A `data` stream shorter than the aligned image must decode exactly the
    /// blocks the scalar reference decodes and leave the rest zero — guards
    /// the DXT5 kernels' block walk on short streams.
    #[test]
    fn dxt5_truncated_stream_matches_reference() {
        let mut rng = Rng(0x5D1D_5C0F_0000_00AA);
        let (w, h) = (12usize, 8usize); // 3 x 2 blocks = 96 bytes when complete
        let data: Vec<u8> = (0..96).map(|_| rng.byte()).collect();
        for len in [
            0usize, 1, 15, 16, 17, 31, 32, 33, 47, 48, 49, 63, 64, 65, 79, 80, 81, 95, 96,
        ] {
            let got = crate::codec::dxt::decode_dxt5(&data[..len], w, h);
            let want = ref_dxt5(&data[..len], w, h);
            assert_eq!(got, want, "dxt5 truncated len={len}");
        }
    }
}
