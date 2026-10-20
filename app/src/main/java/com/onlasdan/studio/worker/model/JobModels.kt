package com.onlasdan.studio.worker.model

import android.os.Parcel
import android.os.Parcelable

data class JobRequest(
    val id: String,
    val type: String, // EXPORT_ALL, EXPORT_SINGLE, REBUILD, ADD_ENTRY, REPLACE_ENTRY, AUTO_REPLACE
    val targetPath: String,
    val isShizuku: Boolean = false,
    val destinationPath: String? = null,
    val entryNames: List<String> = emptyList(),
    val sourceFilePaths: List<String> = emptyList(),
    val parameters: Map<String, String> = emptyMap()
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        type = parcel.readString() ?: "",
        targetPath = parcel.readString() ?: "",
        isShizuku = parcel.readByte() != 0.toByte(),
        destinationPath = parcel.readString(),
        entryNames = parcel.createStringArrayList() ?: emptyList(),
        sourceFilePaths = parcel.createStringArrayList() ?: emptyList(),
        parameters = mutableMapOf<String, String>().apply {
            val size = parcel.readInt()
            for (i in 0 until size) {
                val k = parcel.readString() ?: ""
                val v = parcel.readString() ?: ""
                put(k, v)
            }
        }
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(type)
        parcel.writeString(targetPath)
        parcel.writeByte(if (isShizuku) 1 else 0)
        parcel.writeString(destinationPath)
        parcel.writeStringList(entryNames)
        parcel.writeStringList(sourceFilePaths)
        parcel.writeInt(parameters.size)
        for ((k, v) in parameters) {
            parcel.writeString(k)
            parcel.writeString(v)
        }
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<JobRequest> {
        override fun createFromParcel(parcel: Parcel): JobRequest = JobRequest(parcel)
        override fun newArray(size: Int): Array<JobRequest?> = arrayOfNulls(size)
    }
}

data class JobProgress(
    val id: String,
    val percent: Float = 0f, // 0.0 to 1.0
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val status: String = "QUEUED", // QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    val currentItemName: String? = null,
    val message: String? = null
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        percent = parcel.readFloat(),
        currentItem = parcel.readInt(),
        totalItems = parcel.readInt(),
        status = parcel.readString() ?: "QUEUED",
        currentItemName = parcel.readString(),
        message = parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeFloat(percent)
        parcel.writeInt(currentItem)
        parcel.writeInt(totalItems)
        parcel.writeString(status)
        parcel.writeString(currentItemName)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<JobProgress> {
        override fun createFromParcel(parcel: Parcel): JobProgress = JobProgress(parcel)
        override fun newArray(size: Int): Array<JobProgress?> = arrayOfNulls(size)
    }
}

data class JobResult(
    val id: String,
    val success: Boolean,
    val message: String? = null,
    val outputFiles: List<String> = emptyList()
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        success = parcel.readByte() != 0.toByte(),
        message = parcel.readString(),
        outputFiles = parcel.createStringArrayList() ?: emptyList()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeByte(if (success) 1 else 0)
        parcel.writeString(message)
        parcel.writeStringList(outputFiles)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<JobResult> {
        override fun createFromParcel(parcel: Parcel): JobResult = JobResult(parcel)
        override fun newArray(size: Int): Array<JobResult?> = arrayOfNulls(size)
    }
}
