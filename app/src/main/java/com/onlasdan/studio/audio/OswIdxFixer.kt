package com.onlasdan.studio.audio

import com.onlasdan.studio.core.NativeCore
import java.io.File
import java.io.IOException

/**
 * SA Audio Fixer (IMG Tool parity item I13): regenerates the `.idx` index
 * file for a GTA SA Android audio archive (`.osw`).
 *
 * Facade over the Rust engine (`rust-core/src/audio.rs`) since the
 * full-Rust migration (2026-10-23): ZIP central-directory parsing, the
 * offset formula, and .idx serialization all run in Rust. Verified
 * against the real GENRL.osw — the regenerated index is byte-identical
 * to the game's shipped .idx (652 entries).
 */
object OswIdxFixer {

    data class IdxEntry(val name: String, val dataOffset: Int, val compressedSize: Int)

    data class FixResult(val idxFile: File, val entries: List<IdxEntry>)

    /**
     * Reads [oswFile] (a ZIP) and computes the index entries without writing anything.
     */
    @Throws(IOException::class)
    fun computeIndex(oswFile: File): List<IdxEntry> {
        val lines = NativeCore.audioComputeOsw(oswFile.absolutePath)
            ?: throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
        if (lines.isEmpty()) throw IOException("Engine Rust mengembalikan daftar kosong")
        val header = lines[0].split('\t', limit = 2)
        if (header[0] == "ERROR") {
            throw IOException(header.getOrNull(1) ?: ".osw tidak bisa dibaca")
        }
        val entries = ArrayList<IdxEntry>(maxOf(lines.size - 1, 0))
        for (i in 1 until lines.size) {
            val p = lines[i].split('\t')
            if (p.size < 3) continue
            entries.add(
                IdxEntry(
                    name = p[0],
                    dataOffset = p[1].toIntOrNull() ?: 0,
                    compressedSize = p[2].toIntOrNull() ?: 0
                )
            )
        }
        return entries
    }

    /**
     * Computes the index and writes it to `<oswFile>.idx` next to the archive.
     */
    @Throws(IOException::class)
    fun fix(oswFile: File): FixResult {
        val result = NativeCore.audioFixOsw(oswFile.absolutePath)
        when {
            result == null ->
                throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
            result.startsWith("ERROR ") ->
                throw IOException(result.removePrefix("ERROR "))
        }
        return FixResult(File(oswFile.path + ".idx"), computeIndex(oswFile))
    }
}
