package com.onlasdan.studio.worker.engine

import com.onlasdan.studio.worker.WorkerContracts
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Mekanisme penulisan berkas aman dengan File Locking eksklusif,
 * atomic move/replace, streaming buffer, dan auto-rollback.
 */
object SafeFileWriter {

    /**
     * Handle lock berkas eksklusif yang mengelola siklus hidup FileLock, FileChannel, dan RandomAccessFile.
     */
    class LockHandle(
        val lockFile: File,
        private val raf: RandomAccessFile,
        private val channel: FileChannel,
        private val lock: FileLock
    ) : Closeable {
        private var released = false

        override fun close() {
            release()
        }

        fun release() {
            if (released) return
            released = true
            try {
                if (lock.isValid) {
                    lock.release()
                }
            } catch (_: Throwable) {}
            try {
                channel.close()
            } catch (_: Throwable) {}
            try {
                raf.close()
            } catch (_: Throwable) {}
            try {
                if (lockFile.exists()) {
                    lockFile.delete()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Mendapatkan lock eksklusif menggunakan FileChannel.tryLock() pada file lock pendamping:
     * targetPath + WorkerContracts.EXT_LOCK (.lock).
     *
     * Jika file sedang di-lock oleh operasi lain: tolak langsung dengan melempar
     * IllegalStateException("Berkas sedang diproses oleh operasi lain").
     */
    @Throws(IllegalStateException::class)
    fun acquireLock(targetPath: String): LockHandle {
        val lockFile = File(targetPath + WorkerContracts.EXT_LOCK)
        lockFile.parentFile?.mkdirs()

        val raf = try {
            RandomAccessFile(lockFile, "rw")
        } catch (e: Exception) {
            throw IllegalStateException("Berkas sedang diproses oleh operasi lain", e)
        }

        val channel = raf.channel
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            try { channel.close() } catch (_: Throwable) {}
            try { raf.close() } catch (_: Throwable) {}
            throw IllegalStateException("Berkas sedang diproses oleh operasi lain", e)
        } catch (e: Exception) {
            try { channel.close() } catch (_: Throwable) {}
            try { raf.close() } catch (_: Throwable) {}
            throw IllegalStateException("Berkas sedang diproses oleh operasi lain", e)
        }

        if (lock == null) {
            try { channel.close() } catch (_: Throwable) {}
            try { raf.close() } catch (_: Throwable) {}
            throw IllegalStateException("Berkas sedang diproses oleh operasi lain")
        }

        return LockHandle(lockFile, raf, channel, lock)
    }

    /**
     * Menjalankan blok kode di dalam proteksi lock eksklusif dan selalu melepas lock di blok finally.
     */
    inline fun <T> withExclusiveLock(targetPath: String, block: () -> T): T {
        val handle = acquireLock(targetPath)
        try {
            return block()
        } finally {
            handle.release()
        }
    }

    /**
     * Menjalankan transaksi penulisan atomik.
     * 1. Mengunci targetPath secara eksklusif.
     * 2. Menyiapkan targetPath + WorkerContracts.EXT_TMP (.tmp).
     * 3. Menjalankan penulisan ke tmpFile.
     * 4. Jika selesai 100% dan valid, lakukan replace atomik ke target berkas asli.
     * 5. Jika gagal, dibatalkan, atau terkena OOM: otomatis hapus file .tmp dan lepas lock.
     */
    inline fun <T> withSafeAtomicWrite(
        targetPath: String,
        noinline onRollback: ((File) -> Unit)? = null,
        block: (tmpFile: File) -> T
    ): T {
        return withExclusiveLock(targetPath) {
            val targetFile = File(targetPath)
            val tmpFile = File(targetPath + WorkerContracts.EXT_TMP)

            // Bersihkan file .tmp sisa crash lama jika ada
            cleanupFile(tmpFile)
            tmpFile.parentFile?.mkdirs()

            var completedSuccessfully = false
            try {
                val result = block(tmpFile)
                if (!tmpFile.exists()) {
                    throw IOException("File sementara hasil penulisan tidak ditemukan: ${tmpFile.absolutePath}")
                }
                val moved = atomicReplace(tmpFile, targetFile)
                if (!moved) {
                    throw IOException("Gagal mengganti berkas target dengan berkas sementara secara atomik")
                }
                completedSuccessfully = true
                result
            } finally {
                if (!completedSuccessfully) {
                    cleanupFile(tmpFile)
                    onRollback?.invoke(tmpFile)
                }
            }
        }
    }

    /**
     * Mengganti targetFile dengan tmpFile secara atomik menggunakan ATOMIC_MOVE atau renameTo fallback.
     */
    fun atomicReplace(tmpFile: File, targetFile: File): Boolean {
        if (!tmpFile.exists()) return false
        val tmpPath = tmpFile.toPath()
        val targetPath = targetFile.toPath()

        return try {
            Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: AtomicMoveNotSupportedException) {
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (e2: Exception) {
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)
            }
        } catch (e: Exception) {
            if (targetFile.exists()) targetFile.delete()
            tmpFile.renameTo(targetFile)
        }
    }

    /**
     * Menyalin berkas secara streaming menggunakan FileChannel transferTo untuk efisiensi memori.
     */
    fun streamCopy(
        source: File,
        dest: File,
        bufferSize: Int = 64 * 1024,
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val totalBytes = source.length()
        dest.parentFile?.mkdirs()

        FileInputStream(source).use { fis ->
            val inChannel = fis.channel
            FileOutputStream(dest).use { fos ->
                val outChannel = fos.channel
                var transferred = 0L
                val chunkSize = bufferSize.toLong().coerceIn(16 * 1024L, 2 * 1024 * 1024L)

                while (transferred < totalBytes) {
                    if (isCancelled()) {
                        throw CancellationException("Operasi dibatalkan saat penyalinan berkas")
                    }
                    val count = inChannel.transferTo(transferred, minOf(chunkSize, totalBytes - transferred), outChannel)
                    if (count <= 0) break
                    transferred += count
                    onProgress?.invoke(transferred, totalBytes)
                }
            }
        }
    }

    /**
     * Mengekstrak potongan byte dari FileChannel sumber ke berkas tujuan menggunakan buffer terukur.
     */
    fun streamExtract(
        srcChannel: FileChannel,
        destFile: File,
        offset: Long,
        length: Long,
        bufferSize: Int = 64 * 1024,
        isCancelled: () -> Boolean = { false }
    ) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { fos ->
            val destChannel = fos.channel
            val bufSize = bufferSize.coerceIn(16 * 1024, 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(bufSize)
            var remaining = length
            var currentPos = offset

            while (remaining > 0) {
                if (isCancelled()) {
                    throw CancellationException("Operasi dibatalkan saat ekstraksi entri")
                }
                buffer.clear()
                val toRead = minOf(remaining, bufSize.toLong()).toInt()
                buffer.limit(toRead)

                val bytesRead = srcChannel.read(buffer, currentPos)
                if (bytesRead <= 0) break
                buffer.flip()
                destChannel.write(buffer)
                currentPos += bytesRead
                remaining -= bytesRead
            }
        }
    }

    /**
     * Mengalirkan data antar stream secara aman dengan batas buffer memori.
     */
    fun streamPipe(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = 64 * 1024,
        isCancelled: () -> Boolean = { false },
        onBytesCopied: ((Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(bufferSize.coerceIn(8 * 1024, 512 * 1024))
        var total = 0L
        while (true) {
            if (isCancelled()) {
                throw CancellationException("Operasi dibatalkan saat streaming data")
            }
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
            onBytesCopied?.invoke(total)
        }
        output.flush()
        return total
    }

    /**
     * Menghapus file secara aman tanpa melempar eksepsi jika terjadi kegagalan IO.
     */
    fun cleanupFile(file: File?) {
        try {
            if (file != null && file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {}
    }

    fun getTempFile(targetPath: String): File = File(targetPath + WorkerContracts.EXT_TMP)

    fun getLockFile(targetPath: String): File = File(targetPath + WorkerContracts.EXT_LOCK)
}
