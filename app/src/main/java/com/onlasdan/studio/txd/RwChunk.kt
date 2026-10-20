package com.onlasdan.studio.txd

/**
 * RenderWare binary stream chunk identifiers and texture format constants.
 * Used for parsing and generating GTA RenderWare .txd (Texture Dictionary) files.
 */
object RwChunk {
    // Core RenderWare Chunk IDs
    const val CHUNK_STRUCT = 0x0001
    const val CHUNK_STRING = 0x0002
    const val CHUNK_EXTENSION = 0x0003
    const val CHUNK_TEXTURE_NATIVE = 0x0015
    const val CHUNK_TEXTURE_DICTIONARY = 0x0016

    // RenderWare Version Tokens
    const val RW_VERSION_SA = 0x1803FFFF  // RenderWare 3.6.0.3 (GTA San Andreas)
    const val RW_VERSION_VC = 0x0C02FFFF  // RenderWare 3.4.0.3 (GTA Vice City)
    const val RW_VERSION_III = 0x0800FFFF // RenderWare 3.3.0.2 (GTA III)

    // Platform IDs (stored in Texture Native Struct)
    const val PLATFORM_DEFAULT = 0x00
    const val PLATFORM_D3D8 = 0x08
    const val PLATFORM_D3D9 = 0x09
    const val PLATFORM_PS2 = 0x06
    const val PLATFORM_XBOX = 0x05
    const val PLATFORM_MOBILE = 0x0C

    // Raster Formats (Raster Format Bitmask)
    const val FORMAT_DEFAULT = 0x0000
    const val FORMAT_1555 = 0x0100
    const val FORMAT_565 = 0x0200
    const val FORMAT_4444 = 0x0300
    const val FORMAT_LUM8 = 0x0400
    const val FORMAT_8888 = 0x0500
    const val FORMAT_888 = 0x0600
    const val FORMAT_16 = 0x0700
    const val FORMAT_24 = 0x0800
    const val FORMAT_32 = 0x0900
    const val FORMAT_555 = 0x0A00
    const val FORMAT_PAL8 = 0x2000
    const val FORMAT_PAL4 = 0x4000
    const val FORMAT_MIPMAP = 0x8000

    // Direct3D FourCC Texture Formats (Little-Endian)
    const val FOURCC_DXT1 = 0x31545844 // "DXT1"
    const val FOURCC_DXT2 = 0x32545844 // "DXT2"
    const val FOURCC_DXT3 = 0x33545844 // "DXT3"
    const val FOURCC_DXT4 = 0x34545844 // "DXT4"
    const val FOURCC_DXT5 = 0x35545844 // "DXT5"
    const val FOURCC_ETC1 = 0x31435445 // "ETC1"
    const val FOURCC_PVR = 0x00525650  // "PVR\0"

    /**
     * Checks if the given 32-bit ID is a DXT compression FourCC.
     */
    fun isDxt(fourCc: Int): Boolean {
        return fourCc == FOURCC_DXT1 || fourCc == FOURCC_DXT3 || fourCc == FOURCC_DXT5
    }

    /**
     * Human-readable label for a given texture format.
     */
    fun getFormatName(rasterFormat: Int, d3dFormat: Int, depth: Int): String {
        return when (d3dFormat) {
            FOURCC_DXT1 -> "DXT1"
            FOURCC_DXT3 -> "DXT3"
            FOURCC_DXT5 -> "DXT5"
            FOURCC_ETC1 -> "ETC1"
            else -> {
                when {
                    (rasterFormat and FORMAT_PAL8) != 0 -> "Paletted 8-bit"
                    (rasterFormat and FORMAT_PAL4) != 0 -> "Paletted 4-bit"
                    (rasterFormat and FORMAT_8888) != 0 || depth == 32 -> "RGBA 32-bit"
                    (rasterFormat and FORMAT_888) != 0 || depth == 24 -> "RGB 24-bit"
                    (rasterFormat and FORMAT_565) != 0 -> "RGB 565"
                    (rasterFormat and FORMAT_1555) != 0 -> "RGBA 1555"
                    (rasterFormat and FORMAT_4444) != 0 -> "RGBA 4444"
                    else -> "Raw ($depth-bit)"
                }
            }
        }
    }
}
