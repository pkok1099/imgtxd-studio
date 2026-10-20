package com.onlasdan.studio.txd

import android.graphics.Bitmap
import com.onlasdan.studio.util.AppIntegrity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes texture dictionaries into official RenderWare .txd binary streams.
 * Enables in-place texture replacement and exporting modified TXD archives.
 */
object TxdBuilder {

    /**
     * Replaces an existing texture entry with a new Android Bitmap.
     */
    fun replaceTexture(entry: TxdEntry, newBitmap: Bitmap) {
        val width = newBitmap.width
        val height = newBitmap.height
        val pixels = IntArray(width * height)
        newBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        replaceTexture(entry, pixels, width, height, asDxt1 = null)
        entry.bitmap = newBitmap
    }

    /**
     * Replaces an existing texture entry with ARGB pixel data.
     * If asDxt1 is null, preserves existing format (DXT1 stays DXT1, others convert to uncompressed 32-bit).
     */
    fun replaceTexture(entry: TxdEntry, pixels: IntArray, width: Int, height: Int, asDxt1: Boolean? = null) {
        entry.width = width
        entry.height = height
        entry.bitmap = null

        val useDxt1 = asDxt1 ?: (entry.d3dFormat == RwChunk.FOURCC_DXT1)
        if (useDxt1) {
            entry.rawPixels = DxtDecoder.encodeDxt1(pixels, width, height)
            entry.d3dFormat = RwChunk.FOURCC_DXT1
            entry.rasterFormat = RwChunk.FORMAT_565
            entry.depth = 16
        } else {
            // Encode as uncompressed 32-bit BGRA (universally supported by all GTA versions)
            val bgra = ByteArray(width * height * 4)
            var p = 0
            for (pixel in pixels) {
                val a = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF

                bgra[p] = b.toByte()
                bgra[p + 1] = g.toByte()
                bgra[p + 2] = r.toByte()
                bgra[p + 3] = a.toByte()
                p += 4
            }
            entry.rawPixels = bgra
            entry.d3dFormat = 0
            entry.rasterFormat = RwChunk.FORMAT_8888
            entry.depth = 32
            entry.palette = null
        }
    }

    /**
     * Builds and writes a TXD file from a list of texture entries.
     */
    @Throws(IOException::class)
    fun buildToFile(entries: List<TxdEntry>, destFile: File, rwVersion: Int = RwChunk.RW_VERSION_SA) {
        AppIntegrity.verifyOrThrow()
        val bytes = buildToBytes(entries, rwVersion)
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
    }

    /**
     * Alias for buildToBytes.
     */
    fun build(entries: List<TxdEntry>, rwVersion: Int = RwChunk.RW_VERSION_SA): ByteArray = buildToBytes(entries, rwVersion)

    /**
     * Serializes texture entries to a binary byte array.
     */
    fun buildToBytes(entries: List<TxdEntry>, rwVersion: Int = RwChunk.RW_VERSION_SA): ByteArray {
        AppIntegrity.verifyOrThrow()
        val baos = ByteArrayOutputStream(entries.sumOf { it.rawPixels.size + 256 } + 64)

        // Calculate sizes
        val nativeChunks = ArrayList<ByteArray>(entries.size)
        for (entry in entries) {
            nativeChunks.add(buildNativeChunk(entry, rwVersion))
        }

        // Struct Chunk size (4 bytes: count short + deviceId short)
        val structTotalSize = 12 + 4
        val totalNativeSize = nativeChunks.sumOf { it.size }
        val rootChunkSize = structTotalSize + totalNativeSize

        // 1. Root Chunk Header (12 bytes)
        writeHeader(baos, RwChunk.CHUNK_TEXTURE_DICTIONARY, rootChunkSize, rwVersion)

        // 2. Struct Subchunk (12 + 4 bytes)
        writeHeader(baos, RwChunk.CHUNK_STRUCT, 4, rwVersion)
        val structBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        structBuf.putShort(entries.size.toShort())
        structBuf.putShort(1.toShort()) // D3D8 / D3D9 device
        baos.write(structBuf.array())

        // 3. Texture Native Chunks
        for (chunk in nativeChunks) {
            baos.write(chunk)
        }

        return baos.toByteArray()
    }

    private fun buildNativeChunk(entry: TxdEntry, rwVersion: Int): ByteArray {
        val baos = ByteArrayOutputStream(entry.rawPixels.size + 256)

        val hasPalette = entry.palette != null && entry.palette!!.size == 1024
        val paletteSize = if (hasPalette) 1024 else 0
        val innerStructSize = 88 + paletteSize + 4 + entry.rawPixels.size

        // Total Native size = Inner Struct (12 + innerStructSize) + Extension (12)
        val nativeChunkSize = 12 + innerStructSize + 12

        // Write Native Header
        writeHeader(baos, RwChunk.CHUNK_TEXTURE_NATIVE, nativeChunkSize, rwVersion)

        // Write Inner Struct Header
        writeHeader(baos, RwChunk.CHUNK_STRUCT, innerStructSize, rwVersion)

        val structBuf = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        structBuf.putInt(entry.platformId)
        structBuf.putInt(entry.filterFlags)

        // 32-byte Name
        val nameBytes = ByteArray(32)
        val srcName = entry.name.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(srcName, 0, nameBytes, 0, minOf(srcName.size, 31))
        structBuf.put(nameBytes)

        // 32-byte Mask Name
        val maskBytes = ByteArray(32)
        val srcMask = entry.maskName.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(srcMask, 0, maskBytes, 0, minOf(srcMask.size, 31))
        structBuf.put(maskBytes)

        structBuf.putInt(entry.rasterFormat)
        structBuf.putInt(entry.d3dFormat)
        structBuf.putShort(entry.width.toShort())
        structBuf.putShort(entry.height.toShort())
        structBuf.put(entry.depth.toByte())
        structBuf.put(maxOf(1, entry.mipmapCount).toByte())
        structBuf.put(0x04.toByte()) // Raster type: standard
        structBuf.put(0.toByte())    // Compression flags

        baos.write(structBuf.array())

        // Write Palette if any
        if (hasPalette) {
            baos.write(entry.palette!!)
        }

        // Write Data Size & Raw Pixels
        val sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuf.putInt(entry.rawPixels.size)
        baos.write(sizeBuf.array())
        baos.write(entry.rawPixels)

        // Write Empty Extension Chunk (12 bytes)
        writeHeader(baos, RwChunk.CHUNK_EXTENSION, 0, rwVersion)

        return baos.toByteArray()
    }

    private fun writeHeader(baos: ByteArrayOutputStream, type: Int, size: Int, version: Int) {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(type)
        buf.putInt(size)
        buf.putInt(version)
        baos.write(buf.array())
    }
}
