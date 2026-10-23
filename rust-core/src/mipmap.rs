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

    #[inline]
    fn at(&self, x: usize, y: usize) -> u32 {
        self.pixels[y * self.width + x]
    }
}

fn pack(a: u32, r: u32, g: u32, b: u32) -> u32 {
    ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
}

fn sample_nearest(src: &PixelImage, x: usize, y: usize, dst_w: usize, dst_h: usize) -> u32 {
    let sx = (((2 * x + 1) * src.width) / (2 * dst_w)).min(src.width - 1);
    let sy = (((2 * y + 1) * src.height) / (2 * dst_h)).min(src.height - 1);
    src.at(sx, sy)
}

fn sample_box(src: &PixelImage, x: usize, y: usize, dst_w: usize, dst_h: usize) -> u32 {
    let x0 = (x * src.width) / dst_w;
    let x1 = ((x0 + 1).max(((x + 1) * src.width) / dst_w)).min(src.width);
    let y0 = (y * src.height) / dst_h;
    let y1 = ((y0 + 1).max(((y + 1) * src.height) / dst_h)).min(src.height);
    let (mut sa, mut sr, mut sg, mut sb, mut count) = (0u32, 0u32, 0u32, 0u32, 0u32);
    for sy in y0..y1 {
        for sx in x0..x1 {
            let p = src.at(sx, sy);
            sa += (p >> 24) & 0xFF;
            sr += (p >> 16) & 0xFF;
            sg += (p >> 8) & 0xFF;
            sb += p & 0xFF;
            count += 1;
        }
    }
    pack(sa / count, sr / count, sg / count, sb / count)
}

fn sample_bilinear(src: &PixelImage, x: usize, y: usize, dst_w: usize, dst_h: usize) -> u32 {
    let gx = (x as f64 + 0.5) * src.width as f64 / dst_w as f64 - 0.5;
    let gy = (y as f64 + 0.5) * src.height as f64 / dst_h as f64 - 0.5;
    let x0 = (gx.floor() as i64).clamp(0, src.width as i64 - 1) as usize;
    let y0 = (gy.floor() as i64).clamp(0, src.height as i64 - 1) as usize;
    let x1 = (x0 + 1).min(src.width - 1);
    let y1 = (y0 + 1).min(src.height - 1);
    let fx = (gx - x0 as f64).clamp(0.0, 1.0);
    let fy = (gy - y0 as f64).clamp(0.0, 1.0);

    let c00 = src.at(x0, y0);
    let c10 = src.at(x1, y0);
    let c01 = src.at(x0, y1);
    let c11 = src.at(x1, y1);

    let mix = |shift: u32| -> u32 {
        let v00 = ((c00 >> shift) & 0xFF) as f64;
        let v10 = ((c10 >> shift) & 0xFF) as f64;
        let v01 = ((c01 >> shift) & 0xFF) as f64;
        let v11 = ((c11 >> shift) & 0xFF) as f64;
        let top = v00 + (v10 - v00) * fx;
        let bottom = v01 + (v11 - v01) * fx;
        ((top + (bottom - top) * fy) as i64).clamp(0, 255) as u32
    };
    pack(mix(24), mix(16), mix(8), mix(0))
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

/// Generic kernel resampling: the kernel widens with the downscale ratio
/// (anti-aliasing). Weights are normalized per output pixel; edges clamp.
fn sample_kernel(
    src: &PixelImage,
    x: usize,
    y: usize,
    dst_w: usize,
    dst_h: usize,
    kernel: fn(f64) -> f64,
    radius: f64,
) -> u32 {
    let scale_x = src.width as f64 / dst_w as f64;
    let scale_y = src.height as f64 / dst_h as f64;
    let gx = (x as f64 + 0.5) * scale_x - 0.5;
    let gy = (y as f64 + 0.5) * scale_y - 0.5;
    let rx = (radius * scale_x).ceil() as i64;
    let ry = (radius * scale_y).ceil() as i64;
    let (mut sa, mut sr, mut sg, mut sb, mut sw) = (0.0f64, 0.0, 0.0, 0.0, 0.0);
    for iy in (gy as i64 - ry)..=(gy as i64 + ry) {
        let wy = kernel((iy as f64 - gy) / scale_y);
        if wy == 0.0 {
            continue;
        }
        let cy = iy.clamp(0, src.height as i64 - 1) as usize;
        for ix in (gx as i64 - rx)..=(gx as i64 + rx) {
            let wx = kernel((ix as f64 - gx) / scale_x);
            if wx == 0.0 {
                continue;
            }
            let cx = ix.clamp(0, src.width as i64 - 1) as usize;
            let w = wx * wy;
            let p = src.at(cx, cy);
            sa += ((p >> 24) & 0xFF) as f64 * w;
            sr += ((p >> 16) & 0xFF) as f64 * w;
            sg += ((p >> 8) & 0xFF) as f64 * w;
            sb += (p & 0xFF) as f64 * w;
            sw += w;
        }
    }
    if sw == 0.0 {
        return src.at(
            (gx as i64).clamp(0, src.width as i64 - 1) as usize,
            (gy as i64).clamp(0, src.height as i64 - 1) as usize,
        );
    }
    let norm = |v: f64| -> u32 { (v / sw).round().clamp(0.0, 255.0) as u32 };
    pack(norm(sa), norm(sr), norm(sg), norm(sb))
}

/// Resamples `src` to exactly `dst_w` x `dst_h` using `filter`.
pub fn resample(src: &PixelImage, dst_w: usize, dst_h: usize, filter: MipFilter) -> PixelImage {
    assert!(dst_w > 0 && dst_h > 0, "invalid target dimensions");
    if src.width == dst_w && src.height == dst_h {
        return src.clone();
    }
    let mut out = vec![0u32; dst_w * dst_h];
    for y in 0..dst_h {
        for x in 0..dst_w {
            out[y * dst_w + x] = match filter {
                MipFilter::Nearest => sample_nearest(src, x, y, dst_w, dst_h),
                MipFilter::Box => sample_box(src, x, y, dst_w, dst_h),
                MipFilter::Bilinear => sample_bilinear(src, x, y, dst_w, dst_h),
                MipFilter::Cubic => sample_kernel(src, x, y, dst_w, dst_h, kernel_cubic, 2.0),
                MipFilter::Gaussian => sample_kernel(src, x, y, dst_w, dst_h, kernel_gaussian, 2.0),
                MipFilter::Lanczos4 => sample_kernel(src, x, y, dst_w, dst_h, kernel_lanczos4, 4.0),
            };
        }
    }
    PixelImage { pixels: out, width: dst_w, height: dst_h }
}

/// One halving step; odd sizes round down with a 1px floor.
pub fn downscale_half(src: &PixelImage, filter: MipFilter) -> PixelImage {
    resample(src, (src.width / 2).max(1), (src.height / 2).max(1), filter)
}

/// Full mip chain below `src` (level 1, 2, ...) down to 1x1. Empty for 1x1.
pub fn generate_chain(src: &PixelImage, filter: MipFilter) -> Vec<PixelImage> {
    let mut levels = Vec::new();
    let mut current = src.clone();
    while current.width > 1 || current.height > 1 {
        current = downscale_half(&current, filter);
        levels.push(current.clone());
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
