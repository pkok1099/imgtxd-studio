package com.onlasdan.studio.worker.engine

import com.onlasdan.studio.worker.WorkerContracts
import org.json.JSONObject
import java.io.File

/**
 * Metadata representasi checkpoint untuk proses worker.
 */
data class ProcessCheckpoint(
    val jobId: String,
    val type: String,
    val targetPath: String,
    val totalItems: Int,
    val processedItems: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "RUNNING",
    val extra: Map<String, String> = emptyMap()
) {
    val progressPercent: Float
        get() = if (totalItems > 0) (processedItems.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f) else 0f

    val isFinished: Boolean
        get() = status == "COMPLETED" || status == "CANCELLED" || status == "FAILED"
}

/**
 * Pengelola checkpoint ringan (.checkpoint) untuk pelacakan status operasi berkas,
 * pemulihan (recovery), dan deteksi crash.
 */
object CheckpointManager {

    /**
     * Mendapatkan path berkas checkpoint pendamping: targetPath + WorkerContracts.EXT_CHECKPOINT (.checkpoint).
     */
    fun getCheckpointFile(targetPath: String): File {
        return File(targetPath + WorkerContracts.EXT_CHECKPOINT)
    }

    /**
     * Membuat dan menyimpan checkpoint baru secara atomik ke disk.
     */
    @Synchronized
    fun createCheckpoint(
        jobId: String,
        type: String,
        targetPath: String,
        totalItems: Int,
        processedItems: Int = 0,
        status: String = "RUNNING",
        extra: Map<String, String> = emptyMap()
    ): ProcessCheckpoint {
        val checkpoint = ProcessCheckpoint(
            jobId = jobId,
            type = type,
            targetPath = targetPath,
            totalItems = totalItems,
            processedItems = processedItems,
            timestamp = System.currentTimeMillis(),
            status = status,
            extra = extra
        )
        saveCheckpointToFile(checkpoint)
        return checkpoint
    }

    /**
     * Memperbarui progress atau status pada checkpoint yang sudah ada.
     */
    @Synchronized
    fun updateCheckpoint(
        jobId: String,
        targetPath: String,
        processedItems: Int,
        status: String = "RUNNING",
        totalItems: Int? = null,
        extra: Map<String, String>? = null
    ): ProcessCheckpoint? {
        val existing = getCheckpoint(targetPath) ?: return null
        val updated = existing.copy(
            jobId = jobId,
            processedItems = processedItems,
            status = status,
            totalItems = totalItems ?: existing.totalItems,
            timestamp = System.currentTimeMillis(),
            extra = extra ?: existing.extra
        )
        saveCheckpointToFile(updated)
        return updated
    }

    /**
     * Membaca checkpoint dari disk untuk targetPath tertentu.
     * Mengembalikan null jika berkas tidak ada atau tidak valid.
     */
    @Synchronized
    fun getCheckpoint(targetPath: String): ProcessCheckpoint? {
        val file = getCheckpointFile(targetPath)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val extraMap = mutableMapOf<String, String>()
            if (json.has("extra")) {
                val extraObj = json.optJSONObject("extra")
                if (extraObj != null) {
                    val keys = extraObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        extraMap[key] = extraObj.optString(key, "")
                    }
                }
            }
            ProcessCheckpoint(
                jobId = json.optString("jobId", ""),
                type = json.optString("type", ""),
                targetPath = json.optString("targetPath", targetPath),
                totalItems = json.optInt("totalItems", 0),
                processedItems = json.optInt("processedItems", 0),
                timestamp = json.optLong("timestamp", 0L),
                status = json.optString("status", "RUNNING"),
                extra = extraMap
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Menghapus berkas checkpoint setelah proses berhasil selesai.
     */
    @Synchronized
    fun removeCheckpoint(targetPath: String): Boolean {
        val file = getCheckpointFile(targetPath)
        return try {
            if (file.exists()) file.delete() else true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Memeriksa apakah terdapat checkpoint proses yang belum selesai (crash di tengah jalan).
     */
    fun hasIncompleteCheckpoint(targetPath: String): Boolean {
        val cp = getCheckpoint(targetPath) ?: return false
        return !cp.isFinished
    }

    private fun saveCheckpointToFile(checkpoint: ProcessCheckpoint) {
        val file = getCheckpointFile(checkpoint.targetPath)
        file.parentFile?.mkdirs()
        val tmpFile = File(file.path + ".tmp")
        try {
            val json = JSONObject().apply {
                put("jobId", checkpoint.jobId)
                put("type", checkpoint.type)
                put("targetPath", checkpoint.targetPath)
                put("totalItems", checkpoint.totalItems)
                put("processedItems", checkpoint.processedItems)
                put("timestamp", checkpoint.timestamp)
                put("status", checkpoint.status)
                if (checkpoint.extra.isNotEmpty()) {
                    val extraObj = JSONObject()
                    checkpoint.extra.forEach { (k, v) -> extraObj.put(k, v) }
                    put("extra", extraObj)
                }
            }
            tmpFile.writeText(json.toString(2), Charsets.UTF_8)
            SafeFileWriter.atomicReplace(tmpFile, file)
        } catch (_: Exception) {
            SafeFileWriter.cleanupFile(tmpFile)
        }
    }
}
