package com.onlasdan.studio.texdb

import com.onlasdan.studio.core.NativeCore
import java.io.File
import java.io.IOException

/**
 * UI model of one texture in a GTA mobile texdb cache.
 *
 * The values come from the Rust engine (`texdb::describe_cache`); see
 * [TexdbCache] — this class no longer parses anything itself.
 */
data class TexdbTexture(
    val name: String,
    val width: Int,
    val height: Int,
    val offset: Long,
    val size: Long,
    val hash: Int,
    val hasMipmaps: Boolean,
    val pngCrc: String?,
    val imgCrc: String?,
    val flags: Map<String, String>,
    val category: String = "Default"
)

/**
 * Facade over the Rust texdb engine for read-only cache browsing.
 *
 * Listing (manifest, .toc validation, affiliate inheritance, categories)
 * runs in `rust-core/src/texdb.rs`; this class only maps the wire lines
 * to [TexdbTexture] models and forwards raw extraction. Full-Rust
 * migration, 2026-10-23 — behavior verified line-for-line against the
 * old Kotlin parser on the real `txd` cache (593 textures).
 */
class TexdbCache private constructor(
    val name: String,
    val directory: File,
    val variant: String,
    val variants: List<String>,
    val categoryDefaults: Map<String, String>,
    val textures: List<TexdbTexture>,
    val datFile: File
) {
    companion object {
        /**
         * Loads the cache in [directory] via the Rust engine, which picks
         * the first variant whose .toc validates. Throws IOException with
         * the engine's message when nothing validates.
         */
        @Throws(IOException::class)
        fun load(directory: File): TexdbCache {
            val lines = NativeCore.texdbList(directory.absolutePath)
                ?: throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
            if (lines.isEmpty()) throw IOException("Engine Rust mengembalikan daftar kosong")
            val header = lines[0].split('\t', limit = 5)
            if (header[0] == "ERROR") {
                throw IOException(header.getOrNull(1) ?: "Cache tidak bisa dibuka")
            }
            if (header.size < 5 || header[0] != "OK") {
                throw IOException("Respons engine tidak dikenal: ${lines[0]}")
            }
            val cacheName = header[1]
            val variant = header[2]
            val variants = header[3].split(',').filter { it.isNotEmpty() }
            val datFile = File(directory, header[4])

            var defaults: Map<String, String> = emptyMap()
            var firstEntry = 1
            if (lines.size > 1 && lines[1].startsWith("DEFAULTS\t")) {
                firstEntry = 2
                val body = lines[1].removePrefix("DEFAULTS\t")
                if (body.isNotEmpty()) {
                    defaults = body.split(';').mapNotNull {
                        val kv = it.split('=', limit = 2)
                        if (kv.size == 2) kv[0] to kv[1] else null
                    }.toMap()
                }
            }

            val textures = ArrayList<TexdbTexture>(maxOf(lines.size - firstEntry, 0))
            for (i in firstEntry until lines.size) {
                // name, w, h, offset, size, hash, mips, png, img, category, flags
                val p = lines[i].split('\t', limit = 11)
                if (p.size < 11) continue
                val flags = if (p[10].isEmpty()) {
                    emptyMap()
                } else {
                    p[10].split(';').mapNotNull {
                        val kv = it.split('=', limit = 2)
                        if (kv.size == 2) kv[0] to kv[1] else null
                    }.toMap()
                }
                textures.add(
                    TexdbTexture(
                        name = p[0],
                        width = p[1].toIntOrNull() ?: 0,
                        height = p[2].toIntOrNull() ?: 0,
                        offset = p[3].toLongOrNull() ?: 0L,
                        size = p[4].toLongOrNull() ?: 0L,
                        hash = p[5].toIntOrNull() ?: 0,
                        hasMipmaps = p[6] == "1",
                        pngCrc = p[7].takeIf { it.isNotEmpty() },
                        imgCrc = p[8].takeIf { it.isNotEmpty() },
                        flags = flags,
                        category = p[9].ifEmpty { "Default" }
                    )
                )
            }
            return TexdbCache(cacheName, directory, variant, variants, defaults, textures, datFile)
        }
    }

    /** Copies the raw `.dat` entry span of [texture] into [destination] (engine-side). */
    @Throws(IOException::class)
    fun extractEntry(texture: TexdbTexture, destination: File) {
        val idx = textures.indexOf(texture)
        if (idx < 0) throw IOException("Tekstur tidak ditemukan di cache: ${texture.name}")
        val result = NativeCore.texdbExtractRaw(directory.absolutePath, idx, destination.absolutePath)
        when {
            result == null ->
                throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
            result.startsWith("ERROR ") ->
                throw IOException(result.removePrefix("ERROR "))
        }
    }
}
