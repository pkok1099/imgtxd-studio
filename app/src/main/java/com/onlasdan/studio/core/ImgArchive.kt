package com.onlasdan.studio.core

import com.onlasdan.studio.util.AppIntegrity
import java.io.File
import java.io.IOException

enum class ImgVersion {
    VER1,
    VER2
}

/**
 * Facade over the Rust IMG engine (`rust-core/src/img.rs`) via [NativeCore].
 *
 * Since 2026-10-23 (user decision: "Kotlin hanya untuk UI"), all .img parsing
 * and writing — VER1 + VER2 open/list/extract/add/replace/delete/rename/
 * rebuild/create — runs in Rust. This class only holds the latest entry
 * snapshot for the UI and forwards every operation to the engine; after each
 * mutation the snapshot is re-listed from Rust, which stays the source of
 * truth. Entry identity is value-based (see [ImgEntry.equals]); operations
 * resolve the snapshot index of the passed entry before calling the engine.
 */
class ImgArchive private constructor(
    val imgFile: File,
    val dirFile: File?,
    val version: ImgVersion,
    private var entriesList: ArrayList<ImgEntry>,
    private var totalDataSize: Long
) {
    companion object {
        const val SECTOR_SIZE = 2048
        const val ENTRY_SIZE = 32
        const val NAME_SIZE = 24

        /**
         * Checks if the specified file starts with the "VER2" magic string.
         */
        fun isVer2(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return NativeCore.imgIsVer2(file.absolutePath)
        }

        /**
         * Opens a GTA .img archive via the Rust engine. Auto-detects VER1 vs
         * VER2; for VER1 the corresponding .dir file is resolved by the engine
         * when not provided.
         */
        @Throws(IOException::class)
        fun open(imgFile: File, dirFile: File? = null): ImgArchive {
            AppIntegrity.verifyOrThrow()
            if (!imgFile.exists()) {
                throw IOException("IMG archive file not found: ${imgFile.absolutePath}")
            }
            val snap = loadSnapshot(imgFile, dirFile)
            return ImgArchive(imgFile, snap.dirFile, snap.version, ArrayList(snap.entries), snap.totalDataSize)
        }

        /**
         * Creates a fresh empty archive.
         */
        @Throws(IOException::class)
        fun createEmpty(imgFile: File, version: ImgVersion, dirFile: File? = null): ImgArchive {
            requireOk(
                NativeCore.imgCreate(
                    imgFile.absolutePath,
                    if (version == ImgVersion.VER1) 1 else 2,
                    dirFile?.absolutePath
                )
            )
            val snap = loadSnapshot(imgFile, dirFile)
            return ImgArchive(imgFile, snap.dirFile, snap.version, ArrayList(snap.entries), snap.totalDataSize)
        }

        private data class Snapshot(
            val version: ImgVersion,
            val dirFile: File?,
            val entries: List<ImgEntry>,
            val totalDataSize: Long
        )

        @Throws(IOException::class)
        private fun loadSnapshot(imgFile: File, dirFile: File?): Snapshot {
            val lines = NativeCore.imgList(imgFile.absolutePath, dirFile?.absolutePath)
                ?: throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
            return snapshotFromLines(lines, dirFile)
        }

        /** Parses the engine's listing wire format into a [Snapshot]. */
        @Throws(IOException::class)
        private fun snapshotFromLines(lines: Array<String>, dirFile: File?): Snapshot {
            if (lines.isEmpty()) throw IOException("Engine Rust mengembalikan daftar kosong")
            val header = lines[0].split('\t', limit = 5)
            if (header[0] == "ERROR") {
                throw IOException(header.getOrNull(1) ?: "Arsip tidak bisa dibuka")
            }
            if (header.size < 5 || header[0] != "OK") {
                throw IOException("Respons engine tidak dikenal: ${lines[0]}")
            }
            val version = if (header[1] == "VER1") ImgVersion.VER1 else ImgVersion.VER2
            val total = header[3].toLongOrNull() ?: 0L
            val resolvedDir = header[4].takeIf { it.isNotEmpty() }?.let { File(it) } ?: dirFile
            val entries = ArrayList<ImgEntry>(maxOf(lines.size - 1, 0))
            for (i in 1 until lines.size) {
                val parts = lines[i].split('\t', limit = 3)
                if (parts.size < 3) continue
                val offset = parts[0].toIntOrNull() ?: continue
                val size = parts[1].toIntOrNull() ?: continue
                entries.add(ImgEntry(offset, size, parts[2]))
            }
            return Snapshot(version, if (version == ImgVersion.VER1) resolvedDir else null, entries, total)
        }

        @Throws(IOException::class)
        private fun requireOk(result: String?) {
            when {
                result == null ->
                    throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
                result.startsWith("ERROR ") -> {
                    val msg = result.removePrefix("ERROR ")
                    // Match the old Kotlin engine's exception type for duplicates.
                    if (msg.startsWith("Entry already exists")) throw IllegalArgumentException(msg)
                    throw IOException(msg)
                }
            }
        }
    }

    /**
     * Immutable snapshot of all current entries.
     */
    val entries: List<ImgEntry>
        @Synchronized get() = ArrayList(entriesList)

    val entryCount: Int
        @Synchronized get() = entriesList.size

    val archiveName: String
        get() = imgFile.name

    /**
     * Total data size across all entries (sector-padded, plus the VER2 header),
     * as reported by the engine at the last listing.
     */
    @Synchronized
    fun calculateTotalDataSize(): Long = totalDataSize

    /** Re-lists the archive from the engine, replacing the snapshot. */
    @Synchronized
    @Throws(IOException::class)
    fun refresh() {
        val snap = loadSnapshot(imgFile, dirFile)
        entriesList = ArrayList(snap.entries)
        totalDataSize = snap.totalDataSize
    }

    private fun indexOfEntry(entry: ImgEntry): Int {
        val idx = entriesList.indexOf(entry)
        if (idx < 0) throw IOException("Entri tidak ditemukan di arsip: ${entry.cleanName}")
        return idx
    }

    /**
     * Extracts an entry to the given destination file.
     */
    @Synchronized
    @Throws(IOException::class)
    fun extract(entry: ImgEntry, destinationFile: File) {
        val idx = indexOfEntry(entry)
        requireOk(
            NativeCore.imgExtract(
                imgFile.absolutePath, dirFile?.absolutePath, idx, destinationFile.absolutePath
            )
        )
    }

    /**
     * Adds a new file into the archive.
     */
    @Synchronized
    @Throws(IOException::class)
    fun add(fileToAdd: File, entryName: String? = null) {
        AppIntegrity.verifyOrThrow()
        requireOk(
            NativeCore.imgAdd(imgFile.absolutePath, dirFile?.absolutePath, fileToAdd.absolutePath, entryName)
        )
        refresh()
    }

    /**
     * Replaces an existing entry with the contents of replacementFile.
     * If the replacement fits into the existing allocated sectors, it is replaced in-place.
     * Otherwise, it is appended to the end of the archive.
     */
    @Synchronized
    @Throws(IOException::class)
    fun replace(entry: ImgEntry, replacementFile: File) {
        AppIntegrity.verifyOrThrow()
        val idx = indexOfEntry(entry)
        requireOk(
            NativeCore.imgReplace(imgFile.absolutePath, dirFile?.absolutePath, idx, replacementFile.absolutePath)
        )
        refresh()
    }

    /**
     * Deletes the specified entry.
     */
    @Synchronized
    @Throws(IOException::class)
    fun delete(entry: ImgEntry) {
        delete(listOf(entry))
    }

    /**
     * Batch deletes entries.
     */
    @Synchronized
    @Throws(IOException::class)
    fun delete(entriesToDelete: List<ImgEntry>) {
        AppIntegrity.verifyOrThrow()
        val indices = entriesToDelete.mapNotNull { e ->
            entriesList.indexOf(e).takeIf { it >= 0 }
        }.distinct()
        if (indices.isEmpty()) return
        requireOk(NativeCore.imgDelete(imgFile.absolutePath, dirFile?.absolutePath, indices.toIntArray()))
        refresh()
    }

    /**
     * Renames an existing entry.
     */
    @Synchronized
    @Throws(IOException::class)
    fun rename(entry: ImgEntry, newName: String) {
        val idx = indexOfEntry(entry)
        requireOk(NativeCore.imgRename(imgFile.absolutePath, dirFile?.absolutePath, idx, newName))
        refresh()
    }

    /**
     * Rebuilds the archive by repacking all active entries sequentially into a contiguous
     * layout without gaps, reclaiming all dead space from deleted or replaced entries.
     * The engine returns the post-rebuild listing with the same call, so the
     * snapshot is replaced from it directly — no second archive open.
     * The Rust engine reports no per-entry progress, so the listener fires at start and end.
     */
    @Synchronized
    @Throws(IOException::class)
    fun rebuild(progressListener: ((current: Int, total: Int) -> Unit)? = null) {
        AppIntegrity.verifyOrThrow()
        progressListener?.invoke(0, entryCount)
        val lines = NativeCore.imgRebuild(imgFile.absolutePath, dirFile?.absolutePath)
            ?: throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
        val snap = snapshotFromLines(lines, dirFile)
        entriesList = ArrayList(snap.entries)
        totalDataSize = snap.totalDataSize
        progressListener?.invoke(entryCount, entryCount)
    }
}
