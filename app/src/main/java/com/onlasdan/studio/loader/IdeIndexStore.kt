package com.onlasdan.studio.loader

import android.content.Context
import java.io.File

/**
 * Persisted IDE definition index (model name -> streaming id), built by
 * scanning the game's data folder with the Rust IDE parser
 * (`NativeCore.ideScanDir`). Stored as one small TSV file in filesDir so
 * the Modloader sheet can auto-fill Model IDs across app restarts.
 */
data class IdeIndex(
    val models: Map<String, Int>,
    val txdToIds: Map<String, List<Int>>,
    val rootPath: String,
    val scannedAtMillis: Long,
    val ideFileCount: Int
) {
    val modelCount: Int get() = models.size
}

object IdeIndexStore {

    private const val FILE_NAME = "ide_index.tsv"

    private fun storeFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): IdeIndex? = try {
        val file = storeFile(context)
        if (file.exists()) parse(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun save(context: Context, index: IdeIndex) {
        storeFile(context).writeText(serialize(index))
    }

    /**
     * File format: metadata lines `#root\t<path>`, `#scannedAt\t<millis>`,
     * `#files\t<count>`, then `M\t<name>\t<id>` per model and
     * `T\t<txdName>\t<id,id,...>` per txd name.
     */
    fun serialize(index: IdeIndex): String = buildString {
        append("#root\t").append(index.rootPath.replace('\t', ' ')).append('\n')
        append("#scannedAt\t").append(index.scannedAtMillis).append('\n')
        append("#files\t").append(index.ideFileCount).append('\n')
        for ((name, id) in index.models) {
            append("M\t").append(name).append('\t').append(id).append('\n')
        }
        for ((name, ids) in index.txdToIds) {
            append("T\t").append(name).append('\t')
                .append(ids.joinToString(",")).append('\n')
        }
    }

    /** Inverse of [serialize]; null when the text holds no usable index. */
    fun parse(text: String): IdeIndex? {
        var root = ""
        var scannedAt = 0L
        var files = 0
        val models = LinkedHashMap<String, Int>()
        val txdToIds = LinkedHashMap<String, List<Int>>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            when (parts[0]) {
                "#root" -> root = parts.getOrNull(1) ?: ""
                "#scannedAt" -> scannedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "#files" -> files = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "M" -> if (parts.size >= 3) {
                    parts[2].toIntOrNull()?.let { models[parts[1]] = it }
                }
                "T" -> if (parts.size >= 3) {
                    txdToIds[parts[1]] =
                        parts[2].split(',').mapNotNull { it.toIntOrNull() }
                }
            }
        }
        if (models.isEmpty()) return null
        return IdeIndex(models, txdToIds, root, scannedAt, files)
    }
}
