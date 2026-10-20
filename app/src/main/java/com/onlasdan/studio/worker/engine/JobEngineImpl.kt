package com.onlasdan.studio.worker.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.onlasdan.studio.core.ImgArchive
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.core.ImgVersion
import com.onlasdan.studio.process.ProcessType
import com.onlasdan.studio.txd.TxdBuilder
import com.onlasdan.studio.txd.TxdParser
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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementasi JobEngine untuk eksekusi operasi arsip IMG dan TXD berkinerja tinggi.
 * Menjamin integritas data menggunakan SafeFileWriter (locking, streaming, atomic replace),
 * checkpoint recovery via CheckpointManager, dan pembatalan responsif.
 */
class JobEngineImpl(
    private val shizukuBridge: ShizukuFileBridge? = null
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
    ): JobResult = withContext(Dispatchers.IO) {
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

    /**
     * Merekonstruksi arsip IMG tanpa celah (gap), menyusun ulang sektor secara streaming
     * ke file .tmp, memperbarui header direktori, dan mengganti berkas secara atomik.
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
        val tmpFile = SafeFileWriter.getTempFile(request.targetPath)
        var tmpDirFile: File? = null
        var isSuccess = false

        return try {
            SafeFileWriter.cleanupFile(tmpFile)
            val archive = ImgArchive.open(targetFile)
            val version = archive.version
            val entries = archive.entries
            val total = entries.size

            CheckpointManager.createCheckpoint(request.id, "REBUILD", request.targetPath, total)
            onProgress(
                JobProgress(
                    id = request.id,
                    percent = 0f,
                    currentItem = 0,
                    totalItems = total,
                    status = "RUNNING",
                    message = "Mempersiapkan rebuild arsip..."
                )
            )

            val headerSize = 8 + total * ImgArchive.ENTRY_SIZE
            val headerSectors = if (version == ImgVersion.VER2) {
                (headerSize + ImgArchive.SECTOR_SIZE - 1) / ImgArchive.SECTOR_SIZE
            } else {
                0
            }
            var currentSector = headerSectors

            val dirFile = if (version == ImgVersion.VER1) {
                archive.dirFile ?: File(targetFile.parentFile, targetFile.nameWithoutExtension + ".dir")
            } else null

            if (dirFile != null) {
                tmpDirFile = File(dirFile.path + WorkerContracts.EXT_TMP)
                SafeFileWriter.cleanupFile(tmpDirFile)
            }

            RandomAccessFile(targetFile, "r").use { srcRaf ->
                val srcChannel = srcRaf.channel
                RandomAccessFile(tmpFile, "rw").use { destRaf ->
                    val destChannel = destRaf.channel

                    if (version == ImgVersion.VER2) {
                        // Alokasi ruang header di awal file
                        destChannel.position(0)
                        val magicBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        magicBuf.put(ImgArchive.VER2_MAGIC)
                        magicBuf.flip()
                        destChannel.write(magicBuf)

                        val padBytes = (headerSectors * ImgArchive.SECTOR_SIZE) - 4
                        if (padBytes > 0) {
                            val zeroChunk = ByteBuffer.allocate(minOf(padBytes, budget.ioBufferSize.coerceAtLeast(32 * 1024)))
                            var remainingPad = padBytes
                            while (remainingPad > 0) {
                                if (isCancelled(request.id)) throw CancellationException("Dibatalkan oleh pengguna")
                                zeroChunk.clear()
                                val toWrite = minOf(remainingPad, zeroChunk.capacity())
                                zeroChunk.limit(toWrite)
                                destChannel.write(zeroChunk)
                                remainingPad -= toWrite
                            }
                        }
                    }

                    val bufferSize = budget.ioBufferSize.coerceIn(16 * 1024, 1024 * 1024)
                    val transferBuf = ByteBuffer.allocateDirect(bufferSize)

                    for ((idx, entry) in entries.withIndex()) {
                        if (isCancelled(request.id)) {
                            throw CancellationException("Dibatalkan oleh pengguna")
                        }

                        srcChannel.position(entry.offsetInBytes)
                        destChannel.position(currentSector.toLong() * ImgArchive.SECTOR_SIZE)

                        var bytesRemaining = entry.sizeInBytes
                        while (bytesRemaining > 0) {
                            if (isCancelled(request.id)) throw CancellationException("Dibatalkan oleh pengguna")
                            transferBuf.clear()
                            if (bytesRemaining < transferBuf.capacity()) {
                                transferBuf.limit(bytesRemaining.toInt())
                            }
                            val read = srcChannel.read(transferBuf)
                            if (read <= 0) break
                            transferBuf.flip()
                            destChannel.write(transferBuf)
                            bytesRemaining -= read
                        }

                        entry.offsetInSectors = currentSector
                        currentSector += entry.sizeInSectors

                        val current = idx + 1
                        val percent = current.toFloat() / total.toFloat()
                        onProgress(
                            JobProgress(
                                id = request.id,
                                percent = percent,
                                currentItem = current,
                                totalItems = total,
                                status = "RUNNING",
                                currentItemName = entry.cleanName,
                                message = "Menyusun ulang sektor ($current / $total)"
                            )
                        )
                        CheckpointManager.updateCheckpoint(request.id, request.targetPath, current)
                    }

                    // Tulis struktur header untuk VER2
                    if (version == ImgVersion.VER2) {
                        destChannel.position(0)
                        val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        headerBuf.put(ImgArchive.VER2_MAGIC)
                        headerBuf.putInt(entries.size)
                        headerBuf.flip()
                        destChannel.write(headerBuf)

                        val entriesBuf = ByteBuffer.allocate(entries.size * ImgArchive.ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                        for (entry in entries) {
                            entriesBuf.putInt(entry.offsetInSectors)
                            entriesBuf.putInt(entry.sizeInSectors)
                            val nameBytes = ByteArray(ImgArchive.NAME_SIZE)
                            val srcBytes = entry.cleanName.toByteArray(ImgArchive.CHARSET)
                            System.arraycopy(srcBytes, 0, nameBytes, 0, minOf(srcBytes.size, ImgArchive.NAME_SIZE))
                            entriesBuf.put(nameBytes)
                        }
                        entriesBuf.flip()
                        destChannel.write(entriesBuf)
                    }
                }
            }

            // Tulis direktori untuk VER1 (.dir)
            if (version == ImgVersion.VER1 && dirFile != null && tmpDirFile != null) {
                FileOutputStream(tmpDirFile).use { fos ->
                    val channel = fos.channel
                    val entriesBuf = ByteBuffer.allocate(entries.size * ImgArchive.ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    for (entry in entries) {
                        entriesBuf.putInt(entry.offsetInSectors)
                        entriesBuf.putInt(entry.sizeInSectors)
                        val nameBytes = ByteArray(ImgArchive.NAME_SIZE)
                        val srcBytes = entry.cleanName.toByteArray(ImgArchive.CHARSET)
                        System.arraycopy(srcBytes, 0, nameBytes, 0, minOf(srcBytes.size, ImgArchive.NAME_SIZE))
                        entriesBuf.put(nameBytes)
                    }
                    entriesBuf.flip()
                    channel.write(entriesBuf)
                }
            }

            // Atomic replace
            val replaced = SafeFileWriter.atomicReplace(tmpFile, targetFile)
            if (!replaced) throw IOException("Gagal mengganti berkas arsip dengan hasil rebuild secara atomik")
            if (tmpDirFile != null && dirFile != null) {
                SafeFileWriter.atomicReplace(tmpDirFile, dirFile)
            }

            // Sinkronisasi Shizuku jika path tujuan disediakan
            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                shizukuBridge.copy(targetFile.absolutePath, dest)
                if (version == ImgVersion.VER1 && dirFile != null) {
                    val dirDest = dest.substringBeforeLast(".") + ".dir"
                    shizukuBridge.copy(dirFile.absolutePath, dirDest)
                }
            }

            isSuccess = true
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
            if (!isSuccess) {
                SafeFileWriter.cleanupFile(tmpFile)
                SafeFileWriter.cleanupFile(tmpDirFile)
            }
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
        val txd = TxdParser.parse(targetFile)
        val textures = if (isAll || request.entryNames.isEmpty()) {
            txd.textures
        } else {
            txd.textures.filter { tex -> request.entryNames.any { it.equals(tex.name, ignoreCase = true) } }
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
            RandomAccessFile(archive.imgFile, "r").use { raf ->
                val srcChannel = raf.channel

                for ((idx, entry) in entries.withIndex()) {
                    if (isCancelled(request.id)) {
                        throw CancellationException("Dibatalkan oleh pengguna")
                    }

                    val outEntry = File(destDir, entry.cleanName)
                    val outTmp = File(outEntry.path + WorkerContracts.EXT_TMP)

                    SafeFileWriter.streamExtract(
                        srcChannel = srcChannel,
                        destFile = outTmp,
                        offset = entry.offsetInBytes,
                        length = entry.sizeInBytes,
                        bufferSize = budget.ioBufferSize,
                        isCancelled = { isCancelled(request.id) }
                    )
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
                SafeFileWriter.atomicReplace(tmpDirFile, dirFile)
            }

            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!isV2 && dirFile != null) {
                    shizukuBridge.copy(dirFile.absolutePath, dest.substringBeforeLast(".") + ".dir")
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
                SafeFileWriter.atomicReplace(tmpDirFile, dirFile)
            }

            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!isV2 && dirFile != null) {
                    shizukuBridge.copy(dirFile.absolutePath, dest.substringBeforeLast(".") + ".dir")
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
            SafeFileWriter.cleanupFile(tmpFile)
            val txd = TxdParser.parse(targetFile)
            val textures = txd.textures
            val total = sourceFiles.size
            CheckpointManager.createCheckpoint(request.id, "AUTO_REPLACE", request.targetPath, total)
            var replacedCount = 0

            for ((idx, srcFile) in sourceFiles.withIndex()) {
                if (isCancelled(request.id)) {
                    throw CancellationException("Dibatalkan oleh pengguna")
                }

                val baseName = srcFile.nameWithoutExtension
                val targetTexture = textures.find { it.name.equals(baseName, ignoreCase = true) }

                if (targetTexture != null) {
                    val bitmap = BitmapFactory.decodeFile(srcFile.absolutePath)
                    if (bitmap != null) {
                        TxdBuilder.replaceTexture(targetTexture, bitmap)
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
                TxdBuilder.buildToFile(textures, tmpFile, txd.rwVersion)
                val moved = SafeFileWriter.atomicReplace(tmpFile, targetFile)
                if (!moved) throw IOException("Gagal mengganti berkas TXD secara atomik")

                if (request.isShizuku && shizukuBridge != null) {
                    shizukuBridge.copy(targetFile.absolutePath, request.destinationPath ?: request.targetPath)
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
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Auto Replace TXD gagal")
        } finally {
            if (!isSuccess) {
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
            CheckpointManager.createCheckpoint(request.id, "AUTO_REPLACE", request.targetPath, total)
            var replacedCount = 0

            for ((idx, srcFile) in sourceFiles.withIndex()) {
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
                SafeFileWriter.atomicReplace(tmpDirFile, dirFile)
            }

            if (request.isShizuku && shizukuBridge != null) {
                val dest = request.destinationPath ?: request.targetPath
                shizukuBridge.copy(targetFile.absolutePath, dest)
                if (!isV2 && dirFile != null) {
                    shizukuBridge.copy(dirFile.absolutePath, dest.substringBeforeLast(".") + ".dir")
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
            CheckpointManager.updateCheckpoint(request.id, request.targetPath, 0, status = "FAILED")
            JobResult(id = request.id, success = false, message = e.localizedMessage ?: "Auto Replace IMG gagal")
        } finally {
            if (!isSuccess) {
                SafeFileWriter.cleanupFile(tmpFile)
                SafeFileWriter.cleanupFile(tmpDirFile)
            }
            lockHandle.release()
        }
    }
}
