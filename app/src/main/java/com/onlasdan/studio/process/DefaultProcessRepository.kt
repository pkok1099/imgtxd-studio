package com.onlasdan.studio.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

object DefaultProcessRepository : ProcessRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _processes = MutableStateFlow<List<AppProcess>>(emptyList())
    override val processes: StateFlow<List<AppProcess>> = _processes.asStateFlow()

    override val currentProcess: StateFlow<AppProcess?> = _processes.map { list ->
        list.find { it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val fileLocks = Mutex()

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

        val processId = UUID.randomUUID().toString()
        val newProcess = AppProcess(
            id = processId,
            type = type,
            targetFile = targetFile,
            title = title,
            progress = 0f,
            current = 0,
            total = 0,
            status = ProcessStatus.RUNNING,
            message = "Memulai proses..."
        )

        _processes.update { it + newProcess }

        val currentJob = scope.launch {
            try {
                val result = block { current, total, msg ->
                    _processes.update { list ->
                        list.map { p ->
                            if (p.id == processId) {
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
                            if (p.id == processId) {
                                p.copy(
                                    status = ProcessStatus.COMPLETED,
                                    progress = 1f,
                                    message = "Selesai"
                                )
                            } else p
                        }
                    }
                } else {
                    val err = result.exceptionOrNull()?.localizedMessage ?: "Terjadi kesalahan"
                    _processes.update { list ->
                        list.map { p ->
                            if (p.id == processId) {
                                p.copy(
                                    status = ProcessStatus.FAILED,
                                    errorMessage = err,
                                    message = err
                                )
                            } else p
                        }
                    }
                }
            } catch (e: CancellationException) {
                _processes.update { list ->
                    list.map { p ->
                        if (p.id == processId) {
                            p.copy(
                                status = ProcessStatus.CANCELLED,
                                message = "Dibatalkan oleh pengguna"
                            )
                        } else p
                    }
                }
            } catch (e: Throwable) {
                _processes.update { list ->
                    list.map { p ->
                        if (p.id == processId) {
                            p.copy(
                                status = ProcessStatus.FAILED,
                                errorMessage = e.localizedMessage,
                                message = e.localizedMessage
                            )
                        } else p
                    }
                }
            } finally {
                activeJobs.remove(processId)
            }
        }

        activeJobs[processId] = currentJob
        currentJob.join()

        val finalProcess = _processes.value.find { it.id == processId }
        return when (finalProcess?.status) {
            ProcessStatus.COMPLETED -> Result.success(Unit)
            ProcessStatus.CANCELLED -> Result.failure(CancellationException("Dibatalkan"))
            ProcessStatus.FAILED -> Result.failure(Exception(finalProcess.errorMessage ?: "Gagal memproses"))
            else -> Result.success(Unit)
        }
    }

    override fun cancelProcess(id: String): Boolean {
        val job = activeJobs.remove(id)
        return if (job != null && job.isActive) {
            job.cancel()
            _processes.update { list ->
                list.map { p ->
                    if (p.id == id) p.copy(status = ProcessStatus.CANCELLED, message = "Dibatalkan") else p
                }
            }
            true
        } else {
            false
        }
    }
}
