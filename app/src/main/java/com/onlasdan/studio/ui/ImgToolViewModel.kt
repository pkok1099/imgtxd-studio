package com.onlasdan.studio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onlasdan.studio.core.ImgArchive
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.core.ImgVersion
import com.onlasdan.studio.process.ProcessRepositoryLocator
import com.onlasdan.studio.process.ProcessType
import com.onlasdan.studio.txd.TextureImportOptions
import com.onlasdan.studio.txd.TextureNames
import com.onlasdan.studio.txd.TxdEntry
import com.onlasdan.studio.txd.TxdStore
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.onlasdan.studio.core.ArchiveRepository
import com.onlasdan.studio.loader.LoaderClient
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.LauncherFileType
import com.onlasdan.studio.util.ParentArchiveInfo
import com.onlasdan.studio.util.WindowLauncher
import com.onlasdan.studio.util.WindowMode

data class ImgToolUiState(
    val archive: ImgArchive? = null,
    val entries: List<ImgEntry> = emptyList(),
    val filteredEntries: List<ImgEntry> = emptyList(),
    val displayedEntries: List<ImgEntry> = emptyList(),
    val searchQuery: String = "",
    val selectedEntries: Set<ImgEntry> = emptySet(),
    val isSelecting: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Pair<Int, Int>? = null, // current, total
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val hasStoragePermission: Boolean = false,
    val originalPath: String? = null,
    val isShizukuBacked: Boolean = false,
    val hasUnsavedChanges: Boolean = false,

    // TXD Studio States
    val isTxdViewerOpen: Boolean = false,
    val txdEntries: List<TxdEntry> = emptyList(),
    val activeTxdName: String? = null,
    val activeTxdSourceEntry: ImgEntry? = null,
    val activeTxdTempFile: File? = null,
    val activeTxdOriginalPath: String? = null,
    val isTxdShizuku: Boolean = false,
    val selectedTxdEntry: TxdEntry? = null,
    val isTxdLoading: Boolean = false,
    val hasTxdUnsavedChanges: Boolean = false,

    // Modloader (in-game plugin IPC) states
    val loaderConnected: Boolean? = null,
    val loaderBusy: Boolean = false,
    val loaderSelectedEntry: ImgEntry? = null,
    val loaderModelId: String = "",
    val loaderFeedback: String? = null
) {
    val hasMorePages: Boolean
        get() = displayedEntries.size < filteredEntries.size
}

class ImgToolViewModel : ViewModel() {

    companion object {
        const val PAGE_SIZE = 100
    }

    private val _uiState = MutableStateFlow(ImgToolUiState())
    val uiState: StateFlow<ImgToolUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            ArchiveRepository.archiveUpdates.collect { event ->
                val curArchive = _uiState.value.archive
                if (curArchive != null) {
                    val curCanonical = try {
                        curArchive.imgFile.canonicalPath
                    } catch (e: Exception) {
                        curArchive.imgFile.absolutePath
                    }
                    if (curCanonical == event.archiveCanonicalPath) {
                        try {
                            val refreshed = withContext(Dispatchers.IO) {
                                ImgArchive.open(curArchive.imgFile, curArchive.dirFile)
                            }
                            val allEntries = refreshed.entries
                            val query = _uiState.value.searchQuery
                            val filtered = filterList(allEntries, query)
                            val displayed = filtered.take(maxOf(PAGE_SIZE, _uiState.value.displayedEntries.size))
                            _uiState.update {
                                it.copy(
                                    archive = refreshed,
                                    entries = allEntries,
                                    filteredEntries = filtered,
                                    displayedEntries = displayed,
                                    statusMessage = "Arsip diperbarui (${event.entryName} diupdate)"
                                )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    fun updatePermissionStatus() {
        val hasPermission = StorageHelper.hasManageStoragePermission()
        _uiState.update { it.copy(hasStoragePermission = hasPermission) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun openArchive(imgFile: File, dirFile: File? = null, originalPath: String? = null, isShizuku: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Opening archive...") }
            try {
                val archive = withContext(Dispatchers.IO) {
                    ImgArchive.open(imgFile, dirFile)
                }
                val allEntries = archive.entries
                val query = _uiState.value.searchQuery
                val filtered = filterList(allEntries, query)
                val initialPage = filtered.take(PAGE_SIZE)

                _uiState.update {
                    it.copy(
                        archive = archive,
                        entries = allEntries,
                        filteredEntries = filtered,
                        displayedEntries = initialPage,
                        selectedEntries = emptySet(),
                        isSelecting = false,
                        isLoading = false,
                        originalPath = originalPath,
                        isShizukuBacked = isShizuku,
                        statusMessage = "Loaded ${archive.archiveName} (${archive.version}, ${allEntries.size} entries)"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to open archive: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun openArchiveFromPath(context: Context, path: String, isShizuku: Boolean) {
        viewModelScope.launch {
            val isRestricted = isShizuku || ShizukuManager.isRestrictedPath(path)
            if (isRestricted) {
                _uiState.update { it.copy(isLoading = true, statusMessage = "Mengimpor file via Shizuku...") }
                try {
                    val fileName = File(path).name
                    val cacheDir = File(context.cacheDir, "opened_archives").apply { mkdirs() }
                    val tempImgFile = File(cacheDir, fileName)

                    val copied = withContext(Dispatchers.IO) {
                        ShizukuManager.copyToLocal(path, tempImgFile)
                    }

                    if (!copied) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Gagal membaca file dari Shizuku: $path") }
                        return@launch
                    }

                    // Check for .dir companion file for VER1
                    var tempDirFile: File? = null
                    val dirCandidatePath = path.substringBeforeLast(".") + ".dir"
                    if (ShizukuManager.exists(dirCandidatePath)) {
                        val dirFileObj = File(cacheDir, File(dirCandidatePath).name)
                        if (ShizukuManager.copyToLocal(dirCandidatePath, dirFileObj)) {
                            tempDirFile = dirFileObj
                        }
                    }

                    openArchive(tempImgFile, tempDirFile, originalPath = path, isShizuku = true)
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Error Shizuku open: ${e.localizedMessage}") }
                }
            } else {
                openArchive(File(path), originalPath = path, isShizuku = false)
            }
        }
    }

    fun openFileFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Importing file from storage...") }
            try {
                val resolvedFile = StorageHelper.resolveFileFromUri(context, uri)
                    ?: withContext(Dispatchers.IO) {
                        StorageHelper.copyUriToTempFile(context, uri)
                    }

                if (resolvedFile.name.endsWith(".txd", ignoreCase = true)) {
                    _uiState.update { it.copy(isLoading = false) }
                    openStandaloneTxd(context, resolvedFile.absolutePath, isShizuku = false)
                } else {
                    openArchive(resolvedFile)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to read file from URI: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun openArchiveFromUri(context: Context, uri: Uri) = openFileFromUri(context, uri)

    /**
     * Loads the next chunk of entries for incremental lazy list rendering.
     */
    fun loadNextPage() {
        val currentDisplayed = _uiState.value.displayedEntries
        val allFiltered = _uiState.value.filteredEntries
        if (currentDisplayed.size >= allFiltered.size) return

        val nextEnd = minOf(currentDisplayed.size + PAGE_SIZE, allFiltered.size)
        val nextBatch = allFiltered.subList(currentDisplayed.size, nextEnd)
        _uiState.update { state ->
            state.copy(displayedEntries = currentDisplayed + nextBatch)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            if (query.isNotBlank()) {
                delay(120) // Debounce rapid typing
            }
            val filtered = filterList(_uiState.value.entries, query)
            val initialPage = filtered.take(PAGE_SIZE)
            _uiState.update {
                it.copy(
                    filteredEntries = filtered,
                    displayedEntries = initialPage
                )
            }
        }
    }

    private fun filterList(list: List<ImgEntry>, query: String): List<ImgEntry> {
        if (query.isBlank()) return list
        val q = query.trim()
        return list.filter { it.cleanName.contains(q, ignoreCase = true) }
    }

    private fun updateEntriesState(allEntries: List<ImgEntry>, status: String? = null) {
        val query = _uiState.value.searchQuery
        val filtered = filterList(allEntries, query)
        val displayed = filtered.take(maxOf(PAGE_SIZE, _uiState.value.displayedEntries.size))
        _uiState.update {
            it.copy(
                isLoading = false,
                hasUnsavedChanges = true,
                entries = allEntries,
                filteredEntries = filtered,
                displayedEntries = displayed,
                statusMessage = status ?: it.statusMessage
            )
        }
    }

    fun toggleSelection(entry: ImgEntry) {
        _uiState.update { state ->
            val updated = if (state.selectedEntries.contains(entry)) {
                state.selectedEntries - entry
            } else {
                state.selectedEntries + entry
            }
            state.copy(
                selectedEntries = updated,
                isSelecting = updated.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectedEntries = state.filteredEntries.toSet(),
                isSelecting = state.filteredEntries.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedEntries = emptySet(),
                isSelecting = false
            )
        }
    }

    fun extractEntry(entry: ImgEntry, destinationDir: File) {
        extractSingle(entry, destinationDir)
    }

    fun extractSingle(entry: ImgEntry, destinationDir: File, isShizuku: Boolean = false, destRestrictedPath: String? = null) {
        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            _uiState.update { it.copy(isLoading = true, statusMessage = "Extracting ${entry.cleanName}...") }
            try {
                withContext(Dispatchers.IO) {
                    if (isShizuku && destRestrictedPath != null) {
                        val tempFile = File.createTempFile("extract_", entry.cleanName)
                        archive.extract(entry, tempFile)
                        val remoteDest = "$destRestrictedPath/${entry.cleanName}"
                        ShizukuManager.copyFromLocal(tempFile, remoteDest)
                        tempFile.delete()
                    } else {
                        val target = File(destinationDir, entry.cleanName)
                        archive.extract(entry, target)
                    }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Extracted ${entry.cleanName} successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Extraction failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun extractSelected(destinationDir: File, isShizuku: Boolean = false, destRestrictedPath: String? = null) {
        val selected = _uiState.value.selectedEntries.toList()
        if (selected.isEmpty()) return
        val archive = _uiState.value.archive ?: return

        // Lepaskan seleksi segera setelah aksi dijalankan
        _uiState.update { it.copy(selectedEntries = emptySet(), isSelecting = false, isLoading = true) }

        viewModelScope.launch {
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.EXPORT_ALL,
                targetFile = archive.imgFile.absolutePath,
                title = "Mengekstrak ${selected.size} berkas"
            ) { onProgress ->
                try {
                    withContext(Dispatchers.IO) {
                        for ((idx, entry) in selected.withIndex()) {
                            onProgress(idx, selected.size, "Mengekstrak ${entry.cleanName} (${idx + 1}/${selected.size})")
                            if (isShizuku && destRestrictedPath != null) {
                                val tempFile = File.createTempFile("extract_", entry.cleanName)
                                archive.extract(entry, tempFile)
                                val remoteDest = "$destRestrictedPath/${entry.cleanName}"
                                ShizukuManager.copyFromLocal(tempFile, remoteDest)
                                tempFile.delete()
                            } else {
                                val target = File(destinationDir, entry.cleanName)
                                archive.extract(entry, target)
                            }
                            onProgress(idx + 1, selected.size, "Selesai ${entry.cleanName}")
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    progress = null,
                    selectedEntries = emptySet(),
                    isSelecting = false,
                    statusMessage = if (res.isSuccess) "Berhasil mengekstrak ${selected.size} berkas ke ${destinationDir.name}" else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun exportSelectedToImgArchive(targetImgFile: File, isShizuku: Boolean = false) {
        val selected = _uiState.value.selectedEntries.toList()
        if (selected.isEmpty()) return
        val archive = _uiState.value.archive ?: return

        _uiState.update { it.copy(selectedEntries = emptySet(), isSelecting = false, isLoading = true) }

        viewModelScope.launch {
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.ADD_ENTRY,
                targetFile = targetImgFile.absolutePath,
                title = "Mengekspor ${selected.size} berkas ke ${targetImgFile.name}"
            ) { onProgress ->
                try {
                    withContext(Dispatchers.IO) {
                        val targetArchive = ImgArchive.open(targetImgFile)
                        val cacheDir = File(targetImgFile.parentFile ?: File("/tmp"), ".export_cache").apply { mkdirs() }
                        try {
                            for ((idx, entry) in selected.withIndex()) {
                                onProgress(idx, selected.size, "Mengekstrak ${entry.cleanName} (${idx + 1}/${selected.size})")
                                val tempEntryFile = File(cacheDir, entry.cleanName)
                                archive.extract(entry, tempEntryFile)
                                onProgress(idx, selected.size, "Menambahkan ${entry.cleanName} ke ${targetImgFile.name}")
                                targetArchive.add(tempEntryFile, entry.cleanName)
                                tempEntryFile.delete()
                                onProgress(idx + 1, selected.size, "Selesai ${entry.cleanName}")
                            }
                        } finally {
                            cacheDir.deleteRecursively()
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    progress = null,
                    selectedEntries = emptySet(),
                    isSelecting = false,
                    statusMessage = if (res.isSuccess) "Berhasil mengekspor ${selected.size} berkas ke ${targetImgFile.name}" else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun extractAll(destinationDir: File, isShizuku: Boolean = false, destRestrictedPath: String? = null) {
        val archive = _uiState.value.archive ?: return
        val allEntries = archive.entries
        if (allEntries.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.EXPORT_ALL,
                targetFile = archive.imgFile.absolutePath,
                title = "Mengekstrak semua berkas"
            ) { onProgress ->
                try {
                    withContext(Dispatchers.IO) {
                        for ((idx, entry) in allEntries.withIndex()) {
                            onProgress(idx, allEntries.size, "Mengekstrak ${entry.cleanName} (${idx + 1}/${allEntries.size})")
                            if (isShizuku && destRestrictedPath != null) {
                                val tempFile = File.createTempFile("extract_", entry.cleanName)
                                archive.extract(entry, tempFile)
                                val remoteDest = "$destRestrictedPath/${entry.cleanName}"
                                ShizukuManager.copyFromLocal(tempFile, remoteDest)
                                tempFile.delete()
                            } else {
                                val target = File(destinationDir, entry.cleanName)
                                archive.extract(entry, target)
                            }
                            onProgress(idx + 1, allEntries.size, "Selesai ${entry.cleanName}")
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    progress = null,
                    statusMessage = if (res.isSuccess) "Berhasil mengekstrak ${allEntries.size} berkas ke ${destinationDir.name}" else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun addFileFromPath(context: Context, path: String, isShizuku: Boolean, customName: String? = null) {
        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            _uiState.update { it.copy(isLoading = true, statusMessage = "Adding file...") }
            try {
                withContext(Dispatchers.IO) {
                    val fileToUse = if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
                        val temp = File(context.cacheDir, "temp_add_" + File(path).name)
                        ShizukuManager.copyToLocal(path, temp)
                        temp
                    } else {
                        File(path)
                    }
                    archive.add(fileToUse, customName)
                    if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
                        fileToUse.delete()
                    }
                }
                val allEntries = archive.entries
                updateEntriesState(allEntries, status = "Added ${customName ?: File(path).name} successfully")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Add failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun replaceEntryFromPath(context: Context, entry: ImgEntry, path: String, isShizuku: Boolean) {
        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            _uiState.update { it.copy(isLoading = true, statusMessage = "Replacing ${entry.cleanName}...") }
            try {
                withContext(Dispatchers.IO) {
                    val fileToUse = if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
                        val temp = File(context.cacheDir, "temp_replace_" + File(path).name)
                        ShizukuManager.copyToLocal(path, temp)
                        temp
                    } else {
                        File(path)
                    }
                    archive.replace(entry, fileToUse)
                    if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
                        fileToUse.delete()
                    }
                }
                val allEntries = archive.entries
                updateEntriesState(allEntries, status = "Replaced ${entry.cleanName} successfully")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Replace failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun renameEntry(entry: ImgEntry, newName: String) {
        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            _uiState.update { it.copy(isLoading = true, statusMessage = "Renaming entry...") }
            try {
                withContext(Dispatchers.IO) {
                    archive.rename(entry, newName)
                }
                val allEntries = archive.entries
                updateEntriesState(allEntries, status = "Renamed to $newName")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Rename failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun deleteSelected() {
        val selected = _uiState.value.selectedEntries.toList()
        if (selected.isEmpty()) return

        // Lepaskan seleksi segera setelah aksi dijalankan
        _uiState.update { it.copy(selectedEntries = emptySet(), isSelecting = false, isLoading = true, statusMessage = "Deleting ${selected.size} items...") }

        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    archive.delete(selected)
                }
                val allEntries = archive.entries
                val query = _uiState.value.searchQuery
                val filtered = filterList(allEntries, query)
                val displayed = filtered.take(maxOf(PAGE_SIZE, _uiState.value.displayedEntries.size))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        entries = allEntries,
                        filteredEntries = filtered,
                        displayedEntries = displayed,
                        selectedEntries = emptySet(),
                        isSelecting = false,
                        statusMessage = "Deleted ${selected.size} entries"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Delete failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun rebuildArchive() {
        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            val originalPath = _uiState.value.originalPath
            val isShizukuBacked = _uiState.value.isShizukuBacked

            _uiState.update { it.copy(isLoading = true) }
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.REBUILD,
                targetFile = archive.archiveName,
                title = "Rebuild & rapikan arsip"
            ) { onProgress ->
                try {
                    withContext(Dispatchers.IO) {
                        archive.rebuild { current, total ->
                            onProgress(current, total, "Menyusun ulang sektor ($current / $total)")
                        }

                        if (isShizukuBacked && originalPath != null) {
                            onProgress(archive.entryCount, archive.entryCount, "Menyimpan ke folder game via Shizuku...")
                            ShizukuManager.copyFromLocal(archive.imgFile, originalPath)
                            if (archive.version == ImgVersion.VER1 && archive.dirFile != null) {
                                val dirOriginal = originalPath.substringBeforeLast(".") + ".dir"
                                ShizukuManager.copyFromLocal(archive.dirFile, dirOriginal)
                            }
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            val allEntries = archive.entries
            val query = _uiState.value.searchQuery
            val filtered = filterList(allEntries, query)
            val displayed = filtered.take(PAGE_SIZE)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    progress = null,
                    hasUnsavedChanges = false,
                    entries = allEntries,
                    filteredEntries = filtered,
                    displayedEntries = displayed,
                    statusMessage = if (res.isSuccess) {
                        if (isShizukuBacked)
                            "Arsip berhasil direbuild dan disimpan kembali ke game!"
                        else
                            "Arsip berhasil direbuild dan dirapikan!"
                    } else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun closeArchive() {
        _uiState.update {
            it.copy(
                archive = null,
                entries = emptyList(),
                filteredEntries = emptyList(),
                displayedEntries = emptyList(),
                selectedEntries = emptySet(),
                isSelecting = false,
                searchQuery = "",
                hasUnsavedChanges = false,
                originalPath = null,
                isShizukuBacked = false,
                statusMessage = "Arsip IMG ditutup."
            )
        }
    }

    // --- TXD Studio Integration ---

    fun openTxdFromEntry(context: Context, imgEntry: ImgEntry) {
        val archive = _uiState.value.archive ?: return
        val currentMode = AppSettings.cachedWindowMode
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Mengekstrak dan membaca ${imgEntry.cleanName}...") }
            try {
                val tempTxdFile = File(context.cacheDir, "opened_txd_" + imgEntry.cleanName)
                withContext(Dispatchers.IO) {
                    archive.extract(imgEntry, tempTxdFile)
                }

                if (currentMode != WindowMode.SINGLE_WINDOW) {
                    _uiState.update { it.copy(isLoading = false, statusMessage = null) }
                    val parentInfo = ParentArchiveInfo(
                        archivePath = archive.imgFile.absolutePath,
                        entryName = imgEntry.cleanName,
                        originalRestrictedPath = _uiState.value.originalPath,
                        isShizuku = _uiState.value.isShizukuBacked
                    )
                    WindowLauncher.open(
                        context = context,
                        filePath = tempTxdFile.absolutePath,
                        fileType = LauncherFileType.TXD,
                        parentArchiveInfo = parentInfo
                    )
                    return@launch
                }

                val parser = withContext(Dispatchers.IO) {
                    TxdStore.load(tempTxdFile)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isTxdViewerOpen = true,
                        txdEntries = parser.entries,
                        activeTxdName = imgEntry.cleanName,
                        activeTxdSourceEntry = imgEntry,
                        activeTxdTempFile = tempTxdFile,
                        activeTxdOriginalPath = null,
                        isTxdShizuku = false,
                        selectedTxdEntry = parser.entries.firstOrNull(),
                        statusMessage = "Berhasil memuat ${parser.entries.size} tekstur dari ${imgEntry.cleanName}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Gagal membaca TXD: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun openStandaloneTxd(context: Context, path: String, isShizuku: Boolean) {
        val currentMode = AppSettings.cachedWindowMode
        if (currentMode != WindowMode.SINGLE_WINDOW) {
            WindowLauncher.open(
                context = context,
                filePath = path,
                fileType = LauncherFileType.TXD,
                isShizuku = isShizuku
            )
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Membuka file TXD...") }
            try {
                val txdFile = if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
                    val temp = File(context.cacheDir, "opened_standalone_" + File(path).name)
                    val ok = withContext(Dispatchers.IO) {
                        ShizukuManager.copyToLocal(path, temp)
                    }
                    if (!ok) throw IOException("Gagal menyalin file dari Shizuku: $path")
                    temp
                } else {
                    File(path)
                }

                val parser = withContext(Dispatchers.IO) {
                    TxdStore.load(txdFile)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isTxdViewerOpen = true,
                        txdEntries = parser.entries,
                        activeTxdName = File(path).name,
                        activeTxdSourceEntry = null,
                        activeTxdTempFile = txdFile,
                        activeTxdOriginalPath = path,
                        isTxdShizuku = isShizuku,
                        selectedTxdEntry = parser.entries.firstOrNull(),
                        statusMessage = "Berhasil memuat ${parser.entries.size} tekstur dari ${File(path).name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Gagal membuka TXD: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun selectTxdEntry(entry: TxdEntry?) {
        _uiState.update { it.copy(selectedTxdEntry = entry) }
    }

    fun closeTxdViewer() {
        val temp = _uiState.value.activeTxdTempFile
        try {
            if (_uiState.value.activeTxdSourceEntry != null && temp != null && temp.exists()) {
                temp.delete()
            }
        } catch (ignored: Throwable) {}

        _uiState.update {
            it.copy(
                isTxdViewerOpen = false,
                txdEntries = emptyList(),
                activeTxdName = null,
                activeTxdSourceEntry = null,
                activeTxdTempFile = null,
                activeTxdOriginalPath = null,
                selectedTxdEntry = null
            )
        }
    }

    fun exportSingleTexture(entry: TxdEntry, destinationDir: File) {
        val bitmap = entry.bitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            try {
                withContext(Dispatchers.IO) {
                    destinationDir.mkdirs()
                    val targetFile = File(destinationDir, "${entry.name}.png")
                    targetFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        statusMessage = "Tekstur diekspor ke ${destinationDir.name}/${entry.name}.png"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        errorMessage = "Gagal ekspor tekstur: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun exportAllTextures(destinationDir: File) {
        val list = _uiState.value.txdEntries
        if (list.isEmpty()) return
        val targetName = _uiState.value.activeTxdName ?: "tekstur.txd"

        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.EXPORT_ALL,
                targetFile = targetName,
                title = "Ekspor Semua PNG ($targetName)"
            ) { onProgress ->
                try {
                    var exported = 0
                    withContext(Dispatchers.IO) {
                        destinationDir.mkdirs()
                        for ((idx, entry) in list.withIndex()) {
                            val bmp = entry.bitmap ?: continue
                            val targetFile = File(destinationDir, "${entry.name}.png")
                            targetFile.outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            exported++
                            onProgress(idx + 1, list.size, "Mengekspor ${entry.name}.png ($exported/${list.size})")
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            _uiState.update {
                it.copy(
                    isTxdLoading = false,
                    statusMessage = if (res.isSuccess) "Berhasil mengekspor ${list.size} tekstur ke ${destinationDir.name}" else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun exportSelectedTextures(entries: List<TxdEntry>, destinationDir: File) {
        if (entries.isEmpty()) return
        val targetName = _uiState.value.activeTxdName ?: "tekstur.txd"

        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.EXPORT_ALL,
                targetFile = targetName,
                title = "Ekspor ${entries.size} PNG ($targetName)"
            ) { onProgress ->
                try {
                    var exported = 0
                    withContext(Dispatchers.IO) {
                        destinationDir.mkdirs()
                        for ((idx, entry) in entries.withIndex()) {
                            val bmp = entry.bitmap ?: continue
                            val targetFile = File(destinationDir, "${entry.name}.png")
                            targetFile.outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            exported++
                            onProgress(idx + 1, entries.size, "Mengekspor ${entry.name}.png ($exported/${entries.size})")
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            _uiState.update {
                it.copy(
                    isTxdLoading = false,
                    statusMessage = if (res.isSuccess) "Berhasil mengekspor ${entries.size} tekstur ke ${destinationDir.name}" else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun replaceTexture(
        context: Context,
        targetEntry: TxdEntry,
        imagePath: String,
        isShizuku: Boolean,
        options: TextureImportOptions = TextureImportOptions()
    ) {
        val tempTxdFile = _uiState.value.activeTxdTempFile ?: return
        val sourceImgEntry = _uiState.value.activeTxdSourceEntry
        val originalPath = _uiState.value.activeTxdOriginalPath
        val isShizukuTxd = _uiState.value.isTxdShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            try {
                withContext(Dispatchers.IO) {
                    val imageFile = if (isShizuku || ShizukuManager.isRestrictedPath(imagePath)) {
                        val tmp = File(context.cacheDir, "replace_img_tmp_" + File(imagePath).name)
                        ShizukuManager.copyToLocal(imagePath, tmp)
                        tmp
                    } else {
                        File(imagePath)
                    }

                    val newBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        ?: throw IOException("Gagal membaca file gambar: ${imageFile.name}")

                    if (imageFile != File(imagePath)) {
                        imageFile.delete()
                    }

                    val newPixels = IntArray(newBitmap.width * newBitmap.height)
                    newBitmap.getPixels(newPixels, 0, newBitmap.width, 0, 0, newBitmap.width, newBitmap.height)
                    TxdStore.replaceTextures(
                        tempTxdFile,
                        listOf(targetEntry.sourceIndex),
                        listOf(Triple(newPixels, newBitmap.width, newBitmap.height)),
                        options = options
                    )

                    val archive = _uiState.value.archive
                    if (sourceImgEntry != null && archive != null) {
                        archive.replace(sourceImgEntry, tempTxdFile)

                        val imgOriginalPath = _uiState.value.originalPath
                        if (_uiState.value.isShizukuBacked && imgOriginalPath != null) {
                            ShizukuManager.copyFromLocal(archive.imgFile, imgOriginalPath)
                        }
                    }

                    if (isShizukuTxd && originalPath != null) {
                        ShizukuManager.copyFromLocal(tempTxdFile, originalPath)
                    }
                }

                val updatedList = withContext(Dispatchers.IO) { TxdStore.load(tempTxdFile).entries }
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        txdEntries = updatedList,
                        selectedTxdEntry = updatedList.getOrNull(targetEntry.sourceIndex) ?: targetEntry,
                        statusMessage = "Tekstur ${targetEntry.name} berhasil diganti!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        errorMessage = "Gagal mengganti tekstur: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun autoReplaceTextures(
        context: Context,
        matches: List<Pair<TxdEntry, String>>,
        isShizuku: Boolean,
        options: TextureImportOptions = TextureImportOptions()
    ) {
        val tempTxdFile = _uiState.value.activeTxdTempFile ?: return
        val sourceImgEntry = _uiState.value.activeTxdSourceEntry
        val originalPath = _uiState.value.activeTxdOriginalPath
        val isShizukuTxd = _uiState.value.isTxdShizuku
        val targetName = _uiState.value.activeTxdName ?: "tekstur.txd"

        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true, statusMessage = "Memproses auto-replace ${matches.size} tekstur...") }
            var replacedCount = 0
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.AUTO_REPLACE,
                targetFile = targetName,
                title = "Auto Replace ($targetName)"
            ) { onProgress ->
                try {
                    withContext(Dispatchers.IO) {
                        val batchIndices = mutableListOf<Int>()
                        val batchImages = mutableListOf<Triple<IntArray, Int, Int>>()
                        for ((idx, match) in matches.withIndex()) {
                            val (entry, imagePath) = match
                            val imageFile = if (isShizuku || ShizukuManager.isRestrictedPath(imagePath)) {
                                val tmp = File(context.cacheDir, "autoreplace_tmp_" + File(imagePath).name)
                                ShizukuManager.copyToLocal(imagePath, tmp)
                                tmp
                            } else {
                                File(imagePath)
                            }

                            val newBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                            if (imageFile != File(imagePath)) {
                                imageFile.delete()
                            }

                            if (newBitmap != null) {
                                val px = IntArray(newBitmap.width * newBitmap.height)
                                newBitmap.getPixels(px, 0, newBitmap.width, 0, 0, newBitmap.width, newBitmap.height)
                                batchIndices.add(entry.sourceIndex)
                                batchImages.add(Triple(px, newBitmap.width, newBitmap.height))
                                replacedCount++
                            }
                            onProgress(idx + 1, matches.size, "Mengganti ${entry.name} (${idx + 1}/${matches.size})")
                        }

                        if (replacedCount > 0) {
                            TxdStore.replaceTextures(tempTxdFile, batchIndices, batchImages, options = options)

                            val archive = _uiState.value.archive
                            if (sourceImgEntry != null && archive != null) {
                                archive.replace(sourceImgEntry, tempTxdFile)

                                val imgOriginalPath = _uiState.value.originalPath
                                if (_uiState.value.isShizukuBacked && imgOriginalPath != null) {
                                    ShizukuManager.copyFromLocal(archive.imgFile, imgOriginalPath)
                                }
                            }

                            if (isShizukuTxd && originalPath != null) {
                                ShizukuManager.copyFromLocal(tempTxdFile, originalPath)
                            }
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            val updatedList = withContext(Dispatchers.IO) { TxdStore.load(tempTxdFile).entries }
            _uiState.update {
                it.copy(
                    isTxdLoading = false,
                    txdEntries = updatedList,
                    hasTxdUnsavedChanges = true,
                    statusMessage = if (res.isSuccess) "Berhasil mengganti $replacedCount tekstur secara otomatis!" else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    /**
     * Import Wizard "add" path: adds new textures from image files into the active TXD.
     * Name conflicts follow [policy] (TXD Tool: Just add = suffix name, Overwrite = replace existing).
     */
    fun addTextures(
        context: Context,
        images: List<Pair<String, Boolean>>,
        policy: TextureNames.ConflictPolicy,
        options: TextureImportOptions = TextureImportOptions()
    ) {
        val tempTxdFile = _uiState.value.activeTxdTempFile ?: return
        val sourceImgEntry = _uiState.value.activeTxdSourceEntry
        val originalPath = _uiState.value.activeTxdOriginalPath
        val isShizukuTxd = _uiState.value.isTxdShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true, statusMessage = "Mengimpor ${images.size} tekstur baru...") }
            try {
                var added = 0
                var overwritten = 0
                val updatedEntries = withContext(Dispatchers.IO) {
                    val names = mutableListOf<String>()
                    val batch = mutableListOf<Triple<IntArray, Int, Int>>()
                    for ((imagePath, isImageShizuku) in images) {
                        val imageFile = if (isImageShizuku || ShizukuManager.isRestrictedPath(imagePath)) {
                            val tmp = File(context.cacheDir, "addtex_tmp_" + File(imagePath).name)
                            ShizukuManager.copyToLocal(imagePath, tmp)
                            tmp
                        } else {
                            File(imagePath)
                        }
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (imageFile != File(imagePath)) {
                            imageFile.delete()
                        }
                        if (bitmap == null) continue

                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        names.add(File(imagePath).nameWithoutExtension)
                        batch.add(Triple(pixels, bitmap.width, bitmap.height))
                    }

                    if (batch.isNotEmpty()) {
                        val result = TxdStore.addTextures(tempTxdFile, names, batch, policy, options)
                        added = result.added
                        overwritten = result.overwritten
                    }

                    if (added + overwritten > 0) {

                        val archive = _uiState.value.archive
                        if (sourceImgEntry != null && archive != null) {
                            archive.replace(sourceImgEntry, tempTxdFile)

                            val imgOriginalPath = _uiState.value.originalPath
                            if (_uiState.value.isShizukuBacked && imgOriginalPath != null) {
                                ShizukuManager.copyFromLocal(archive.imgFile, imgOriginalPath)
                            }
                        }

                        if (isShizukuTxd && originalPath != null) {
                            ShizukuManager.copyFromLocal(tempTxdFile, originalPath)
                        }
                    }
                    TxdStore.load(tempTxdFile).entries
                }

                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        txdEntries = updatedEntries,
                        hasTxdUnsavedChanges = true,
                        statusMessage = "Impor selesai: $added tekstur baru ditambahkan, $overwritten ditimpa"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        errorMessage = "Gagal mengimpor tekstur: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * IMG Tool "SA audio fixer" (parity item I13): regenerates the `.idx` companion
     * file for a modified `.osw` audio archive so the game indexes it correctly.
     */
    fun fixAudioIdx(context: Context, oswPath: String, isShizuku: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            try {
                val entryCount = withContext(Dispatchers.IO) {
                    if (isShizuku || ShizukuManager.isRestrictedPath(oswPath)) {
                        val tmp = File(context.cacheDir, "oswfix_tmp_" + File(oswPath).name)
                        if (!ShizukuManager.copyToLocal(oswPath, tmp)) {
                            throw IOException("Gagal menyalin file dari Shizuku: $oswPath")
                        }
                        val result = com.onlasdan.studio.audio.OswIdxFixer.fix(tmp)
                        if (!ShizukuManager.copyFromLocal(result.idxFile, oswPath + ".idx")) {
                            throw IOException("Gagal menulis .idx ke lokasi asal")
                        }
                        tmp.delete()
                        result.idxFile.delete()
                        result.entries.size
                    } else {
                        com.onlasdan.studio.audio.OswIdxFixer.fix(File(oswPath)).entries.size
                    }
                }
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        statusMessage = "IDX fixed successfully! $entryCount entri audio diindeks ulang untuk ${File(oswPath).name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        errorMessage = "Gagal memperbaiki .idx: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * TXD Tool "Export from Multiple TXDs": exports every texture of several TXD files
     * as PNG into [destinationDir], optionally in a subfolder named after each TXD.
     * A file that fails to parse is skipped and counted, the rest still export.
     */
    fun exportMultipleTxds(
        context: Context,
        txdFiles: List<Pair<String, Boolean>>,
        destinationDir: File,
        createSubfolders: Boolean
    ) {
        if (txdFiles.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            var exportedTextures = 0
            var failedFiles = 0
            val res = ProcessRepositoryLocator.get().executeProcess(
                type = ProcessType.EXPORT_ALL,
                targetFile = destinationDir.absolutePath,
                title = "Ekspor Multi-TXD (${txdFiles.size} file)"
            ) { onProgress ->
                try {
                    withContext(Dispatchers.IO) {
                        destinationDir.mkdirs()
                        for ((idx, pair) in txdFiles.withIndex()) {
                            val (path, isShizuku) = pair
                            val baseName = File(path).nameWithoutExtension
                            try {
                                val localFile = if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
                                    val tmp = File(context.cacheDir, "multitxd_tmp_" + File(path).name)
                                    if (!ShizukuManager.copyToLocal(path, tmp)) {
                                        throw IOException("Gagal menyalin file dari Shizuku: $path")
                                    }
                                    tmp
                                } else {
                                    File(path)
                                }
                                val parser = TxdStore.load(localFile)
                                val outDir = if (createSubfolders) File(destinationDir, baseName) else destinationDir
                                outDir.mkdirs()
                                for (entry in parser.entries) {
                                    val bmp = entry.bitmap ?: continue
                                    val targetFile = File(outDir, "${entry.name}.png")
                                    targetFile.outputStream().use { out ->
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    exportedTextures++
                                }
                                if (localFile.absolutePath != File(path).absolutePath) {
                                    localFile.delete()
                                }
                            } catch (e: Exception) {
                                failedFiles++
                            }
                            onProgress(idx + 1, txdFiles.size, "Mengekspor $baseName.txd (${idx + 1}/${txdFiles.size})")
                        }
                    }
                    if (exportedTextures == 0 && failedFiles > 0) {
                        Result.failure(IOException("Semua file TXD gagal diekspor"))
                    } else {
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            _uiState.update {
                it.copy(
                    isTxdLoading = false,
                    statusMessage = if (res.isSuccess) {
                        "Ekspor multi-TXD selesai: $exportedTextures tekstur dari ${txdFiles.size - failedFiles} file" +
                            if (failedFiles > 0) " ($failedFiles file gagal)" else ""
                    } else null,
                    errorMessage = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    // --- Modloader (in-game plugin IPC) ---

    fun setLoaderModelId(text: String) {
        _uiState.update {
            it.copy(loaderModelId = text.filter { ch -> ch.isDigit() }.take(5))
        }
    }

    fun selectLoaderEntry(entry: ImgEntry) {
        _uiState.update { it.copy(loaderSelectedEntry = entry, loaderFeedback = null) }
    }

    fun checkLoaderConnection() {
        if (_uiState.value.loaderBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(loaderBusy = true, loaderFeedback = null) }
            val ok = LoaderClient.ping()
            _uiState.update {
                it.copy(
                    loaderBusy = false,
                    loaderConnected = ok,
                    loaderFeedback = if (ok) {
                        "Terhubung ke plugin Modloader di dalam game."
                    } else {
                        "Game tidak terdeteksi. Pastikan GTA SA sedang berjalan dan plugin Modloader aktif."
                    }
                )
            }
        }
    }

    private fun validLoaderModelId(): Int? {
        val modelId = _uiState.value.loaderModelId.toIntOrNull() ?: return null
        return modelId.takeIf { it in 0..LoaderClient.MAX_MODEL_ID }
    }

    fun substituteLoaderEntry(context: Context) {
        val state = _uiState.value
        if (state.loaderBusy) return
        val archive = state.archive ?: return
        val entry = state.loaderSelectedEntry ?: run {
            _uiState.update { it.copy(loaderFeedback = "Pilih dulu berkas model (.dff/.txd) dari arsip.") }
            return
        }
        val modelId = validLoaderModelId() ?: run {
            _uiState.update {
                it.copy(loaderFeedback = "Isi Model ID yang valid (0–${LoaderClient.MAX_MODEL_ID}).")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loaderBusy = true, loaderFeedback = null) }
            val result = try {
                val bytes = withContext(Dispatchers.IO) {
                    val tmp = File.createTempFile("loader_", entry.cleanName, context.cacheDir)
                    try {
                        archive.extract(entry, tmp)
                        tmp.readBytes()
                    } finally {
                        tmp.delete()
                    }
                }
                LoaderClient.substitute(modelId, bytes)
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            loaderBusy = false,
                            loaderConnected = true,
                            loaderFeedback = "${entry.cleanName} terkirim sebagai model $modelId. " +
                                "Tekan Reload Model agar game memuat ulang modelnya.",
                            statusMessage = "Model $modelId dikirim ke game"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            loaderBusy = false,
                            loaderConnected = false,
                            loaderFeedback = e.localizedMessage,
                            errorMessage = e.localizedMessage
                        )
                    }
                }
            )
        }
    }

    fun reloadLoaderModel() {
        if (_uiState.value.loaderBusy) return
        val modelId = validLoaderModelId() ?: run {
            _uiState.update {
                it.copy(loaderFeedback = "Isi Model ID yang valid (0–${LoaderClient.MAX_MODEL_ID}).")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loaderBusy = true, loaderFeedback = null) }
            LoaderClient.reload(modelId).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            loaderBusy = false,
                            loaderConnected = true,
                            loaderFeedback = "Perintah muat ulang model $modelId terkirim ke game.",
                            statusMessage = "Model $modelId dimuat ulang di game"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            loaderBusy = false,
                            loaderConnected = false,
                            loaderFeedback = e.localizedMessage,
                            errorMessage = e.localizedMessage
                        )
                    }
                }
            )
        }
    }
}
