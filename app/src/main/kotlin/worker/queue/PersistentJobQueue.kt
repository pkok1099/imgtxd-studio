package com.onlasdan.studio.worker.queue

import android.content.Context
import android.util.Log
import com.onlasdan.studio.worker.model.JobProgress
import com.onlasdan.studio.worker.model.JobRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Antrean job persisten di disk berbasis file JSON atomic (.tmp -> fsync -> rename).
 * Menyimpan seluruh daftar job dan status per-item (PENDING, PROCESSING, DONE, ERROR).
 *
 * Sesuai aturan:
 * - Menulis secara atomic dengan fsync agar terhindar dari file korup jika terjadi power loss / kill mendadak.
 * - Saat resume, item yang berstatus "PROCESSING" dianggap interrupted/gagal dan dicek retry counter-nya (maks 1 retry).
 */
class PersistentJobQueue private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PersistentJobQueue"
        private const val QUEUE_FILENAME = "persistent_worker_queue.json"
        private const val MAX_ITEM_RETRY = 1

        @Volatile
        private var instance: PersistentJobQueue? = null

        fun getInstance(context: Context): PersistentJobQueue {
            return instance ?: synchronized(this) {
                instance ?: PersistentJobQueue(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val queueFile: File
        get() = File(context.filesDir, QUEUE_FILENAME)

    private val lock = Any()

    data class PersistentItem(
        val index: Int,
        val name: String,
        val sourcePath: String?,
        var status: String = "PENDING", // PENDING, PROCESSING, DONE, ERROR
        var retryCount: Int = 0,
        var errorMessage: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("index", index)
            put("name", name)
            put("sourcePath", sourcePath)
            put("status", status)
            put("retryCount", retryCount)
            put("errorMessage", errorMessage)
        }

        companion object {
            fun fromJson(json: JSONObject): PersistentItem = PersistentItem(
                index = json.getInt("index"),
                name = json.getString("name"),
                sourcePath = if (json.isNull("sourcePath")) null else json.optString("sourcePath"),
                status = json.optString("status", "PENDING"),
                retryCount = json.optInt("retryCount", 0),
                errorMessage = if (json.isNull("errorMessage")) null else json.optString("errorMessage")
            )
        }
    }

    data class PersistentJob(
        val request: JobRequest,
        var status: String = "QUEUED", // QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
        val items: MutableList<PersistentItem> = mutableListOf(),
        var currentItemIndex: Int = 0,
        var totalItems: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request", JSONObject(request.toJson()))
            put("status", status)
            put("currentItemIndex", currentItemIndex)
            put("totalItems", totalItems)
            val itemsArr = JSONArray()
            items.forEach { itemsArr.put(it.toJson()) }
            put("items", itemsArr)
        }

        companion object {
            fun fromJson(json: JSONObject): PersistentJob? {
                val reqJson = json.optJSONObject("request") ?: return null
                val req = JobRequest.fromJson(reqJson.toString()) ?: return null
                val items = mutableListOf<PersistentItem>()
                val itemsArr = json.optJSONArray("items")
                if (itemsArr != null) {
                    for (i in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.optJSONObject(i) ?: continue
                        items.add(PersistentItem.fromJson(itemObj))
                    }
                }
                return PersistentJob(
                    request = req,
                    status = json.optString("status", "QUEUED"),
                    items = items,
                    currentItemIndex = json.optInt("currentItemIndex", 0),
                    totalItems = json.optInt("totalItems", items.size)
                )
            }
        }
    }

    /**
     * Membaca seluruh antrean dari disk. Jika ditemukan item berstatus "PROCESSING"
     * (karena crash / kill sebelum selesai), transisikan sesuai aturan retry:
     * - Jika retryCount < MAX_ITEM_RETRY: kembali ke PENDING dengan retryCount + 1.
     * - Jika sudah >= MAX_ITEM_RETRY: ubah menjadi ERROR ("Interrupted during execution").
     */
    fun loadQueue(): MutableList<PersistentJob> = synchronized(lock) {
        val jobs = mutableListOf<PersistentJob>()
        if (!queueFile.exists()) return jobs

        try {
            val content = queueFile.readText()
            if (content.isBlank()) return jobs
            val root = JSONObject(content)
            val jobsArr = root.optJSONArray("jobs") ?: return jobs

            var needsSave = false
            for (i in 0 until jobsArr.length()) {
                val jobObj = jobsArr.optJSONObject(i) ?: continue
                val job = PersistentJob.fromJson(jobObj) ?: continue

                // Pulihkan status item yang terhenti saat PROCESSING
                job.items.forEach { item ->
                    if (item.status == "PROCESSING") {
                        if (item.retryCount < MAX_ITEM_RETRY) {
                            Log.w(TAG, "Item ${item.name} in job ${job.request.id} was PROCESSING. Retrying (${item.retryCount + 1}/$MAX_ITEM_RETRY)")
                            item.status = "PENDING"
                            item.retryCount += 1
                        } else {
                            Log.e(TAG, "Item ${item.name} in job ${job.request.id} exceeded retry limit. Marking ERROR")
                            item.status = "ERROR"
                            item.errorMessage = "Interrupted during execution (process terminated)"
                        }
                        needsSave = true
                    }
                }

                if (job.status == "RUNNING") {
                    val allDone = job.items.isNotEmpty() && job.items.all { it.status == "DONE" || it.status == "ERROR" }
                    job.status = if (allDone) "COMPLETED" else "QUEUED"
                    needsSave = true
                }

                jobs.add(job)
            }

            if (needsSave) {
                saveQueueInternal(jobs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memuat persistent queue dari disk", e)
        }
        return jobs
    }

    /**
     * Menyimpan seluruh daftar antrean ke disk secara ATOMIC (.tmp -> fsync -> rename).
     */
    fun saveQueue(jobs: List<PersistentJob>) = synchronized(lock) {
        saveQueueInternal(jobs)
    }

    private fun saveQueueInternal(jobs: List<PersistentJob>) {
        val tmpFile = File(context.filesDir, "$QUEUE_FILENAME.tmp")
        try {
            val root = JSONObject()
            val jobsArr = JSONArray()
            jobs.forEach { jobsArr.put(it.toJson()) }
            root.put("jobs", jobsArr)
            root.put("updatedAt", System.currentTimeMillis())

            val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmpFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync() // Memaksa OS melakukan flush ke storage fisik
            }

            if (tmpFile.renameTo(queueFile)) {
                // Berhasil atomic rename
            } else {
                // Fallback jika atomic rename gagal
                if (queueFile.delete() && tmpFile.renameTo(queueFile)) {
                    // Sukses setelah delete
                } else {
                    Log.e(TAG, "Gagal me-rename tmpFile ke queueFile")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyimpan persistent queue ke disk", e)
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    /**
     * Menambahkan JobRequest baru ke disk antrean persisten.
     */
    fun enqueue(request: JobRequest): PersistentJob = synchronized(lock) {
        val queue = loadQueue()

        // Ekstrak item dari entryNames / sourceFilePaths
        val items = mutableListOf<PersistentItem>()
        val total = maxOf(request.entryNames.size, request.sourceFilePaths.size)
        for (i in 0 until total) {
            val name = request.entryNames.getOrNull(i) ?: "item_$i"
            val src = request.sourceFilePaths.getOrNull(i)
            items.add(PersistentItem(index = i, name = name, sourcePath = src))
        }

        val persistentJob = PersistentJob(
            request = request,
            status = "QUEUED",
            items = items,
            currentItemIndex = 0,
            totalItems = items.size
        )

        queue.add(persistentJob)
        saveQueueInternal(queue)
        return persistentJob
    }

    /**
     * Mengambil snapshot progress untuk seluruh job yang aktif.
     */
    fun getProgressSnapshots(): List<JobProgress> = synchronized(lock) {
        val queue = loadQueue()
        return queue.map { job ->
            val doneCount = job.items.count { it.status == "DONE" || it.status == "ERROR" }
            val percent = if (job.totalItems > 0) doneCount.toFloat() / job.totalItems.toFloat() else 0f
            JobProgress(
                id = job.request.id,
                percent = percent,
                currentItem = job.currentItemIndex,
                totalItems = job.totalItems,
                status = job.status,
                currentItemName = job.items.getOrNull(job.currentItemIndex)?.name,
                message = "${job.status}: $doneCount/${job.totalItems} items"
            )
        }
    }

    /**
     * Hapus pekerjaan yang sudah selesai (COMPLETED / CANCELLED) dari antrean disk.
     */
    fun removeJob(jobId: String) = synchronized(lock) {
        val queue = loadQueue()
        if (queue.removeAll { it.request.id == jobId }) {
            saveQueueInternal(queue)
        }
    }
}
