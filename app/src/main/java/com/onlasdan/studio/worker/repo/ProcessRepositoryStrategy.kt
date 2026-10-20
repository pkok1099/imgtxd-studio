package com.onlasdan.studio.worker.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.DefaultProcessRepository
import com.onlasdan.studio.process.ProcessRepository
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.process.ProcessType
import com.onlasdan.studio.worker.WorkerContracts
import com.onlasdan.studio.worker.workerDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Strategi pemilihan repository proses.
 * Mengarahkan operasi ke [WorkerProcessRepositoryImpl] (proses terisolasi :worker) jika diaktifkan,
 * atau kembali ke [DefaultProcessRepository] (in-process) jika dinonaktifkan di pengaturan.
 */
class ProcessRepositoryStrategy private constructor(
    private val context: Context
) : ProcessRepository {

    companion object {
        private val KEY_RUNTIME_ENABLED = booleanPreferencesKey(WorkerContracts.PREF_KEY_RUNTIME_ENABLED)

        @Volatile
        private var instance: ProcessRepositoryStrategy? = null

        /**
         * Singleton accessor agar mudah diakses oleh ViewModel, Activity, dan komponen UI.
         */
        fun getInstance(context: Context): ProcessRepositoryStrategy {
            return instance ?: synchronized(this) {
                instance ?: ProcessRepositoryStrategy(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workerRepo: WorkerProcessRepositoryImpl = WorkerProcessRepositoryImpl.getInstance(context)
    private val defaultRepo: DefaultProcessRepository = DefaultProcessRepository

    /**
     * Aliran status apakah runtime proses terisolasi sedang aktif.
     */
    val isRuntimeEnabled: StateFlow<Boolean> = context.workerDataStore.data
        .map { prefs ->
            prefs[KEY_RUNTIME_ENABLED] ?: WorkerContracts.DEFAULT_RUNTIME_ENABLED
        }
        .stateIn(scope, SharingStarted.Eagerly, WorkerContracts.DEFAULT_RUNTIME_ENABLED)

    /**
     * Mengatur status aktif runtime proses terisolasi ke DataStore.
     */
    suspend fun setRuntimeEnabled(enabled: Boolean) {
        context.workerDataStore.edit { prefs ->
            prefs[KEY_RUNTIME_ENABLED] = enabled
        }
    }

    /**
     * Mengembalikan repository aktif berdasarkan pengaturan toggle saat ini.
     */
    val activeRepository: ProcessRepository
        get() = if (isRuntimeEnabled.value) workerRepo else defaultRepo

    /**
     * Aliran daftar proses yang menggabungkan state dari repository aktif dan pasif
     * agar pekerjaan yang sedang berjalan tetap terlihat di UI meskipun toggle diganti.
     */
    override val processes: StateFlow<List<AppProcess>> = combine(
        isRuntimeEnabled,
        workerRepo.processes,
        defaultRepo.processes
    ) { enabled, workerList, defaultList ->
        if (enabled) {
            // Mode worker aktif: utamakan worker, sertakan proses default jika masih berjalan
            if (defaultList.any { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED }) {
                (workerList + defaultList).distinctBy { it.id }
            } else {
                workerList
            }
        } else {
            // Mode in-process: utamakan default, sertakan proses worker jika masih menyelesaikan tugas
            if (workerList.any { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED }) {
                (defaultList + workerList).distinctBy { it.id }
            } else {
                defaultList
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val currentProcess: StateFlow<AppProcess?> = processes.map { list ->
        list.find { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun isFileBusy(filePath: String): Boolean {
        return workerRepo.isFileBusy(filePath) || defaultRepo.isFileBusy(filePath)
    }

    override fun getActiveProcessForFile(filePath: String): AppProcess? {
        return activeRepository.getActiveProcessForFile(filePath)
            ?: workerRepo.getActiveProcessForFile(filePath)
            ?: defaultRepo.getActiveProcessForFile(filePath)
    }

    override fun hasRunningProcess(filePath: String?): Boolean {
        return workerRepo.hasRunningProcess(filePath) || defaultRepo.hasRunningProcess(filePath)
    }

    override suspend fun executeProcess(
        type: ProcessType,
        targetFile: String,
        title: String,
        block: suspend (updateProgress: (current: Int, total: Int, message: String?) -> Unit) -> Result<Unit>
    ): Result<Unit> {
        return activeRepository.executeProcess(type, targetFile, title, block)
    }

    override fun cancelProcess(id: String): Boolean {
        val cancelledWorker = workerRepo.cancelProcess(id)
        val cancelledDefault = defaultRepo.cancelProcess(id)
        return cancelledWorker || cancelledDefault
    }
}
