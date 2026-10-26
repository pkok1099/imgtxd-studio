//! Raw (uncompressed) pixel format conversions shared by texdb listings and
//! PNG import — pure moves of the former `texdb.rs` inline loops, so every
//! function's output is unchanged by construction. Pixels are `u32` ARGB
//! (0xAARRGGBB, little-endian byte order B,G,R,A in memory).
//!
//! The decode directions route through `codec::simd::conv` (runtime-selected
//! SSE2/AVX2 on x86_64, NEON on aarch64, identical portable math elsewhere);
//! the encode directions are the scalar chunked loops below (memory-bound).

/// Portable RGBA8888 → ARGB used as the fallback and SIMD tail reference.
/// Compiled on every arch: on x86_64 it is the tier<2 runtime fallback, on
/// aarch64 it is the reference the NEON parity test compares against (the
/// pass-12 qemu-aarch64 run caught this fn being x86-only since pass-7 —
/// which had broken aarch64 compilation of the whole crate).
#[cfg_attr(target_arch = "aarch64", allow(dead_code))]
pub(crate) fn rgba32_to_argb_portable(data: &[u8]) -> Vec<u32> {
    data.as_chunks::<4>()
        .0
        .iter()
        .map(|c| ((c[3] as u32) << 24) | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
        .collect()
}

/// RGB565 (2 bytes LE per pixel) → ARGB. Channel expansion is the
/// round-half-up form `((v * 255) + half) / max` matching the Kotlin original.
pub fn rgb565_to_argb(data: &[u8]) -> Vec<u32> {
    crate::codec::simd::conv::rgb565_to_argb(data)
}

/// RGBA4444 (2 bytes LE per pixel) → ARGB. Each nibble scales by ×17.
pub fn rgba4444_to_argb(data: &[u8]) -> Vec<u32> {
    crate::codec::simd::conv::rgba4444_to_argb(data)
}

/// RGBA8888 (4 bytes R,G,B,A per pixel) → ARGB.
pub fn rgba32_to_argb(data: &[u8]) -> Vec<u32> {
    crate::codec::simd::conv::rgba32_to_argb(data)
}

/// RGB888 (3 bytes R,G,B per pixel) → opaque ARGB.
pub fn rgb24_to_argb(data: &[u8]) -> Vec<u32> {
    data.as_chunks::<3>()
        .0
        .iter()
        .map(|c| 0xFF00_0000 | ((c[0] as u32) << 16) | ((c[1] as u32) << 8) | c[2] as u32)
        .collect()
}

/// Grayscale (1 byte per pixel) → opaque ARGB (channel replicated).
pub fn gray8_to_argb(data: &[u8]) -> Vec<u32> {
    data.iter()
        .map(|&v| 0xFF00_0000 | ((v as u32) << 16) | ((v as u32) << 8) | v as u32)
        .collect()
}

/// Grayscale+alpha (2 bytes per pixel) → ARGB.
pub fn gray_alpha8_to_argb(data: &[u8]) -> Vec<u32> {
    data.as_chunks::<2>()
        .0
        .iter()
        .map(|c| ((c[1] as u32) << 24) | ((c[0] as u32) << 16) | ((c[0] as u32) << 8) | c[0] as u32)
        .collect()
}

/// ARGB pixels → RGB565 bytes (2 LE per pixel; truncating >> on each channel).
/// Fixed-size chunked writes (no `extend_from_slice`) so LLVM can keep the
/// loop branch-free and vectorize the 2-byte stores.
pub fn argb_to_rgb565_bytes(pixels: &[u32]) -> Vec<u8> {
    let mut out = vec![0u8; pixels.len() * 2];
    for (q, &p) in out.as_chunks_mut::<2>().0.iter_mut().zip(pixels) {
        let v =
            ((((p >> 16) & 0xFF) >> 3) << 11) | ((((p >> 8) & 0xFF) >> 2) << 5) | ((p & 0xFF) >> 3);
        *q = (v as u16).to_le_bytes();
    }
    out
}

/// ARGB pixels → RGBA4444 bytes (2 LE per pixel; truncating >> 4 per nibble).
pub fn argb_to_rgba4444_bytes(pixels: &[u32]) -> Vec<u8> {
    let mut out = vec![0u8; pixels.len() * 2];
    for (q, &p) in out.as_chunks_mut::<2>().0.iter_mut().zip(pixels) {
        let v = ((((p >> 16) & 0xFF) >> 4) << 12)
            | ((((p >> 8) & 0xFF) >> 4) << 8)
            | (((p & 0xFF) >> 4) << 4)
            | ((p >> 24) & 0xFF) >> 4;
        *q = (v as u16).to_le_bytes();
    }
    out
}

/// ARGB pixels → RGBA8888 bytes (R,G,B,A per pixel). Each pixel is one
/// fixed `[u8; 4]` store — no bounds checks, no growth, and LLVM lowers the
/// loop to 16-byte shuffle+store groups instead of four checked byte pushes.
pub fn argb_to_rgba32_bytes(pixels: &[u32]) -> Vec<u8> {
    let mut out = vec![0u8; pixels.len() * 4];
    for (q, &p) in out.as_chunks_mut::<4>().0.iter_mut().zip(pixels) {
        *q = [
            ((p >> 16) & 0xFF) as u8,
            ((p >> 8) & 0xFF) as u8,
            (p & 0xFF) as u8,
            (p >> 24) as u8,
        ];
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rgb565_roundtrip_preserves_channels() {
        // encode from a known ARGB, decode back; 565 is lossy so check the
        // decode expansion matches the reference formula per channel.
        let px = [0xFF21_4523u32, 0x0000_0000, 0xFFFF_FFFF];
        let enc = argb_to_rgb565_bytes(&px);
        let dec = rgb565_to_argb(&enc);
        let expand = |v: u32, half: u32, max: u32| (v * 255 + half) / max;
        for (orig, got) in px.iter().zip(dec.iter()) {
            let or = ((orig >> 16) & 0xFF) >> 3;
            let og = ((orig >> 8) & 0xFF) >> 2;
            let ob = (orig & 0xFF) >> 3;
            let er = expand(or, 15, 31) << 16;
            let eg = expand(og, 31, 63) << 8;
            let eb = expand(ob, 15, 31);
            assert_eq!(*got, 0xFF00_0000 | er | eg | eb, "orig {orig:08x}");
        }
    }

    #[test]
    fn rgba4444_roundtrip_and_alpha() {
        let px = [0x1234_5678u32, 0xFFED_DCBA];
        let enc = argb_to_rgba4444_bytes(&px);
        let dec = rgba4444_to_argb(&enc);
        for (orig, got) in px.iter().zip(dec.iter()) {
            let r = (((orig >> 16) & 0xFF) >> 4) * 17;
            let g = (((orig >> 8) & 0xFF) >> 4) * 17;
            let b = ((orig & 0xFF) >> 4) * 17;
            let a = (((orig >> 24) & 0xFF) >> 4) * 17;
            assert_eq!(
                *got,
                (a << 24) | (r << 16) | (g << 8) | b,
                "orig {orig:08x}"
            );
        }
    }

    #[test]
    fn rgba32_roundtrip_exact() {
        let px = [0x1234_5678u32, 0xFFAA_BBCC, 0x0000_0000];
        let enc = argb_to_rgba32_bytes(&px);
        let dec = rgba32_to_argb(&enc);
        assert_eq!(px.as_slice(), dec.as_slice());
    }

    #[test]
    fn gray_variants() {
        assert_eq!(gray8_to_argb(&[0x80]), vec![0xFF80_8080]);
        assert_eq!(gray_alpha8_to_argb(&[0x40, 0xC0]), vec![0xC040_4040]);
        assert_eq!(rgb24_to_argb(&[0x11, 0x22, 0x33]), vec![0xFF11_2233]);
    }
}
