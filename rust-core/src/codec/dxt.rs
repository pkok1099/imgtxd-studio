//! DXT1/DXT5 block codec — faithful port of the Kotlin `DxtDecoder`
//! (decodeDxt1ToPixels / decodeDxt5ToPixels / encodeDxt1).
//! Pixels are `u32` ARGB (0xAARRGGBB), row-major, same as the Kotlin IntArray.

pub fn rgb565_to_argb(c: u32, alpha: u32) -> u32 {
    let r = (((c >> 11) & 0x1F) * 255 + 15) / 31;
    let g = (((c >> 5) & 0x3F) * 255 + 31) / 63;
    let b = ((c & 0x1F) * 255 + 15) / 31;
    (alpha << 24) | (r << 16) | (g << 8) | b
}

fn argb_to_rgb565(r: u32, g: u32, b: u32) -> u32 {
    let r5 = (r * 31 + 127) / 255;
    let g6 = (g * 63 + 127) / 255;
    let b5 = (b * 31 + 127) / 255;
    (r5 << 11) | (g6 << 5) | b5
}

pub fn interpolate_color(c0: u32, c1: u32, w0: u32, w1: u32, total: u32) -> u32 {
    let ch = |shift: u32| {
        let a = (c0 >> shift) & 0xFF;
        let b = (c1 >> shift) & 0xFF;
        (a * w0 + b * w1) / total
    };
    (0xFF << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)
}

fn read_u16le(data: &[u8], off: usize) -> u32 {
    (data[off] as u32) | ((data[off + 1] as u32) << 8)
}

fn read_u32le(data: &[u8], off: usize) -> u32 {
    (data[off] as u32)
        | ((data[off + 1] as u32) << 8)
        | ((data[off + 2] as u32) << 16)
        | ((data[off + 3] as u32) << 24)
}

/// Decodes a DXT1 color block sequence into ARGB pixels.
/// Dimensions aligned to 4 (the overwhelming majority of game textures)
/// take a bounds-check-free path. Large textures are split into horizontal
/// block-row bands decoded on worker threads (deterministic: disjoint row
/// ranges, identical output to the serial path; threshold 16384 blocks =
/// 512x512 because decode work per block is small).
pub fn decode_dxt1(data: &[u8], width: usize, height: usize) -> Vec<u32> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut pixels = vec![0u32; width * height];
    if blocks_x * blocks_y < 16384 {
        decode_dxt1_band(data, width, height, blocks_x, 0, blocks_y, &mut pixels);
        return pixels;
    }
    let workers = crate::parallel::inner_workers(4);
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
                decode_dxt1_band(data, width, height, blocks_x, by_start, by_end, cur);
            });
            by_start = by_end;
        }
    });
    pixels
}

/// Decodes block-rows [by_start, by_end) into `pixels` (pixel rows from y0).
fn decode_dxt1_band(
    data: &[u8],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
) {
    let y0 = (by_start * 4).min(height);
    let band_y = y0 * width; // pixel offset of the band's first row
    let mut offset = by_start * blocks_x * 8;
    let mut palette = [0u32; 4];
    let aligned = width.is_multiple_of(4) && height.is_multiple_of(4);
    // Aligned bands take the vectorized whole-band path (bit-identical to
    // the scalar expansion below — differential-tested per random blocks).
    if aligned
        && crate::codec::simd::dxt::dxt1_band(
            data, width, blocks_x, by_start, by_end, pixels, band_y,
        )
    {
        return;
    }

    for by in by_start..by_end {
        for bx in 0..blocks_x {
            if offset + 8 > data.len() {
                return;
            }
            let c0 = read_u16le(data, offset);
            let c1 = read_u16le(data, offset + 2);
            palette[0] = rgb565_to_argb(c0, 255);
            palette[1] = rgb565_to_argb(c1, 255);
            if c0 > c1 {
                palette[2] = interpolate_color(palette[0], palette[1], 2, 1, 3);
                palette[3] = interpolate_color(palette[0], palette[1], 1, 2, 3);
            } else {
                palette[2] = interpolate_color(palette[0], palette[1], 1, 1, 2);
                palette[3] = 0x0000_0000;
            }
            let lookup = read_u32le(data, offset + 4);
            if aligned {
                let base = by * width * 4 + bx * 4 - band_y;
                for py in 0..4 {
                    let shift = py * 8;
                    let row = base + py * width;
                    pixels[row] = palette[((lookup >> shift) & 0x03) as usize];
                    pixels[row + 1] = palette[((lookup >> (shift + 2)) & 0x03) as usize];
                    pixels[row + 2] = palette[((lookup >> (shift + 4)) & 0x03) as usize];
                    pixels[row + 3] = palette[((lookup >> (shift + 6)) & 0x03) as usize];
                }
            } else {
                for py in 0..4 {
                    let y = by * 4 + py;
                    if y >= height {
                        continue;
                    }
                    for px in 0..4 {
                        let x = bx * 4 + px;
                        if x >= width {
                            continue;
                        }
                        let shift = (py * 4 + px) * 2;
                        let code = ((lookup >> shift) & 0x03) as usize;
                        pixels[(y * width + x) - band_y] = palette[code];
                    }
                }
            }
            offset += 8;
        }
    }
}

/// Decodes a DXT5 block sequence (interpolated alpha + DXT1 color) into ARGB pixels.
/// Aligned dimensions take a bounds-check-free path (see decode_dxt1).
/// Large textures are split into horizontal block-row bands decoded on worker
/// threads (deterministic, identical output; threshold 16384 blocks).
pub fn decode_dxt5(data: &[u8], width: usize, height: usize) -> Vec<u32> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut pixels = vec![0u32; width * height];
    if blocks_x * blocks_y < 16384 {
        decode_dxt5_band(data, width, height, blocks_x, 0, blocks_y, &mut pixels);
        return pixels;
    }
    let workers = crate::parallel::inner_workers(4);
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
                decode_dxt5_band(data, width, height, blocks_x, by_start, by_end, cur);
            });
            by_start = by_end;
        }
    });
    pixels
}

/// Decodes block-rows [by_start, by_end) into `pixels` (pixel rows from y0).
fn decode_dxt5_band(
    data: &[u8],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    pixels: &mut [u32],
) {
    let y0 = (by_start * 4).min(height);
    let band_y = y0 * width;
    let mut offset = by_start * blocks_x * 16;
    let mut alpha_palette = [0u32; 8];
    let mut color_palette = [0u32; 4];
    let aligned = width.is_multiple_of(4) && height.is_multiple_of(4);
    // Aligned bands take the vectorized whole-band path (see decode_dxt1_band).
    if aligned
        && crate::codec::simd::dxt::dxt5_band(
            data, width, blocks_x, by_start, by_end, pixels, band_y,
        )
    {
        return;
    }

    for by in by_start..by_end {
        for bx in 0..blocks_x {
            if offset + 16 > data.len() {
                return;
            }
            let a0 = data[offset] as u32;
            let a1 = data[offset + 1] as u32;
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
            let c0 = read_u16le(data, co);
            let c1 = read_u16le(data, co + 2);
            color_palette[0] = rgb565_to_argb(c0, 255);
            color_palette[1] = rgb565_to_argb(c1, 255);
            color_palette[2] = interpolate_color(color_palette[0], color_palette[1], 2, 1, 3);
            color_palette[3] = interpolate_color(color_palette[0], color_palette[1], 1, 2, 3);
            let color_lookup = read_u32le(data, co + 4);

            if aligned {
                let base = by * width * 4 + bx * 4 - band_y;
                for py in 0..4 {
                    let row = base + py * width;
                    for px in 0..4 {
                        let idx = py * 4 + px;
                        let a_code = ((alpha_lookup >> (idx * 3)) & 0x07) as usize;
                        let alpha = alpha_palette[a_code];
                        let c_code = ((color_lookup >> (idx * 2)) & 0x03) as usize;
                        let rgb = color_palette[c_code] & 0x00FF_FFFF;
                        pixels[row + px] = (alpha << 24) | rgb;
                    }
                }
            } else {
                for py in 0..4 {
                    let y = by * 4 + py;
                    if y >= height {
                        continue;
                    }
                    for px in 0..4 {
                        let x = bx * 4 + px;
                        if x >= width {
                            continue;
                        }
                        let idx = py * 4 + px;
                        let a_code = ((alpha_lookup >> (idx * 3)) & 0x07) as usize;
                        let alpha = alpha_palette[a_code];
                        let c_code = ((color_lookup >> (idx * 2)) & 0x03) as usize;
                        let rgb = color_palette[c_code] & 0x00FF_FFFF;
                        pixels[(y * width + x) - band_y] = (alpha << 24) | rgb;
                    }
                }
            }
            offset += 16;
        }
    }
}

fn find_closest_palette_index(pixel: u32, palette: &[u32], count: usize) -> u32 {
    let pr = ((pixel >> 16) & 0xFF) as i32;
    let pg = ((pixel >> 8) & 0xFF) as i32;
    let pb = (pixel & 0xFF) as i32;
    let mut best_index = 0u32;
    let mut best_dist = i32::MAX;
    for (i, &pal) in palette[..count].iter().enumerate() {
        let dr = pr - ((pal >> 16) & 0xFF) as i32;
        let dg = pg - ((pal >> 8) & 0xFF) as i32;
        let db = pb - (pal & 0xFF) as i32;
        let dist = dr * dr + dg * dg + db * db;
        if dist < best_dist {
            best_dist = dist;
            best_index = i as u32;
            if dist == 0 {
                break;
            }
        }
    }
    best_index
}

/// Encodes ARGB pixels to DXT1 (min/max endpoint fit, same algorithm as Kotlin).
///
/// Blocks are independent, so large images are split into horizontal block-row
/// bands encoded on worker threads (deterministic: every band writes a
/// disjoint, contiguous slice; results are identical to the serial path).
pub fn encode_dxt1(pixels: &[u32], width: usize, height: usize) -> Vec<u8> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut out = vec![0u8; blocks_x * blocks_y * 8];
    // Small textures: thread spawn overhead dominates, encode inline.
    if blocks_x * blocks_y < 1024 {
        encode_dxt1_band(pixels, width, height, blocks_x, 0, blocks_y, &mut out);
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
                encode_dxt1_band(pixels, width, height, blocks_x, by_start, by_end, chunk_out);
            });
        }
    });
    out
}

/// Encodes a horizontal band of block-rows [by_start, by_end) into `out`
/// (band-local byte offset starts at 0; the slice is exactly the band's size).
fn encode_dxt1_band(
    pixels: &[u32],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    out: &mut [u8],
) {
    let mut out_offset = 0usize;
    let mut block_pixels = [0u32; 16];

    for by in by_start..by_end {
        for bx in 0..blocks_x {
            let (mut min_r, mut min_g, mut min_b) = (255u32, 255u32, 255u32);
            let (mut max_r, mut max_g, mut max_b) = (0u32, 0u32, 0u32);
            let mut has_alpha = false;

            for py in 0..4 {
                for px in 0..4 {
                    let x = (bx * 4 + px).min(width - 1);
                    let y = (by * 4 + py).min(height - 1);
                    let pixel = pixels[y * width + x];
                    block_pixels[py * 4 + px] = pixel;
                    let a = (pixel >> 24) & 0xFF;
                    if a < 128 {
                        has_alpha = true;
                    } else {
                        let r = (pixel >> 16) & 0xFF;
                        let g = (pixel >> 8) & 0xFF;
                        let b = pixel & 0xFF;
                        min_r = min_r.min(r);
                        max_r = max_r.max(r);
                        min_g = min_g.min(g);
                        max_g = max_g.max(g);
                        min_b = min_b.min(b);
                        max_b = max_b.max(b);
                    }
                }
            }

            let c_max = argb_to_rgb565(max_r, max_g, max_b);
            let c_min = argb_to_rgb565(min_r, min_g, min_b);
            let (color0, color1) = if has_alpha {
                (c_max.min(c_min), c_max.max(c_min))
            } else {
                (c_max.max(c_min), c_max.min(c_min))
            };

            out[out_offset] = (color0 & 0xFF) as u8;
            out[out_offset + 1] = ((color0 >> 8) & 0xFF) as u8;
            out[out_offset + 2] = (color1 & 0xFF) as u8;
            out[out_offset + 3] = ((color1 >> 8) & 0xFF) as u8;

            let mut palette = [0u32; 4];
            palette[0] = rgb565_to_argb(color0, 255);
            palette[1] = rgb565_to_argb(color1, 255);
            if color0 > color1 {
                palette[2] = interpolate_color(palette[0], palette[1], 2, 1, 3);
                palette[3] = interpolate_color(palette[0], palette[1], 1, 2, 3);
            } else {
                palette[2] = interpolate_color(palette[0], palette[1], 1, 1, 2);
                palette[3] = 0x0000_0000;
            }

            let mut lookup: u32 = 0;
            for (i, &p) in block_pixels.iter().enumerate() {
                let a = (p >> 24) & 0xFF;
                let best = if has_alpha && a < 128 {
                    3
                } else {
                    find_closest_palette_index(p, &palette, if has_alpha { 3 } else { 4 })
                };
                lookup |= best << (i * 2);
            }
            out[out_offset + 4] = (lookup & 0xFF) as u8;
            out[out_offset + 5] = ((lookup >> 8) & 0xFF) as u8;
            out[out_offset + 6] = ((lookup >> 16) & 0xFF) as u8;
            out[out_offset + 7] = ((lookup >> 24) & 0xFF) as u8;
            out_offset += 8;
        }
    }
}

/// Opaque color block (DXT5 semantics: always the 4-color palette).
fn encode_color_block_opaque(block: &[u32; 16]) -> [u8; 8] {
    let (mut min_r, mut min_g, mut min_b) = (255u32, 255u32, 255u32);
    let (mut max_r, mut max_g, mut max_b) = (0u32, 0u32, 0u32);
    for &p in block.iter() {
        let r = (p >> 16) & 0xFF;
        let g = (p >> 8) & 0xFF;
        let b = p & 0xFF;
        min_r = min_r.min(r);
        max_r = max_r.max(r);
        min_g = min_g.min(g);
        max_g = max_g.max(g);
        min_b = min_b.min(b);
        max_b = max_b.max(b);
    }
    let color0 = argb_to_rgb565(max_r, max_g, max_b).max(argb_to_rgb565(min_r, min_g, min_b));
    let color1 = argb_to_rgb565(max_r, max_g, max_b).min(argb_to_rgb565(min_r, min_g, min_b));
    let mut palette = [0u32; 4];
    palette[0] = rgb565_to_argb(color0, 255);
    palette[1] = rgb565_to_argb(color1, 255);
    palette[2] = interpolate_color(palette[0], palette[1], 2, 1, 3);
    palette[3] = interpolate_color(palette[0], palette[1], 1, 2, 3);
    let mut lookup: u32 = 0;
    for (i, &p) in block.iter().enumerate() {
        lookup |= find_closest_palette_index(p, &palette, 4) << (i * 2);
    }
    let mut out = [0u8; 8];
    out[0] = (color0 & 0xFF) as u8;
    out[1] = ((color0 >> 8) & 0xFF) as u8;
    out[2] = (color1 & 0xFF) as u8;
    out[3] = ((color1 >> 8) & 0xFF) as u8;
    out[4] = (lookup & 0xFF) as u8;
    out[5] = ((lookup >> 8) & 0xFF) as u8;
    out[6] = ((lookup >> 16) & 0xFF) as u8;
    out[7] = ((lookup >> 24) & 0xFF) as u8;
    out
}

/// Encodes ARGB pixels to DXT5: interpolated alpha block (a0 > a1 mode,
/// palette mirrored from the decoder) + an opaque 4-color block.
///
/// Band-parallel over block-rows exactly like `encode_dxt1` (deterministic).
pub fn encode_dxt5(pixels: &[u32], width: usize, height: usize) -> Vec<u8> {
    let blocks_x = width.div_ceil(4);
    let blocks_y = height.div_ceil(4);
    let mut out = vec![0u8; blocks_x * blocks_y * 16];
    // Small textures: thread spawn overhead dominates, encode inline.
    if blocks_x * blocks_y < 1024 {
        encode_dxt5_band(pixels, width, height, blocks_x, 0, blocks_y, &mut out);
        return out;
    }
    let workers = crate::parallel::inner_workers(4);
    let rows_per_band = blocks_y.div_ceil(workers).max(4);
    let chunk = rows_per_band * blocks_x * 16;
    std::thread::scope(|s| {
        for (band, chunk_out) in out.chunks_mut(chunk).enumerate() {
            let by_start = band * rows_per_band;
            let by_end = (by_start + rows_per_band).min(blocks_y);
            s.spawn(move || {
                encode_dxt5_band(pixels, width, height, blocks_x, by_start, by_end, chunk_out);
            });
        }
    });
    out
}

/// Encodes a horizontal band of block-rows [by_start, by_end) into `out`.
fn encode_dxt5_band(
    pixels: &[u32],
    width: usize,
    height: usize,
    blocks_x: usize,
    by_start: usize,
    by_end: usize,
    out: &mut [u8],
) {
    let mut block_pixels = [0u32; 16];
    let mut o = 0usize;
    for by in by_start..by_end {
        for bx in 0..blocks_x {
            let mut a_max = 0u32;
            let mut a_min = 255u32;
            for py in 0..4 {
                for px in 0..4 {
                    let x = (bx * 4 + px).min(width - 1);
                    let y = (by * 4 + py).min(height - 1);
                    let p = pixels[y * width + x];
                    block_pixels[py * 4 + px] = p;
                    let a = (p >> 24) & 0xFF;
                    a_max = a_max.max(a);
                    a_min = a_min.min(a);
                }
            }
            let mut palette = [0u32; 8];
            palette[0] = a_max;
            palette[1] = a_min;
            if a_max > a_min {
                for (i, (w0, w1)) in [(6, 1), (5, 2), (4, 3), (3, 4), (2, 5), (1, 6)]
                    .iter()
                    .enumerate()
                {
                    palette[2 + i] = (w0 * a_max + w1 * a_min) / 7;
                }
            } else {
                palette = [a_max; 8];
            }
            out[o] = a_max as u8;
            out[o + 1] = a_min as u8;
            let mut lookup: u64 = 0;
            for (i, &p) in block_pixels.iter().enumerate() {
                let a = ((p >> 24) & 0xFF) as i32;
                let mut best = 0usize;
                let mut best_dist = i32::MAX;
                for (j, &pa) in palette.iter().enumerate() {
                    let d = (a - pa as i32).abs();
                    if d < best_dist {
                        best_dist = d;
                        best = j;
                    }
                }
                lookup |= (best as u64) << (i * 3);
            }
            for i in 0..6 {
                out[o + 2 + i] = ((lookup >> (8 * i)) & 0xFF) as u8;
            }
            let color = encode_color_block_opaque(&block_pixels);
            out[o + 8..o + 16].copy_from_slice(&color);
            o += 16;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_dxt1_solid_red_block() {
        // c0 = 0xF800 (pure red), c1 = 0x001F (pure blue), all indices 0
        let data = [0x00, 0xF8, 0x1F, 0x00, 0, 0, 0, 0];
        let px = decode_dxt1(&data, 4, 4);
        assert_eq!(px.len(), 16);
        assert!(px.iter().all(|&p| p == 0xFFFF_0000), "got {:08x}", px[0]);
    }

    #[test]
    fn decode_dxt1_one_bit_alpha_mode() {
        // c0 < c1 -> palette[3] transparent; indices all 3
        let data = [0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF];
        let px = decode_dxt1(&data, 4, 4);
        assert!(px.iter().all(|&p| p == 0x0000_0000));
    }

    #[test]
    fn decode_dxt5_alpha_endpoints() {
        // a0=255, a1=0, alpha indices all 0 -> alpha 255; color block red
        let mut data = vec![255u8, 0, 0, 0, 0, 0, 0, 0];
        data.extend_from_slice(&[0x00, 0xF8, 0x1F, 0x00, 0, 0, 0, 0]);
        let px = decode_dxt5(&data, 4, 4);
        assert!(px.iter().all(|&p| p == 0xFFFF_0000), "got {:08x}", px[0]);
    }

    #[test]
    fn encode_dxt5_roundtrip_solid_and_gradient_alpha() {
        // Solid color with constant alpha 0x80
        let px = vec![0x80FF_0000u32; 16];
        let enc = encode_dxt5(&px, 4, 4);
        assert_eq!(enc.len(), 16);
        let dec = decode_dxt5(&enc, 4, 4);
        assert!(dec.iter().all(|&p| p == 0x80FF_0000), "got {:08x}", dec[0]);
        // Alpha gradient using exactly the decoder palette values for (255, 0)
        let alphas = [255u32, 0, 218, 182, 145, 109, 72, 36];
        let mut px = Vec::new();
        for i in 0..16 {
            px.push((alphas[i % 8] << 24) | 0x00FF_FF00);
        }
        let enc = encode_dxt5(&px, 4, 4);
        let dec = decode_dxt5(&enc, 4, 4);
        for (orig, got) in px.iter().zip(dec.iter()) {
            assert_eq!((got >> 24) & 0xFF, (orig >> 24) & 0xFF, "alpha mismatch");
            assert_eq!(got & 0x00FF_FFFF, 0x00FF_FF00, "color drift");
        }
    }

    #[test]
    fn encode_dxt1_solid_color_exact_bytes() {
        // All pixels 0xFF804020: endpoints collapse to argbToRgb565(128,64,32) = 0x8204
        let px = vec![0xFF80_4020u32; 16];
        let out = encode_dxt1(&px, 4, 4);
        assert_eq!(out, vec![0x04, 0x82, 0x04, 0x82, 0, 0, 0, 0]);
    }

    #[test]
    fn encode_decode_roundtrip_dimensions() {
        let px: Vec<u32> = (0..64).map(|i| 0xFF00_0000 | (i * 4) << 8 | i).collect();
        let enc = encode_dxt1(&px, 8, 8);
        assert_eq!(enc.len(), 4 * 8);
        let dec = decode_dxt1(&enc, 8, 8);
        assert_eq!(dec.len(), 64);
        assert!(dec.iter().all(|&p| (p >> 24) == 0xFF));
    }

    #[test]
    fn encode_dxt5_banded_and_guard_inline_identical() {
        // 256x256 = 4096 blocks (>= 1024 encode threshold): the encoder
        // bands across worker threads without a guard and runs inline while
        // an OuterGuard is held. Band and inline execution must stay
        // byte-identical — the pass-13 nested-parallelism switch rides on it.
        let mut rng = 0x1234_5678u32;
        let px: Vec<u32> = (0..256 * 256)
            .map(|_| {
                rng = rng.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                0xFF00_0000 | (rng & 0x00FF_FFFF)
            })
            .collect();
        let banded = encode_dxt5(&px, 256, 256);
        let inline = {
            let _outer = crate::parallel::OuterGuard::acquire();
            encode_dxt5(&px, 256, 256)
        };
        assert_eq!(banded, inline, "guard must not change encode bytes");
    }

    #[test]
    fn decode_dxt5_banded_and_guard_inline_identical() {
        // 512x512 = 16384 blocks (== decode threshold): banded path either
        // way; the guard only switches it to inline execution.
        let mut rng = 0x0BAD_F00Du32;
        let px: Vec<u32> = (0..512 * 512)
            .map(|_| {
                rng = rng.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                0xFF00_0000 | (rng & 0x00FF_FFFF)
            })
            .collect();
        let enc = encode_dxt5(&px, 512, 512);
        let banded = decode_dxt5(&enc, 512, 512);
        let inline = {
            let _outer = crate::parallel::OuterGuard::acquire();
            decode_dxt5(&enc, 512, 512)
        };
        assert_eq!(banded, inline, "guard must not change decode bytes");
    }
}
