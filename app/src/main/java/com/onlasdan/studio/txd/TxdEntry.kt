package com.onlasdan.studio.txd

import android.graphics.Bitmap
import com.onlasdan.studio.core.NativeCore

/**
 * UI-side model of one texture inside a RenderWare .txd.
 *
 * Since the full-Rust migration (2026-10-23) this class carries metadata
 * only: the encoded pixel data lives in the file on disk and the Rust
 * engine (`rust-core/src/txd.rs`) is the source of truth. [sourcePath] +
 * [sourceIndex] identify the texture inside its file; [bitmap] decodes
 * through the engine on demand and caches, exactly like the old lazy
 * decoder — but with zero format logic in Kotlin.
 */
class TxdEntry(
    var name: String,
    var maskName: String = "",
    var width: Int,
    var height: Int,
    var depth: Int,
    var rasterFormat: Int = RwChunk.FORMAT_8888,
    var d3dFormat: Int = 0,
    var filterFlags: Int = 0x1102, // Linear filtering, wrap U/V
    var platformId: Int = RwChunk.PLATFORM_D3D9,
    var mipmapCount: Int = 1,
    /** Index of this texture inside [sourcePath]'s dictionary; -1 when detached. */
    var sourceIndex: Int = -1,
    /** The .txd file this entry was listed from. */
    var sourcePath: String? = null,
    /** Format label computed by the Rust engine (port of RwChunk.getFormatName). */
    var formatLabelText: String = ""
) {
    private var bitmapCache: Bitmap? = null

    /**
     * Decoded bitmap, loaded via the Rust engine on first access and cached.
     * Assigning a bitmap primes the cache (used right after an import whose
     * dimensions still match).
     */
    var bitmap: Bitmap?
        get() {
            if (bitmapCache == null) {
                val path = sourcePath
                if (path != null && sourceIndex >= 0 && width > 0 && height > 0) {
                    bitmapCache = try {
                        NativeCore.txdTexturePixels(path, sourceIndex)?.let { px ->
                            if (px.size == width * height) {
                                Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
                            } else {
                                null
                            }
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            return bitmapCache
        }
        set(value) {
            bitmapCache = value
        }

    val formatLabel: String
        get() = formatLabelText

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

/** Resampling filters for texture import (labels for the UI; ordinals map to the Rust engine). */
enum class MipFilter(val label: String) {
    NEAREST("Nearest"),
    BOX("Box (Area)"),
    BILINEAR("Linear")
}

/** Options of the TXD Tool-style Import Wizard. */
data class TextureImportOptions(
    val generateMipmaps: Boolean = false,
    val mipFilter: MipFilter = MipFilter.BOX,
    val maxDimension: Int? = null
)

/** Name-conflict policies for texture import (TXD Tool's Import Wizard). */
object TextureNames {
    enum class ConflictPolicy(val label: String) {
        JUST_ADD("Tambah Saja"),
        OVERWRITE("Timpa")
    }
}
