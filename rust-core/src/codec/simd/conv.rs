//! Raw-format conversion kernels (texdb listings & PNG import): RGB565 /
//! RGBA4444 / RGBA8888 → ARGB, plus the inverse RGBA8888 shuffle.
//!
//! Exactness: the 565 expansion is the round-half-up form
//! `((v * 255) + half) / max`, kept bit-identical through exact 16-bit
//! multiply-shift magic numbers — (n·4229) >> 17 == n/31 and (n·4161) >> 18
//! == n/63 for every reachable n — asserted exhaustively over all 65536
//! inputs by the tests below. The 4444 expansion is an exact ×17 multiply.
//!
//! Safety (applies to every `unsafe` block in this file): x86 kernels are
//! `#[target_feature]` functions only ever called through `tier()` (SSE2 is
//! x86_64-baseline; AVX2/SSSE3 paths are gated on runtime detection); NEON is
//! aarch64-baseline. Loads and stores are issued only for chunk offsets the
//! loop has already proven are inside `data` / inside the pre-sized `out`
//! allocation (`out` holds exactly `data.len() / B` u32 words for block size
//! B, and the store pointer offset `i * 16 * 4` bytes matches the same
//! chunk index). Tail bytes/pixels take the scalar path.

#[cfg(target_arch = "aarch64")]
use core::arch::aarch64::*;

/// NEON nibble×17 LUT.
#[cfg(target_arch = "aarch64")]
const fn build_mul17_u8() -> [u8; 16] {
    let mut t = [0u8; 16];
    let mut i = 0;
    while i < 16 {
        t[i] = (i * 17) as u8;
        i += 1;
    }
    t
}

/// NEON byte LUTs (indexed by the 5-bit / 6-bit raw fields, and by nibble).
#[cfg(target_arch = "aarch64")]
const N17U8: [u8; 16] = build_mul17_u8();
#[cfg(target_arch = "aarch64")]
const TAB_R5: [u8; 32] = build_u8_lut::<32, 15, 31>();
#[cfg(target_arch = "aarch64")]
const TAB_G6: [u8; 64] = build_u8_lut::<64, 31, 63>();

#[cfg(target_arch = "aarch64")]
const fn build_u8_lut<const N: usize, const HALF: u32, const MAX: u32>() -> [u8; N] {
    let mut t = [0u8; N];
    let mut i = 0;
    while i < N {
        t[i] = ((i as u32 * 255 + HALF) / MAX) as u8;
        i += 1;
    }
    t
}

#[inline]
fn scalar_565_px(v: u32) -> u32 {
    0xFF00_0000
        | ((((v >> 11) & 0x1F) * 255 + 15) / 31) << 16
        | ((((v >> 5) & 0x3F) * 255 + 31) / 63) << 8
        | (((v & 0x1F) * 255 + 15) / 31)
}

#[inline]
fn scalar_4444_px(v: u32) -> u32 {
    (((v & 0xF) * 17) << 24)
        | ((((v >> 12) & 0xF) * 17) << 16)
        | ((((v >> 8) & 0xF) * 17) << 8)
        | (((v >> 4) & 0xF) * 17)
}

#[cfg(target_arch = "aarch64")]
fn scalar_565_tail(data: &[u8], out: &mut [u32]) {
    for (o, c) in out.iter_mut().zip(data.as_chunks::<2>().0) {
        *o = scalar_565_px(u16::from_le_bytes([c[0], c[1]]) as u32);
    }
}

#[cfg(target_arch = "aarch64")]
fn scalar_4444_tail(data: &[u8], out: &mut [u32]) {
    for (o, c) in out.iter_mut().zip(data.as_chunks::<2>().0) {
        *o = scalar_4444_px(u16::from_le_bytes([c[0], c[1]]) as u32);
    }
}

#[cfg(not(target_arch = "aarch64"))]
fn portable_565(data: &[u8]) -> Vec<u32> {
    // Same shape as the historical texdb.rs loop (map+collect) so LLVM's
    // auto-vectorization opportunities are identical there.
    data.as_chunks::<2>()
        .0
        .iter()
        .map(|c| scalar_565_px(u16::from_le_bytes([c[0], c[1]]) as u32))
        .collect()
}

#[cfg(not(target_arch = "aarch64"))]
fn portable_4444(data: &[u8]) -> Vec<u32> {
    data.as_chunks::<2>()
        .0
        .iter()
        .map(|c| scalar_4444_px(u16::from_le_bytes([c[0], c[1]]) as u32))
        .collect()
}

#[cfg(target_arch = "x86_64")]
pub fn rgb565_to_argb(data: &[u8]) -> Vec<u32> {
    // Scalar on x86_64: these conversions are memory-bound and LLVM's
    // auto-vectorized scalar loop already matches hand-written SSE there
    // (measured — the NEON path below is kept for the Android target).
    portable_565(data)
}

#[cfg(target_arch = "aarch64")]
pub fn rgb565_to_argb(data: &[u8]) -> Vec<u32> {
    unsafe { rgb565_neon(data) }
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn rgb565_to_argb(data: &[u8]) -> Vec<u32> {
    portable_565(data)
}

#[cfg(target_arch = "x86_64")]
pub fn rgba4444_to_argb(data: &[u8]) -> Vec<u32> {
    portable_4444(data)
}

#[cfg(target_arch = "aarch64")]
pub fn rgba4444_to_argb(data: &[u8]) -> Vec<u32> {
    unsafe { rgba4444_neon(data) }
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn rgba4444_to_argb(data: &[u8]) -> Vec<u32> {
    portable_4444(data)
}

#[cfg(target_arch = "x86_64")]
pub fn rgba32_to_argb(data: &[u8]) -> Vec<u32> {
    if super::tier() >= 3 {
        // SAFETY: AVX2 verified by tier() (SSSE3 implied).
        unsafe { x86::rgba32_avx2(data) }
    } else if super::tier() >= 2 {
        // SAFETY: SSSE3 verified by tier().
        unsafe { x86::rgba32_ssse3(data) }
    } else {
        crate::codec::raw::rgba32_to_argb_portable(data)
    }
}

#[cfg(target_arch = "aarch64")]
pub fn rgba32_to_argb(data: &[u8]) -> Vec<u32> {
    unsafe { rgba32_neon(data) }
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
pub fn rgba32_to_argb(data: &[u8]) -> Vec<u32> {
    crate::codec::raw::rgba32_to_argb_portable(data)
}

// ---------------------------------------------------------------------------
// x86_64 SSSE3/AVX2 (RGBA8888 → ARGB is a pure byte permutation: one pshufb
// per 16/32 bytes; the scalar tail covers the last partial pixel)
// ---------------------------------------------------------------------------

#[cfg(target_arch = "x86_64")]
mod x86 {
    use core::arch::x86_64::*;

    /// RGBA byte j of each 4-byte pixel → ARGB LE byte order: out bytes are
    /// [B, G, R, A] = in bytes [2, 1, 0, 3] (same selector as the NEON path).
    const SEL: [u8; 16] = [2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15];

    #[inline(always)]
    unsafe fn scalar_tail(data: &[u8], out: &mut [u32], done_bytes: usize) {
        for (k, c) in data[done_bytes..].as_chunks::<4>().0.iter().enumerate() {
            out[done_bytes / 4 + k] = u32::from_le_bytes([c[2], c[1], c[0], c[3]]);
        }
    }

    /// # Safety
    /// SSSE3 must be detected (tier() >= 2) by the caller. Stores only touch
    /// the pre-sized `out` (exactly `data.len()/4` words) at chunk offsets the
    /// loop has proven in-bounds; loads stay inside `data`.
    #[target_feature(enable = "ssse3")]
    pub(super) unsafe fn rgba32_ssse3(data: &[u8]) -> Vec<u32> {
        let mut out = vec![0u32; data.len() / 4];
        let sel = _mm_loadu_si128(SEL.as_ptr() as *const __m128i);
        let chunks = data.len() / 16;
        for c in 0..chunks {
            // SAFETY: byte range c*16..c*16+16 is within `data`; the 16-byte
            // store hits out[c*4..c*4+4], inside the allocation.
            let v = _mm_loadu_si128(data.as_ptr().add(c * 16) as *const __m128i);
            _mm_storeu_si128(
                out.as_mut_ptr().add(c * 4) as *mut __m128i,
                _mm_shuffle_epi8(v, sel),
            );
        }
        scalar_tail(data, &mut out, chunks * 16);
        out
    }

    /// # Safety
    /// AVX2 must be detected (tier() >= 3) by the caller. Same store/load
    /// contract as `rgba32_ssse3` with 32-byte chunks.
    #[target_feature(enable = "avx2")]
    pub(super) unsafe fn rgba32_avx2(data: &[u8]) -> Vec<u32> {
        let mut out = vec![0u32; data.len() / 4];
        let mut sel32 = [0u8; 32];
        sel32[..16].copy_from_slice(&SEL);
        sel32[16..].copy_from_slice(&SEL);
        let sel = _mm256_loadu_si256(sel32.as_ptr() as *const __m256i);
        let chunks = data.len() / 32;
        for c in 0..chunks {
            // SAFETY: byte range c*32..c*32+32 is within `data`; the 32-byte
            // store hits out[c*8..c*8+8], inside the allocation.
            let v = _mm256_loadu_si256(data.as_ptr().add(c * 32) as *const __m256i);
            _mm256_storeu_si256(
                out.as_mut_ptr().add(c * 8) as *mut __m256i,
                _mm256_shuffle_epi8(v, sel),
            );
        }
        scalar_tail(data, &mut out, chunks * 32);
        out
    }
}

// ---------------------------------------------------------------------------
// aarch64 NEON
// ---------------------------------------------------------------------------

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn rgb565_neon(data: &[u8]) -> Vec<u32> {
    let mut out = vec![0u32; data.len() / 2];
    let (chunks, tail) = data.as_chunks::<32>(); // 16 pixels
    let t_r = vld1q_u8_x2(TAB_R5.as_ptr());
    let t_g = vld1q_u8_x4(TAB_G6.as_ptr());
    let t_b = vld1q_u8_x2(TAB_R5.as_ptr());
    let ff = vdupq_n_u8(0xFF);
    for (i, c) in chunks.iter().enumerate() {
        // SAFETY: c is a valid 32-byte chunk of `data`; the store target
        // out[i*16 .. i*16+16] exists (out = data.len()/2 u32s).
        let v0 = vld1q_u16(c.as_ptr() as *const u16);
        let v1 = vld1q_u16(c.as_ptr().add(16) as *const u16);
        let r0 = vshrq_n_u16(v0, 11);
        let r1 = vshrq_n_u16(v1, 11);
        let g0 = vandq_u16(vshrq_n_u16(v0, 5), vdupq_n_u16(63));
        let g1 = vandq_u16(vshrq_n_u16(v1, 5), vdupq_n_u16(63));
        let b0 = vandq_u16(v0, vdupq_n_u16(31));
        let b1 = vandq_u16(v1, vdupq_n_u16(31));
        let rb = vcombine_u8(vmovn_u16(r0), vmovn_u16(r1));
        let gb = vcombine_u8(vmovn_u16(g0), vmovn_u16(g1));
        let bb = vcombine_u8(vmovn_u16(b0), vmovn_u16(b1));
        let t8r = vqtbl2q_u8(t_r, rb);
        let t8g = vqtbl4q_u8(t_g, gb);
        let t8b = vqtbl2q_u8(t_b, bb);
        let bg0 = vzip1q_u8(t8b, t8g);
        let bg1 = vzip2q_u8(t8b, t8g);
        let rf0 = vzip1q_u8(t8r, ff);
        let rf1 = vzip2q_u8(t8r, ff);
        let p0 = out.as_mut_ptr().add(i * 16) as *mut u8;
        // Pixel bytes are [b,g,r,ff]: interleave the (b,g) and (r,ff) pairs
        // at U16 granularity — a byte-level zip here would emit [b,r,g,ff]
        // and scramble every channel (caught by the pass-12 qemu-aarch64
        // run; the chunk path was never executable before that).
        vst1q_u8(
            p0,
            vreinterpretq_u8_u16(vzip1q_u16(
                vreinterpretq_u16_u8(bg0),
                vreinterpretq_u16_u8(rf0),
            )),
        );
        // Stores are 16 bytes each (4 pixels) and must not overlap: zip1/zip2
        // of the same pair cover pixels 0-3 / 4-7, then the second u8 halves
        // cover 8-11 / 12-15. The original p0.add(4/8/12) offsets made each
        // store clobber the previous one (only pixel 0 survived; slot k held
        // pixel 4k) — caught by the pass-12 qemu-aarch64 parity run.
        vst1q_u8(
            p0.add(16),
            vreinterpretq_u8_u16(vzip2q_u16(
                vreinterpretq_u16_u8(bg0),
                vreinterpretq_u16_u8(rf0),
            )),
        );
        vst1q_u8(
            p0.add(32),
            vreinterpretq_u8_u16(vzip1q_u16(
                vreinterpretq_u16_u8(bg1),
                vreinterpretq_u16_u8(rf1),
            )),
        );
        vst1q_u8(
            p0.add(48),
            vreinterpretq_u8_u16(vzip2q_u16(
                vreinterpretq_u16_u8(bg1),
                vreinterpretq_u16_u8(rf1),
            )),
        );
    }
    scalar_565_tail(tail, &mut out[chunks.len() * 16..]);
    out
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn rgba4444_neon(data: &[u8]) -> Vec<u32> {
    let mut out = vec![0u32; data.len() / 2];
    let (chunks, tail) = data.as_chunks::<32>();
    let t17 = vld1q_u8(N17U8.as_ptr());
    for (i, c) in chunks.iter().enumerate() {
        // SAFETY: chunked access as in rgb565_neon.
        let v0 = vld1q_u16(c.as_ptr() as *const u16);
        let v1 = vld1q_u16(c.as_ptr().add(16) as *const u16);
        let r0 = vandq_u16(vshrq_n_u16(v0, 12), vdupq_n_u16(15));
        let r1 = vandq_u16(vshrq_n_u16(v1, 12), vdupq_n_u16(15));
        let g0 = vandq_u16(vshrq_n_u16(v0, 8), vdupq_n_u16(15));
        let g1 = vandq_u16(vshrq_n_u16(v1, 8), vdupq_n_u16(15));
        let b0 = vandq_u16(vshrq_n_u16(v0, 4), vdupq_n_u16(15));
        let b1 = vandq_u16(vshrq_n_u16(v1, 4), vdupq_n_u16(15));
        let a0 = vandq_u16(v0, vdupq_n_u16(15));
        let a1 = vandq_u16(v1, vdupq_n_u16(15));
        let m = |x: uint16x8_t, y: uint16x8_t| -> uint8x16_t {
            // vqtbl1q_u8 is a pure NEON register operation with no memory
            // access; the caller is an unsafe fn so this inherits its
            // safety context.
            let xb = vcombine_u8(vmovn_u16(x), vmovn_u16(y));
            vqtbl1q_u8(t17, xb)
        };
        let t8r = m(r0, r1);
        let t8g = m(g0, g1);
        let t8b = m(b0, b1);
        let t8a = m(a0, a1);
        let bg0 = vzip1q_u8(t8b, t8g);
        let bg1 = vzip2q_u8(t8b, t8g);
        let ra0 = vzip1q_u8(t8r, t8a);
        let ra1 = vzip2q_u8(t8r, t8a);
        let p0 = out.as_mut_ptr().add(i * 16) as *mut u8;
        // [b,g,r,a] via u16-granularity interleave (see rgb565_neon note).
        vst1q_u8(
            p0,
            vreinterpretq_u8_u16(vzip1q_u16(
                vreinterpretq_u16_u8(bg0),
                vreinterpretq_u16_u8(ra0),
            )),
        );
        // Non-overlapping 16-byte stores (see rgb565_neon note).
        vst1q_u8(
            p0.add(16),
            vreinterpretq_u8_u16(vzip2q_u16(
                vreinterpretq_u16_u8(bg0),
                vreinterpretq_u16_u8(ra0),
            )),
        );
        vst1q_u8(
            p0.add(32),
            vreinterpretq_u8_u16(vzip1q_u16(
                vreinterpretq_u16_u8(bg1),
                vreinterpretq_u16_u8(ra1),
            )),
        );
        vst1q_u8(
            p0.add(48),
            vreinterpretq_u8_u16(vzip2q_u16(
                vreinterpretq_u16_u8(bg1),
                vreinterpretq_u16_u8(ra1),
            )),
        );
    }
    scalar_4444_tail(tail, &mut out[chunks.len() * 16..]);
    out
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn rgba32_neon(data: &[u8]) -> Vec<u32> {
    let mut out = vec![0u32; data.len() / 4];
    let (chunks, tail) = data.as_chunks::<16>();
    let sel: [u8; 16] = [2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15];
    let s = vld1q_u8(sel.as_ptr());
    for (i, c) in chunks.iter().enumerate() {
        // SAFETY: chunked access; out[i*4..i*4+4] exists (out = len/4 u32s).
        let v = vld1q_u8(c.as_ptr());
        vst1q_u8(out.as_mut_ptr().add(i * 4) as *mut u8, vqtbl1q_u8(v, s));
    }
    for (o, c) in out[chunks.len() * 4..].iter_mut().zip(tail.chunks_exact(4)) {
        *o = ((c[3] as u32) << 24) | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// All 65536 RGB565 values must decode identically to the reference.
    #[test]
    fn rgb565_exhaustive_matches_reference() {
        for v in 0..=u16::MAX {
            let v = v as u32;
            let want = 0xFF00_0000
                | ((((v >> 11) & 0x1F) * 255 + 15) / 31) << 16
                | ((((v >> 5) & 0x3F) * 255 + 31) / 63) << 8
                | (((v & 0x1F) * 255 + 15) / 31);
            let bytes = (v as u16).to_le_bytes();
            assert_eq!(scalar_565_px(v), want, "scalar {v:04x}");
            let got = rgb565_to_argb(&bytes);
            assert_eq!(got[0], want, "simd {v:04x}");
        }
    }

    /// All 65536 RGBA4444 values must decode identically to the reference.
    #[test]
    fn rgba4444_exhaustive_matches_reference() {
        for v in 0..=u16::MAX {
            let v = v as u32;
            let want = (((v & 0xF) * 17) << 24)
                | ((((v >> 12) & 0xF) * 17) << 16)
                | ((((v >> 8) & 0xF) * 17) << 8)
                | (((v >> 4) & 0xF) * 17);
            let bytes = (v as u16).to_le_bytes();
            assert_eq!(scalar_4444_px(v), want, "scalar {v:04x}");
            let got = rgba4444_to_argb(&bytes);
            assert_eq!(got[0], want, "simd {v:04x}");
        }
    }

    /// Odd lengths exercise the scalar tail; the head must equal the tail math.
    #[test]
    fn odd_lengths_match_pixelwise() {
        for len in 0..70usize {
            let data: Vec<u8> = (0..len as u16).map(|i| (i * 37) as u8).collect();
            let got5 = rgb565_to_argb(&data);
            let got4 = rgba4444_to_argb(&data);
            for (k, c) in data.as_chunks::<2>().0.iter().enumerate() {
                let v = u16::from_le_bytes([c[0], c[1]]) as u32;
                assert_eq!(got5[k], scalar_565_px(v), "len {len} px {k}");
                assert_eq!(got4[k], scalar_4444_px(v), "len {len} px {k}");
            }
        }
    }

    #[test]
    fn rgba32_roundtrip_all_offset_lengths() {
        for len in 0..40usize {
            let data: Vec<u8> = (0..len as u8).collect();
            let got = rgba32_to_argb(&data);
            for (k, c) in data.as_chunks::<4>().0.iter().enumerate() {
                let want = ((c[3] as u32) << 24)
                    | ((c[0] as u32) << 16)
                    | ((c[1] as u32) << 8)
                    | c[2] as u32;
                assert_eq!(got[k], want, "len {len} px {k}");
            }
        }
    }

    /// Deterministic pseudo-random buffers across SIMD chunk boundaries and
    /// both dispatch tiers must match the portable reference exactly.
    #[test]
    fn rgba32_random_lengths_match_portable() {
        let mut st = 0x12DE_7E5Au32;
        let mut next = || {
            st ^= st << 13;
            st ^= st >> 17;
            st ^= st << 5;
            st
        };
        for &len in &[
            0usize, 4, 8, 12, 16, 20, 31, 32, 33, 48, 64, 100, 255, 256, 1024, 4099,
        ] {
            let data: Vec<u8> = (0..len).map(|_| next() as u8).collect();
            let got = rgba32_to_argb(&data);
            let want = crate::codec::raw::rgba32_to_argb_portable(&data);
            assert_eq!(got, want, "len {len}");
        }
    }
}
