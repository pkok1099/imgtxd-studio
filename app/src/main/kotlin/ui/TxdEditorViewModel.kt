package com.onlasdan.studio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onlasdan.studio.core.ArchiveRepository
import com.onlasdan.studio.txd.TxdEntry
import com.onlasdan.studio.txd.TxdStore
import com.onlasdan.studio.util.ParentArchiveInfo
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class TxdEditorUiState(
    val txdName: String = "",
    val txdEntries: List<TxdEntry> = emptyList(),
    val activeTxdFile: File? = null,
    val originalPath: String? = null,
    val isShizuku: Boolean = false,
    val parentArchiveInfo: ParentArchiveInfo? = null,
    val selectedEntry: TxdEntry? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val hasUnsavedChanges: Boolean = false
)

class TxdEditorViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(TxdEditorUiState())
    val uiState: StateFlow<TxdEditorUiState> = _uiState.asStateFlow()

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun loadTxd(
        context: Context,
        uri: Uri? = null,
        filePath: String? = null,
        isShizuku: Boolean = false,
        parentInfo: ParentArchiveInfo? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Opening TXD file...") }
            try {
                val txdFile: File
                val name: String
                val effectiveOriginalPath: String?

                if (filePath != null) {
                    effectiveOriginalPath = filePath
                    name = File(filePath).name
                    txdFile = if (isShizuku || ShizukuManager.isRestrictedPath(filePath)) {
                        val temp = File(context.cacheDir, "txd_win_" + System.currentTimeMillis() + "_" + name)
                        val ok = withContext(Dispatchers.IO) {
                            ShizukuManager.copyToLocal(filePath, temp)
                        }
                        if (!ok) throw IOException("Failed to copy file from Shizuku: $filePath")
                        temp
                    } else {
                        File(filePath)
                    }
                } else if (uri != null) {
                    val resolved = StorageHelper.resolveFileFromUri(context, uri)
                    if (resolved != null) {
                        txdFile = resolved
                        name = resolved.name
                        effectiveOriginalPath = resolved.absolutePath
                    } else {
                        val temp = withContext(Dispatchers.IO) {
                            StorageHelper.copyUriToTempFile(context, uri)
                        }
                        txdFile = temp
                        name = temp.name
                        effectiveOriginalPath = null
                    }
                } else {
                    throw IllegalArgumentException("No TXD path or URI given")
                }

                val parser = withContext(Dispatchers.IO) {
                    TxdStore.load(txdFile)
                }

                _uiState.update {
                    it.copy(
                        txdName = name,
                        txdEntries = parser.entries,
                        activeTxdFile = txdFile,
                        originalPath = effectiveOriginalPath,
                        isShizuku = isShizuku,
                        parentArchiveInfo = parentInfo,
                        selectedEntry = parser.entries.firstOrNull(),
                        isLoading = false,
                        hasUnsavedChanges = false,
                        statusMessage = "Loaded ${parser.entries.size} textures"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load TXD: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Explicit save action: ensures the active TXD is flushed to disk and synced to original/Shizuku destination,
     * resetting [TxdEditorUiState.hasUnsavedChanges] to false.
     */
    fun saveTxd() {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Saving TXD...") }
            try {
                withContext(Dispatchers.IO) {
                    syncTxdBack(txdFile, parentInfo, isShizuku, originalPath)
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        hasUnsavedChanges = false,
                        statusMessage = "TXD saved successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Save failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun exportSingleTexture(entry: TxdEntry, destinationDir: File) {
        val bitmap = entry.bitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
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
                        isSaving = false,
                        statusMessage = "Textures exported to ${destinationDir.name}/${entry.name}.png"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Export failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun exportAllTextures(destinationDir: File) {
        val list = _uiState.value.txdEntries
        if (list.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                var count = 0
                withContext(Dispatchers.IO) {
                    destinationDir.mkdirs()
                    for (entry in list) {
                        val bmp = entry.bitmap ?: continue
                        val targetFile = File(destinationDir, "${entry.name}.png")
                        targetFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        count++
                    }
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        statusMessage = "Exported $count textures to ${destinationDir.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Export all failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun exportSelectedTextures(entries: List<TxdEntry>, destinationDir: File) {
        if (entries.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                var count = 0
                withContext(Dispatchers.IO) {
                    destinationDir.mkdirs()
                    for (entry in entries) {
                        val bmp = entry.bitmap ?: continue
                        val targetFile = File(destinationDir, "${entry.name}.png")
                        targetFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        count++
                    }
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        statusMessage = "Exported $count textures to ${destinationDir.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Texture export failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun replaceTexture(
        context: Context,
        targetEntry: TxdEntry,
        imagePath: String,
        isImageShizuku: Boolean,
        options: com.onlasdan.studio.txd.TextureImportOptions = com.onlasdan.studio.txd.TextureImportOptions()
    ) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                withContext(Dispatchers.IO) {
                    val imageFile = if (isImageShizuku || ShizukuManager.isRestrictedPath(imagePath)) {
                        val tmp = File(context.cacheDir, "replace_img_tmp_" + File(imagePath).name)
                        ShizukuManager.copyToLocal(imagePath, tmp)
                        tmp
                    } else {
                        File(imagePath)
                    }

                    val newBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        ?: throw IOException("Failed to read image: ${imageFile.name}")

                    if (imageFile != File(imagePath)) {
                        imageFile.delete()
                    }

                    val newPixels = IntArray(newBitmap.width * newBitmap.height)
                    try {
                        newBitmap.getPixels(newPixels, 0, newBitmap.width, 0, 0, newBitmap.width, newBitmap.height)
                    } finally {
                        // Free native bitmap memory immediately (getPixels copied into JVM heap).
                        newBitmap.recycle()
                    }
                    TxdStore.replaceTextures(
                        txdFile,
                        listOf(targetEntry.sourceIndex),
                        listOf(Triple(newPixels, newBitmap.width, newBitmap.height)),
                        options = options
                    )

                    // If opened from parent IMG archive, update parent archive safely with Mutex
                    if (parentInfo != null) {
                        ArchiveRepository.updateEntryInArchive(
                            archiveFile = File(parentInfo.archivePath),
                            entryName = parentInfo.entryName,
                            newFile = txdFile,
                            shizukuDestPath = if (parentInfo.isShizuku) parentInfo.originalRestrictedPath else null
                        )
                    } else if (isShizuku && originalPath != null) {
                        if (!ShizukuManager.copyFromLocal(txdFile, originalPath)) {
                            throw IOException("Failed to write TXD back to its source: $originalPath")
                        }
                    }
                }

                val reloaded = withContext(Dispatchers.IO) { TxdStore.load(txdFile).entries }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = reloaded,
                        selectedEntry = reloaded.getOrNull(targetEntry.sourceIndex) ?: targetEntry,
                        statusMessage = "Texture ${targetEntry.name} replaced!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Texture replace failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun autoReplaceTextures(
        context: Context,
        matches: List<Pair<TxdEntry, String>>,
        isImageShizuku: Boolean,
        options: com.onlasdan.studio.txd.TextureImportOptions = com.onlasdan.studio.txd.TextureImportOptions()
    ) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Auto-replacing ${matches.size} textures...") }
            try {
                var count = 0
                withContext(Dispatchers.IO) {
                    val batchIndices = mutableListOf<Int>()
                    val batchImages = mutableListOf<Triple<IntArray, Int, Int>>()
                    for ((entry, path) in matches) {
                        val imageFile = if (isImageShizuku || ShizukuManager.isRestrictedPath(path)) {
                            val tmp = File(context.cacheDir, "autoreplace_tmp_" + File(path).name)
                            ShizukuManager.copyToLocal(path, tmp)
                            tmp
                        } else {
                            File(path)
                        }

                        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (imageFile != File(path)) {
                            imageFile.delete()
                        }

                        if (bmp != null) {
                            val px = IntArray(bmp.width * bmp.height)
                            try {
                                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                            } finally {
                                // Free native bitmap memory immediately.
                                bmp.recycle()
                            }
                            batchIndices.add(entry.sourceIndex)
                            batchImages.add(Triple(px, bmp.width, bmp.height))
                            count++
                        }
                    }

                    if (count > 0) {
                        TxdStore.replaceTextures(txdFile, batchIndices, batchImages, options = options)

                        if (parentInfo != null) {
                            ArchiveRepository.updateEntryInArchive(
                                archiveFile = File(parentInfo.archivePath),
                                entryName = parentInfo.entryName,
                                newFile = txdFile,
                                shizukuDestPath = if (parentInfo.isShizuku) parentInfo.originalRestrictedPath else null
                            )
                        } else if (isShizuku && originalPath != null) {
                            if (!ShizukuManager.copyFromLocal(txdFile, originalPath)) {
                                throw IOException("Failed to write TXD back to its source: $originalPath")
                            }
                        }
                    }
                }

                val reloaded = withContext(Dispatchers.IO) { TxdStore.load(txdFile).entries }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = reloaded,
                        statusMessage = "Auto-replaced $count textures!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Auto-replace failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Import Wizard "add" path: adds new textures from image files into this TXD.
     * Name conflicts follow [policy] (TXD Tool: Just add = suffix name, Overwrite = replace existing).
     */
    fun addTextures(
        context: Context,
        images: List<Pair<String, Boolean>>,
        policy: com.onlasdan.studio.txd.TextureNames.ConflictPolicy,
        options: com.onlasdan.studio.txd.TextureImportOptions = com.onlasdan.studio.txd.TextureImportOptions()
    ) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Importing ${images.size} new textures...") }
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
                        try {
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        } finally {
                            // Free native bitmap memory immediately.
                            bitmap.recycle()
                        }
                        names.add(File(imagePath).nameWithoutExtension)
                        batch.add(Triple(pixels, bitmap.width, bitmap.height))
                    }

                    if (batch.isNotEmpty()) {
                        val result = TxdStore.addTextures(txdFile, names, batch, policy, options)
                        added = result.added
                        overwritten = result.overwritten
                    }

                    if (added + overwritten > 0) {
                        syncTxdBack(txdFile, parentInfo, isShizuku, originalPath)
                    }
                    TxdStore.load(txdFile).entries
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = updatedEntries,
                        hasUnsavedChanges = true,
                        statusMessage = "Import done: $added new textures added, $overwritten overwritten"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Texture import failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Deletes textures at the given [indices] from the active TXD.
     */
    fun deleteTextures(indices: List<Int>) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Deleting ${indices.size} textures...") }
            try {
                val updatedEntries = withContext(Dispatchers.IO) {
                    val count = TxdStore.deleteTextures(txdFile, indices)
                    if (count > 0) {
                        syncTxdBack(txdFile, parentInfo, isShizuku, originalPath)
                    }
                    TxdStore.load(txdFile).entries
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = updatedEntries,
                        selectedEntry = null,
                        hasUnsavedChanges = true,
                        statusMessage = "${indices.size} texture(s) deleted"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Delete failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Renames the texture at [index] to [newName].
     */
    fun renameTexture(index: Int, newName: String) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Renaming texture...") }
            try {
                val updatedEntries = withContext(Dispatchers.IO) {
                    TxdStore.renameTexture(txdFile, index, newName)
                    syncTxdBack(txdFile, parentInfo, isShizuku, originalPath)
                    TxdStore.load(txdFile).entries
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = updatedEntries,
                        selectedEntry = updatedEntries.getOrNull(index),
                        hasUnsavedChanges = true,
                        statusMessage = "Texture renamed to '$newName'"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Rename failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Updates properties (maskName, filterFlags) of the texture at [index].
     */
    fun updateProperties(index: Int, maskName: String, filterFlags: Int) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Updating properties...") }
            try {
                val updatedEntries = withContext(Dispatchers.IO) {
                    TxdStore.updateProperties(txdFile, index, maskName, filterFlags)
                    syncTxdBack(txdFile, parentInfo, isShizuku, originalPath)
                    TxdStore.load(txdFile).entries
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = updatedEntries,
                        selectedEntry = updatedEntries.getOrNull(index),
                        hasUnsavedChanges = true,
                        statusMessage = "Texture properties updated"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Properties update failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Imports textures directly from another TXD archive into this active TXD.
     */
    fun importFromTxd(
        context: Context,
        sourcePath: String,
        isSourceShizuku: Boolean,
        policy: com.onlasdan.studio.txd.TextureNames.ConflictPolicy
    ) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Importing textures from TXD archive...") }
            try {
                var added = 0
                var overwritten = 0
                val updatedEntries = withContext(Dispatchers.IO) {
                    val sourceFile = if (isSourceShizuku || ShizukuManager.isRestrictedPath(sourcePath)) {
                        val tmp = File(context.cacheDir, "import_src_" + File(sourcePath).name)
                        ShizukuManager.copyToLocal(sourcePath, tmp)
                        tmp
                    } else {
                        File(sourcePath)
                    }

                    try {
                        val result = TxdStore.importFromTxd(txdFile, sourceFile, policy)
                        added = result.added
                        overwritten = result.overwritten
                    } finally {
                        if (sourceFile != File(sourcePath)) {
                            sourceFile.delete()
                        }
                    }

                    if (added + overwritten > 0) {
                        syncTxdBack(txdFile, parentInfo, isShizuku, originalPath)
                    }
                    TxdStore.load(txdFile).entries
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = updatedEntries,
                        hasUnsavedChanges = true,
                        statusMessage = "Import done: $added textures added, $overwritten overwritten"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Import from TXD failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private suspend fun syncTxdBack(
        txdFile: File,
        parentInfo: ParentArchiveInfo?,
        isShizuku: Boolean,
        originalPath: String?
    ) {
        if (parentInfo != null) {
            ArchiveRepository.updateEntryInArchive(
                archiveFile = File(parentInfo.archivePath),
                entryName = parentInfo.entryName,
                newFile = txdFile,
                shizukuDestPath = if (parentInfo.isShizuku) parentInfo.originalRestrictedPath else null
            )
        } else if (isShizuku && originalPath != null) {
            if (!ShizukuManager.copyFromLocal(txdFile, originalPath)) {
                throw IOException("Failed to write TXD back to its source: $originalPath")
            }
        }
    }
}
