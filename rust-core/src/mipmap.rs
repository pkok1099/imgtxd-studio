//! Pixel resampling — faithful port of the Kotlin `MipmapGenerator`
//! (NEAREST / BOX / BILINEAR, chain generation, reduce-to-fit) plus the
//! `TextureNames` conflict helper from the same file.
//! Pixels are `u32` ARGB, row-major. Channel math matches Kotlin exactly
//! (integer averages; f64 bilinear with truncation toward zero).

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MipFilter {
    Nearest,
    Box,
    Bilinear,
    Cubic,
    Gaussian,
    Lanczos4,
}

impl MipFilter {
    pub fn from_i32(v: i32) -> MipFilter {
        match v {
            0 => MipFilter::Nearest,
            2 => MipFilter::Bilinear,
            3 => MipFilter::Cubic,
            4 => MipFilter::Gaussian,
            5 => MipFilter::Lanczos4,
            _ => MipFilter::Box,
        }
    }
}

#[derive(Clone, Debug)]
pub struct PixelImage {
    pub pixels: Vec<u32>,
    pub width: usize,
    pub height: usize,
}

impl PixelImage {
    pub fn new(pixels: Vec<u32>, width: usize, height: usize) -> Self {
        assert!(
            width > 0 && height > 0,
            "invalid dimensions {width}x{height}"
        );
        assert_eq!(pixels.len(), width * height, "pixel count mismatch");
        Self {
            pixels,
            width,
            height,
        }
    }
}

fn pack(a: u32, r: u32, g: u32, b: u32) -> u32 {
    ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
}

/// Precomputed per-output-column source positions (identical math to
/// sampling inline, hoisted out of the row loop).
fn nearest_axis(dst: usize, src_len: usize) -> Vec<usize> {
    (0..dst)
        .map(|i| (((2 * i + 1) * src_len) / (2 * dst)).min(src_len - 1))
        .collect()
}

/// Precomputed per-output-column source ranges for BOX.
fn box_axis(dst: usize, src_len: usize) -> Vec<(usize, usize)> {
    (0..dst)
        .map(|i| {
            let s0 = (i * src_len) / dst;
            let s1 = ((s0 + 1).max(((i + 1) * src_len) / dst)).min(src_len);
            (s0, s1)
        })
        .collect()
}

/// Bilinear interpolation positions per output index: (i0, i1, frac).
fn bilinear_axis(dst: usize, src_len: usize) -> Vec<(usize, usize, f64)> {
    (0..dst)
        .map(|i| {
            let g = (i as f64 + 0.5) * src_len as f64 / dst as f64 - 0.5;
            let i0 = (g.floor() as i64).clamp(0, src_len as i64 - 1) as usize;
            let i1 = (i0 + 1).min(src_len - 1);
            let f = (g - i0 as f64).clamp(0.0, 1.0);
            (i0, i1, f)
        })
        .collect()
}

fn kernel_cubic(t: f64) -> f64 {
    // Catmull-Rom (a = -0.5).
    let t = t.abs();
    if t < 1.0 {
        1.5 * t * t * t - 2.5 * t * t + 1.0
    } else if t < 2.0 {
        -0.5 * t * t * t + 2.5 * t * t - 4.0 * t + 2.0
    } else {
        0.0
    }
}

fn kernel_gaussian(t: f64) -> f64 {
    if t.abs() >= 2.0 {
        0.0
    } else {
        (-2.0 * t * t).exp()
    }
}

fn sinc(t: f64) -> f64 {
    if t == 0.0 {
        1.0
    } else {
        let pt = std::f64::consts::PI * t;
        pt.sin() / pt
    }
}

fn kernel_lanczos4(t: f64) -> f64 {
    if t.abs() >= 4.0 {
        0.0
    } else {
        sinc(t) * sinc(t / 4.0)
    }
}

/// One tap of a precomputed resampling weight axis: source position
/// (already clamped) and its kernel weight.
struct Tap {
    pos: usize,
    w: f64,
}

/// Precomputed kernel weights per output index for one axis. The taps
/// reproduce `sample_kernel`'s window exactly: gx = (i + 0.5) * scale - 0.5,
/// taps from floor(gx) - r ..= floor(gx) + r with r = ceil(radius * scale),
/// weight = kernel((t - gx) / scale), zero weights dropped (they contribute
/// nothing to the weighted sums), positions clamped to the source edge.
fn kernel_axis(dst: usize, src_len: usize, radius: f64, kernel: fn(f64) -> f64) -> Vec<Vec<Tap>> {
    let scale = src_len as f64 / dst as f64;
    let r = (radius * scale).ceil() as i64;
    let last = src_len as i64 - 1;
    (0..dst)
        .map(|i| {
            let g = (i as f64 + 0.5) * scale - 0.5;
            let mut taps = Vec::new();
            for t in (g as i64 - r)..=(g as i64 + r) {
                let w = kernel((t as f64 - g) / scale);
                if w != 0.0 {
                    taps.push(Tap {
                        pos: t.clamp(0, last) as usize,
                        w,
                    });
                }
            }
            taps
        })
        .collect()
}

/// Shared per-call context handed to the per-row band workers.
struct RowCtx<'a> {
    px: &'a [u32],
    sw: usize,
    sh: usize,
    dst_w: usize,
    dst_h: usize,
}

/// Output-pixel count above which row bands go to worker threads for the
/// scalar row kernels (bilinear-general, cubic/gaussian/lanczos: ~30-90
/// cycles per output pixel) — at 256x256 the per-band work dwarfs spawn.
const PARALLEL_MIN_PX: usize = 65536;
/// Threshold for the vectorized exact-2:1 kernels (SSSE3/NEON 4-tap mean,
/// ~2-3 cycles per output pixel): spawning 8 workers for ~35 us of compute
/// is a net loss (measured on 8-core: 512->256 cost 0.70 ms vs ~50 us of
/// kernel work), so banding waits until ~512x512 output.
const PARALLEL_MIN_PX_FAST_KERNEL: usize = 262144;

/// 1-minute load average per core at or above which the host counts as
/// busy. Pass-11 measured thread-spawn cost inflate from ~0.1-0.2 ms
/// (idle) to ~0.5-0.7 ms (loaded VM, neighbor compilation), which turned
/// banding into a 2-3x loss even at 4x the idle threshold. The margin
/// below keeps idle-host behavior untouched and only raises the cutoff
/// when observed load says spawn cost is inflated.
const BUSY_LOAD_PER_CORE: f64 = 0.5;
/// Under load, require 8x the output pixels before paying spawn cost:
/// 8 x 262144 = 2M px (fast kernels) / 8 x 65536 = 512K px (row kernels).
/// Serial work at 2M px is ~1.5-2 ms even at 2-3 cycles/px, dwarfing the
/// worst measured loaded-spawn overhead (~0.7 ms); on an idle host the
/// margin is 1 and the pass-8 thresholds apply unchanged. Both sides of
/// the cutoff write identical output (disjoint row bands, per-pixel math
/// unchanged), so the margin only trades latency, never bytes.
const BUSY_THRESHOLD_MARGIN: usize = 8;

/// First field of `/proc/loadavg` (1-minute load average) as f64.
fn parse_loadavg_first(s: &str) -> Option<f64> {
    s.split_whitespace().next()?.parse().ok()
}

/// 1-minute load average divided by the core count. `None` when the
/// platform has no loadavg file or the core count is unavailable —
/// callers treat that as "not busy" (pass-11 behavior).
fn host_load_per_core() -> Option<f64> {
    let cores = std::thread::available_parallelism().ok()?.get() as f64;
    let raw = std::fs::read_to_string("/proc/loadavg").ok()?;
    Some(parse_loadavg_first(&raw)? / cores)
}

/// Pure decision core of [`load_margin`] (unit-testable, no host state).
fn load_margin_for(load_per_core: Option<f64>) -> usize {
    match load_per_core {
        Some(load) if load >= BUSY_LOAD_PER_CORE => BUSY_THRESHOLD_MARGIN,
        _ => 1,
    }
}

/// Runtime threshold multiplier: [`BUSY_THRESHOLD_MARGIN`] when the host
/// is busy (1-min loadavg per core >= [`BUSY_LOAD_PER_CORE`]), 1 otherwise.
/// Reading /proc/loadavg costs ~1 us and is reached only for outputs
/// already at/above the idle threshold (>= ~35 us of kernel work).
fn load_margin() -> usize {
    load_margin_for(host_load_per_core())
}

/// Runs the row-band closure over all output rows — inline for small outputs
/// (thread spawn overhead dominates) or on scoped worker threads writing
/// disjoint row bands (deterministic: per-pixel math is unchanged, so the
/// result is identical to the serial path). `min_px` picks the threshold
/// constant matching the kernel the closure runs; on a busy host the
/// effective threshold is multiplied by [`load_margin`] (pass-11 showed
/// banding losing 2-3x under load even at 4x the idle cutoff).
fn dispatch_rows(
    out: &mut [u32],
    dst_w: usize,
    dst_h: usize,
    min_px: usize,
    f: &(impl Fn(usize, usize, &mut [u32]) + Sync),
) {
    let px_count = dst_w * dst_h;
    if px_count < min_px {
        f(0, dst_h, out);
        return;
    }
    // Load-aware margin — reached only for outputs at/above the idle
    // threshold, so the loadavg read never taxes the small-output path.
    if px_count < min_px * load_margin() {
        f(0, dst_h, out);
        return;
    }
    // Nested-parallelism control: inside an outer concurrent phase (batch
    // import, sync_after_write, thumbnail sync) the caller is already one of
    // N balanced workers, so banding here would multiply threads past the
    // core count. inner_workers reports 1 then, and the inline path writes
    // the same bytes as the banded one (same kernel, disjoint slices).
    let workers = crate::parallel::inner_workers(4);
    if workers <= 1 {
        f(0, dst_h, out);
        return;
    }
    // At least 4 rows per band so per-thread work stays >> spawn cost.
    let rows_per_band = dst_h.div_ceil(workers).max(4);
    let chunk = rows_per_band * dst_w;
    std::thread::scope(|s| {
        for (band, chunk_out) in out.chunks_mut(chunk).enumerate() {
            let y0 = band * rows_per_band;
            let y1 = (y0 + rows_per_band).min(dst_h);
            s.spawn(move || f(y0, y1, chunk_out));
        }
    });
}

/// BOX rows [y0, y1) into band-local `out` (slice row 0 = output row y0).
fn box_rows(
    ctx: &RowCtx,
    xs: &[(usize, usize)],
    ys: &[(usize, usize)],
    y0: usize,
    y1: usize,
    out: &mut [u32],
) {
    let (px, sw, dst_w) = (ctx.px, ctx.sw, ctx.dst_w);
    for (ry, y) in (y0..y1).enumerate() {
        let (y0b, y1b) = ys[y];
        let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
        for (x, or) in orow.iter_mut().enumerate() {
            let (x0, x1) = xs[x];
            let (mut sa, mut sr, mut sg, mut sb, mut count) = (0u32, 0u32, 0u32, 0u32, 0u32);
            for srow in y0b..y1b {
                let base = srow * sw;
                for sx in x0..x1 {
                    let p = px[base + sx];
                    sa += (p >> 24) & 0xFF;
                    sr += (p >> 16) & 0xFF;
                    sg += (p >> 8) & 0xFF;
                    sb += p & 0xFF;
                    count += 1;
                }
            }
            *or = pack(sa / count, sr / count, sg / count, sb / count);
        }
    }
}

/// BOX rows [y0, y1) for the exact 2×2 case (dst = src/2 on both axes).
/// Each output pixel averages 4 source pixels with the same integer
/// `(sum / 4)` truncating division as `box_rows` — the SIMD paths widen the
/// bytes to u16 lanes, sum all four pixels per channel and shift right by 2,
/// which is that same division exactly.
fn box2x2_rows(ctx: &RowCtx, y0: usize, y1: usize, out: &mut [u32]) {
    #[cfg(target_arch = "x86_64")]
    {
        if crate::codec::simd::tier() >= 2 {
            // SAFETY: SSSE3 verified by tier(); rows [2y, 2y+2) and columns
            // [2x, 2x+4) are in-bounds because src is exactly 2·dst_w wide
            // and 2·dst_h tall, and `out` covers rows [y0, y1) of dst_w.
            unsafe {
                box2x2_rows_x86(ctx, y0, y1, out);
            }
            return;
        }
    }
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: NEON baseline; bounds contract as above.
        unsafe {
            box2x2_rows_neon(ctx, y0, y1, out);
        }
        return;
    }
    #[cfg(not(target_arch = "aarch64"))]
    box2x2_rows_scalar(ctx, y0, y1, out);
}

#[cfg(not(target_arch = "aarch64"))]
fn box2x2_rows_scalar(ctx: &RowCtx, y0: usize, y1: usize, out: &mut [u32]) {
    let (px, sw, dst_w) = (ctx.px, ctx.sw, ctx.dst_w);
    for (ry, y) in (y0..y1).enumerate() {
        let (r0, r1) = (2 * y * sw, (2 * y + 1) * sw);
        let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
        for (x, or) in orow.iter_mut().enumerate() {
            let (a, b) = (px[r0 + 2 * x], px[r0 + 2 * x + 1]);
            let (c, d) = (px[r1 + 2 * x], px[r1 + 2 * x + 1]);
            let sa =
                ((a >> 24) & 0xFF) + ((b >> 24) & 0xFF) + ((c >> 24) & 0xFF) + ((d >> 24) & 0xFF);
            let sr =
                ((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF);
            let sg = ((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF);
            let sb = (a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF);
            *or = pack(sa / 4, sr / 4, sg / 4, sb / 4);
        }
    }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "ssse3")]
unsafe fn box2x2_rows_x86(ctx: &RowCtx, y0: usize, y1: usize, out: &mut [u32]) {
    use core::arch::x86_64::*;
    let (px, sw, dst_w) = (ctx.px, ctx.sw, ctx.dst_w);
    let zero = _mm_setzero_si128();
    // Gather the low byte of each u16 lane → [b, g, r, a] packed pixel.
    let packsel = _mm_setr_epi8(
        0, 2, 4, 6, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128, -128,
    );
    for (ry, y) in (y0..y1).enumerate() {
        let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
        let r0 = &px[2 * y * sw..][..sw];
        let r1 = &px[(2 * y + 1) * sw..][..sw];
        let mut x = 0;
        while x + 2 <= dst_w {
            // SAFETY: 2x..2x+4 < sw = 2*dst_w source pixels per row; the
            // store writes orow[x..x+2] which exists (x+2 <= dst_w).
            let a0 = _mm_loadu_si128(r0.as_ptr().add(2 * x) as *const __m128i);
            let a1 = _mm_loadu_si128(r1.as_ptr().add(2 * x) as *const __m128i);
            // Spread bytes to u16 lanes: [px0 | px1] and [px2 | px3] halves.
            let (lo0, hi0) = (_mm_unpacklo_epi8(a0, zero), _mm_unpackhi_epi8(a0, zero));
            let (lo1, hi1) = (_mm_unpacklo_epi8(a1, zero), _mm_unpackhi_epi8(a1, zero));
            // Horizontal pair sum then cross-row sum: lanes 0..3 = 4-px sums.
            let pair = |v: __m128i| _mm_add_epi16(v, _mm_srli_si128(v, 8));
            let s01 = _mm_add_epi16(pair(lo0), pair(lo1));
            let s23 = _mm_add_epi16(pair(hi0), pair(hi1));
            let avg0 = _mm_srli_epi16(s01, 2);
            let avg1 = _mm_srli_epi16(s23, 2);
            let p = _mm_unpacklo_epi32(
                _mm_shuffle_epi8(avg0, packsel),
                _mm_shuffle_epi8(avg1, packsel),
            );
            _mm_storel_epi64(orow.as_mut_ptr().add(x) as *mut __m128i, p);
            x += 2;
        }
        if x < dst_w {
            // Odd-width tail: one pixel, identical scalar math.
            let (r0b, r1b) = (2 * y * sw, (2 * y + 1) * sw);
            let (a, b) = (px[r0b + 2 * x], px[r0b + 2 * x + 1]);
            let (c, d) = (px[r1b + 2 * x], px[r1b + 2 * x + 1]);
            let ch = |s: u32| -> u32 {
                (((a >> s) & 0xFF) + ((b >> s) & 0xFF) + ((c >> s) & 0xFF) + ((d >> s) & 0xFF)) / 4
            };
            *orow.last_mut().unwrap() = pack(ch(24), ch(16), ch(8), ch(0));
        }
    }
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn box2x2_rows_neon(ctx: &RowCtx, y0: usize, y1: usize, out: &mut [u32]) {
    use core::arch::aarch64::*;
    let (px, sw, dst_w) = (ctx.px, ctx.sw, ctx.dst_w);
    let zero = vdupq_n_u8(0);
    for (ry, y) in (y0..y1).enumerate() {
        let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
        let r0 = &px[2 * y * sw..][..sw];
        let r1 = &px[(2 * y + 1) * sw..][..sw];
        let mut x = 0;
        while x + 2 <= dst_w {
            // SAFETY: bounds contract as in box2x2_rows_x86.
            let a0 = vld1q_u8(r0.as_ptr().add(2 * x) as *const u8);
            let a1 = vld1q_u8(r1.as_ptr().add(2 * x) as *const u8);
            let (lo0, hi0) = (vzip1q_u8(a0, zero), vzip2q_u8(a0, zero));
            let (lo1, hi1) = (vzip1q_u8(a1, zero), vzip2q_u8(a1, zero));
            let pair = |v: uint8x16_t| {
                vaddq_u16(
                    vreinterpretq_u16_u8(v),
                    vreinterpretq_u16_u8(vextq_u8(v, v, 8)),
                )
            };
            let avg0 = vshrq_n_u16(vaddq_u16(pair(lo0), pair(lo1)), 2);
            let avg1 = vshrq_n_u16(vaddq_u16(pair(hi0), pair(hi1)), 2);
            // Pick even bytes: [b, g, r, a] per pixel.
            let p0 = vuzp1q_u8(vreinterpretq_u8_u16(avg0), vreinterpretq_u8_u16(avg0));
            let p1 = vuzp1q_u8(vreinterpretq_u8_u16(avg1), vreinterpretq_u8_u16(avg1));
            let u0 = vreinterpretq_u32_u8(p0);
            let u1 = vreinterpretq_u32_u8(p1);
            orow[x] = vgetq_lane_u32(u0, 0);
            orow[x + 1] = vgetq_lane_u32(u1, 0);
            x += 2;
        }
        if x < dst_w {
            let (r0b, r1b) = (2 * y * sw, (2 * y + 1) * sw);
            let (a, b) = (px[r0b + 2 * x], px[r0b + 2 * x + 1]);
            let (c, d) = (px[r1b + 2 * x], px[r1b + 2 * x + 1]);
            let ch = |s: u32| -> u32 {
                (((a >> s) & 0xFF) + ((b >> s) & 0xFF) + ((c >> s) & 0xFF) + ((d >> s) & 0xFF)) / 4
            };
            *orow.last_mut().unwrap() = pack(ch(24), ch(16), ch(8), ch(0));
        }
    }
}

/// Bilinear rows [y0, y1) into band-local `out`.
fn bilinear_rows(
    ctx: &RowCtx,
    xs: &[(usize, usize, f64)],
    ys: &[(usize, usize, f64)],
    y0: usize,
    y1: usize,
    out: &mut [u32],
) {
    let (px, sw, dst_w) = (ctx.px, ctx.sw, ctx.dst_w);
    for (ry, y) in (y0..y1).enumerate() {
        let (y0b, y1b, fy) = ys[y];
        let r0 = y0b * sw;
        let r1 = y1b * sw;
        let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
        for (x, or) in orow.iter_mut().enumerate() {
            let (x0, x1, fx) = xs[x];
            let c00 = px[r0 + x0];
            let c10 = px[r0 + x1];
            let c01 = px[r1 + x0];
            let c11 = px[r1 + x1];
            let mix = |shift: u32| -> u32 {
                let v00 = ((c00 >> shift) & 0xFF) as f64;
                let v10 = ((c10 >> shift) & 0xFF) as f64;
                let v01 = ((c01 >> shift) & 0xFF) as f64;
                let v11 = ((c11 >> shift) & 0xFF) as f64;
                let top = v00 + (v10 - v00) * fx;
                let bottom = v01 + (v11 - v01) * fx;
                ((top + (bottom - top) * fy) as i64).clamp(0, 255) as u32
            };
            *or = pack(mix(24), mix(16), mix(8), mix(0));
        }
    }
}

/// Kernel-filter rows [y0, y1) into band-local `out`.
fn kernel_rows(
    ctx: &RowCtx,
    txs: &[Vec<Tap>],
    tys: &[Vec<Tap>],
    y0: usize,
    y1: usize,
    out: &mut [u32],
) {
    let (px, sw, dst_w) = (ctx.px, ctx.sw, ctx.dst_w);
    for (ry, y) in (y0..y1).enumerate() {
        let tys_row = &tys[y];
        let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
        for (x, or) in orow.iter_mut().enumerate() {
            let (mut sa, mut sr, mut sg, mut sb, mut sw_sum) = (0.0f64, 0.0, 0.0, 0.0, 0.0);
            for ty in tys_row {
                let row = ty.pos * sw;
                let wy = ty.w;
                for tx in &txs[x] {
                    let w = wy * tx.w;
                    let p = px[row + tx.pos];
                    sa += ((p >> 24) & 0xFF) as f64 * w;
                    sr += ((p >> 16) & 0xFF) as f64 * w;
                    sg += ((p >> 8) & 0xFF) as f64 * w;
                    sb += (p & 0xFF) as f64 * w;
                    sw_sum += w;
                }
            }
            if sw_sum == 0.0 {
                *or = px[ty_fallback(sw, ctx.sh, x, y, dst_w, ctx.dst_h)];
                continue;
            }
            let norm = |v: f64| -> u32 { (v / sw_sum).round().clamp(0.0, 255.0) as u32 };
            *or = pack(norm(sa), norm(sr), norm(sg), norm(sb));
        }
    }
}

/// Resamples `src` to exactly `dst_w` x `dst_h` using `filter`.
///
/// All filters precompute their per-axis sampling state once per call and
/// keep the per-pixel math identical to the per-pixel sampling versions
/// they replace (same tap windows, same weight products, same summation
/// order), so outputs are unchanged — the kernels are simply no longer
/// re-evaluated (sin/exp/div) for every output pixel. Large outputs are
/// split into horizontal row bands processed on worker threads.
pub fn resample(src: &PixelImage, dst_w: usize, dst_h: usize, filter: MipFilter) -> PixelImage {
    assert!(dst_w > 0 && dst_h > 0, "invalid target dimensions");
    if src.width == dst_w && src.height == dst_h {
        return src.clone();
    }
    let pixels = resample_px(&src.pixels, src.width, src.height, dst_w, dst_h, filter);
    PixelImage {
        pixels,
        width: dst_w,
        height: dst_h,
    }
}

/// Borrowed-slice core of [`resample`], shared with `txd::resample` whose
/// public signature is the Kotlin port's slice-based one. The per-pixel math
/// is unchanged by this split — only the plumbing moved (pass-11).
pub fn resample_px(
    px: &[u32],
    sw: usize,
    sh: usize,
    dst_w: usize,
    dst_h: usize,
    filter: MipFilter,
) -> Vec<u32> {
    assert!(dst_w > 0 && dst_h > 0, "invalid target dimensions");
    assert_eq!(px.len(), sw * sh, "pixel count mismatch");
    if sw == dst_w && sh == dst_h {
        return px.to_vec();
    }
    let mut out = vec![0u32; dst_w * dst_h];
    let ctx = RowCtx {
        px,
        sw,
        sh,
        dst_w,
        dst_h,
    };
    match filter {
        MipFilter::Nearest => {
            if sw == dst_w * 2 && sh == dst_h * 2 {
                // Exact 2:1: `nearest_axis` degenerates to 2i+1 —
                // ((2i+1)·2d)/(2d) is exactly 2i+1 in integer math — so
                // gather the odd-lattice pixels directly without tables;
                // LLVM auto-vectorizes this just like the pre-pass-11
                // inline sampler (table indirection defeated that).
                // Locked by `resample_matches_reference_random` (2:1 case).
                for (ry, y) in (0..dst_h).enumerate() {
                    let srow = (2 * y + 1) * sw;
                    let orow = &mut out[ry * dst_w..(ry + 1) * dst_w];
                    for (x, or) in orow.iter_mut().enumerate() {
                        *or = px[srow + 2 * x + 1];
                    }
                }
            } else {
                let xs = nearest_axis(dst_w, sw);
                let ys = nearest_axis(dst_h, sh);
                for y in 0..dst_h {
                    let sy = ys[y] * sw;
                    for x in 0..dst_w {
                        out[y * dst_w + x] = px[sy + xs[x]];
                    }
                }
            }
        }
        MipFilter::Box => {
            let xs = box_axis(dst_w, sw);
            let ys = box_axis(dst_h, sh);
            if sw == dst_w * 2 && sh == dst_h * 2 {
                // Exact 2×2 windows (the mip-chain case): vectorized path.
                dispatch_rows(
                    &mut out,
                    dst_w,
                    dst_h,
                    PARALLEL_MIN_PX_FAST_KERNEL,
                    &|y0, y1, oband| {
                        box2x2_rows(&ctx, y0, y1, oband);
                    },
                );
            } else {
                dispatch_rows(&mut out, dst_w, dst_h, PARALLEL_MIN_PX, &|y0, y1, oband| {
                    box_rows(&ctx, &xs, &ys, y0, y1, oband);
                });
            }
        }
        MipFilter::Bilinear => {
            if sw == dst_w * 2 && sh == dst_h * 2 {
                // Exact 2:1 (the mip-chain case): `bilinear_axis` yields the
                // window (2i, 2i+1) with fx = fy = 0.5 for every index. All
                // f64 intermediates of the scalar kernel are then exact
                // dyadic rationals — halves/quarters of integers <= 1020,
                // every +, -, *0.5 rounding-free — so the per-channel result
                // is exactly floor((v00+v10+v01+v11)/4): the same truncating
                // 4-tap mean the Box 2x2 kernel computes. Routing there
                // reuses the vectorized (SSSE3/NEON) kernel unchanged;
                // `bilinear_exact_2x1_matches_general_path` proves equality
                // with the general f64 path on random images.
                dispatch_rows(
                    &mut out,
                    dst_w,
                    dst_h,
                    PARALLEL_MIN_PX_FAST_KERNEL,
                    &|y0, y1, oband| {
                        box2x2_rows(&ctx, y0, y1, oband);
                    },
                );
            } else {
                let xs = bilinear_axis(dst_w, sw);
                let ys = bilinear_axis(dst_h, sh);
                dispatch_rows(&mut out, dst_w, dst_h, PARALLEL_MIN_PX, &|y0, y1, oband| {
                    bilinear_rows(&ctx, &xs, &ys, y0, y1, oband);
                });
            }
        }
        filter => {
            let (kernel, radius) = match filter {
                MipFilter::Cubic => (kernel_cubic as fn(f64) -> f64, 2.0),
                MipFilter::Gaussian => (kernel_gaussian as fn(f64) -> f64, 2.0),
                _ => (kernel_lanczos4 as fn(f64) -> f64, 4.0),
            };
            let txs = kernel_axis(dst_w, sw, radius, kernel);
            let tys = kernel_axis(dst_h, sh, radius, kernel);
            dispatch_rows(&mut out, dst_w, dst_h, PARALLEL_MIN_PX, &|y0, y1, oband| {
                kernel_rows(&ctx, &txs, &tys, y0, y1, oband);
            });
        }
    }
    out
}

/// Weightless-window fallback (identical to `sample_kernel`'s sw == 0 path).
fn ty_fallback(sw: usize, sh: usize, x: usize, y: usize, dst_w: usize, dst_h: usize) -> usize {
    let scale_x = sw as f64 / dst_w as f64;
    let scale_y = sh as f64 / dst_h as f64;
    let gx = (x as f64 + 0.5) * scale_x - 0.5;
    let gy = (y as f64 + 0.5) * scale_y - 0.5;
    let cx = (gx as i64).clamp(0, sw as i64 - 1) as usize;
    let cy = (gy as i64).clamp(0, sh as i64 - 1) as usize;
    cy * sw + cx
}

/// One halving step; odd sizes round down with a 1px floor.
pub fn downscale_half(src: &PixelImage, filter: MipFilter) -> PixelImage {
    resample(src, (src.width / 2).max(1), (src.height / 2).max(1), filter)
}

/// Full mip chain below `src` (level 1, 2, ...) down to 1x1. Empty for 1x1.
/// Each level is produced directly from the previous one — no per-level
/// buffer clone.
pub fn generate_chain(src: &PixelImage, filter: MipFilter) -> Vec<PixelImage> {
    let mut levels = Vec::new();
    loop {
        let current = levels.last().unwrap_or(src);
        if current.width == 1 && current.height == 1 {
            break;
        }
        let next = downscale_half(current, filter);
        levels.push(next);
    }
    levels
}

/// Dimensions after fitting `width`x`height` inside `max_dimension`.
pub fn resized_dimensions(width: usize, height: usize, max_dimension: usize) -> (usize, usize) {
    assert!(max_dimension > 0, "invalid max dimension");
    let longest = width.max(height);
    if longest <= max_dimension {
        return (width, height);
    }
    let scale = max_dimension as f64 / longest as f64;
    (
        ((width as f64 * scale) as usize).max(1),
        ((height as f64 * scale) as usize).max(1),
    )
}

/// Reduces `src` so its longest side is at most `max_dimension`.
pub fn reduce_to_fit(src: &PixelImage, max_dimension: usize, filter: MipFilter) -> PixelImage {
    let (w, h) = resized_dimensions(src.width, src.height, max_dimension);
    if w == src.width && h == src.height {
        return src.clone();
    }
    resample(src, w, h, filter)
}

/// TXD Tool "Just add" policy: returns `desired` when free, else `name_1`, `name_2`, ...
/// (case-insensitive check), mirroring Kotlin `TextureNames.uniqueName`.
pub fn unique_name(existing: &[String], desired: &str) -> String {
    let taken: std::collections::HashSet<String> =
        existing.iter().map(|n| n.to_lowercase()).collect();
    if !taken.contains(&desired.to_lowercase()) {
        return desired.to_string();
    }
    let mut index = 1;
    loop {
        let candidate = format!("{desired}_{index}");
        if !taken.contains(&candidate.to_lowercase()) {
            return candidate;
        }
        index += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn img(pixels: Vec<u32>, w: usize, h: usize) -> PixelImage {
        PixelImage::new(pixels, w, h)
    }

    #[test]
    fn box_downscale_2x2_averages_all_channels() {
        let src = img(vec![0xFF000000, 0xFF404040, 0xFF808080, 0xFFC0C0C0], 2, 2);
        let out = downscale_half(&src, MipFilter::Box);
        assert_eq!((out.width, out.height), (1, 1));
        // gray average: (0 + 64 + 128 + 192) / 4 = 96 = 0x60, alpha 255
        assert_eq!(out.pixels[0], 0xFF606060);
    }

    #[test]
    fn nearest_picks_texel_centers() {
        let px: Vec<u32> = (0..16).map(|i| 0xFF000000 | i).collect();
        let src = img(px, 4, 4);
        let out = resample(&src, 2, 2, MipFilter::Nearest);
        // centers: (1,1), (3,1), (1,3), (3,3) -> values 5, 7, 13, 15
        assert_eq!(
            out.pixels,
            vec![0xFF000005, 0xFF000007, 0xFF00000D, 0xFF00000F]
        );
    }

    #[test]
    fn chain_reaches_1x1() {
        let src = img(vec![0xFF112233; 16], 4, 4);
        let chain = generate_chain(&src, MipFilter::Box);
        assert_eq!(chain.len(), 2);
        assert_eq!((chain[0].width, chain[0].height), (2, 2));
        assert_eq!((chain[1].width, chain[1].height), (1, 1));
        assert_eq!(chain[1].pixels[0], 0xFF112233);
    }

    #[test]
    fn resized_dimensions_scales_longest_side() {
        assert_eq!(resized_dimensions(4000, 1000, 1024), (1024, 256));
        assert_eq!(resized_dimensions(512, 512, 1024), (512, 512));
    }

    #[test]
    fn unique_name_suffixes_case_insensitive() {
        let existing = vec!["a".to_string(), "A_1".to_string()];
        assert_eq!(unique_name(&existing, "a"), "a_2");
        assert_eq!(unique_name(&existing, "b"), "b");
    }

    #[test]
    fn load_margin_decision_table() {
        // No load reading (non-Linux, read failure) => idle behavior.
        assert_eq!(load_margin_for(None), 1);
        // Idle host: unchanged thresholds.
        assert_eq!(load_margin_for(Some(0.0)), 1);
        assert_eq!(load_margin_for(Some(0.17 / 8.0)), 1);
        // At/above the busy cutoff: banded work waits for 8x the pixels.
        assert_eq!(load_margin_for(Some(0.5)), BUSY_THRESHOLD_MARGIN);
        // Heavily loaded (pass-11 conditions): margin applies.
        assert_eq!(load_margin_for(Some(4.0)), BUSY_THRESHOLD_MARGIN);
        assert_eq!(load_margin_for(Some(10.0)), BUSY_THRESHOLD_MARGIN);
    }

    #[test]
    fn parse_loadavg_first_field() {
        assert_eq!(
            parse_loadavg_first("0.17 0.13 0.08 1/688 2375150"),
            Some(0.17)
        );
        assert_eq!(parse_loadavg_first("2.50 1.00 0.50 3/477 1234"), Some(2.5));
        assert_eq!(parse_loadavg_first(""), None);
        assert_eq!(parse_loadavg_first("junk 0.5"), None);
        // Trailing garbage in the first field is not a number.
        assert_eq!(parse_loadavg_first("0.13x 0.5"), None);
    }

    /// Exercises both branches of the load-aware dispatch (small => inline,
    /// at-threshold => banded, or inline again when the host is busy): every
    /// output row must be written exactly once with its own index regardless
    /// of which path ran, which is the property that makes the margin safe.
    #[test]
    fn dispatch_rows_rows_written_exactly_once() {
        for &(w, h) in &[(8usize, 4usize), (64, 64), (256, 257)] {
            let mut out = vec![u32::MAX; w * h];
            dispatch_rows(&mut out, w, h, PARALLEL_MIN_PX, &|y0, y1, band| {
                for (ry, y) in (y0..y1).enumerate() {
                    band[ry * w..(ry + 1) * w].fill(y as u32);
                }
            });
            for (y, row) in out.chunks(w).enumerate() {
                assert!(
                    row.iter().all(|&v| v == y as u32),
                    "row {y} corrupted at {w}x{h}"
                );
            }
        }
    }
}

#[cfg(test)]
mod filter_tests {
    use super::*;

    #[test]
    fn kernel_filters_preserve_solid_color() {
        let src = PixelImage::new(vec![0xFF40_80C0; 64], 8, 8);
        for f in [MipFilter::Cubic, MipFilter::Gaussian, MipFilter::Lanczos4] {
            let out = downscale_half(&src, f);
            assert_eq!(out.pixels.len(), 16);
            assert!(
                out.pixels.iter().all(|&p| p == 0xFF40_80C0),
                "{f:?} drifted: {:08x}",
                out.pixels[0]
            );
        }
    }

    #[test]
    fn kernel_filters_downscale_gradient_monotonic() {
        // Horizontal ramp 0,32,64,... in red; halved output must stay ordered.
        let mut px = Vec::new();
        for _ in 0..4 {
            for x in 0..8 {
                px.push(0xFF00_0000 | ((x * 32) as u32) << 16);
            }
        }
        let src = PixelImage::new(px, 8, 4);
        for f in [MipFilter::Cubic, MipFilter::Gaussian, MipFilter::Lanczos4] {
            let out = downscale_half(&src, f);
            let reds: Vec<u32> = out.pixels[..4].iter().map(|p| (p >> 16) & 0xFF).collect();
            assert!(
                reds.windows(2).all(|w| w[0] <= w[1] + 24),
                "{f:?} non-monotonic: {reds:?}"
            );
        }
    }

    /// Bit-exactness vs the original nested-tap scalar algorithm: rebuild
    /// the per-axis taps the way `kernel_axis` does, run the original
    /// per-pixel double loop, and require an identical output from
    /// `resample` (which now routes through the flattened/vectorized path).
    #[test]
    fn kernel_filters_bit_exact_vs_original_algorithm() {
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
        let mut rng = Rng(0x1234_ABCD_5678_EF90);
        for &(sw, sh, dw, dh) in &[
            (16usize, 12usize, 8usize, 6usize),
            (23, 17, 11, 9),
            (64, 48, 32, 24),
            (40, 30, 13, 7),
        ] {
            let px: Vec<u32> = (0..sw * sh)
                .map(|_| {
                    let r = rng.next_u32() & 0xFF;
                    let g = rng.next_u32() & 0xFF;
                    let b = rng.next_u32() & 0xFF;
                    let a = rng.next_u32() & 0xFF;
                    (a << 24) | (r << 16) | (g << 8) | b
                })
                .collect();
            let src = PixelImage::new(px, sw, sh);
            for f in [MipFilter::Cubic, MipFilter::Gaussian, MipFilter::Lanczos4] {
                let (kernel, radius) = match f {
                    MipFilter::Cubic => (kernel_cubic as fn(f64) -> f64, 2.0),
                    MipFilter::Gaussian => (kernel_gaussian as fn(f64) -> f64, 2.0),
                    _ => (kernel_lanczos4 as fn(f64) -> f64, 4.0),
                };
                let txs = kernel_axis(dw, sw, radius, kernel);
                let tys = kernel_axis(dh, sh, radius, kernel);
                let mut want = Vec::with_capacity(dw * dh);
                for ty_row in &tys {
                    for tx_row in &txs {
                        let (mut sa, mut sr, mut sg, mut sb, mut sw_sum) =
                            (0.0f64, 0.0, 0.0, 0.0, 0.0);
                        for ty in ty_row {
                            let row = ty.pos * sw;
                            for tx in tx_row {
                                let w = ty.w * tx.w;
                                let p = src.pixels[row + tx.pos];
                                sa += ((p >> 24) & 0xFF) as f64 * w;
                                sr += ((p >> 16) & 0xFF) as f64 * w;
                                sg += ((p >> 8) & 0xFF) as f64 * w;
                                sb += (p & 0xFF) as f64 * w;
                                sw_sum += w;
                            }
                        }
                        let norm =
                            |v: f64| -> u32 { (v / sw_sum).round().clamp(0.0, 255.0) as u32 };
                        want.push(pack(norm(sa), norm(sr), norm(sg), norm(sb)));
                    }
                }
                let got = resample(&src, dw, dh, f);
                assert_eq!(got.pixels, want, "{f:?} {sw}x{sh}->{dw}x{dh}");
            }
        }
    }

    /// The exact-2:1 bilinear fast path (routed to `box2x2_rows`) must
    /// produce the same pixels as the general f64 kernel: with fx = fy =
    /// 0.5 every intermediate is an exact dyadic rational (no rounding in
    /// any +, -, *0.5 on integers <= 1020), so the truncating 4-tap mean
    /// equals the scalar result for every channel of every pixel. Random
    /// images (all four channels, alpha included), even/odd destination
    /// widths and the smallest 2x2 source guard the routing in `resample`.
    #[test]
    fn bilinear_exact_2x1_matches_general_path() {
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
        let mut rng = Rng(0x0B1E_5EED_2C0F_E00D);
        for &(sw, sh) in &[
            (2usize, 2usize),
            (4, 2),
            (2, 4),
            (4, 6),
            (16, 16),
            (30, 18),
            (64, 48),
            (130, 2),
        ] {
            let px: Vec<u32> = (0..sw * sh).map(|_| rng.next_u32()).collect();
            let src = PixelImage::new(px, sw, sh);
            let (dw, dh) = (sw / 2, sh / 2);
            // General f64 path, driven exactly as `resample` drives it.
            let xs = bilinear_axis(dw, sw);
            let ys = bilinear_axis(dh, sh);
            let ctx = RowCtx {
                px: &src.pixels,
                sw,
                sh,
                dst_w: dw,
                dst_h: dh,
            };
            let mut want = vec![0u32; dw * dh];
            bilinear_rows(&ctx, &xs, &ys, 0, dh, &mut want);
            let got = resample(&src, dw, dh, MipFilter::Bilinear);
            assert_eq!((got.width, got.height), (dw, dh));
            assert_eq!(got.pixels, want, "bilinear 2:1 {sw}x{sh}->{dw}x{dh}");
        }
    }
}
