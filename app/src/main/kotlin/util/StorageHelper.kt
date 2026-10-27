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
     *
     * SECURITY: `uri.lastPathSegment` is attacker-controlled (any app offering
     * a content URI can return arbitrary text). We sanitize it to a single
     * filename component (no `/`, no `..`) before composing under cacheDir —
     * otherwise a malicious provider could write outside `opened_archives/`
     * (e.g. `../../databases/main.db`). With MANAGE_EXTERNAL_STORAGE granted,
     * the app's filesystem reach is broad, so this matters even for cached paths.
     */
    fun copyUriToTempFile(context: Context, uri: Uri, fileName: String? = null): File {
        val resolvedRaw = fileName ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "archive.img")
        // Strip path separators and parent references — keep only the last
        // path component. Also strips leading dots so ".profile" stays in
        // cacheDir and is not interpreted as a hidden file outside.
        val safeName = File(resolvedRaw).name.ifBlank { "archive.img" }
        // Defense-in-depth: reject any name that still resolves outside cacheDir.
        val cacheDir = File(context.cacheDir, "opened_archives").apply { mkdirs() }
        val tempFile = File(cacheDir, safeName)
        val canonicalCache = cacheDir.canonicalPath
        val canonicalTemp = tempFile.canonicalPath
        if (!canonicalTemp.startsWith(canonicalCache + File.separator) && canonicalTemp != canonicalCache) {
            throw SecurityException("Resolved path escapes cache directory: $canonicalTemp")
        }

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
