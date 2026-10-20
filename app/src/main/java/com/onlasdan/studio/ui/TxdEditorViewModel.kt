package com.onlasdan.studio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onlasdan.studio.core.ArchiveRepository
import com.onlasdan.studio.txd.TxdBuilder
import com.onlasdan.studio.txd.TxdEntry
import com.onlasdan.studio.txd.TxdParser
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
                    TxdParser.parse(txdFile)
                }

                _uiState.update {
                    it.copy(
                        txdName = name,
                        txdEntries = parser.textures,
                        activeTxdFile = txdFile,
                        originalPath = effectiveOriginalPath,
                        isShizuku = isShizuku,
                        parentArchiveInfo = parentInfo,
                        selectedEntry = parser.textures.firstOrNull(),
                        isLoading = false,
                        hasUnsavedChanges = false,
                        statusMessage = "Berhasil memuat ${parser.textures.size} tekstur"
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

    fun replaceTexture(context: Context, targetEntry: TxdEntry, imagePath: String, isImageShizuku: Boolean) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val currentEntries = _uiState.value.txdEntries
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

                    TxdBuilder.replaceTexture(targetEntry, newBitmap)
                    TxdBuilder.buildToFile(currentEntries, txdFile)

                    // If opened from parent IMG archive, update parent archive safely with Mutex
                    if (parentInfo != null) {
                        ArchiveRepository.updateEntryInArchive(
                            archiveFile = File(parentInfo.archivePath),
                            entryName = parentInfo.entryName,
                            newFile = txdFile,
                            shizukuDestPath = if (parentInfo.isShizuku) parentInfo.originalRestrictedPath else null
                        )
                    } else if (isShizuku && originalPath != null) {
                        ShizukuManager.copyFromLocal(txdFile, originalPath)
                    }
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = currentEntries.toList(),
                        selectedEntry = targetEntry,
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

    fun autoReplaceTextures(context: Context, matches: List<Pair<TxdEntry, String>>, isImageShizuku: Boolean) {
        val txdFile = _uiState.value.activeTxdFile ?: return
        val currentEntries = _uiState.value.txdEntries
        val parentInfo = _uiState.value.parentArchiveInfo
        val originalPath = _uiState.value.originalPath
        val isShizuku = _uiState.value.isShizuku

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Memproses auto-replace ${matches.size} tekstur...") }
            try {
                var count = 0
                withContext(Dispatchers.IO) {
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
                            TxdBuilder.replaceTexture(entry, bmp)
                            count++
                        }
                    }

                    if (count > 0) {
                        TxdBuilder.buildToFile(currentEntries, txdFile)

                        if (parentInfo != null) {
                            ArchiveRepository.updateEntryInArchive(
                                archiveFile = File(parentInfo.archivePath),
                                entryName = parentInfo.entryName,
                                newFile = txdFile,
                                shizukuDestPath = if (parentInfo.isShizuku) parentInfo.originalRestrictedPath else null
                            )
                        } else if (isShizuku && originalPath != null) {
                            ShizukuManager.copyFromLocal(txdFile, originalPath)
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        txdEntries = currentEntries.toList(),
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
}
