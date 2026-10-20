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
import com.onlasdan.studio.txd.TxdBuilder
import com.onlasdan.studio.txd.TxdEntry
import com.onlasdan.studio.txd.TxdParser
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

    // TXD Studio States
    val isTxdViewerOpen: Boolean = false,
    val txdEntries: List<TxdEntry> = emptyList(),
    val activeTxdName: String? = null,
    val activeTxdSourceEntry: ImgEntry? = null,
    val activeTxdTempFile: File? = null,
    val activeTxdOriginalPath: String? = null,
    val isTxdShizuku: Boolean = false,
    val selectedTxdEntry: TxdEntry? = null,
    val isTxdLoading: Boolean = false
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

    fun openArchiveFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Importing file from storage...") }
            try {
                val resolvedFile = StorageHelper.resolveFileFromUri(context, uri)
                    ?: withContext(Dispatchers.IO) {
                        StorageHelper.copyUriToTempFile(context, uri)
                    }

                openArchive(resolvedFile)
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

        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            _uiState.update { it.copy(isLoading = true, progress = Pair(0, selected.size), statusMessage = "Extracting ${selected.size} files...") }
            try {
                withContext(Dispatchers.IO) {
                    for ((idx, entry) in selected.withIndex()) {
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
                        _uiState.update { it.copy(progress = Pair(idx + 1, selected.size)) }
                    }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        progress = null,
                        selectedEntries = emptySet(),
                        isSelecting = false,
                        statusMessage = "Extracted ${selected.size} files successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        progress = null,
                        errorMessage = "Batch extraction failed: ${e.localizedMessage}"
                    )
                }
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

        viewModelScope.launch {
            val archive = _uiState.value.archive ?: return@launch
            _uiState.update { it.copy(isLoading = true, statusMessage = "Deleting ${selected.size} items...") }
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

            _uiState.update { it.copy(isLoading = true, progress = Pair(0, archive.entryCount), statusMessage = "Rebuilding archive...") }
            try {
                withContext(Dispatchers.IO) {
                    archive.rebuild { current, total ->
                        _uiState.update { it.copy(progress = Pair(current, total)) }
                    }

                    // If file was opened from Shizuku (Android/data), write changes back to original file
                    if (isShizukuBacked && originalPath != null) {
                        _uiState.update { it.copy(statusMessage = "Menyimpan kembali ke direktori game via Shizuku...") }
                        ShizukuManager.copyFromLocal(archive.imgFile, originalPath)
                        if (archive.version == ImgVersion.VER1 && archive.dirFile != null) {
                            val dirOriginal = originalPath.substringBeforeLast(".") + ".dir"
                            ShizukuManager.copyFromLocal(archive.dirFile, dirOriginal)
                        }
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
                        entries = allEntries,
                        filteredEntries = filtered,
                        displayedEntries = displayed,
                        statusMessage = if (isShizukuBacked)
                            "Archive berhasil direbuild dan disimpan kembali ke game!"
                        else
                            "Archive rebuilt and compacted successfully!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        progress = null,
                        errorMessage = "Rebuild failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // --- TXD Studio Integration ---

    fun openTxdFromEntry(context: Context, imgEntry: ImgEntry) {
        val archive = _uiState.value.archive ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Mengekstrak dan membaca ${imgEntry.cleanName}...") }
            try {
                val tempTxdFile = File(context.cacheDir, "opened_txd_" + imgEntry.cleanName)
                withContext(Dispatchers.IO) {
                    archive.extract(imgEntry, tempTxdFile)
                }

                val parser = withContext(Dispatchers.IO) {
                    TxdParser.parse(tempTxdFile)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isTxdViewerOpen = true,
                        txdEntries = parser.textures,
                        activeTxdName = imgEntry.cleanName,
                        activeTxdSourceEntry = imgEntry,
                        activeTxdTempFile = tempTxdFile,
                        activeTxdOriginalPath = null,
                        isTxdShizuku = false,
                        selectedTxdEntry = parser.textures.firstOrNull(),
                        statusMessage = "Berhasil memuat ${parser.textures.size} tekstur dari ${imgEntry.cleanName}"
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
                    TxdParser.parse(txdFile)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isTxdViewerOpen = true,
                        txdEntries = parser.textures,
                        activeTxdName = File(path).name,
                        activeTxdSourceEntry = null,
                        activeTxdTempFile = txdFile,
                        activeTxdOriginalPath = path,
                        isTxdShizuku = isShizuku,
                        selectedTxdEntry = parser.textures.firstOrNull(),
                        statusMessage = "Berhasil memuat ${parser.textures.size} tekstur dari ${File(path).name}"
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

        viewModelScope.launch {
            _uiState.update { it.copy(isTxdLoading = true) }
            try {
                var exported = 0
                withContext(Dispatchers.IO) {
                    destinationDir.mkdirs()
                    for (entry in list) {
                        val bmp = entry.bitmap ?: continue
                        val targetFile = File(destinationDir, "${entry.name}.png")
                        targetFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        exported++
                    }
                }
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        statusMessage = "Berhasil mengekspor $exported tekstur ke ${destinationDir.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        errorMessage = "Gagal ekspor semua tekstur: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun replaceTexture(context: Context, targetEntry: TxdEntry, imagePath: String, isShizuku: Boolean) {
        val tempTxdFile = _uiState.value.activeTxdTempFile ?: return
        val currentEntries = _uiState.value.txdEntries
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

                    TxdBuilder.replaceTexture(targetEntry, newBitmap)
                    TxdBuilder.buildToFile(currentEntries, tempTxdFile)

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

                _uiState.update {
                    it.copy(
                        isTxdLoading = false,
                        selectedTxdEntry = targetEntry,
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
}
