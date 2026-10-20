package com.onlasdan.studio.worker.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class JobRequest(
    val id: String,
    val type: String, // EXPORT_ALL, EXPORT_SINGLE, REBUILD, ADD_ENTRY, REPLACE_ENTRY, AUTO_REPLACE
    val targetPath: String,
    val isShizuku: Boolean = false,
    val destinationPath: String? = null,
    val entryNames: List<String> = emptyList(),
    val sourceFilePaths: List<String> = emptyList(),
    val parameters: Map<String, String> = emptyMap()
) : Parcelable

@Parcelize
data class JobProgress(
    val id: String,
    val percent: Float = 0f, // 0.0 to 1.0
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val status: String = "QUEUED", // QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    val currentItemName: String? = null,
    val message: String? = null
) : Parcelable

@Parcelize
data class JobResult(
    val id: String,
    val success: Boolean,
    val message: String? = null,
    val outputFiles: List<String> = emptyList()
) : Parcelable
