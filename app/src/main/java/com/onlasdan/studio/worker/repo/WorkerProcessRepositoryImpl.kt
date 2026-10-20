package com.onlasdan.studio.worker.repo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.ProcessRepository
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.process.ProcessType
import com.onlasdan.studio.worker.IWorkerCallback
import com.onlasdan.studio.worker.IWorkerService
import com.onlasdan.studio.worker.model.JobProgress
import com.onlasdan.studio.worker.model.JobRequest
import com.onlasdan.studio.worker.model.JobResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementasi ProcessRepository berbasis proses terisolasi (:worker) melalui IPC AIDL.
 * Mendukung auto-reconnect, DeathRecipient, pelaporan progress realtime, dan pemulihan pekerjaan yatim.
 */
class WorkerProcessRepositoryImpl private constructor(
    private val context: Context
) : ProcessRepository {

    companion object {
        private const val TAG = "WorkerProcessRepo"
        private const val WORKER_SERVICE_CLASS = "com.onlasdan.studio.worker.WorkerService"

        @Volatile
        private var instance: WorkerProcessRepositoryImpl? = null

        fun getInstance(context: Context): WorkerProcessRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: WorkerProcessRepositoryImpl(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _processes = MutableStateFlow<List<AppProcess>>(emptyList())
    override val processes: StateFlow<List<AppProcess>> = _processes.asStateFlow()

    override val currentProcess: StateFlow<AppProcess?> = _processes.map { list ->
        list.find { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val fileLocks = Mutex()
    private val connectionMutex = Mutex()

    @Volatile
    private var service: IWorkerService? = null
    @Volatile
    private var serviceBinder: IBinder? = null
    @Volatile
    private var isBound = false

    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<JobResult>>()
    private val processMetadata = ConcurrentHashMap<String, ProcessMetadata>()
    private val fallbackJobs = ConcurrentHashMap<String, Job>()
    private var deathRecoveryJob: Job? = null

    private data class ProcessMetadata(
        val type: ProcessType,
        val targetFile: String,
        val title: String
    )

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Koneksi ke WorkerService terputus tak terduga (DeathRecipient dipicu)")
        handleServiceDied()
    }

    private val workerCallback = object : IWorkerCallback.Stub() {
        override fun onProgress(progress: JobProgress) {
            handleJobProgress(progress)
        }

        override fun onFinished(result: JobResult) {
            handleJobFinished(result)
        }

        override fun onQueueChanged(queue: List<JobProgress>) {
            handleQueueChanged(queue)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "WorkerService terhubung: $name")
            if (binder == null) return

            val svc = IWorkerService.Stub.asInterface(binder)
            service = svc
            serviceBinder = binder
            isBound = true

            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                Log.e(TAG, "Gagal mendaftarkan DeathRecipient pada service binder", e)
            }

            try {
                svc.registerCallback(workerCallback)
            } catch (e: RemoteException) {
                Log.e(TAG, "Gagal mendaftarkan IWorkerCallback ke WorkerService", e)
            }

            // Sinkronkan antrean aktif dari service
            scope.launch {
                try {
                    val activeJobs = svc.activeJobs
                    if (!activeJobs.isNullOrEmpty()) {
                        handleQueueChanged(activeJobs)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal mengambil daftar pekerjaan aktif saat tersambung", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "WorkerService terputus: $name")
            handleServiceDied()
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "WorkerService binding died: $name")
            handleServiceDied()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "WorkerService mengembalikan null binding")
            isBound = false
            service = null
        }
    }

    init {
        // 1. Pulihkan pekerjaan yatim yang tersisa dari sesi sebelumnya
        scope.launch {
            try {
                val orphanProcesses = OrphanJobRecovery.recoverOrphanJobs(context)
                if (orphanProcesses.isNotEmpty()) {
                    _processes.update { it + orphanProcesses }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menjalankan pemulihan orphan jobs pada inisialisasi", e)
            }

            // 2. Lakukan inisiasi koneksi ke WorkerService di latar belakang
            ensureConnected()
        }
    }

    /**
     * Memastikan koneksi IPC ke WorkerService telah terhubung.
     */
    suspend fun ensureConnected(): Boolean = connectionMutex.withLock {
        if (isBound && service != null && serviceBinder?.isBinderAlive == true) {
            return@withLock true
        }

        try {
            val intent = Intent().apply {
                component = ComponentName(context.packageName, WORKER_SERVICE_CLASS)
            }
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound = bound

            if (bound) {
                // Tunggu hingga 3 detik sampai binder terpasang (untuk perangkat dengan load tinggi)
                for (i in 0 until 60) {
                    if (service != null && serviceBinder?.isBinderAlive == true) {
                        return@withLock true
                    }
                    delay(50)
                }
            }
            return@withLock (service != null)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal melakukan bind ke WorkerService", e)
            return@withLock false
        }
    }

    private fun handleServiceDied() {
        synchronized(this) {
            if (deathRecoveryJob?.isActive == true) return
            deathRecoveryJob = scope.launch {
                connectionMutex.withLock {
                    try {
                        serviceBinder?.unlinkToDeath(deathRecipient, 0)
                    } catch (_: Exception) {}
                    try {
                        context.unbindService(serviceConnection)
                    } catch (_: Exception) {}
                    service = null
                    serviceBinder = null
                    isBound = false
                }

                // 1. Tandai semua proses yang sedang menunggu sebagai gagal
                val crashMessage = OrphanJobRecovery.CRASH_MESSAGE
                pendingResults.forEach { (jobId, deferred) ->
                    if (deferred.isActive) {
                        deferred.complete(JobResult(id = jobId, success = false, message = crashMessage))
                    }
                }
                pendingResults.clear()

                // 2. Perbarui status proses aktif di StateFlow menjadi FAILED
                _processes.update { list ->
                    list.map { p ->
                        if (p.status == ProcessStatus.RUNNING || p.status == ProcessStatus.QUEUED) {
                            p.copy(
                                status = ProcessStatus.FAILED,
                                message = crashMessage,
                                errorMessage = crashMessage
                            )
                        } else p
                    }
                }

                // 3. Jalankan pembersihan file sementara dan reset penanda aktif di DataStore
                try {
                    OrphanJobRecovery.recoverOrphanJobs(context, force = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal menjalankan OrphanJobRecovery setelah service crash", e)
                }

                // 4. Otomatis coba re-bind setelah jeda
                delay(1000)
                try {
                    Log.i(TAG, "Mencoba melakukan re-bind ke WorkerService...")
                    ensureConnected()
                } catch (e: Exception) {
                    Log.w(TAG, "Re-bind gagal, akan dicoba kembali saat ada permintaan berikutnya", e)
                }
            }
        }
    }

    private fun handleJobProgress(progress: JobProgress) {
        val meta = processMetadata[progress.id]
        val mappedStatus = mapToProcessStatus(progress.status)

        _processes.update { list ->
            val existing = list.find { it.id == progress.id }
            if (existing != null) {
                list.map { p ->
                    if (p.id == progress.id) {
                        p.copy(
                            progress = progress.percent,
                            current = progress.currentItem,
                            total = progress.totalItems,
                            status = mappedStatus,
                            message = progress.message ?: p.message
                        )
                    } else p
                }
            } else {
                val newProcess = AppProcess(
                    id = progress.id,
                    type = meta?.type ?: parseProcessType(progress.currentItemName),
                    targetFile = meta?.targetFile ?: "Arsip",
                    title = meta?.title ?: progress.currentItemName ?: "Operasi Latar Belakang",
                    progress = progress.percent,
                    current = progress.currentItem,
                    total = progress.totalItems,
                    status = mappedStatus,
                    message = progress.message
                )
                list + newProcess
            }
        }
    }

    private fun handleJobFinished(result: JobResult) {
        // Lengkapi deferred untuk pemanggil yang menunggu (executeProcess)
        pendingResults.remove(result.id)?.complete(result)

        _processes.update { list ->
            list.map { p ->
                if (p.id == result.id) {
                    if (result.success) {
                        p.copy(
                            status = ProcessStatus.COMPLETED,
                            progress = 1f,
                            message = result.message ?: "Selesai"
                        )
                    } else {
                        val errMsg = result.message ?: "Gagal memproses berkas"
                        p.copy(
                            status = ProcessStatus.FAILED,
                            errorMessage = errMsg,
                            message = errMsg
                        )
                    }
                } else p
            }
        }
    }

    private fun handleQueueChanged(queue: List<JobProgress>) {
        _processes.update { list ->
            val updatedMap = list.associateBy { it.id }.toMutableMap()
            for (item in queue) {
                val existing = updatedMap[item.id]
                val meta = processMetadata[item.id]
                val status = mapToProcessStatus(item.status)
                if (existing != null) {
                    updatedMap[item.id] = existing.copy(
                        progress = item.percent,
                        current = item.currentItem,
                        total = item.totalItems,
                        status = status,
                        message = item.message ?: existing.message
                    )
                } else {
                    updatedMap[item.id] = AppProcess(
                        id = item.id,
                        type = meta?.type ?: parseProcessType(item.currentItemName),
                        targetFile = meta?.targetFile ?: "Arsip",
                        title = meta?.title ?: item.currentItemName ?: "Operasi Latar Belakang",
                        progress = item.percent,
                        current = item.currentItem,
                        total = item.totalItems,
                        status = status,
                        message = item.message
                    )
                }
            }
            updatedMap.values.toList()
        }
    }

    private fun mapToProcessStatus(status: String): ProcessStatus {
        return when (status.uppercase()) {
            "QUEUED" -> ProcessStatus.QUEUED
            "RUNNING" -> ProcessStatus.RUNNING
            "COMPLETED" -> ProcessStatus.COMPLETED
            "FAILED" -> ProcessStatus.FAILED
            "CANCELLED" -> ProcessStatus.CANCELLED
            else -> ProcessStatus.RUNNING
        }
    }

    private fun parseProcessType(typeStr: String?): ProcessType {
        if (typeStr == null) return ProcessType.REBUILD
        return ProcessType.entries.find { it.name.equals(typeStr, ignoreCase = true) }
            ?: ProcessType.REBUILD
    }

    override fun isFileBusy(filePath: String): Boolean {
        return _processes.value.any {
            it.targetFile.equals(filePath, ignoreCase = true) &&
                    (it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED)
        }
    }

    override fun getActiveProcessForFile(filePath: String): AppProcess? {
        return _processes.value.find {
            it.targetFile.equals(filePath, ignoreCase = true) &&
                    (it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED)
        }
    }

    override fun hasRunningProcess(filePath: String?): Boolean {
        return if (filePath.isNullOrBlank()) {
            _processes.value.any { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED }
        } else {
            isFileBusy(filePath)
        }
    }

    override suspend fun executeProcess(
        type: ProcessType,
        targetFile: String,
        title: String,
        block: suspend (updateProgress: (current: Int, total: Int, message: String?) -> Unit) -> Result<Unit>
    ): Result<Unit> {
        val busyProcess = fileLocks.withLock {
            getActiveProcessForFile(targetFile)
        }

        if (busyProcess != null) {
            return Result.failure(
                IllegalStateException(
                    "Berkas '${targetFile.substringAfterLast('/')}' sedang diproses (${busyProcess.title}). Harap tunggu proses selesai terlebih dahulu."
                )
            )
        }

        val jobId = UUID.randomUUID().toString()
        processMetadata[jobId] = ProcessMetadata(type, targetFile, title)

        val connected = ensureConnected()
        val svc = service

        if (!connected || svc == null) {
            Log.w(TAG, "WorkerService tidak tersedia, menjalankan fallback in-process")
            return executeInProcessFallback(jobId, type, targetFile, title, block)
        }

        val initialProcess = AppProcess(
            id = jobId,
            type = type,
            targetFile = targetFile,
            title = title,
            progress = 0f,
            current = 0,
            total = 0,
            status = ProcessStatus.QUEUED,
            message = "Menunggu antrean..."
        )
        _processes.update { it + initialProcess }

        val deferred = CompletableDeferred<JobResult>()
        pendingResults[jobId] = deferred

        val request = JobRequest(
            id = jobId,
            type = type.name,
            targetPath = targetFile,
            isShizuku = false,
            destinationPath = null,
            entryNames = emptyList(),
            sourceFilePaths = emptyList(),
            parameters = mapOf("title" to title)
        )

        return try {
            svc.submitJob(request)
            val result = deferred.await()
            if (result.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.message ?: "Gagal memproses pekerjaan"))
            }
        } catch (e: CancellationException) {
            cancelProcess(jobId)
            Result.failure(e)
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException saat submitJob ke WorkerService, fallback in-process", e)
            executeInProcessFallback(jobId, type, targetFile, title, block)
        } catch (e: Exception) {
            Log.e(TAG, "Kesalahan saat eksekusi proses via WorkerService", e)
            Result.failure(e)
        } finally {
            pendingResults.remove(jobId)
        }
    }

    /**
     * Mengajukan JobRequest lengkap secara langsung ke WorkerService.
     */
    suspend fun submitJobRequest(request: JobRequest, title: String? = null): Result<JobResult> {
        val busyProcess = fileLocks.withLock {
            getActiveProcessForFile(request.targetPath)
        }

        if (busyProcess != null) {
            return Result.failure(
                IllegalStateException(
                    "Berkas '${request.targetPath.substringAfterLast('/')}' sedang diproses (${busyProcess.title})."
                )
            )
        }

        val type = parseProcessType(request.type)
        val jobTitle = title ?: request.parameters["title"] ?: "${type.label}: ${request.targetPath.substringAfterLast('/')}"
        processMetadata[request.id] = ProcessMetadata(type, request.targetPath, jobTitle)

        val connected = ensureConnected()
        val svc = service
        if (!connected || svc == null) {
            return Result.failure(IllegalStateException("WorkerService tidak dapat dihubungi"))
        }

        val initialProcess = AppProcess(
            id = request.id,
            type = type,
            targetFile = request.targetPath,
            title = jobTitle,
            progress = 0f,
            current = 0,
            total = 0,
            status = ProcessStatus.QUEUED,
            message = "Menunggu antrean..."
        )
        _processes.update { it + initialProcess }

        val deferred = CompletableDeferred<JobResult>()
        pendingResults[request.id] = deferred

        return try {
            svc.submitJob(request)
            val result = deferred.await()
            Result.success(result)
        } catch (e: CancellationException) {
            cancelProcess(request.id)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingResults.remove(request.id)
        }
    }

    private suspend fun executeInProcessFallback(
        jobId: String,
        type: ProcessType,
        targetFile: String,
        title: String,
        block: suspend (updateProgress: (current: Int, total: Int, message: String?) -> Unit) -> Result<Unit>
    ): Result<Unit> {
        val existing = _processes.value.find { it.id == jobId }
        if (existing == null) {
            val initialProcess = AppProcess(
                id = jobId,
                type = type,
                targetFile = targetFile,
                title = title,
                progress = 0f,
                current = 0,
                total = 0,
                status = ProcessStatus.RUNNING,
                message = "Memulai proses (in-process)..."
            )
            _processes.update { it + initialProcess }
        } else {
            _processes.update { list ->
                list.map {
                    if (it.id == jobId) it.copy(status = ProcessStatus.RUNNING, message = "Memulai proses (in-process)...") else it
                }
            }
        }

        val job = scope.launch {
            try {
                val result = block { current, total, msg ->
                    _processes.update { list ->
                        list.map { p ->
                            if (p.id == jobId) {
                                val prog = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                                p.copy(
                                    current = current,
                                    total = total,
                                    progress = prog,
                                    message = msg ?: p.message
                                )
                            } else p
                        }
                    }
                }

                if (result.isSuccess) {
                    _processes.update { list ->
                        list.map { p ->
                            if (p.id == jobId) {
                                p.copy(status = ProcessStatus.COMPLETED, progress = 1f, message = "Selesai")
                            } else p
                        }
                    }
                } else {
                    val err = result.exceptionOrNull()?.localizedMessage ?: "Terjadi kesalahan"
                    _processes.update { list ->
                        list.map { p ->
                            if (p.id == jobId) {
                                p.copy(status = ProcessStatus.FAILED, errorMessage = err, message = err)
                            } else p
                        }
                    }
                }
            } catch (e: CancellationException) {
                _processes.update { list ->
                    list.map { p ->
                        if (p.id == jobId) p.copy(status = ProcessStatus.CANCELLED, message = "Dibatalkan oleh pengguna") else p
                    }
                }
            } catch (e: Throwable) {
                val err = e.localizedMessage ?: "Terjadi kesalahan"
                _processes.update { list ->
                    list.map { p ->
                        if (p.id == jobId) p.copy(status = ProcessStatus.FAILED, errorMessage = err, message = err) else p
                    }
                }
            } finally {
                fallbackJobs.remove(jobId)
            }
        }

        fallbackJobs[jobId] = job
        job.join()

        val finalProcess = _processes.value.find { it.id == jobId }
        return when (finalProcess?.status) {
            ProcessStatus.COMPLETED -> Result.success(Unit)
            ProcessStatus.CANCELLED -> Result.failure(CancellationException("Dibatalkan"))
            ProcessStatus.FAILED -> Result.failure(Exception(finalProcess.errorMessage ?: "Gagal memproses"))
            else -> Result.success(Unit)
        }
    }

    override fun cancelProcess(id: String): Boolean {
        var cancelled = false

        // 1. Batalkan lewat AIDL WorkerService jika tersedia
        val svc = service
        if (svc != null) {
            try {
                cancelled = svc.cancelJob(id)
            } catch (e: RemoteException) {
                Log.e(TAG, "Gagal membatalkan job melalui WorkerService IPC", e)
            }
        }

        // 2. Batalkan deferred pending jika ada
        val deferred = pendingResults.remove(id)
        if (deferred != null && deferred.isActive) {
            deferred.cancel(CancellationException("Dibatalkan oleh pengguna"))
            cancelled = true
        }

        // 3. Batalkan pekerjaan fallback in-process jika ada
        val fallback = fallbackJobs.remove(id)
        if (fallback != null && fallback.isActive) {
            fallback.cancel()
            cancelled = true
        }

        // 4. Perbarui status proses di StateFlow
        _processes.update { list ->
            list.map { p ->
                if (p.id == id && (p.status == ProcessStatus.RUNNING || p.status == ProcessStatus.QUEUED)) {
                    p.copy(status = ProcessStatus.CANCELLED, message = "Dibatalkan oleh pengguna")
                } else p
            }
        }

        return cancelled
    }
}
