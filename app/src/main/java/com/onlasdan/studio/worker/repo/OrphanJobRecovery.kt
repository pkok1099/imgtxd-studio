package com.onlasdan.studio.worker.repo

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.process.ProcessType
import com.onlasdan.studio.worker.WorkerContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Mekanisme pemulihan pekerjaan yatim (Orphan Job Recovery).
 * Berjalan saat aplikasi pertama kali dibuka atau ketika binder service worker mati tak terduga.
 */
object OrphanJobRecovery {
    private const val TAG = "OrphanJobRecovery"
    const val CRASH_MESSAGE = "Proses latar belakang terhenti tak terduga (dihentikan oleh sistem Android)"

    private val KEY_ACTIVE_JOB_ID = stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_JOB_ID)
    private val KEY_LAST_CRASH_INFO = stringPreferencesKey(WorkerContracts.PREF_KEY_LAST_CRASH_INFO)

    /**
     * Memeriksa apakah proses :worker sedang aktif berjalan di sistem Android.
     */
    fun isWorkerProcessRunning(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val workerProcessName = "${context.packageName}:worker"
            val runningProcesses = am.runningAppProcesses ?: return false
            runningProcesses.any { it.processName == workerProcessName }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memeriksa status proses :worker", e)
            false
        }
    }

    /**
     * Mencari sisa file sementara (.tmp, .lock, .checkpoint) pada direktori cache dan berkas aplikasi.
     */
    fun findOrphanFiles(context: Context): List<File> {
        val directories = listOfNotNull(
            context.cacheDir,
            context.filesDir,
            context.externalCacheDir,
            context.getExternalFilesDir(null),
            File(context.filesDir, "worker_temp"),
            File(context.cacheDir, "worker_checkpoints")
        )

        val orphanFiles = mutableListOf<File>()
        for (dir in directories) {
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile) {
                        val name = file.name
                        if (name.endsWith(WorkerContracts.EXT_TMP, ignoreCase = true) ||
                            name.endsWith(WorkerContracts.EXT_LOCK, ignoreCase = true) ||
                            name.endsWith(WorkerContracts.EXT_CHECKPOINT, ignoreCase = true)
                        ) {
                            orphanFiles.add(file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal memindai direktori ${dir.absolutePath}", e)
            }
        }
        return orphanFiles
    }

    /**
     * Membersihkan file sementara yatim (.tmp, .lock, .checkpoint) agar media penyimpanan tidak penuh.
     */
    fun cleanupOrphanFiles(files: List<File>): Int {
        var deletedCount = 0
        for (file in files) {
            try {
                if (file.delete()) {
                    deletedCount++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal menghapus berkas yatim: ${file.absolutePath}", e)
            }
        }
        return deletedCount
    }

    /**
     * Memeriksa dan memulihkan pekerjaan yang terhenti secara yatim (misalnya akibat LMK / crash proses :worker).
     * Jika [force] = true, pembersihan dilakukan langsung tanpa memverifikasi proses :worker masih hidup.
     */
    suspend fun recoverOrphanJobs(context: Context, force: Boolean = false): List<AppProcess> = withContext(Dispatchers.IO) {
        val workerAlive = isWorkerProcessRunning(context)
        if (workerAlive && !force) {
            // Proses worker masih berjalan normal, bukan pekerjaan yatim
            return@withContext emptyList()
        }

        val prefs = try {
            context.workerDataStore.data.first()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membaca DataStore untuk pemeriksaan orphan job", e)
            null
        }

        val activeJobId = prefs?.get(KEY_ACTIVE_JOB_ID)
        val orphanFiles = findOrphanFiles(context)

        if (activeJobId.isNullOrBlank() && orphanFiles.isEmpty()) {
            return@withContext emptyList()
        }

        Log.w(TAG, "Ditemukan pekerjaan yatim! activeJobId=$activeJobId, orphanFiles=${orphanFiles.size}. Memulai pembersihan...")

        // 1. Bersihkan semua file .tmp dan .lock yatim yang tertinggal
        val deletedCount = cleanupOrphanFiles(orphanFiles)
        Log.i(TAG, "Berhasil membersihkan $deletedCount berkas sementara yatim.")

        // 2. Hapus penanda aktif di DataStore & catat pesan kegagalan yang jelas
        try {
            context.workerDataStore.edit { preferences ->
                preferences.remove(KEY_ACTIVE_JOB_ID)
                preferences[KEY_LAST_CRASH_INFO] = "$CRASH_MESSAGE [Job: ${activeJobId ?: "unknown"}, Time: ${System.currentTimeMillis()}]"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memperbarui DataStore saat pemulihan pekerjaan", e)
        }

        // 3. Kembalikan representasi AppProcess gagal yang informatif untuk UI
        val jobId = if (!activeJobId.isNullOrBlank()) activeJobId else "orphan_${UUID.randomUUID()}"
        val failedProcess = AppProcess(
            id = jobId,
            type = ProcessType.REBUILD,
            targetFile = "Operasi Sebelumnya",
            title = "Pemulihan Pekerjaan Terhenti",
            progress = 0f,
            current = 0,
            total = 0,
            status = ProcessStatus.FAILED,
            message = CRASH_MESSAGE,
            errorMessage = CRASH_MESSAGE
        )

        listOf(failedProcess)
    }
}

/**
 * Top-level convenience function sesuai spesifikasi tugas.
 */
suspend fun recoverOrphanJobs(context: Context): List<AppProcess> {
    return OrphanJobRecovery.recoverOrphanJobs(context)
}
