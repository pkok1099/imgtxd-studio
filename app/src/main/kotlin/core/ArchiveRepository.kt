package com.onlasdan.studio.core

import com.onlasdan.studio.util.AppIntegrity
import com.onlasdan.studio.util.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class ArchiveUpdateEvent(
    val archiveCanonicalPath: String,
    val entryName: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ArchiveRepository {

    private val pathMutexes = ConcurrentHashMap<String, Mutex>()

    private val _archiveUpdates = MutableSharedFlow<ArchiveUpdateEvent>(extraBufferCapacity = 32)
    val archiveUpdates: SharedFlow<ArchiveUpdateEvent> = _archiveUpdates.asSharedFlow()

    fun getMutex(path: String): Mutex {
        val key = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            path
        }
        return pathMutexes.computeIfAbsent(key) { Mutex() }
    }

    suspend fun <T> withLock(path: String, action: suspend () -> T): T {
        val mutex = getMutex(path)
        return mutex.withLock {
            action()
        }
    }

    /**
     * Safely updates an entry inside an IMG archive with mutex locking and notifies all listening windows.
     */
    suspend fun updateEntryInArchive(
        archiveFile: File,
        entryName: String,
        newFile: File,
        shizukuDestPath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        AppIntegrity.verifyOrThrow()
        val canonical = try { archiveFile.canonicalPath } catch (e: Exception) { archiveFile.absolutePath }
        val mutex = getMutex(canonical)

        mutex.withLock {
            try {
                val archive = ImgArchive.open(archiveFile)
                val targetEntry = archive.entries.find { it.cleanName.equals(entryName, ignoreCase = true) }
                    ?: return@withContext false

                archive.replace(targetEntry, newFile)

                if (shizukuDestPath != null && ShizukuManager.hasPermission()) {
                    if (!ShizukuManager.copyFromLocal(archive.imgFile, shizukuDestPath)) {
                        throw IOException("Failed to write archive back to game folder: $shizukuDestPath")
                    }
                }

                _archiveUpdates.emit(
                    ArchiveUpdateEvent(
                        archiveCanonicalPath = canonical,
                        entryName = entryName
                    )
                )
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
