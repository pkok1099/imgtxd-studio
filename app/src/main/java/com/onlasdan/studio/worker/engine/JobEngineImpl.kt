package com.onlasdan.studio.worker.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.onlasdan.studio.core.ImgArchive
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.core.ImgVersion
import com.onlasdan.studio.process.ProcessType
import com.onlasdan.studio.txd.TxdStore
import com.onlasdan.studio.worker.JobEngine
import com.onlasdan.studio.worker.MemoryBudget
import com.onlasdan.studio.worker.ShizukuFileBridge
import com.onlasdan.studio.worker.WorkerContracts
import com.onlasdan.studio.worker.model.JobProgress
import com.onlasdan.studio.worker.model.JobRequest
import com.onlasdan.studio.worker.model.JobResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementasi JobEngine untuk eksekusi operasi arsip IMG dan TXD berkinerja tinggi.
 * Menjamin integritas data menggunakan SafeFileWriter (locking, streaming, atomic replace),
 * checkpoint recovery via CheckpointManager, dan pembatalan responsif.
 */
class JobEngineImpl(
    private val shizukuBridge: ShizukuFileBridge? = null,
    private val coreDispatcherProvider: (() -> kotlinx.coroutines.CoroutineDispatcher)? = null
) : JobEngine {

    private val cancelledJobIds = ConcurrentHashMap.newKeySet<String>()

    override fun cancel(jobId: String): Boolean {
        cancelledJobIds.add(jobId)
        return true
    }

    private fun isCancelled(jobId: String): Boolean {
        return cancelledJobIds.contains(jobId)
    }

    override suspend fun execute(
        request: JobRequest,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val dispatcher = coreDispatcherProvider?.invoke() ?: Dispatchers.IO
        return withContext(dispatcher) {
            if (isCancelled(request.id)) {
                return@withContext JobResult(
                    id = request.id,
                    success = false,
                    message = "Dibatalkan oleh pengguna"
                )
            }

        try {
            when (request.type.uppercase()) {
                "REBUILD", ProcessType.REBUILD.name -> executeRebuild(request, budget, onProgress)
                "EXPORT_ALL", ProcessType.EXPORT_ALL.name -> executeExport(request, budget, isAll = true, onProgress)
                "EXPORT_SINGLE", ProcessType.EXPORT_SINGLE.name -> executeExport(request, budget, isAll = false, onProgress)
                "ADD_ENTRY", ProcessType.ADD_ENTRY.name -> executeAddEntry(request, budget, onProgress)
                "REPLACE_ENTRY", ProcessType.REPLACE_ENTRY.name -> executeReplaceEntry(request, budget, onProgress)
                "AUTO_REPLACE", ProcessType.AUTO_REPLACE.name -> executeAutoReplace(request, budget, onProgress)
                else -> JobResult(
                    id = request.id,
                    success = false,
                    message = "Tipe pekerjaan '${request.type}' tidak didukung"
                )
            }
        } catch (e: CancellationException) {
            JobResult(
                id = request.id,
                success = false,
                message = "Dibatalkan oleh pengguna"
            )
        } catch (e: Throwable) {
            JobResult(
                id = request.id,
                success = false,
                message = e.localizedMessage ?: e.message ?: "Terjadi kesalahan internal saat memproses berkas"
            )
        } finally {
            cancelledJobIds.remove(request.id)
        }
    }
}

    /**
     * Merekonstruksi arsip IMG tanpa celah (gap) via engine Rust
     * (NativeCore.imgRebuild — repack ke berkas sementara lalu tukar atomik
     * di dalam engine). Lock, checkpoint, dan sinkronisasi Shizuku tetap
     * diatur di sini; progres dilaporkan di awal dan akhir karena engine
     * tidak memberi progres per entri.
     */
    private fun executeRebuild(
        request: JobRequest,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val targetFile = File(request.targetPath)
        if (!targetFile.exists()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Berkas IMG tidak ditemukan: ${targetFile.absolutePath}"
            )
        }

        val lockHandle = SafeFileWriter.acquireLock(request.targetPath)

        return try {
            com.onlasdan.studio.util.AppIntegrity.verifyOrThrow()
            // The rebuild call returns the post-rebuild listing header
            // (version / entry count / resolved .dir path), so this chain no
            // longer opens the archive separately just to read them.
            val lines = com.onlasdan.studio.core.NativeCore.imgRebuild(
                targetFile.absolutePath, null
            ) ?: throw IOException("Engine Rust tidak tersedia (libimgtxd_core gagal dimuat)")
            val header = lines.firstOrNull()?.split('\t', limit = 5)
                ?: throw IOException("Engine Rust mengembalikan daftar kosong")
            if (header[0] == "ERROR") {
                throw IOException(header.getOrNull(1) ?: "Rebuild gagal")
            }
            val version = if (header.getOrNull(1) == "VER1") ImgVersion.VER1 else ImgVersion.VER2
            val total = header.getOrNull(2)?.toIntOrNull() ?: 0
            val dirFile = header.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { File(it) }

            CheckpointManager.createCheckpoint(request.id, "REBUILD", request.targetPath, total)
            onProgress(
                JobProgress(
                    id = request.id,
                    percent = 0f,
                    currentItem = 0,
                    totalItems = total,
                    status = "RUNNING",
                    message = "Menyusun ulang sektor via engine Rust..."
                )
            )

            if (isCancelled(request.id)) throw CancellationException("Dibatalkan oleh pengguna")

            // Sinkronisasi Shizuku jika path tujuan disediakan
            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                val copied = shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!copied) throw IOException("Gagal menyalin berkas arsip ke lokasi tujuan Shizuku: $dest")
                if (version == ImgVersion.VER1 && dirFile != null) {
                    val dirDest = dest.substringBeforeLast(".") + ".dir"
                    val dirCopied = shizukuBridge.copy(dirFile.absolutePath, dirDest)
                    if (!dirCopied) throw IOException("Gagal menyalin berkas .dir ke lokasi tujuan Shizuku: $dirDest")
                }
            }

            CheckpointManager.removeCheckpoint(request.targetPath)
            onProgress(
                JobProgress(
                    id = request.id,
                    percent = 1f,
                    currentItem = total,
                    totalItems = total,
                    status = "COMPLETED",
                    message = "Rebuild arsip selesai!"
                )
            )

            JobResult(
                id = request.id,
                success = true,
                message = "Arsip berhasil direbuild dan dirapikan",
                outputFiles = listOf(targetFile.absolutePath)
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Rebuild gagal")
        } finally {
            lockHandle.release()
        }
    }

    /**
     * Mengekstrak seluruh entri atau entri spesifik dari arsip IMG atau tekstur TXD
     * ke folder tujuan destinationPath secara streaming.
     */
    private fun executeExport(
        request: JobRequest,
        budget: MemoryBudget,
        isAll: Boolean,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val targetFile = File(request.targetPath)
        if (!targetFile.exists()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Berkas target tidak ditemukan: ${targetFile.absolutePath}"
            )
        }

        val destDirPath = request.destinationPath
            ?: File(targetFile.parentFile, targetFile.nameWithoutExtension + "_exported").absolutePath
        val destDir = File(destDirPath)
        destDir.mkdirs()

        val isTxd = targetFile.name.endsWith(".txd", ignoreCase = true)

        return SafeFileWriter.withExclusiveLock(request.targetPath) {
            if (isTxd) {
                exportTxdTextures(request, targetFile, destDir, isAll, onProgress)
            } else {
                exportImgEntries(request, targetFile, destDir, budget, isAll, onProgress)
            }
        }
    }

    private fun exportTxdTextures(
        request: JobRequest,
        targetFile: File,
        destDir: File,
        isAll: Boolean,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val txd = TxdStore.load(targetFile)
        val textures = if (isAll || request.entryNames.isEmpty()) {
            txd.entries
        } else {
            txd.entries.filter { tex -> request.entryNames.any { it.equals(tex.name, ignoreCase = true) } }
        }

        val total = textures.size
        CheckpointManager.createCheckpoint(request.id, request.type, request.targetPath, total)
        val exportedPaths = mutableListOf<String>()

        return try {
            for ((idx, entry) in textures.withIndex()) {
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }

                val bmp = entry.bitmap
                if (bmp != null) {
                    val outPng = File(destDir, "${entry.name}.png")
                    val outTmp = File(outPng.path + WorkerContracts.EXT_TMP)

                    FileOutputStream(outTmp).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        fos.flush()
                    }
                    SafeFileWriter.atomicReplace(outTmp, outPng)
                    exportedPaths.add(outPng.absolutePath)
                    entry.bitmap = null // Lepaskan memori bitmap seketika
                }

                val current = idx + 1
                onProgress(
                    JobProgress(
                        id = request.id,
                        percent = current.toFloat() / total.toFloat(),
                        currentItem = current,
                        totalItems = total,
                        status = "RUNNING",
                        currentItemName = entry.name,
                        message = "Mengekspor ${entry.name}.png ($current / $total)"
                    )
                )
                CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
            }

            CheckpointManager.removeCheckpoint(request.targetPath)
            JobResult(
                id = request.id,
                success = true,
                message = "Berhasil mengekstrak ${exportedPaths.size} tekstur",
                outputFiles = exportedPaths
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, exportedPaths.size, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, exportedPaths.size, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Ekspor TXD gagal")
        }
    }

    private fun exportImgEntries(
        request: JobRequest,
        targetFile: File,
        destDir: File,
        budget: MemoryBudget,
        isAll: Boolean,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val archive = ImgArchive.open(targetFile)
        val entries = if (isAll || request.entryNames.isEmpty()) {
            archive.entries
        } else {
            archive.entries.filter { ent -> request.entryNames.any { it.equals(ent.cleanName, ignoreCase = true) } }
        }

        val total = entries.size
        CheckpointManager.createCheckpoint(request.id, request.type, request.targetPath, total)
        val exportedPaths = mutableListOf<String>()

        return try {
            for ((idx, entry) in entries.withIndex()) {
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }

                val outEntry = File(destDir, entry.cleanName)
                val outTmp = File(outEntry.path + WorkerContracts.EXT_TMP)

                // Ekstraksi byte kini di engine Rust (via fasad ImgArchive).
                archive.extract(entry, outTmp)
                SafeFileWriter.atomicReplace(outTmp, outEntry)
                exportedPaths.add(outEntry.absolutePath)

                val current = idx + 1
                onProgress(
                    JobProgress(
                        id = request.id,
                        percent = current.toFloat() / total.toFloat(),
                        currentItem = current,
                        totalItems = total,
                        status = "RUNNING",
                        currentItemName = entry.cleanName,
                        message = "Mengekstrak ${entry.cleanName} ($current / $total)"
                    )
                )
                CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
            }

            CheckpointManager.removeCheckpoint(request.targetPath)
            JobResult(
                id = request.id,
                success = true,
                message = "Berhasil mengekstrak ${exportedPaths.size} berkas",
                outputFiles = exportedPaths
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, exportedPaths.size, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, exportedPaths.size, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Ekspor IMG gagal")
        }
    }

    /**
     * Menambahkan berkas eksternal dari sourceFilePaths ke dalam arsip IMG secara aman
     * melalui file .tmp dan rename atomik.
     */
    private fun executeAddEntry(
        request: JobRequest,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val targetFile = File(request.targetPath)
        if (!targetFile.exists()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Berkas arsip tidak ditemukan: ${targetFile.absolutePath}"
            )
        }

        val sourceFiles = request.sourceFilePaths.map { File(it) }
        if (sourceFiles.isEmpty()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Tidak ada berkas sumber yang dipilih untuk ditambahkan"
            )
        }

        val lockHandle = SafeFileWriter.acquireLock(request.targetPath)
        val tmpFile = SafeFileWriter.getTempFile(request.targetPath)
        var tmpDirFile: File? = null
        var isSuccess = false

        return try {
            SafeFileWriter.cleanupFile(tmpFile)
            SafeFileWriter.streamCopy(
                source = targetFile,
                dest = tmpFile,
                bufferSize = budget.ioBufferSize,
                isCancelled = { isCancelled(request.id) }
            )

            val isV2 = ImgArchive.isVer2(tmpFile)
            val dirFile = if (!isV2) File(targetFile.parentFile, targetFile.nameWithoutExtension + ".dir") else null
            if (dirFile != null && dirFile.exists()) {
                tmpDirFile = File(dirFile.path + WorkerContracts.EXT_TMP)
                SafeFileWriter.cleanupFile(tmpDirFile)
                SafeFileWriter.streamCopy(
                    source = dirFile,
                    dest = tmpDirFile,
                    bufferSize = budget.ioBufferSize,
                    isCancelled = { isCancelled(request.id) }
                )
            }

            val archive = ImgArchive.open(tmpFile, tmpDirFile)
            val total = sourceFiles.size
            CheckpointManager.createCheckpoint(request.id, "ADD_ENTRY", request.targetPath, total)

            for ((idx, srcFile) in sourceFiles.withIndex()) {
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }
                if (!srcFile.exists()) {
                    throw IOException("Berkas sumber tidak ditemukan: ${srcFile.absolutePath}")
                }

                val requestedName = request.entryNames.getOrNull(idx) ?: srcFile.name
                val cleanName = if (requestedName.indexOf('\u0000') >= 0) requestedName.substringBefore('\u0000') else requestedName
                val existing = archive.entries.find { it.cleanName.equals(cleanName, ignoreCase = true) }

                if (existing != null) {
                    archive.replace(existing, srcFile)
                } else {
                    archive.add(srcFile, cleanName)
                }

                val current = idx + 1
                onProgress(
                    JobProgress(
                        id = request.id,
                        percent = current.toFloat() / total.toFloat(),
                        currentItem = current,
                        totalItems = total,
                        status = "RUNNING",
                        currentItemName = cleanName,
                        message = "Menambahkan $cleanName ($current / $total)"
                    )
                )
                CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
            }

            val replaced = SafeFileWriter.atomicReplace(tmpFile, targetFile)
            if (!replaced) throw IOException("Gagal mengganti berkas arsip secara atomik")
            if (tmpDirFile != null && dirFile != null) {
                if (!SafeFileWriter.atomicReplace(tmpDirFile, dirFile)) {
                    throw IOException("Gagal mengganti berkas .dir secara atomik")
                }
            }

            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                val copied = shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!copied) throw IOException("Gagal menyalin berkas arsip ke lokasi tujuan Shizuku: $dest")
                if (!isV2 && dirFile != null) {
                    val dirDest = dest.substringBeforeLast(".") + ".dir"
                    val dirCopied = shizukuBridge.copy(dirFile.absolutePath, dirDest)
                    if (!dirCopied) throw IOException("Gagal menyalin berkas .dir ke lokasi tujuan Shizuku: $dirDest")
                }
            }

            isSuccess = true
            CheckpointManager.removeCheckpoint(request.targetPath)
            JobResult(
                id = request.id,
                success = true,
                message = "Berhasil menambahkan $total berkas ke arsip",
                outputFiles = listOf(targetFile.absolutePath)
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Tambah berkas gagal")
        } finally {
            if (!isSuccess) {
                SafeFileWriter.cleanupFile(tmpFile)
                SafeFileWriter.cleanupFile(tmpDirFile)
            }
            lockHandle.release()
        }
    }

    /**
     * Mengganti entri dalam arsip IMG dengan berkas baru secara aman.
     */
    private fun executeReplaceEntry(
        request: JobRequest,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val targetFile = File(request.targetPath)
        if (!targetFile.exists()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Berkas arsip tidak ditemukan: ${targetFile.absolutePath}"
            )
        }

        val sourceFiles = request.sourceFilePaths.map { File(it) }
        if (sourceFiles.isEmpty()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Tidak ada berkas pengganti yang dipilih"
            )
        }

        val lockHandle = SafeFileWriter.acquireLock(request.targetPath)
        val tmpFile = SafeFileWriter.getTempFile(request.targetPath)
        var tmpDirFile: File? = null
        var isSuccess = false

        return try {
            SafeFileWriter.cleanupFile(tmpFile)
            SafeFileWriter.streamCopy(
                source = targetFile,
                dest = tmpFile,
                bufferSize = budget.ioBufferSize,
                isCancelled = { isCancelled(request.id) }
            )

            val isV2 = ImgArchive.isVer2(tmpFile)
            val dirFile = if (!isV2) File(targetFile.parentFile, targetFile.nameWithoutExtension + ".dir") else null
            if (dirFile != null && dirFile.exists()) {
                tmpDirFile = File(dirFile.path + WorkerContracts.EXT_TMP)
                SafeFileWriter.cleanupFile(tmpDirFile)
                SafeFileWriter.streamCopy(
                    source = dirFile,
                    dest = tmpDirFile,
                    bufferSize = budget.ioBufferSize,
                    isCancelled = { isCancelled(request.id) }
                )
            }

            val archive = ImgArchive.open(tmpFile, tmpDirFile)
            val total = sourceFiles.size
            CheckpointManager.createCheckpoint(request.id, "REPLACE_ENTRY", request.targetPath, total)

            for ((idx, srcFile) in sourceFiles.withIndex()) {
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }
                if (!srcFile.exists()) {
                    throw IOException("Berkas sumber tidak ditemukan: ${srcFile.absolutePath}")
                }

                val requestedName = request.entryNames.getOrNull(idx) ?: srcFile.name
                val cleanName = if (requestedName.indexOf('\u0000') >= 0) requestedName.substringBefore('\u0000') else requestedName
                val existing = archive.entries.find { it.cleanName.equals(cleanName, ignoreCase = true) }

                if (existing != null) {
                    archive.replace(existing, srcFile)
                } else {
                    if (request.parameters["add_if_missing"] == "true") {
                        archive.add(srcFile, cleanName)
                    } else {
                        throw IllegalArgumentException("Entri '$cleanName' tidak ditemukan dalam arsip")
                    }
                }

                val current = idx + 1
                onProgress(
                    JobProgress(
                        id = request.id,
                        percent = current.toFloat() / total.toFloat(),
                        currentItem = current,
                        totalItems = total,
                        status = "RUNNING",
                        currentItemName = cleanName,
                        message = "Mengganti $cleanName ($current / $total)"
                    )
                )
                CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
            }

            val replaced = SafeFileWriter.atomicReplace(tmpFile, targetFile)
            if (!replaced) throw IOException("Gagal mengganti berkas arsip secara atomik")
            if (tmpDirFile != null && dirFile != null) {
                if (!SafeFileWriter.atomicReplace(tmpDirFile, dirFile)) {
                    throw IOException("Gagal mengganti berkas .dir secara atomik")
                }
            }

            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                val copied = shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!copied) throw IOException("Gagal menyalin berkas arsip ke lokasi tujuan Shizuku: $dest")
                if (!isV2 && dirFile != null) {
                    val dirDest = dest.substringBeforeLast(".") + ".dir"
                    val dirCopied = shizukuBridge.copy(dirFile.absolutePath, dirDest)
                    if (!dirCopied) throw IOException("Gagal menyalin berkas .dir ke lokasi tujuan Shizuku: $dirDest")
                }
            }

            isSuccess = true
            CheckpointManager.removeCheckpoint(request.targetPath)
            JobResult(
                id = request.id,
                success = true,
                message = "Berhasil mengganti $total berkas dalam arsip",
                outputFiles = listOf(targetFile.absolutePath)
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Ganti berkas gagal")
        } finally {
            if (!isSuccess) {
                SafeFileWriter.cleanupFile(tmpFile)
                SafeFileWriter.cleanupFile(tmpDirFile)
            }
            lockHandle.release()
        }
    }

    /**
     * Mengganti tekstur TXD atau berkas model secara massal berdasarkan nama berkas mod.
     */
    private fun executeAutoReplace(
        request: JobRequest,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val targetFile = File(request.targetPath)
        if (!targetFile.exists()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Berkas target tidak ditemukan: ${targetFile.absolutePath}"
            )
        }

        val sourceFiles = request.sourceFilePaths.map { File(it) }.filter { it.exists() }
        if (sourceFiles.isEmpty()) {
            return JobResult(
                id = request.id,
                success = false,
                message = "Tidak ada berkas pengganti yang valid ditemukan"
            )
        }

        val isTxd = targetFile.name.endsWith(".txd", ignoreCase = true)

        return if (isTxd) {
            autoReplaceTxd(request, targetFile, sourceFiles, onProgress)
        } else {
            autoReplaceImg(request, targetFile, sourceFiles, budget, onProgress)
        }
    }

    private fun autoReplaceTxd(
        request: JobRequest,
        targetFile: File,
        sourceFiles: List<File>,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val lockHandle = SafeFileWriter.acquireLock(request.targetPath)
        val tmpFile = SafeFileWriter.getTempFile(request.targetPath)
        var isSuccess = false

        return try {
            // The TXD batch is applied in ONE engine call after the whole scan
            // finishes, so an interrupted run never modified the target file —
            // there is nothing to resume from a previous attempt. Always scan
            // from scratch; this also overwrites any stale checkpoint left by
            // an older crashed job on the same path (getResumeState matches by
            // path only, not by job id/type).
            val total = sourceFiles.size
            SafeFileWriter.cleanupFile(tmpFile)
            CheckpointManager.createCheckpoint(request.id, "AUTO_REPLACE", request.targetPath, total)
            val startIndex = 0

            val txd = TxdStore.load(targetFile)
            val textures = txd.entries
            var replacedCount = startIndex
            val batchIndices = mutableListOf<Int>()
            val batchImages = mutableListOf<Triple<IntArray, Int, Int>>()

            for (idx in startIndex until total) {
                val srcFile = sourceFiles[idx]
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }

                val baseName = srcFile.nameWithoutExtension
                val targetTexture = textures.find { it.name.equals(baseName, ignoreCase = true) }

                if (targetTexture != null) {
                    val bitmap = BitmapFactory.decodeFile(srcFile.absolutePath)
                    if (bitmap != null) {
                        val px = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        batchIndices.add(targetTexture.sourceIndex)
                        batchImages.add(Triple(px, bitmap.width, bitmap.height))
                        bitmap.recycle()
                        replacedCount++
                    }
                }

                val current = idx + 1
                onProgress(
                    JobProgress(
                        id = request.id,
                        percent = current.toFloat() / total.toFloat(),
                        currentItem = current,
                        totalItems = total,
                        status = "RUNNING",
                        currentItemName = srcFile.name,
                        message = "Auto Replace: ${srcFile.name} ($current / $total)"
                    )
                )
                CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
            }

            if (replacedCount > 0) {
                // Engine Rust bekerja pada salinan .tmp, lalu tukar atomik.
                SafeFileWriter.streamCopy(
                    source = targetFile,
                    dest = tmpFile,
                    bufferSize = 256 * 1024,
                    isCancelled = { isCancelled(request.id) }
                )
                TxdStore.replaceTextures(tmpFile, batchIndices, batchImages)
                val moved = SafeFileWriter.atomicReplace(tmpFile, targetFile)
                if (!moved) throw IOException("Gagal mengganti berkas TXD secara atomik")

                if (request.isShizuku && shizukuBridge != null) {
                    val dest = request.destinationPath ?: request.targetPath
                    val copied = shizukuBridge.copy(targetFile.absolutePath, dest)
                    if (!copied) throw IOException("Gagal menyalin berkas TXD ke lokasi tujuan Shizuku: $dest")
                }
            }

            isSuccess = true
            CheckpointManager.removeCheckpoint(request.targetPath)
            JobResult(
                id = request.id,
                success = true,
                message = "Berhasil mengganti $replacedCount tekstur dalam berkas TXD",
                outputFiles = listOf(targetFile.absolutePath)
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            val isInterrupted = e.message?.contains("timeout", ignoreCase = true) == true
            val status = if (isInterrupted) "INTERRUPTED" else "FAILED"
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = status)
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Auto Replace TXD gagal")
        } finally {
            if (!isSuccess && !CheckpointManager.hasIncompleteCheckpoint(request.targetPath)) {
                SafeFileWriter.cleanupFile(tmpFile)
            }
            lockHandle.release()
        }
    }

    private fun autoReplaceImg(
        request: JobRequest,
        targetFile: File,
        sourceFiles: List<File>,
        budget: MemoryBudget,
        onProgress: (JobProgress) -> Unit
    ): JobResult {
        val lockHandle = SafeFileWriter.acquireLock(request.targetPath)
        val tmpFile = SafeFileWriter.getTempFile(request.targetPath)
        var tmpDirFile: File? = null
        var isSuccess = false

        return try {
            val resumeState = CheckpointManager.getResumeState(request.targetPath)
            val startIndex = resumeState?.processedItems ?: 0
            val isResuming = startIndex > 0 && tmpFile.exists() && tmpFile.length() > 0L

            if (!isResuming) {
                SafeFileWriter.cleanupFile(tmpFile)
                SafeFileWriter.streamCopy(
                    source = targetFile,
                    dest = tmpFile,
                    bufferSize = budget.ioBufferSize,
                    isCancelled = { isCancelled(request.id) }
                )
            }

            val isV2 = ImgArchive.isVer2(tmpFile)
            val dirFile = if (!isV2) File(targetFile.parentFile, targetFile.nameWithoutExtension + ".dir") else null
            if (dirFile != null && dirFile.exists()) {
                tmpDirFile = File(dirFile.path + WorkerContracts.EXT_TMP)
                if (!isResuming) {
                    SafeFileWriter.cleanupFile(tmpDirFile)
                    SafeFileWriter.streamCopy(
                        source = dirFile,
                        dest = tmpDirFile,
                        bufferSize = budget.ioBufferSize,
                        isCancelled = { isCancelled(request.id) }
                    )
                }
            }

            val archive = ImgArchive.open(tmpFile, tmpDirFile)
            val total = sourceFiles.size
            if (!isResuming) {
                CheckpointManager.createCheckpoint(request.id, "AUTO_REPLACE", request.targetPath, total)
            }
            var replacedCount = startIndex

            for (idx in startIndex until total) {
                val srcFile = sourceFiles[idx]
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }

                val cleanName = srcFile.name
                val existing = archive.entries.find { it.cleanName.equals(cleanName, ignoreCase = true) }

                if (existing != null) {
                    archive.replace(existing, srcFile)
                    replacedCount++
                } else if (request.parameters["add_if_missing"] == "true") {
                    archive.add(srcFile, cleanName)
                    replacedCount++
                }

                val current = idx + 1
                onProgress(
                    JobProgress(
                        id = request.id,
                        percent = current.toFloat() / total.toFloat(),
                        currentItem = current,
                        totalItems = total,
                        status = "RUNNING",
                        currentItemName = cleanName,
                        message = "Auto Replace: $cleanName ($current / $total)"
                    )
                )
                CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
            }

            val replaced = SafeFileWriter.atomicReplace(tmpFile, targetFile)
            if (!replaced) throw IOException("Gagal mengganti berkas arsip secara atomik")
            if (tmpDirFile != null && dirFile != null) {
                if (!SafeFileWriter.atomicReplace(tmpDirFile, dirFile)) {
                    throw IOException("Gagal mengganti berkas .dir secara atomik")
                }
            }

            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                val copied = shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!copied) throw IOException("Gagal menyalin berkas arsip ke lokasi tujuan Shizuku: $dest")
                if (!isV2 && dirFile != null) {
                    val dirDest = dest.substringBeforeLast(".") + ".dir"
                    val dirCopied = shizukuBridge.copy(dirFile.absolutePath, dirDest)
                    if (!dirCopied) throw IOException("Gagal menyalin berkas .dir ke lokasi tujuan Shizuku: $dirDest")
                }
            }

            isSuccess = true
            CheckpointManager.removeCheckpoint(request.targetPath)
            JobResult(
                id = request.id,
                success = true,
                message = "Berhasil mengganti $replacedCount berkas dalam arsip IMG",
                outputFiles = listOf(targetFile.absolutePath)
            )
        } catch (e: CancellationException) {
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "CANCELLED")
            JobResult(id = request.id, success = false, message = "Dibatalkan oleh pengguna")
        } catch (e: Exception) {
            val isInterrupted = e.message?.contains("timeout", ignoreCase = true) == true
            val status = if (isInterrupted) "INTERRUPTED" else "FAILED"
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = status)
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Auto Replace IMG gagal")
        } finally {
            if (!isSuccess && !CheckpointManager.hasIncompleteCheckpoint(request.targetPath)) {
                SafeFileWriter.cleanupFile(tmpFile)
                SafeFileWriter.cleanupFile(tmpDirFile)
            }
            lockHandle.release()
        }
    }
}
