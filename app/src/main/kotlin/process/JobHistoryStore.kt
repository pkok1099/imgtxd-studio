package com.onlasdan.studio.process

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One finished operation, shown on the Operations History screen
 * (TXD Tool parity: Operations History + results + delete all history).
 */
data class OperationHistoryRecord(
    val id: String,
    val typeLabel: String,
    val title: String,
    val targetFile: String,
    val status: String, // COMPLETED, FAILED, CANCELLED
    val message: String?,
    val timestamp: Long,
    val durationMs: Long
)

/**
 * Append-only persistent store for finished operations, kept as a small JSON
 * array in internal storage. Records are written by ProcessRepositoryStrategy,
 * the single funnel every background operation passes through.
 */
object JobHistoryStore {

    private const val FILE_NAME = "operations_history.json"
    private const val MAX_RECORDS = com.onlasdan.studio.Constants.Paging.MAX_HISTORY_RECORDS

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun historyFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    @Synchronized
    fun record(newRecord: OperationHistoryRecord) {
        val file = historyFile() ?: return
        try {
            val records = load().toMutableList()
            records.add(0, newRecord)
            val trimmed = records.take(MAX_RECORDS)
            val array = JSONArray()
            for (record in trimmed) {
                array.put(JSONObject().apply {
                    put("id", record.id)
                    put("typeLabel", record.typeLabel)
                    put("title", record.title)
                    put("targetFile", record.targetFile)
                    put("status", record.status)
                    put("message", record.message ?: JSONObject.NULL)
                    put("timestamp", record.timestamp)
                    put("durationMs", record.durationMs)
                })
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        } catch (ignored: Throwable) {
            // History must never break the operation it describes
        }
    }

    @Synchronized
    fun load(): List<OperationHistoryRecord> {
        val file = historyFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val result = ArrayList<OperationHistoryRecord>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result.add(
                    OperationHistoryRecord(
                        id = obj.optString("id"),
                        typeLabel = obj.optString("typeLabel"),
                        title = obj.optString("title"),
                        targetFile = obj.optString("targetFile"),
                        status = obj.optString("status"),
                        message = if (obj.isNull("message")) null else obj.optString("message"),
                        timestamp = obj.optLong("timestamp"),
                        durationMs = obj.optLong("durationMs")
                    )
                )
            }
            result
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        try {
            historyFile()?.delete()
        } catch (ignored: Throwable) {
        }
    }
}
