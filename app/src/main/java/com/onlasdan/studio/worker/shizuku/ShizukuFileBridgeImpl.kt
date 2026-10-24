package com.onlasdan.studio.worker.shizuku

import android.content.Context
import com.onlasdan.studio.util.ShizukuManager.shellQuote
import com.onlasdan.studio.worker.ShizukuFileBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exception thrown when the Shizuku IPC service dies or disconnects during I/O operations.
 * Allows worker components such as SafeFileWriter to perform a clean rollback without crashing.
 */
class ShizukuDisconnectedException(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null
) : IOException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE = "Layanan Shizuku terputus saat proses berlangsung. Berkas dipulihkan ke kondisi awal."
    }
}

/**
 * Implementation of [ShizukuFileBridge] for the :worker background process.
 * Provides transparent file access to Android/data, Android/obb, and restricted paths
 * via Shizuku shell with automatic fallback to standard I/O for accessible files.
 */
class ShizukuFileBridgeImpl(
    private val context: Context? = null
) : ShizukuFileBridge {

    init {
        context?.let { WorkerShizukuManager.init(it) }
    }

    override fun isShizukuAvailable(): Boolean = WorkerShizukuManager.isShizukuAvailable()

    override fun isShizukuBound(): Boolean = WorkerShizukuManager.isShizukuBound()

    override fun openInputStream(path: String): InputStream? {
        try {
            WorkerShizukuManager.ensureBinder(context)
            val file = File(path)

            // Direct standard file reading if file is not in a restricted path and accessible
            if (!WorkerShizukuManager.isRestrictedPath(path) && file.exists() && file.canRead()) {
                return FileInputStream(file)
            }

            // If Shizuku is not ready, try reading via standard File I/O before giving up
            if (!WorkerShizukuManager.isShizukuReady()) {
                if (file.exists() && file.canRead()) {
                    return FileInputStream(file)
                }
                return null
            }

            // For restricted path, verify existence via Shizuku
            if (!exists(path)) {
                return null
            }

            val process = WorkerShizukuManager.newProcess(arrayOf("sh", "-c", "cat ${shellQuote(path)}"))
            return ShizukuInputStream(process, process.inputStream)
        } catch (e: Throwable) {
            val unwrapped = WorkerShizukuManager.unwrapException(e)
            if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE, unwrapped)
            }
            if (unwrapped is IOException) throw unwrapped
            throw IOException("Gagal membuka aliran baca untuk berkas $path: ${unwrapped.message}", unwrapped)
        }
    }

    override fun openOutputStream(path: String): OutputStream? {
        try {
            WorkerShizukuManager.ensureBinder(context)
            val file = File(path)
            val parent = file.parentFile

            // Direct standard file writing for non-restricted files
            if (!WorkerShizukuManager.isRestrictedPath(path)) {
                try {
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    return FileOutputStream(file)
                } catch (_: SecurityException) {
                    // Access denied, fallback to Shizuku below
                }
            }

            // Restricted path requires active Shizuku
            if (!WorkerShizukuManager.isShizukuReady()) {
                throw IOException("Shizuku diperlukan untuk jalur berkas yang dibatasi: $path tetapi tidak siap.")
            }

            // Ensure parent directory exists via Shizuku shell
            val parentPath = file.parent
            if (!parentPath.isNullOrEmpty()) {
                val (mkdirExit, mkdirErr) = WorkerShizukuManager.exec("mkdir -p ${shellQuote(parentPath)}")
                if (mkdirExit != 0) {
                    throw IOException("Gagal membuat direktori induk $parentPath: $mkdirErr")
                }
            }

            // Stage into a sibling temp file and rename inside the same shell:
            // the target is only ever replaced atomically by mv, never left
            // truncated by a cat that dies halfway (disconnect, kill, disk full).
            // A non-zero shell exit removes the staging file and is surfaced by
            // ShizukuOutputStream.close() so callers roll back cleanly.
            //
            // shellQuote on every interpolated path — prevents command injection
            // when path contains shell metacharacters (`$`, `\"`, `` ` ``, `;`).
            val stagingPath = "$path.staging" + System.nanoTime()
            val command = "cat > ${shellQuote(stagingPath)} && mv -f ${shellQuote(stagingPath)} ${shellQuote(path)} || { rm -f ${shellQuote(stagingPath)}; exit 1; }"
            val process = WorkerShizukuManager.newProcess(arrayOf("sh", "-c", command))
            return ShizukuOutputStream(process, process.outputStream, path)
        } catch (e: Throwable) {
            val unwrapped = WorkerShizukuManager.unwrapException(e)
            if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE, unwrapped)
            }
            if (unwrapped is IOException) throw unwrapped
            throw IOException("Gagal membuka aliran tulis untuk berkas $path: ${unwrapped.message}", unwrapped)
        }
    }

    override fun delete(path: String): Boolean {
        return try {
            WorkerShizukuManager.ensureBinder(context)
            val file = File(path)
            if (!WorkerShizukuManager.isRestrictedPath(path) && file.exists()) {
                if (file.delete()) return true
            }

            if (!WorkerShizukuManager.isShizukuReady()) {
                return !file.exists()
            }

            val (exitCode, _) = WorkerShizukuManager.exec("rm -rf ${shellQuote(path)}")
            exitCode == 0
        } catch (e: Throwable) {
            val unwrapped = WorkerShizukuManager.unwrapException(e)
            if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE, unwrapped)
            }
            false
        }
    }

    override fun copy(sourcePath: String, destPath: String): Boolean {
        return try {
            WorkerShizukuManager.ensureBinder(context)
            val srcFile = File(sourcePath)
            val dstFile = File(destPath)

            val srcRestricted = WorkerShizukuManager.isRestrictedPath(sourcePath)
            val dstRestricted = WorkerShizukuManager.isRestrictedPath(destPath)

            // Case 1: Both files are directly accessible via standard File I/O
            if (!srcRestricted && !dstRestricted && srcFile.exists() && srcFile.canRead()) {
                try {
                    dstFile.parentFile?.mkdirs()
                    srcFile.copyTo(dstFile, overwrite = true)
                    return dstFile.exists() && dstFile.length() == srcFile.length()
                } catch (_: SecurityException) {
                    // Fallback to stream copy
                }
            }

            // Case 2: Both files in restricted path and Shizuku is ready -> fast atomic copy in shell
            if (WorkerShizukuManager.isShizukuReady() && srcRestricted && dstRestricted) {
                val parentPath = dstFile.parent ?: ""
                if (parentPath.isNotEmpty()) {
                    WorkerShizukuManager.exec("mkdir -p ${shellQuote(parentPath)}")
                }
                val (cpExit, _) = WorkerShizukuManager.exec("cp -f ${shellQuote(sourcePath)} ${shellQuote(destPath)}")
                if (cpExit == 0 && exists(destPath)) {
                    return true
                }
            }

            // Case 3: Universal streaming copy across storage boundaries
            val inStream = openInputStream(sourcePath) ?: return false
            val outStream = openOutputStream(destPath) ?: run {
                try { inStream.close() } catch (_: Throwable) {}
                return false
            }

            inStream.use { input ->
                outStream.use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                    output.flush()
                }
            }

            exists(destPath)
        } catch (e: Throwable) {
            val unwrapped = WorkerShizukuManager.unwrapException(e)
            if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE, unwrapped)
            }
            if (unwrapped is IOException) throw unwrapped
            false
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            WorkerShizukuManager.ensureBinder(context)
            val file = File(path)
            if (!WorkerShizukuManager.isRestrictedPath(path) && file.exists()) {
                return true
            }

            if (!WorkerShizukuManager.isShizukuReady()) {
                return file.exists()
            }

            val (exitCode, _) = WorkerShizukuManager.exec("test -e ${shellQuote(path)}")
            exitCode == 0
        } catch (e: Throwable) {
            val unwrapped = WorkerShizukuManager.unwrapException(e)
            if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE, unwrapped)
            }
            false
        }
    }

    override fun getFileSize(path: String): Long {
        return try {
            WorkerShizukuManager.ensureBinder(context)
            val file = File(path)
            if (!WorkerShizukuManager.isRestrictedPath(path) && file.exists()) {
                val len = file.length()
                if (len > 0L) return len
            }

            if (!WorkerShizukuManager.isShizukuReady()) {
                return if (file.exists()) file.length() else 0L
            }

            val (exitCode, output) = WorkerShizukuManager.exec("stat -c %s ${shellQuote(path)} 2>/dev/null")
            if (exitCode == 0 && output.isNotBlank()) {
                val parsed = output.lines().firstOrNull()?.trim()?.toLongOrNull()
                if (parsed != null && parsed >= 0L) return parsed
            }

            // Fallback: wc -c < "$path"
            val (wcExit, wcOutput) = WorkerShizukuManager.exec("wc -c < ${shellQuote(path)} 2>/dev/null")
            if (wcExit == 0 && wcOutput.isNotBlank()) {
                val parsed = wcOutput.trim().toLongOrNull()
                if (parsed != null && parsed >= 0L) return parsed
            }

            if (file.exists()) file.length() else 0L
        } catch (e: Throwable) {
            val unwrapped = WorkerShizukuManager.unwrapException(e)
            if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE, unwrapped)
            }
            0L
        }
    }

    /**
     * Input stream wrapper that detects Shizuku binder disconnection during streaming.
     */
    private class ShizukuInputStream(
        private val process: Process,
        private val rawStream: InputStream
    ) : InputStream() {
        private val isClosed = AtomicBoolean(false)

        private inline fun <T> runSafe(block: () -> T): T {
            return try {
                block()
            } catch (e: Throwable) {
                val unwrapped = WorkerShizukuManager.unwrapException(e)
                if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                    throw ShizukuDisconnectedException(
                        ShizukuDisconnectedException.DEFAULT_MESSAGE,
                        unwrapped
                    )
                }
                if (unwrapped is IOException) throw unwrapped
                throw IOException("Error saat membaca aliran Shizuku: ${unwrapped.message}", unwrapped)
            }
        }

        override fun read(): Int = runSafe { rawStream.read() }

        override fun read(b: ByteArray): Int = runSafe { rawStream.read(b) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = runSafe { rawStream.read(b, off, len) }

        override fun available(): Int = runSafe { rawStream.available() }

        override fun skip(n: Long): Long = runSafe { rawStream.skip(n) }

        override fun close() {
            if (isClosed.compareAndSet(false, true)) {
                try {
                    rawStream.close()
                } catch (e: Throwable) {
                    val unwrapped = WorkerShizukuManager.unwrapException(e)
                    if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                        throw ShizukuDisconnectedException(
                            ShizukuDisconnectedException.DEFAULT_MESSAGE,
                            unwrapped
                        )
                    }
                } finally {
                    try {
                        process.destroy()
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Output stream wrapper that detects Shizuku binder disconnection during streaming
     * and validates exit status on close to guarantee write integrity.
     */
    private class ShizukuOutputStream(
        private val process: Process,
        private val rawStream: OutputStream,
        private val targetPath: String
    ) : OutputStream() {
        private val isClosed = AtomicBoolean(false)

        private inline fun <T> runSafe(block: () -> T): T {
            return try {
                block()
            } catch (e: Throwable) {
                val unwrapped = WorkerShizukuManager.unwrapException(e)
                if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                    throw ShizukuDisconnectedException(
                        ShizukuDisconnectedException.DEFAULT_MESSAGE,
                        unwrapped
                    )
                }
                if (unwrapped is IOException) throw unwrapped
                throw IOException("Error saat menulis ke aliran Shizuku: ${unwrapped.message}", unwrapped)
            }
        }

        override fun write(b: Int) = runSafe { rawStream.write(b) }

        override fun write(b: ByteArray) = runSafe { rawStream.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = runSafe { rawStream.write(b, off, len) }

        override fun flush() = runSafe { rawStream.flush() }

        override fun close() {
            if (isClosed.compareAndSet(false, true)) {
                try {
                    rawStream.flush()
                    rawStream.close()
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        if (!WorkerShizukuManager.isShizukuAvailable()) {
                            throw ShizukuDisconnectedException(ShizukuDisconnectedException.DEFAULT_MESSAGE)
                        }
                        throw IOException("Proses penulisan Shizuku ke $targetPath berakhir dengan kode keluar $exitCode")
                    }
                } catch (e: Throwable) {
                    val unwrapped = WorkerShizukuManager.unwrapException(e)
                    if (WorkerShizukuManager.isShizukuDisconnectError(unwrapped)) {
                        throw ShizukuDisconnectedException(
                            ShizukuDisconnectedException.DEFAULT_MESSAGE,
                            unwrapped
                        )
                    }
                    if (unwrapped is IOException) throw unwrapped
                    throw IOException("Gagal menutup aliran penulisan Shizuku ke $targetPath: ${unwrapped.message}", unwrapped)
                } finally {
                    try {
                        process.destroy()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
