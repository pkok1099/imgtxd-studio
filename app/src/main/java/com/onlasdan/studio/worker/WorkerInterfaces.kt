package com.onlasdan.studio.worker

import com.onlasdan.studio.worker.model.JobProgress
import com.onlasdan.studio.worker.model.JobRequest
import com.onlasdan.studio.worker.model.JobResult
import java.io.InputStream
import java.io.OutputStream

data class MemoryBudget(
    val ioBufferSize: Int = 64 * 1024,
    val chunkSize: Int = 1024 * 1024,
    val maxParallelItems: Int = 2,
    val maxCacheSizeMb: Int = 64,
    val totalBudgetMb: Int = 512
)

interface JobEngine {
    suspend fun execute(
        request: JobRequest,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult

    fun cancel(jobId: String): Boolean
}

interface MemoryBudgetProvider {
    fun getCurrentBudget(): MemoryBudget
    fun onTrimMemory(level: Int)
}

interface ShizukuFileBridge {
    fun isShizukuAvailable(): Boolean
    fun isShizukuBound(): Boolean
    fun openInputStream(path: String): InputStream?
    fun openOutputStream(path: String): OutputStream?
    fun delete(path: String): Boolean
    fun copy(sourcePath: String, destPath: String): Boolean
    fun exists(path: String): Boolean
    fun getFileSize(path: String): Long
}
