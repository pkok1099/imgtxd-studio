package com.onlasdan.studio.txd

import android.graphics.Bitmap
import android.opengl.ETC1
import com.onlasdan.studio.util.AppIntegrity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance RenderWare .txd (Texture Dictionary) parser for GTA games.
 * Supports DXT1, DXT3, DXT5, RGBA32, RGB24, 8-bit Paletted, 16-bit, and ETC1 textures.
 */
class TxdParser private constructor(
    val textures: List<TxdEntry>,
    val rwVersion: Int
) {

    companion object {

        @Throws(IOException::class)
        fun parse(file: File): TxdParser {
            AppIntegrity.verifyOrThrow()
            if (!file.exists()) {
                throw IOException("File TXD tidak ditemukan: ${file.absolutePath}")
            }
            FileInputStream(file).use { fis ->
                return parse(fis)
            }
        }

        @Throws(IOException::class)
        fun parse(bytes: ByteArray): TxdParser {
            AppIntegrity.verifyOrThrow()
            ByteArrayInputStream(bytes).use { bais ->
                return parse(bais)
            }
        }

        @Throws(IOException::class)
        fun parse(inputStream: InputStream): TxdParser {
            AppIntegrity.verifyOrThrow()
            val allBytes = inputStream.readBytes()
            val buf = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Read Root Chunk Header (12 bytes)
            if (buf.remaining() < 12) {
                throw IOException("Format TXD rusak: ukuran file terlalu kecil")
            }
            val rootChunkId = buf.int
            val rootChunkSize = buf.int
            val rwVersion = buf.int

            if (rootChunkId != RwChunk.CHUNK_TEXTURE_DICTIONARY) {
                throw IOException("Bukan file TXD RenderWare yang valid (Root Chunk: 0x%04X)".format(rootChunkId))
            }

            // Subchunk 1: rwID_STRUCT (contains texture count)
            val structId = buf.int
            val structSize = buf.int
            val structVersion = buf.int
            if (structId != RwChunk.CHUNK_STRUCT || structSize < 4) {
                throw IOException("Header TXD struct tidak valid")
            }
            val count = buf.short.toInt() and 0xFFFF
            val deviceId = buf.short.toInt() and 0xFFFF

            // Skip remaining bytes of struct if any
            val remainingStruct = structSize - 4
            if (remainingStruct > 0) {
                buf.position(buf.position() + remainingStruct)
            }

            val entries = ArrayList<TxdEntry>(count)

            // Read 'count' Texture Native chunks
            for (i in 0 until count) {
                if (buf.remaining() < 12) break

                val nativeChunkId = buf.int
                val nativeChunkSize = buf.int
                val nativeVersion = buf.int

                if (nativeChunkId != RwChunk.CHUNK_TEXTURE_NATIVE) {
                    // Skip unknown chunk
                    buf.position(minOf(buf.position() + nativeChunkSize, buf.limit()))
                    continue
                }

                val nativeStart = buf.position()
                val nativeEnd = minOf(nativeStart + nativeChunkSize, buf.limit())

                // Inner Struct Chunk
                val innerStructId = buf.int
                val innerStructSize = buf.int
                val innerStructVersion = buf.int
                val innerStructEnd = buf.position() + innerStructSize

                val platformId = buf.int
                val filterFlags = buf.int

                // 32-byte Name
                val nameBytes = ByteArray(32)
                buf.get(nameBytes)
                val rawName = String(nameBytes, Charsets.ISO_8859_1)
                val cleanName = if (rawName.indexOf('\u0000') >= 0) rawName.substringBefore('\u0000') else rawName

                // 32-byte Alpha Mask Name
                val maskBytes = ByteArray(32)
                buf.get(maskBytes)
                val rawMask = String(maskBytes, Charsets.ISO_8859_1)
                val cleanMask = if (rawMask.indexOf('\u0000') >= 0) rawMask.substringBefore('\u0000') else rawMask

                val rasterFormat = buf.int
                val d3dFormat = buf.int
                val width = buf.short.toInt() and 0xFFFF
                val height = buf.short.toInt() and 0xFFFF
                val depth = buf.get().toInt() and 0xFF
                val mipmapCount = buf.get().toInt() and 0xFF
                val rasterType = buf.get().toInt() and 0xFF
                val compressionFlags = buf.get().toInt() and 0xFF

                // Optional Palette (depth == 8 or PAL8)
                val hasPalette = (rasterFormat and RwChunk.FORMAT_PAL8) != 0 || depth == 8
                val palette = if (hasPalette && buf.position() + 1024 <= buf.limit()) {
                    val palBytes = ByteArray(1024)
                    buf.get(palBytes)
                    palBytes
                } else {
                    null
                }

                // Surface Data Size & Pixels
                val dataSize = buf.int
                val rawPixels = ByteArray(dataSize)
                val bytesToRead = minOf(dataSize, buf.remaining())
                buf.get(rawPixels, 0, bytesToRead)

                // Skip inner struct to end (e.g. mipmaps)
                if (buf.position() < innerStructEnd) {
                    buf.position(minOf(innerStructEnd, buf.limit()))
                }

                // Skip Extension Chunk (0x03)
                if (buf.position() < nativeEnd) {
                    if (buf.remaining() >= 12) {
                        val extId = buf.int
                        val extSize = buf.int
                        val extVer = buf.int
                        buf.position(minOf(buf.position() + extSize, nativeEnd))
                    } else {
                        buf.position(nativeEnd)
                    }
                }

                val entry = TxdEntry(
                    name = cleanName.ifEmpty { "texture_$i" },
                    maskName = cleanMask,
                    width = width,
                    height = height,
                    depth = depth,
                    rasterFormat = rasterFormat,
                    d3dFormat = d3dFormat,
                    filterFlags = filterFlags,
                    platformId = platformId,
                    rawPixels = rawPixels,
                    palette = palette,
                    mipmapCount = maxOf(1, mipmapCount)
                )

                entries.add(entry)
            }

            return TxdParser(entries, rwVersion)
        }

        /**
         * Converts raw texture byte data to an Android Bitmap based on its raster and compression formats.
         */
        fun decodeBitmap(entry: TxdEntry): Bitmap? {
            val width = entry.width
            val height = entry.height
            if (width <= 0 || height <= 0 || entry.rawPixels.isEmpty()) return null

            return when {
                // DXT1 Compression
                entry.d3dFormat == RwChunk.FOURCC_DXT1 -> {
                    DxtDecoder.decodeDxt1(entry.rawPixels, width, height)
                }

                // DXT3 Compression
                entry.d3dFormat == RwChunk.FOURCC_DXT3 -> {
                    DxtDecoder.decodeDxt3(entry.rawPixels, width, height)
                }

                // DXT5 Compression
                entry.d3dFormat == RwChunk.FOURCC_DXT5 -> {
                    DxtDecoder.decodeDxt5(entry.rawPixels, width, height)
                }

                // ETC1 Compression (Mobile Android)
                entry.d3dFormat == RwChunk.FOURCC_ETC1 -> {
                    decodeEtc1(entry.rawPixels, width, height)
                }

                // 8-bit Paletted Textures
                (entry.rasterFormat and RwChunk.FORMAT_PAL8) != 0 || entry.depth == 8 -> {
                    decodePaletted8(entry)
                }

                // 32-bit RGBA / BGRA
                (entry.rasterFormat and RwChunk.FORMAT_8888) != 0 || entry.depth == 32 -> {
                    decodeRgba32(entry.rawPixels, width, height)
                }

                // 24-bit RGB / BGR
                (entry.rasterFormat and RwChunk.FORMAT_888) != 0 || entry.depth == 24 -> {
                    decodeRgb24(entry.rawPixels, width, height)
                }

                // 16-bit RGB 565
                (entry.rasterFormat and RwChunk.FORMAT_565) != 0 -> {
                    decodeRgb565(entry.rawPixels, width, height)
                }

                // 16-bit RGBA 1555
                (entry.rasterFormat and RwChunk.FORMAT_1555) != 0 -> {
                    decodeRgba1555(entry.rawPixels, width, height)
                }

                // 16-bit RGBA 4444
                (entry.rasterFormat and RwChunk.FORMAT_4444) != 0 -> {
                    decodeRgba4444(entry.rawPixels, width, height)
                }

                // Fallback attempt: if 4 bytes per pixel, treat as 32-bit RGBA
                entry.rawPixels.size >= width * height * 4 -> {
                    decodeRgba32(entry.rawPixels, width, height)
                }

                else -> null
            }
        }

        private fun decodePaletted8(entry: TxdEntry): Bitmap {
            val width = entry.width
            val height = entry.height
            val palette = entry.palette ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            for (i in 0 until minOf(pixels.size, entry.rawPixels.size)) {
                val idx = entry.rawPixels[i].toInt() and 0xFF
                val palOffset = idx * 4
                if (palOffset + 3 < palette.size) {
                    val r = palette[palOffset].toInt() and 0xFF
                    val g = palette[palOffset + 1].toInt() and 0xFF
                    val b = palette[palOffset + 2].toInt() and 0xFF
                    val a = palette[palOffset + 3].toInt() and 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun decodeRgba32(raw: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            var p = 0
            val limit = minOf(pixels.size, raw.size / 4)
            for (i in 0 until limit) {
                // RenderWare PC stores raw 32-bit in BGRA format
                val b = raw[p].toInt() and 0xFF
                val g = raw[p + 1].toInt() and 0xFF
                val r = raw[p + 2].toInt() and 0xFF
                val a = raw[p + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                p += 4
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun decodeRgb24(raw: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            var p = 0
            val limit = minOf(pixels.size, raw.size / 3)
            for (i in 0 until limit) {
                // RenderWare PC stores raw 24-bit in BGR format
                val b = raw[p].toInt() and 0xFF
                val g = raw[p + 1].toInt() and 0xFF
                val r = raw[p + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                p += 3
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun decodeRgb565(raw: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            var p = 0
            val limit = minOf(pixels.size, raw.size / 2)
            for (i in 0 until limit) {
                val c = (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8)
                val r = (((c ushr 11) and 0x1F) * 255 + 15) / 31
                val g = (((c ushr 5) and 0x3F) * 255 + 31) / 63
                val b = ((c and 0x1F) * 255 + 15) / 31
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                p += 2
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun decodeRgba1555(raw: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            var p = 0
            val limit = minOf(pixels.size, raw.size / 2)
            for (i in 0 until limit) {
                val c = (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8)
                val a = if ((c and 0x8000) != 0) 255 else 0
                val r = (((c ushr 10) and 0x1F) * 255 + 15) / 31
                val g = (((c ushr 5) and 0x1F) * 255 + 15) / 31
                val b = ((c and 0x1F) * 255 + 15) / 31
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                p += 2
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun decodeRgba4444(raw: ByteArray, width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            var p = 0
            val limit = minOf(pixels.size, raw.size / 2)
            for (i in 0 until limit) {
                val c = (raw[p].toInt() and 0xFF) or ((raw[p + 1].toInt() and 0xFF) shl 8)
                val a = ((c ushr 12) and 0x0F) * 17
                val r = ((c ushr 8) and 0x0F) * 17
                val g = ((c ushr 4) and 0x0F) * 17
                val b = (c and 0x0F) * 17
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                p += 2
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun decodeEtc1(data: ByteArray, width: Int, height: Int): Bitmap? {
            return try {
                val inputBuf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
                inputBuf.put(data).position(0)

                // Output RGB buffer (3 bytes per pixel)
                val rgbBuf = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder())
                ETC1.decodeImage(inputBuf, rgbBuf, width, height, 3, width * 3)
                rgbBuf.position(0)

                val pixels = IntArray(width * height)
                for (i in 0 until width * height) {
                    val r = rgbBuf.get().toInt() and 0xFF
                    val g = rgbBuf.get().toInt() and 0xFF
                    val b = rgbBuf.get().toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }
        }
    }
}
