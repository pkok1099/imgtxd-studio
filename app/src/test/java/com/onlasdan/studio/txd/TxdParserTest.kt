package com.onlasdan.studio.txd

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TxdParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDxt1EncodingAndDecodingRoundTrip() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height)

        // Block 0: Red
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                pixels[y * width + x] = 0xFFFF0000.toInt()
            }
        }
        // Block 1: Green
        for (y in 0 until 4) {
            for (x in 4 until 8) {
                pixels[y * width + x] = 0xFF00FF00.toInt()
            }
        }
        // Block 2: Blue
        for (y in 4 until 8) {
            for (x in 0 until 4) {
                pixels[y * width + x] = 0xFF0000FF.toInt()
            }
        }
        // Block 3: Half-transparent / dark
        for (y in 4 until 8) {
            for (x in 4 until 8) {
                pixels[y * width + x] = 0xFF808080.toInt()
            }
        }

        val compressed = DxtDecoder.encodeDxt1(pixels, width, height)
        assertEquals(32, compressed.size) // 2x2 blocks * 8 bytes = 32

        val decoded = DxtDecoder.decodeDxt1ToPixels(compressed, width, height)
        assertEquals(width * height, decoded.size)

        // Verify Block 0 (Red)
        val p0 = decoded[0]
        val r0 = (p0 ushr 16) and 0xFF
        val g0 = (p0 ushr 8) and 0xFF
        val b0 = p0 and 0xFF
        assertTrue("Red channel should be high (was $r0)", r0 > 240)
        assertTrue("Green channel should be low (was $g0)", g0 < 15)
        assertTrue("Blue channel should be low (was $b0)", b0 < 15)

        // Verify Block 1 (Green)
        val p1 = decoded[4]
        val r1 = (p1 ushr 16) and 0xFF
        val g1 = (p1 ushr 8) and 0xFF
        val b1 = p1 and 0xFF
        assertTrue("Red channel should be low (was $r1)", r1 < 15)
        assertTrue("Green channel should be high (was $g1)", g1 > 240)
        assertTrue("Blue channel should be low (was $b1)", b1 < 15)
    }

    @Test
    fun testTxdBuildAndParseSingleTexture() {
        val texData = ByteArray(32) { (it + 1).toByte() }
        val entry = TxdEntry(
            name = "vehicle_body",
            maskName = "",
            width = 8,
            height = 8,
            depth = 16,
            rasterFormat = RwChunk.FORMAT_565,
            d3dFormat = RwChunk.FOURCC_DXT1,
            rawPixels = texData,
            platformId = RwChunk.PLATFORM_D3D9
        )

        val txdBytes = TxdBuilder.build(listOf(entry))
        assertTrue("TXD bytes should not be empty", txdBytes.isNotEmpty())

        val parser = TxdParser.parse(txdBytes)
        assertEquals(1, parser.textures.size)

        val parsed = parser.textures[0]
        assertEquals("vehicle_body", parsed.name)
        assertEquals("", parsed.maskName)
        assertEquals(8, parsed.width)
        assertEquals(8, parsed.height)
        assertEquals(16, parsed.depth)
        assertEquals(RwChunk.FOURCC_DXT1, parsed.d3dFormat)
        assertArrayEquals(texData, parsed.rawPixels)
        assertEquals("DXT1", parsed.formatLabel)
    }

    @Test
    fun testTxdBuildAndParseMultipleTextures() {
        val entry1 = TxdEntry(
            name = "cj_face",
            maskName = "cj_face_a",
            width = 16,
            height = 16,
            depth = 16,
            rasterFormat = RwChunk.FORMAT_565,
            d3dFormat = RwChunk.FOURCC_DXT1,
            rawPixels = ByteArray(128) { 0x11 },
            platformId = RwChunk.PLATFORM_D3D9
        )

        val entry2 = TxdEntry(
            name = "cj_hair",
            maskName = "",
            width = 32,
            height = 32,
            depth = 32,
            rasterFormat = RwChunk.FORMAT_8888,
            d3dFormat = 0,
            rawPixels = ByteArray(32 * 32 * 4) { 0x22 },
            platformId = RwChunk.PLATFORM_D3D9
        )

        val entry3 = TxdEntry(
            name = "cj_shoes",
            maskName = "",
            width = 8,
            height = 8,
            depth = 16,
            rasterFormat = RwChunk.FORMAT_565,
            d3dFormat = RwChunk.FOURCC_DXT5,
            rawPixels = ByteArray(64) { 0x33 },
            platformId = RwChunk.PLATFORM_D3D9
        )

        val txdFile = tempFolder.newFile("player.txd")
        TxdBuilder.buildToFile(listOf(entry1, entry2, entry3), txdFile)

        val parser = TxdParser.parse(txdFile)
        assertEquals(3, parser.textures.size)

        assertEquals("cj_face", parser.textures[0].name)
        assertEquals("cj_face_a", parser.textures[0].maskName)
        assertEquals(16, parser.textures[0].width)
        assertEquals("DXT1", parser.textures[0].formatLabel)
        assertArrayEquals(entry1.rawPixels, parser.textures[0].rawPixels)

        assertEquals("cj_hair", parser.textures[1].name)
        assertEquals(32, parser.textures[1].width)
        assertEquals("RGBA 32-bit", parser.textures[1].formatLabel)
        assertArrayEquals(entry2.rawPixels, parser.textures[1].rawPixels)

        assertEquals("cj_shoes", parser.textures[2].name)
        assertEquals(8, parser.textures[2].width)
        assertEquals("DXT5", parser.textures[2].formatLabel)
        assertArrayEquals(entry3.rawPixels, parser.textures[2].rawPixels)
    }

    @Test
    fun testTxdReplaceDataRoundTrip() {
        val initialEntry = TxdEntry(
            name = "infernus",
            maskName = "",
            width = 8,
            height = 8,
            depth = 16,
            rasterFormat = RwChunk.FORMAT_565,
            d3dFormat = RwChunk.FOURCC_DXT1,
            rawPixels = ByteArray(32) { 0x01 },
            platformId = RwChunk.PLATFORM_D3D9
        )

        val initialBytes = TxdBuilder.build(listOf(initialEntry))
        val parsed1 = TxdParser.parse(initialBytes)
        val target = parsed1.textures[0]

        // Replace infernus texture with new RGBA pixel data (16x16)
        val newPixels = IntArray(16 * 16) { 0xFF00FF00.toInt() }
        TxdBuilder.replaceTexture(target, newPixels, 16, 16, asDxt1 = false)

        val updatedBytes = TxdBuilder.build(parsed1.textures)
        val parsed2 = TxdParser.parse(updatedBytes)
        assertEquals(1, parsed2.textures.size)

        val updatedTex = parsed2.textures[0]
        assertEquals("infernus", updatedTex.name)
        assertEquals(16, updatedTex.width)
        assertEquals(16, updatedTex.height)
        assertEquals(32, updatedTex.depth)
        assertEquals(0, updatedTex.d3dFormat)
        assertEquals("RGBA 32-bit", updatedTex.formatLabel)
        assertEquals(16 * 16 * 4, updatedTex.rawPixels.size)
    }
}
