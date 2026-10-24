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
            _uiState.update { it.copy(isLoading = true, statusMessage = "Membuka file TXD...") }
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
                        if (!ok) throw IOException("Gagal menyalin file dari Shizuku: $filePath")
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
                    throw IllegalArgumentException("Tidak ada path atau URI file TXD yang diberikan")
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
                        statusMessage = "Berhasil memuat ${parser.entries.size} tekstur"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Gagal memuat TXD: ${e.localizedMessage}"
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
                        statusMessage = "Tekstur diekspor ke ${destinationDir.name}/${entry.name}.png"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Gagal ekspor: ${e.localizedMessage}"
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
                        statusMessage = "Berhasil mengekspor $count tekstur ke ${destinationDir.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Gagal ekspor semua: ${e.localizedMessage}"
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
                        statusMessage = "Berhasil mengekspor $count tekstur ke ${destinationDir.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Gagal ekspor tekstur: ${e.localizedMessage}"
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
                        ?: throw IOException("Gagal membaca gambar: ${imageFile.name}")

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
                            throw IOException("Gagal menulis TXD kembali ke lokasi asal: $originalPath")
                        }
                    }
                }

                val reloaded = withContext(Dispatchers.IO) { TxdStore.load(txdFile).entries }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = reloaded,
                        selectedEntry = reloaded.getOrNull(targetEntry.sourceIndex) ?: targetEntry,
                        statusMessage = "Tekstur ${targetEntry.name} berhasil diganti!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Gagal mengganti tekstur: ${e.localizedMessage}"
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
            _uiState.update { it.copy(isSaving = true, statusMessage = "Memproses auto-replace ${matches.size} tekstur...") }
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
                                throw IOException("Gagal menulis TXD kembali ke lokasi asal: $originalPath")
                            }
                        }
                    }
                }

                val reloaded = withContext(Dispatchers.IO) { TxdStore.load(txdFile).entries }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = reloaded,
                        statusMessage = "Berhasil mengganti $count tekstur secara otomatis!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Gagal auto-replace: ${e.localizedMessage}"
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
            _uiState.update { it.copy(isSaving = true, statusMessage = "Mengimpor ${images.size} tekstur baru...") }
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
                        if (parentInfo != null) {
                            ArchiveRepository.updateEntryInArchive(
                                archiveFile = File(parentInfo.archivePath),
                                entryName = parentInfo.entryName,
                                newFile = txdFile,
                                shizukuDestPath = if (parentInfo.isShizuku) parentInfo.originalRestrictedPath else null
                            )
                        } else if (isShizuku && originalPath != null) {
                            if (!ShizukuManager.copyFromLocal(txdFile, originalPath)) {
                                throw IOException("Gagal menulis TXD kembali ke lokasi asal: $originalPath")
                            }
                        }
                    }
                    TxdStore.load(txdFile).entries
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = updatedEntries,
                        hasUnsavedChanges = true,
                        statusMessage = "Impor selesai: $added tekstur baru ditambahkan, $overwritten ditimpa"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Gagal mengimpor tekstur: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
