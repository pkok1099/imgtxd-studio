package com.onlasdan.studio.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object StorageHelper {

    /**
     * Checks if the app has full file access (MANAGE_EXTERNAL_STORAGE).
     * On Android 11+ (API 30+), including Android 16 (API 36), this is required
     * for direct modding of game folders in /sdcard/Android/data or custom directories.
     */
    fun hasManageStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Returns an intent to prompt the user to grant MANAGE_APP_ALL_FILES_ACCESS_PERMISSION.
     */
    fun getManageStorageIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (e: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * Copies a content URI (from SAF Storage Access Framework) into a local file
     * in the app's cache directory so that high-performance FileChannel and random-access operations can run.
     */
    fun copyUriToTempFile(context: Context, uri: Uri, fileName: String? = null): File {
        val resolvedName = fileName ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "archive.img")
        val cacheDir = File(context.cacheDir, "opened_archives").apply { mkdirs() }
        val tempFile = File(cacheDir, resolvedName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream from URI: $uri")

        return tempFile
    }

    /**
     * Resolves a URI to a local File if it is a file:// URI or accessible.
     */
    fun resolveFileFromUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            uri.path?.let { return File(it) }
        }
        return null
    }
}
