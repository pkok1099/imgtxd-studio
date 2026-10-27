package com.onlasdan.studio.txd

import com.onlasdan.studio.core.NativeCore
import com.onlasdan.studio.util.AppIntegrity
import java.io.File
import java.io.IOException

/**
 * Facade over the Rust TXD engine (`rust-core/src/txd.rs`) via [NativeCore].
 *
 * Replaces the Kotlin TxdParser/TxdBuilder pair (full-Rust migration,
 * 2026-10-23): listing, decoding, replacing and adding textures all run
 * in Rust against the file on disk; this object only maps wire lines to
 * [TxdEntry] models and forwards operations. Every mutation rewrites the
 * file inside the engine, so callers re-[load] to refresh their state.
 */
object TxdStore {

    data class LoadedTxd(val entries: List<TxdEntry>, val rwVersion: Int)

    data class AddResult(val added: Int, val overwritten: Int, val total: Int)

    /** asDxt1Mode for [replaceTextures]: preserve the entry's format family. */
    const val MODE_PRESERVE = 0

    /** asDxt1Mode for [replaceTextures]: force DXT1 encoding. */
    const val MODE_FORCE_DXT1 = 1

    /** asDxt1Mode for [replaceTextures]: force uncompressed 32-bit encoding. */
    const val MODE_FORCE_RAW = 2

    /**
     * Lists a TXD file's textures via the Rust engine.
     */
    @Throws(IOException::class)
    fun load(file: File): LoadedTxd {
        AppIntegrity.verifyOrThrow()
        if (!file.exists()) {
            throw IOException("TXD file not found: ${file.absolutePath}")
        }
        val lines = NativeCore.txdList(file.absolutePath)
            ?: throw IOException("Rust engine unavailable (libimgtxd_core failed to load)")
        if (lines.isEmpty()) throw IOException("Engine Rust mengembalikan daftar kosong")
        val header = lines[0].split('\t', limit = 3)
        if (header[0] == "ERROR") {
            throw IOException(header.getOrNull(1) ?: "TXD can't be opened")
        }
        if (header.size < 3 || header[0] != "OK") {
            throw IOException("Unknown engine response: ${lines[0]}")
        }
        val rwVersion = header[1].toIntOrNull() ?: RwChunk.RW_VERSION_SA
        val entries = ArrayList<TxdEntry>(maxOf(lines.size - 1, 0))
        for (i in 1 until lines.size) {
            // name, mask, w, h, depth, raster, d3d, filter, platform, mips, label
            val p = lines[i].split('\t', limit = 11)
            if (p.size < 11) continue
            entries.add(
                TxdEntry(
                    name = p[0],
                    maskName = p[1],
                    width = p[2].toIntOrNull() ?: 0,
                    height = p[3].toIntOrNull() ?: 0,
                    depth = p[4].toIntOrNull() ?: 0,
                    rasterFormat = p[5].toIntOrNull() ?: 0,
                    d3dFormat = p[6].toIntOrNull() ?: 0,
                    filterFlags = p[7].toIntOrNull() ?: 0,
                    platformId = p[8].toIntOrNull() ?: 0,
                    mipmapCount = p[9].toIntOrNull() ?: 1,
                    sourceIndex = entries.size,
                    sourcePath = file.absolutePath,
                    formatLabelText = p[10]
                )
            )
        }
        return LoadedTxd(entries, rwVersion)
    }

    /**
     * Batch-replaces textures (by their sourceIndex) with new ARGB pixels.
     * The engine rewrites the file; re-[load] afterwards to refresh state.
     */
    @Throws(IOException::class)
    fun replaceTextures(
        file: File,
        indices: List<Int>,
        images: List<Triple<IntArray, Int, Int>>,
        asDxt1Mode: Int = MODE_PRESERVE,
        options: TextureImportOptions = TextureImportOptions()
    ) {
        AppIntegrity.verifyOrThrow()
        requireOk(
            NativeCore.txdReplaceTextures(
                path = file.absolutePath,
                indices = indices.toIntArray(),
                pixelsList = images.map { it.first }.toTypedArray(),
                widths = images.map { it.second }.toIntArray(),
                heights = images.map { it.third }.toIntArray(),
                asDxt1Mode = asDxt1Mode,
                genMips = options.generateMipmaps,
                mipFilter = options.mipFilter.ordinal,
                maxDimension = options.maxDimension ?: 0
            )
        )
    }

    /**
     * Batch-adds textures from ARGB pixels, resolving name conflicts with
     * [policy] inside the engine (Just add = unique suffix names,
     * Overwrite = replace in place). New textures use automatic encoding.
     */
    @Throws(IOException::class)
    fun addTextures(
        file: File,
        names: List<String>,
        images: List<Triple<IntArray, Int, Int>>,
        policy: TextureNames.ConflictPolicy,
        options: TextureImportOptions = TextureImportOptions()
    ): AddResult {
        AppIntegrity.verifyOrThrow()
        val result = requireOk(
            NativeCore.txdAddTextures(
                path = file.absolutePath,
                names = names.toTypedArray(),
                pixelsList = images.map { it.first }.toTypedArray(),
                widths = images.map { it.second }.toIntArray(),
                heights = images.map { it.third }.toIntArray(),
                policy = if (policy == TextureNames.ConflictPolicy.OVERWRITE) 1 else 0,
                genMips = options.generateMipmaps,
                mipFilter = options.mipFilter.ordinal,
                maxDimension = options.maxDimension ?: 0
            )
        )
        // result: "added=A overwritten=B total=T"
        val fields = result.removePrefix("OK ").split(' ')
            .mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: 0) else null
            }
            .toMap()
        return AddResult(
            added = fields["added"] ?: 0,
            overwritten = fields["overwritten"] ?: 0,
            total = fields["total"] ?: 0
        )
    }

    /**
     * Deletes textures at the given indices and rewrites the TXD file in place.
     */
    @Throws(IOException::class)
    fun deleteTextures(file: File, indices: List<Int>): Int {
        AppIntegrity.verifyOrThrow()
        if (indices.isEmpty()) return 0
        val result = requireOk(
            NativeCore.txdDeleteTextures(
                path = file.absolutePath,
                indices = indices.toIntArray()
            )
        )
        // result: "deleted=N"
        val countStr = result.removePrefix("OK ").removePrefix("deleted=")
        return countStr.toIntOrNull() ?: indices.size
    }

    /**
     * Renames a texture at [index] inside the TXD file to [newName].
     */
    @Throws(IOException::class)
    fun renameTexture(file: File, index: Int, newName: String) {
        AppIntegrity.verifyOrThrow()
        requireOk(
            NativeCore.txdRenameTexture(
                path = file.absolutePath,
                index = index,
                newName = newName
            )
        )
    }

    /**
     * Updates texture properties (maskName, filterFlags) at [index] inside the TXD file.
     */
    @Throws(IOException::class)
    fun updateProperties(file: File, index: Int, maskName: String, filterFlags: Int) {
        AppIntegrity.verifyOrThrow()
        requireOk(
            NativeCore.txdUpdateProperties(
                path = file.absolutePath,
                index = index,
                maskName = maskName,
                filterFlags = filterFlags
            )
        )
    }

    /**
     * Imports textures directly from another TXD file [sourceFile] into [destFile].
     */
    @Throws(IOException::class)
    fun importFromTxd(
        destFile: File,
        sourceFile: File,
        policy: TextureNames.ConflictPolicy
    ): AddResult {
        AppIntegrity.verifyOrThrow()
        val result = requireOk(
            NativeCore.txdImportFromTxd(
                destPath = destFile.absolutePath,
                sourcePath = sourceFile.absolutePath,
                policy = if (policy == TextureNames.ConflictPolicy.OVERWRITE) 1 else 0
            )
        )
        // result: "added=A overwritten=B total=T"
        val fields = result.removePrefix("OK ").split(' ')
            .mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: 0) else null
            }
            .toMap()
        return AddResult(
            added = fields["added"] ?: 0,
            overwritten = fields["overwritten"] ?: 0,
            total = fields["total"] ?: 0
        )
    }

    @Throws(IOException::class)
    private fun requireOk(result: String?): String {
        when {
            result == null ->
                throw IOException("Rust engine unavailable (libimgtxd_core failed to load)")
            result.startsWith("ERROR ") ->
                throw IOException(result.removePrefix("ERROR "))
            else -> return result
        }
    }
}
