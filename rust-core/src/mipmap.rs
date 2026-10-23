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
        assert!(width > 0 && height > 0, "invalid dimensions {width}x{height}");
        assert_eq!(pixels.len(), width * height, "pixel count mismatch");
        Self { pixels, width, height }
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
                    taps.push(Tap { pos: t.clamp(0, last) as usize, w });
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
    dst_w: usize,
    dst_h: usize,
}

/// Runs the row-band closure over all output rows — inline for small outputs
/// (thread spawn overhead dominates) or on scoped worker threads writing
/// disjoint row bands (deterministic: per-pixel math is unchanged, so the
/// result is identical to the serial path).
fn dispatch_rows(
    out: &mut [u32],
    dst_w: usize,
    dst_h: usize,
    f: &(impl Fn(usize, usize, &mut [u32]) + Sync),
) {
    // Below ~256x256 total output pixels, spawning threads costs more than it saves.
    if dst_w * dst_h < 65536 {
        f(0, dst_h, out);
        return;
    }
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .min(16);
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
            let (mut sa, mut sr, mut sg, mut sb, mut count) =
                (0u32, 0u32, 0u32, 0u32, 0u32);
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
    src: &PixelImage,
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
            let (mut sa, mut sr, mut sg, mut sb, mut sw_sum) =
                (0.0f64, 0.0, 0.0, 0.0, 0.0);
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
                *or = px[ty_fallback(src, x, y, dst_w, ctx.dst_h)];
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
    let sw = src.width;
    let px = &src.pixels;
    let mut out = vec![0u32; dst_w * dst_h];
    let ctx = RowCtx { px, sw, dst_w, dst_h };
    match filter {
        MipFilter::Nearest => {
            let xs = nearest_axis(dst_w, sw);
            let ys = nearest_axis(dst_h, src.height);
            for y in 0..dst_h {
                let sy = ys[y] * sw;
                for x in 0..dst_w {
                    out[y * dst_w + x] = px[sy + xs[x]];
                }
            }
        }
        MipFilter::Box => {
            let xs = box_axis(dst_w, sw);
            let ys = box_axis(dst_h, src.height);
            dispatch_rows(&mut out, dst_w, dst_h, &|y0, y1, oband| {
                box_rows(&ctx, &xs, &ys, y0, y1, oband);
            });
        }
        MipFilter::Bilinear => {
            let xs = bilinear_axis(dst_w, sw);
            let ys = bilinear_axis(dst_h, src.height);
            dispatch_rows(&mut out, dst_w, dst_h, &|y0, y1, oband| {
                bilinear_rows(&ctx, &xs, &ys, y0, y1, oband);
            });
        }
        filter => {
            let (kernel, radius) = match filter {
                MipFilter::Cubic => (kernel_cubic as fn(f64) -> f64, 2.0),
                MipFilter::Gaussian => (kernel_gaussian as fn(f64) -> f64, 2.0),
                _ => (kernel_lanczos4 as fn(f64) -> f64, 4.0),
            };
            let txs = kernel_axis(dst_w, sw, radius, kernel);
            let tys = kernel_axis(dst_h, src.height, radius, kernel);
            dispatch_rows(&mut out, dst_w, dst_h, &|y0, y1, oband| {
                kernel_rows(&ctx, src, &txs, &tys, y0, y1, oband);
            });
        }
    }
    PixelImage { pixels: out, width: dst_w, height: dst_h }
}

/// Weightless-window fallback (identical to `sample_kernel`'s sw == 0 path).
fn ty_fallback(src: &PixelImage, x: usize, y: usize, dst_w: usize, dst_h: usize) -> usize {
    let scale_x = src.width as f64 / dst_w as f64;
    let scale_y = src.height as f64 / dst_h as f64;
    let gx = (x as f64 + 0.5) * scale_x - 0.5;
    let gy = (y as f64 + 0.5) * scale_y - 0.5;
    let cx = (gx as i64).clamp(0, src.width as i64 - 1) as usize;
    let cy = (gy as i64).clamp(0, src.height as i64 - 1) as usize;
    cy * src.width + cx
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
        assert_eq!(out.pixels, vec![0xFF000005, 0xFF000007, 0xFF00000D, 0xFF00000F]);
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
}

#[cfg(test)]
mod filter_tests {
    use super::*;

    #[test]
    fn kernel_filters_preserve_solid_color() {
        let src = PixelImage::new(vec![0xFF40_80C0; 64], 8, 8);
        for f in [
            MipFilter::Cubic,
            MipFilter::Gaussian,
            MipFilter::Lanczos4,
        ] {
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
}
