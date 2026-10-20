package com.onlasdan.studio.txd

import android.graphics.Bitmap

/**
 * Represents an individual texture entry parsed from a RenderWare .txd file.
 */
data class TxdEntry(
    var name: String,
    var maskName: String = "",
    var width: Int,
    var height: Int,
    var depth: Int,
    var rasterFormat: Int = RwChunk.FORMAT_8888,
    var d3dFormat: Int = 0,
    var filterFlags: Int = 0x1102, // Linear filtering, wrap U/V
    var platformId: Int = RwChunk.PLATFORM_D3D9,
    var rawPixels: ByteArray,
    var palette: ByteArray? = null,
    var mipmapCount: Int = 1
) {
    /**
     * Cached decoded Android Bitmap. Decoded on-demand when accessed or displayed.
     */
    var bitmap: Bitmap? = null
        get() {
            if (field == null) {
                try {
                    field = TxdParser.decodeBitmap(this)
                } catch (ignored: Throwable) {}
            }
            return field
        }

    val formatLabel: String
        get() = RwChunk.getFormatName(rasterFormat, d3dFormat, depth)

    val dimensionsLabel: String
        get() = "${width}x${height}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TxdEntry) return false
        return name == other.name && width == other.width && height == other.height && d3dFormat == other.d3dFormat
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + d3dFormat
        return result
    }

    override fun toString(): String = "TxdEntry(name='$name', size=${width}x${height}, format=$formatLabel)"
}
