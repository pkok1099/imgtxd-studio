package com.onlasdan.studio.process

import kotlinx.coroutines.flow.StateFlow

data class AppProcess(
    val id: String,
    val type: ProcessType,
    val targetFile: String,
    val title: String,
    val progress: Float = 0f,
    val current: Int = 0,
    val total: Int = 0,
    val status: ProcessStatus = ProcessStatus.QUEUED,
    val message: String? = null,
    val errorMessage: String? = null
)

enum class ProcessType(val label: String) {
    EXPORT_ALL("Ekspor Semua"),
    EXPORT_SINGLE("Ekspor Berkas"),
    REBUILD("Rebuild Arsip"),
    ADD_ENTRY("Tambah Berkas"),
    REPLACE_ENTRY("Ganti Berkas"),
    AUTO_REPLACE("Auto Replace"),
    SHIZUKU_SYNC("Sinkronisasi Shizuku")
}

enum class ProcessStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

interface ProcessRepository {
    val processes: StateFlow<List<AppProcess>>

    fun isFileBusy(filePath: String): Boolean

    fun getActiveProcessForFile(filePath: String): AppProcess?

    fun hasRunningProcess(filePath: String? = null): Boolean

    suspend fun executeProcess(
        type: ProcessType,
        targetFile: String,
        title: String,
        block: suspend (updateProgress: (current: Int, total: Int, message: String?) -> Unit) -> Result<Unit>
    ): Result<Unit>

    fun cancelProcess(id: String): Boolean
}
