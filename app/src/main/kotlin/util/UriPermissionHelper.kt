package com.onlasdan.studio.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility untuk menangani persistable URI permission (SAF) dan fallback copy-to-cache.
 */
object UriPermissionHelper {

    private const val TAG = "UriPermissionHelper"

    /**
     * Mencoba mengambil persistable URI permission jika URI berasal dari dokumen/pohon dokumen.
     * Mengembalikan true jika berhasil atau tidak memerlukan takePersistableUriPermission.
     */
    fun takePersistablePermissionSafely(context: Context, uri: Uri, write: Boolean = false): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Tidak dapat mengambil persistable permission untuk URI: $uri (bukan open_document atau permission ditolak)", e)
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Error takePersistableUriPermission", e)
            false
        }
    }

    /**
     * Melepaskan persistable URI permission setelah operasi batch selesai.
     */
    fun releasePersistablePermissionSafely(context: Context, uri: Uri, write: Boolean = false) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {}
    }

    /**
     * Fallback jika URI tidak dapat dipersist: salin file ke cache internal aplikasi
     * agar dapat dibaca oleh proses :worker secara independen meskipun UI telah mati.
     */
    fun copyUriToCacheFallback(context: Context, uri: Uri, targetName: String): File? {
        return try {
            val cacheDir = File(context.cacheDir, "worker_staged_files").apply { mkdirs() }
            val destFile = File(cacheDir, targetName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyalin URI ke cache fallback: $uri", e)
            null
        }
    }
}
