package com.onlasdan.studio.txd

import android.graphics.Bitmap

/**
 * High-performance pure-Kotlin decoder and encoder for S3TC / DXT compressed textures (DXT1, DXT3, DXT5).
 */
object DxtDecoder {

    /**
     * Decodes DXT1 compressed byte data into an ARGB_8888 Bitmap.
     */
    fun decodeDxt1(data: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = decodeDxt1ToPixels(data, width, height)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decodes DXT1 compressed byte data into an ARGB pixel IntArray.
     */
    fun decodeDxt1ToPixels(data: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val numBlocksX = (width + 3) / 4
        val numBlocksY = (height + 3) / 4
        var offset = 0

        val palette = IntArray(4)

        for (by in 0 until numBlocksY) {
            for (bx in 0 until numBlocksX) {
                if (offset + 8 > data.size) break

                val c0 = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                val c1 = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)

                palette[0] = rgb565ToArgb(c0, 255)
                palette[1] = rgb565ToArgb(c1, 255)

                if (c0 > c1) {
                    palette[2] = interpolateColor(palette[0], palette[1], 2, 1, 3)
                    palette[3] = interpolateColor(palette[0], palette[1], 1, 2, 3)
                } else {
                    palette[2] = interpolateColor(palette[0], palette[1], 1, 1, 2)
                    palette[3] = 0x00000000 // 1-bit transparent black
                }

                val b0 = data[offset + 4].toLong() and 0xFF
                val b1 = data[offset + 5].toLong() and 0xFF
                val b2 = data[offset + 6].toLong() and 0xFF
                val b3 = data[offset + 7].toLong() and 0xFF
                val lookup = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

                for (py in 0 until 4) {
                    val y = by * 4 + py
                    if (y >= height) continue

                    for (px in 0 until 4) {
                        val x = bx * 4 + px
                        if (x >= width) continue

                        val shift = (py * 4 + px) * 2
                        val code = ((lookup ushr shift) and 0x03).toInt()
                        pixels[y * width + x] = palette[code]
                    }
                }
                offset += 8
            }
        }
        return pixels
    }

    /**
     * Decodes DXT3 compressed byte data into an ARGB_8888 Bitmap.
     */
    fun decodeDxt3(data: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = decodeDxt3ToPixels(data, width, height)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decodes DXT3 compressed byte data into an ARGB pixel IntArray.
     */
    fun decodeDxt3ToPixels(data: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val numBlocksX = (width + 3) / 4
        val numBlocksY = (height + 3) / 4
        var offset = 0
        val palette = IntArray(4)

        for (by in 0 until numBlocksY) {
            for (bx in 0 until numBlocksX) {
                if (offset + 16 > data.size) break

                // Alpha table: 8 bytes (4 bits per pixel)
                val alphaOffset = offset
                val colorOffset = offset + 8

                val c0 = (data[colorOffset].toInt() and 0xFF) or ((data[colorOffset + 1].toInt() and 0xFF) shl 8)
                val c1 = (data[colorOffset + 2].toInt() and 0xFF) or ((data[colorOffset + 3].toInt() and 0xFF) shl 8)

                palette[0] = rgb565ToArgb(c0, 255)
                palette[1] = rgb565ToArgb(c1, 255)
                palette[2] = interpolateColor(palette[0], palette[1], 2, 1, 3)
                palette[3] = interpolateColor(palette[0], palette[1], 1, 2, 3)

                val b0 = data[colorOffset + 4].toLong() and 0xFF
                val b1 = data[colorOffset + 5].toLong() and 0xFF
                val b2 = data[colorOffset + 6].toLong() and 0xFF
                val b3 = data[colorOffset + 7].toLong() and 0xFF
                val lookup = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

                for (py in 0 until 4) {
                    val y = by * 4 + py
                    if (y >= height) continue

                    for (px in 0 until 4) {
                        val x = bx * 4 + px
                        if (x >= width) continue

                        val pixelIdx = py * 4 + px
                        val alphaByte = data[alphaOffset + (pixelIdx / 2)].toInt() and 0xFF
                        val a4 = if (pixelIdx % 2 == 0) alphaByte and 0x0F else (alphaByte ushr 4) and 0x0F
                        val alpha = (a4 * 255) / 15

                        val shift = pixelIdx * 2
                        val code = ((lookup ushr shift) and 0x03).toInt()
                        val rgb = palette[code] and 0x00FFFFFF

                        pixels[y * width + x] = (alpha shl 24) or rgb
                    }
                }
                offset += 16
            }
        }
        return pixels
    }

    /**
     * Decodes DXT5 compressed byte data into an ARGB_8888 Bitmap.
     */
    fun decodeDxt5(data: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = decodeDxt5ToPixels(data, width, height)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decodes DXT5 compressed byte data into an ARGB pixel IntArray.
     */
    fun decodeDxt5ToPixels(data: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val numBlocksX = (width + 3) / 4
        val numBlocksY = (height + 3) / 4
        var offset = 0

        val alphaPalette = IntArray(8)
        val colorPalette = IntArray(4)

        for (by in 0 until numBlocksY) {
            for (bx in 0 until numBlocksX) {
                if (offset + 16 > data.size) break

                val a0 = data[offset].toInt() and 0xFF
                val a1 = data[offset + 1].toInt() and 0xFF

                alphaPalette[0] = a0
                alphaPalette[1] = a1

                if (a0 > a1) {
                    alphaPalette[2] = (6 * a0 + 1 * a1) / 7
                    alphaPalette[3] = (5 * a0 + 2 * a1) / 7
                    alphaPalette[4] = (4 * a0 + 3 * a1) / 7
                    alphaPalette[5] = (3 * a0 + 4 * a1) / 7
                    alphaPalette[6] = (2 * a0 + 5 * a1) / 7
                    alphaPalette[7] = (1 * a0 + 6 * a1) / 7
                } else {
                    alphaPalette[2] = (4 * a0 + 1 * a1) / 5
                    alphaPalette[3] = (3 * a0 + 2 * a1) / 5
                    alphaPalette[4] = (2 * a0 + 3 * a1) / 5
                    alphaPalette[5] = (1 * a0 + 4 * a1) / 5
                    alphaPalette[6] = 0
                    alphaPalette[7] = 255
                }

                // 48-bit alpha indices (6 bytes)
                val aIndices = LongArray(6)
                for (i in 0 until 6) {
                    aIndices[i] = data[offset + 2 + i].toLong() and 0xFF
                }
                val alphaLookup = aIndices[0] or
                        (aIndices[1] shl 8) or
                        (aIndices[2] shl 16) or
                        (aIndices[3] shl 24) or
                        (aIndices[4] shl 32) or
                        (aIndices[5] shl 40)

                // DXT1 Color Block (8 bytes)
                val colorOffset = offset + 8
                val c0 = (data[colorOffset].toInt() and 0xFF) or ((data[colorOffset + 1].toInt() and 0xFF) shl 8)
                val c1 = (data[colorOffset + 2].toInt() and 0xFF) or ((data[colorOffset + 3].toInt() and 0xFF) shl 8)

                colorPalette[0] = rgb565ToArgb(c0, 255)
                colorPalette[1] = rgb565ToArgb(c1, 255)
                colorPalette[2] = interpolateColor(colorPalette[0], colorPalette[1], 2, 1, 3)
                colorPalette[3] = interpolateColor(colorPalette[0], colorPalette[1], 1, 2, 3)

                val b0 = data[colorOffset + 4].toLong() and 0xFF
                val b1 = data[colorOffset + 5].toLong() and 0xFF
                val b2 = data[colorOffset + 6].toLong() and 0xFF
                val b3 = data[colorOffset + 7].toLong() and 0xFF
                val colorLookup = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

                for (py in 0 until 4) {
                    val y = by * 4 + py
                    if (y >= height) continue

                    for (px in 0 until 4) {
                        val x = bx * 4 + px
                        if (x >= width) continue

                        val pixelIdx = py * 4 + px

                        val aShift = pixelIdx * 3
                        val aCode = ((alphaLookup ushr aShift) and 0x07).toInt()
                        val alpha = alphaPalette[aCode]

                        val cShift = pixelIdx * 2
                        val cCode = ((colorLookup ushr cShift) and 0x03).toInt()
                        val rgb = colorPalette[cCode] and 0x00FFFFFF

                        pixels[y * width + x] = (alpha shl 24) or rgb
                    }
                }
                offset += 16
            }
        }
        return pixels
    }

    /**
     * Fast DXT1 encoder for converting an arbitrary Bitmap into DXT1 compressed blocks.
     */
    fun encodeDxt1(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return encodeDxt1(pixels, width, height)
    }

    /**
     * Fast DXT1 encoder for converting ARGB pixels into DXT1 compressed blocks.
     */
    fun encodeDxt1(pixels: IntArray, width: Int, height: Int): ByteArray {
        val numBlocksX = (width + 3) / 4
        val numBlocksY = (height + 3) / 4
        val out = ByteArray(numBlocksX * numBlocksY * 8)
        var outOffset = 0

        val blockPixels = IntArray(16)

        for (by in 0 until numBlocksY) {
            for (bx in 0 until numBlocksX) {
                var minR = 255; var maxR = 0
                var minG = 255; var maxG = 0
                var minB = 255; var maxB = 0
                var hasAlpha = false

                for (py in 0 until 4) {
                    for (px in 0 until 4) {
                        val x = minOf(bx * 4 + px, width - 1)
                        val y = minOf(by * 4 + py, height - 1)
                        val pixel = pixels[y * width + x]
                        blockPixels[py * 4 + px] = pixel

                        val a = (pixel ushr 24) and 0xFF
                        if (a < 128) {
                            hasAlpha = true
                        } else {
                            val r = (pixel ushr 16) and 0xFF
                            val g = (pixel ushr 8) and 0xFF
                            val b = pixel and 0xFF

                            if (r < minR) minR = r; if (r > maxR) maxR = r
                            if (g < minG) minG = g; if (g > maxG) maxG = g
                            if (b < minB) minB = b; if (b > maxB) maxB = b
                        }
                    }
                }

                val c0 = argbToRgb565(maxR, maxG, maxB)
                val c1 = argbToRgb565(minR, minG, minB)

                val color0: Int
                val color1: Int
                if (hasAlpha) {
                    color0 = minOf(c0, c1)
                    color1 = maxOf(c0, c1)
                } else {
                    color0 = maxOf(c0, c1)
                    color1 = minOf(c0, c1)
                }

                out[outOffset] = (color0 and 0xFF).toByte()
                out[outOffset + 1] = ((color0 ushr 8) and 0xFF).toByte()
                out[outOffset + 2] = (color1 and 0xFF).toByte()
                out[outOffset + 3] = ((color1 ushr 8) and 0xFF).toByte()

                val palette = IntArray(4)
                palette[0] = rgb565ToArgb(color0, 255)
                palette[1] = rgb565ToArgb(color1, 255)
                if (color0 > color1) {
                    palette[2] = interpolateColor(palette[0], palette[1], 2, 1, 3)
                    palette[3] = interpolateColor(palette[0], palette[1], 1, 2, 3)
                } else {
                    palette[2] = interpolateColor(palette[0], palette[1], 1, 1, 2)
                    palette[3] = 0x00000000
                }

                var lookup = 0L
                for (i in 0 until 16) {
                    val p = blockPixels[i]
                    val a = (p ushr 24) and 0xFF
                    val bestCode = if (hasAlpha && a < 128) {
                        3
                    } else {
                        findClosestPaletteIndex(p, palette, if (hasAlpha) 3 else 4)
                    }
                    lookup = lookup or (bestCode.toLong() shl (i * 2))
                }

                out[outOffset + 4] = (lookup and 0xFF).toByte()
                out[outOffset + 5] = ((lookup ushr 8) and 0xFF).toByte()
                out[outOffset + 6] = ((lookup ushr 16) and 0xFF).toByte()
                out[outOffset + 7] = ((lookup ushr 24) and 0xFF).toByte()

                outOffset += 8
            }
        }
        return out
    }

    private fun rgb565ToArgb(c: Int, alpha: Int): Int {
        val r = (((c ushr 11) and 0x1F) * 255 + 15) / 31
        val g = (((c ushr 5) and 0x3F) * 255 + 31) / 63
        val b = ((c and 0x1F) * 255 + 15) / 31
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun argbToRgb565(r: Int, g: Int, b: Int): Int {
        val r5 = (r * 31 + 127) / 255
        val g6 = (g * 63 + 127) / 255
        val b5 = (b * 31 + 127) / 255
        return (r5 shl 11) or (g6 shl 5) or b5
    }

    private fun interpolateColor(c0: Int, c1: Int, w0: Int, w1: Int, total: Int): Int {
        val r0 = (c0 ushr 16) and 0xFF; val r1 = (c1 ushr 16) and 0xFF
        val g0 = (c0 ushr 8) and 0xFF;  val g1 = (c1 ushr 8) and 0xFF
        val b0 = c0 and 0xFF;         val b1 = c1 and 0xFF

        val r = (r0 * w0 + r1 * w1) / total
        val g = (g0 * w0 + g1 * w1) / total
        val b = (b0 * w0 + b1 * w1) / total

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun findClosestPaletteIndex(pixel: Int, palette: IntArray, count: Int): Int {
        val pr = (pixel ushr 16) and 0xFF
        val pg = (pixel ushr 8) and 0xFF
        val pb = pixel and 0xFF

        var bestIndex = 0
        var bestDist = Int.MAX_VALUE

        for (i in 0 until count) {
            val cr = (palette[i] ushr 16) and 0xFF
            val cg = (palette[i] ushr 8) and 0xFF
            val cb = palette[i] and 0xFF

            val dr = pr - cr
            val dg = pg - cg
            val db = pb - cb
            val dist = dr * dr + dg * dg + db * db

            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
                if (dist == 0) break
            }
        }
        return bestIndex
    }
}
