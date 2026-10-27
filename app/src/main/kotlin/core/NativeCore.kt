package com.onlasdan.studio.core

/**
 * Bridge to the Rust core engine (libimgtxd_core.so, crate `rust-core/`).
 *
 * The Rust rewrite of the whole core was decided by the user on 2026-10-23.
 * Modules land incrementally; every entry point here must keep a Kotlin
 * fallback until its Rust counterpart is verified against golden tests.
 */
object NativeCore {

    val isAvailable: Boolean = try {
        System.loadLibrary("imgtxd_core")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeVersion(): String
    private external fun nativeDecodeDxt1(data: ByteArray, width: Int, height: Int): IntArray?
    private external fun nativeMipmapHalf(pixels: IntArray, width: Int, height: Int, filter: Int): IntArray?
    private external fun nativeTexdbImportImages(
        dir: String, names: Array<String>, pngs: Array<ByteArray>,
        format: Int, conflict: Int, siblingModel: String, reduceTo: Int,
        camnorm: Boolean, hashCheck: Boolean, correctCaps: Boolean,
        useRle: Boolean, genMips: Boolean, mipFilter: Int, defrag: Boolean
    ): String?
    private external fun nativeTexdbRebuildCache(dir: String): String?
    private external fun nativeTexdbTexturePixels(dir: String, index: Int): IntArray?
    private external fun nativeTexdbCheckDff(dir: String, dffPath: String): String?
    private external fun nativeTexdbDeleteTextures(dir: String, indices: IntArray): String?
    private external fun nativeTexdbRenameTexture(dir: String, index: Int, newName: String): String?
    private external fun nativeTexdbAddAlias(dir: String, targetIndex: Int, aliasName: String): String?
    private external fun nativeTexdbRepackCache(dir: String, target: Int): String?
    private external fun nativeDffInspect(path: String): String?
    private external fun nativeDffExtractMesh(path: String): ByteArray?
    private external fun nativeImgVerify(path: String): String?
    private external fun nativeImgIsVer2(path: String): Boolean
    private external fun nativeImgList(path: String, dir: String?): Array<String>?
    private external fun nativeImgCreate(path: String, version: Int, dir: String?): String?
    private external fun nativeImgExtract(path: String, dir: String?, index: Int, dest: String): String?
    private external fun nativeImgAdd(path: String, dir: String?, filePath: String, entryName: String?): String?
    private external fun nativeImgReplace(path: String, dir: String?, index: Int, filePath: String): String?
    private external fun nativeImgDelete(path: String, dir: String?, indices: IntArray): String?
    private external fun nativeImgRename(path: String, dir: String?, index: Int, newName: String): String?
    private external fun nativeImgRebuild(path: String, dir: String?): Array<String>?
    private external fun nativeTxdList(path: String): Array<String>?
    private external fun nativeTxdCreate(path: String, rwVersion: Int): String?
    private external fun nativeTxdDeleteTextures(path: String, indices: IntArray): String?
    private external fun nativeTxdRenameTexture(path: String, index: Int, newName: String): String?
    private external fun nativeTxdUpdateProperties(path: String, index: Int, maskName: String, filterFlags: Int): String?
    private external fun nativeTxdImportFromTxd(destPath: String, sourcePath: String, policy: Int): String?
    private external fun nativeTxdTexturePixels(path: String, index: Int): IntArray?
    private external fun nativeTxdReplaceTextures(
        path: String, indices: IntArray, pixelsList: Array<IntArray>,
        widths: IntArray, heights: IntArray, asDxt1Mode: Int,
        genMips: Boolean, mipFilter: Int, maxDimension: Int
    ): String?
    private external fun nativeTxdAddTextures(
        path: String, names: Array<String>, pixelsList: Array<IntArray>,
        widths: IntArray, heights: IntArray, policy: Int,
        genMips: Boolean, mipFilter: Int, maxDimension: Int
    ): String?
    private external fun nativeEncodeDxt1(pixels: IntArray, width: Int, height: Int): ByteArray?
    private external fun nativeTexdbList(dir: String): Array<String>?
    private external fun nativeTexdbExtractRaw(dir: String, index: Int, dest: String): String?
    private external fun nativeAudioFixOsw(path: String): String?
    private external fun nativeAudioComputeOsw(path: String): Array<String>?
    private external fun nativeGameAudit(path: String): String?
    private external fun nativeDffRenameTexture(path: String, oldName: String, newName: String): String?
    private external fun nativeDataLinesPlan(fileText: String, pastedText: String): String?
    private external fun nativeIdeScanDir(root: String): Array<String>?
    private external fun nativeIdeScanPaths(paths: Array<String>): Array<String>?
    fun version(): String? = if (isAvailable) {
        try {
            nativeVersion()
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    fun decodeDxt1(data: ByteArray, width: Int, height: Int): IntArray? = if (isAvailable) {
        try {
            nativeDecodeDxt1(data, width, height)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** filter: 0 = NEAREST, 1 = BOX, 2 = BILINEAR (matches MipFilter ordinal order used by the benchmark). */
    fun mipmapHalf(pixels: IntArray, width: Int, height: Int, filter: Int): IntArray? = if (isAvailable) {
        try {
            nativeMipmapHalf(pixels, width, height, filter)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /**
     * Imports images into the texdb cache in [dir] (Rust engine, TXD Tool
     * wizard semantics). format: 0 compressed+alpha, 1 compressed no-alpha,
     * 2 uncompressed+alpha, 3 uncompressed no-alpha, 4 uncompressed 32bpp,
     * 5 uncompressed low-memory. conflict: 0 just add, 1 overwrite,
     * 2 create sibling. When [defragment] is set (TXD Tool "fast
     * overwrite"), the engine writes the result in a clean defragmented
     * layout inside the same call — no separate rebuild pass is needed.
     * Returns the engine's "OK ..."/"ERROR ..." line.
     */
    fun texdbImportImages(
        dir: String,
        images: List<Pair<String, ByteArray>>,
        format: Int,
        conflict: Int,
        siblingModel: String,
        reduceTo: Int,
        camnorm: Boolean,
        hashCheck: Boolean,
        correctCaps: Boolean,
        useRle: Boolean,
        genMips: Boolean,
        mipFilter: Int = 1,
        defragment: Boolean = false
    ): String? = if (isAvailable) {
        try {
            nativeTexdbImportImages(
                dir,
                images.map { it.first }.toTypedArray(),
                images.map { it.second }.toTypedArray(),
                format, conflict, siblingModel, reduceTo,
                camnorm, hashCheck, correctCaps, useRle, genMips, mipFilter,
                defragment
            )
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Rebuilds the cache's .dat/.toc in place. Returns "OK ..."/"ERROR ..." or null. */
    fun texdbRebuildCache(dir: String): String? = if (isAvailable) {
        try {
            nativeTexdbRebuildCache(dir)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Level-0 pixels of one cache texture: [width, height, ARGB...] or null. */
    fun texdbTexturePixels(dir: String, index: Int): IntArray? = if (isAvailable) {
        try {
            nativeTexdbTexturePixels(dir, index)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Texture names a DFF needs that the cache lacks. Returns "OK\n<names>"/"ERROR ..." or null. */
    fun texdbCheckDff(dir: String, dffPath: String): String? = if (isAvailable) {
        try {
            nativeTexdbCheckDff(dir, dffPath)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Deletes textures (and their dangling affiliates). Returns "OK ..."/"ERROR ..." or null. */
    fun texdbDeleteTextures(dir: String, indices: IntArray): String? = if (isAvailable) {
        try {
            nativeTexdbDeleteTextures(dir, indices)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Renames one texture (manifest, listing hash, affiliate links). */
    fun texdbRenameTexture(dir: String, index: Int, newName: String): String? = if (isAvailable) {
        try {
            nativeTexdbRenameTexture(dir, index, newName)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Adds an affiliate (alias) entry pointing at [targetIndex]. */
    fun texdbAddAlias(dir: String, targetIndex: Int, aliasName: String): String? = if (isAvailable) {
        try {
            nativeTexdbAddAlias(dir, targetIndex, aliasName)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Multi-line DFF inspection report via Rust. Returns "OK\n<report>"/"ERROR ..." or null. */
    fun dffInspect(path: String): String? = if (isAvailable) {
        try {
            nativeDffInspect(path)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Extracts 3D mesh packet from a DFF model for OpenGL rendering. */
    fun dffExtractMesh(path: String): ByteArray? = if (isAvailable) {
        try {
            nativeDffExtractMesh(path)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** IMG structural health report. Returns "OK\n<report>" or null. */
    fun imgVerify(path: String): String? = if (isAvailable) {
        try {
            nativeImgVerify(path)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    // --- IMG archive engine (VER1 + VER2), full Rust since 2026-10-23 ---
    // The Kotlin ImgArchive is only a facade over these calls.

    fun imgIsVer2(path: String): Boolean = if (isAvailable) {
        try {
            nativeImgIsVer2(path)
        } catch (_: Throwable) {
            false
        }
    } else {
        false
    }

    /**
     * Raw archive listing: element 0 is the header
     * ("OK\t<VER1|VER2>\t<count>\t<totalDataSize>\t<dirPath|>"), each following
     * element is "<offsetSectors>\t<sizeSectors>\t<cleanName>".
     * A single "ERROR\t<message>" element signals failure.
     */
    fun imgList(path: String, dir: String?): Array<String>? = if (isAvailable) {
        try {
            nativeImgList(path, dir)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Creates a fresh empty archive. version: 1 = VER1, 2 = VER2. "OK …"/"ERROR …". */
    fun imgCreate(path: String, version: Int, dir: String?): String? = if (isAvailable) {
        try {
            nativeImgCreate(path, version, dir)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    fun imgExtract(path: String, dir: String?, index: Int, dest: String): String? = if (isAvailable) {
        try {
            nativeImgExtract(path, dir, index, dest)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    fun imgAdd(path: String, dir: String?, filePath: String, entryName: String?): String? = if (isAvailable) {
        try {
            nativeImgAdd(path, dir, filePath, entryName)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    fun imgReplace(path: String, dir: String?, index: Int, filePath: String): String? = if (isAvailable) {
        try {
            nativeImgReplace(path, dir, index, filePath)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    fun imgDelete(path: String, dir: String?, indices: IntArray): String? = if (isAvailable) {
        try {
            nativeImgDelete(path, dir, indices)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    fun imgRename(path: String, dir: String?, index: Int, newName: String): String? = if (isAvailable) {
        try {
            nativeImgRename(path, dir, index, newName)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /**
     * Rebuilds the archive and returns the post-rebuild listing in the same
     * wire format as [imgList] (single "ERROR\t<message>" element on failure),
     * so callers refresh their snapshot without a second archive open.
     */
    fun imgRebuild(path: String, dir: String?): Array<String>? = if (isAvailable) {
        try {
            nativeImgRebuild(path, dir)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    // --- TXD engine, texdb listing, audio fixer (full Rust since 2026-10-23) ---

    /**
     * TXD listing: element 0 is "OK\t<rwVersion>\t<count>", each following
     * element is the texture's wire line (see TxdStore). A single
     * "ERROR\t<message>" element signals failure.
     */
    fun txdList(path: String): Array<String>? = if (isAvailable) {
        try {
            nativeTxdList(path)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Decoded level-0 ARGB pixels of one TXD texture. */
    fun txdTexturePixels(path: String, index: Int): IntArray? = if (isAvailable) {
        try {
            nativeTxdTexturePixels(path, index)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** asDxt1Mode: 0 = preserve, 1 = force DXT1, 2 = force raw. maxDimension 0 = no reduce. */
    fun txdReplaceTextures(
        path: String, indices: IntArray, pixelsList: Array<IntArray>,
        widths: IntArray, heights: IntArray, asDxt1Mode: Int,
        genMips: Boolean, mipFilter: Int, maxDimension: Int
    ): String? = if (isAvailable) {
        try {
            nativeTxdReplaceTextures(
                path, indices, pixelsList, widths, heights,
                asDxt1Mode, genMips, mipFilter, maxDimension
            )
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** policy: 0 = JustAdd, 1 = Overwrite. Returns "OK added=A overwritten=B total=T". */
    fun txdAddTextures(
        path: String, names: Array<String>, pixelsList: Array<IntArray>,
        widths: IntArray, heights: IntArray, policy: Int,
        genMips: Boolean, mipFilter: Int, maxDimension: Int
    ): String? = if (isAvailable) {
        try {
            nativeTxdAddTextures(
                path, names, pixelsList, widths, heights,
                policy, genMips, mipFilter, maxDimension
            )
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** DXT1-encodes ARGB pixels. */
    fun encodeDxt1(pixels: IntArray, width: Int, height: Int): ByteArray? = if (isAvailable) {
        try {
            nativeEncodeDxt1(pixels, width, height)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Creates a new empty TXD dictionary file at [path]. Returns "OK created" or "ERROR <msg>". */
    fun txdCreate(path: String, rwVersion: Int = 0): String? = if (isAvailable) {
        try {
            nativeTxdCreate(path, rwVersion)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Deletes textures at [indices] in [path]. Returns "OK deleted=N" or "ERROR <msg>". */
    fun txdDeleteTextures(path: String, indices: IntArray): String? = if (isAvailable) {
        try {
            nativeTxdDeleteTextures(path, indices)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Renames texture at [index] in [path] to [newName]. Returns "OK renamed" or "ERROR <msg>". */
    fun txdRenameTexture(path: String, index: Int, newName: String): String? = if (isAvailable) {
        try {
            nativeTxdRenameTexture(path, index, newName)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Updates properties (maskName, filterFlags) at [index] in [path]. Returns "OK updated" or "ERROR <msg>". */
    fun txdUpdateProperties(path: String, index: Int, maskName: String, filterFlags: Int): String? = if (isAvailable) {
        try {
            nativeTxdUpdateProperties(path, index, maskName, filterFlags)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Imports textures directly from [sourcePath] TXD into [destPath] TXD. Returns "added=A overwritten=B total=T" or "ERROR <msg>". */
    fun txdImportFromTxd(destPath: String, sourcePath: String, policy: Int): String? = if (isAvailable) {
        try {
            nativeTxdImportFromTxd(destPath, sourcePath, policy)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /**
     * Texdb listing: element 0 header, element 1 "DEFAULTS\t…", then one line
     * per texture (see TexdbCache facade). "ERROR\t<message>" signals failure.
     */
    fun texdbList(dir: String): Array<String>? = if (isAvailable) {
        try {
            nativeTexdbList(dir)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Writes one texdb entry's raw .dat span to a file. "OK …"/"ERROR …". */
    fun texdbExtractRaw(dir: String, index: Int, dest: String): String? = if (isAvailable) {
        try {
            nativeTexdbExtractRaw(dir, index, dest)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Regenerates the .idx next to a .osw. "OK <count> entri; …"/"ERROR …". */
    fun audioFixOsw(path: String): String? = if (isAvailable) {
        try {
            nativeAudioFixOsw(path)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** .osw index entries without writing: header + "name\toffset\tsize" lines. */
    fun audioComputeOsw(path: String): Array<String>? = if (isAvailable) {
        try {
            nativeAudioComputeOsw(path)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Whole-game texture audit (missing/unused) over a GTA files/ folder. */
    fun gameAudit(filesDir: String): String? = if (isAvailable) {
        try {
            nativeGameAudit(filesDir)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Renames a texture reference inside a DFF file (backup .dff.bak dibuat sekali). */
    fun dffRenameTexture(path: String, oldName: String, newName: String): String? = if (isAvailable) {
        try {
            nativeDffRenameTexture(path, oldName, newName)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Repacks all textures into a target family: 0 = DXT, 1 = ETC1, 2 = mentah (565/4444). */
    fun texdbRepackCache(dir: String, target: Int): String? = if (isAvailable) {
        try {
            nativeTexdbRepackCache(dir, target)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /**
     * Data-line merge plan (handling.cfg / vehicles.ide / carcols.dat) from
     * the Rust engine (`rust-core/src/datalines.rs`). Wire block:
     * "OK\t<changed>\t<fileLineCount>\t<sectioned 0|1>\t<notesLineCount>\n"
     * followed by that many note lines and then the merged file text
     * verbatim to the end of the string. Null when the engine is missing.
     */
    fun dataLinesPlan(fileText: String, pastedText: String): String? = if (isAvailable) {
        try {
            nativeDataLinesPlan(fileText, pastedText)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Result of an IDE definition scan (see `rust-core/src/ide.rs`). */
    data class IdeScanResult(
        /** Model name (lowercase) -> streaming model id; duplicates keep the first id. */
        val models: Map<String, Int>,
        /** Txd name (lowercase) -> candidate model ids sharing that TXD. */
        val txdToIds: Map<String, List<Int>>,
        /** How many .ide files were read. */
        val filesRead: Int
    )

    /** Scans a folder recursively for .ide files and builds the name -> id index. */
    fun ideScanDir(root: String): IdeScanResult? = if (isAvailable) {
        try {
            parseIdeScanWire(nativeIdeScanDir(root))
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /** Builds the IDE index from an explicit list of .ide files, in order. */
    fun ideScanPaths(paths: List<String>): IdeScanResult? = if (isAvailable) {
        try {
            parseIdeScanWire(nativeIdeScanPaths(paths.toTypedArray()))
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }

    /**
     * Parses the wire lines of `ide::wire_lines`: header
     * "OK\t<modelCount>\t<txdNameCount>\t<filesRead>", then "M\t<name>\t<id>"
     * and "T\t<txdName>\t<id,id,...>" lines. A single "ERROR\t<message>"
     * element (or anything malformed) yields null.
     */
    internal fun parseIdeScanWire(lines: Array<String>?): IdeScanResult? {
        if (lines.isNullOrEmpty()) return null
        val header = lines[0].split('\t')
        if (header.size < 4 || header[0] != "OK") return null
        val models = LinkedHashMap<String, Int>()
        val txdToIds = LinkedHashMap<String, List<Int>>()
        for (i in 1 until lines.size) {
            val parts = lines[i].split('\t')
            if (parts.size < 3) continue
            when (parts[0]) {
                "M" -> parts[2].toIntOrNull()?.let { models[parts[1]] = it }
                "T" -> txdToIds[parts[1]] =
                    parts[2].split(',').mapNotNull { it.toIntOrNull() }
            }
        }
        return IdeScanResult(models, txdToIds, header[3].toIntOrNull() ?: 0)
    }

}
