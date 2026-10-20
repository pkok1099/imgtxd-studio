package com.onlasdan.studio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.DefaultProcessRepository
import com.onlasdan.studio.process.ProcessRepositoryLocator
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.txd.TxdEntry
import com.onlasdan.studio.ui.framework.ArchiveEntryCard
import com.onlasdan.studio.ui.framework.ArchiveScreen
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ContextualPillDock
import com.onlasdan.studio.ui.settings.RuntimeSettingsSheet
import com.onlasdan.studio.ui.sheets.AutoReplaceConfirmationSheet
import com.onlasdan.studio.ui.sheets.MoreActionsBottomSheet
import com.onlasdan.studio.ui.sheets.ProcessDetailBottomSheet
import com.onlasdan.studio.ui.sheets.ReplacementThumbnail
import com.onlasdan.studio.ui.sheets.TextureDetailBottomSheet
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.TxdViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxdEditorScreen(
    txdName: String,
    textures: List<TxdEntry>,
    isLoading: Boolean,
    statusMessage: String? = null,
    errorMessage: String? = null,
    onClose: () -> Unit,
    onExportSingle: (entry: TxdEntry, destDir: File) -> Unit,
    onExportAll: (destDir: File) -> Unit,
    onExportSelected: ((entries: List<TxdEntry>, destDir: File) -> Unit)? = null,
    onReplaceTexture: (entry: TxdEntry, imagePath: String, isShizuku: Boolean) -> Unit,
    onAutoReplace: (matches: List<Pair<TxdEntry, String>>, isShizuku: Boolean) -> Unit,
    onClearMessages: (() -> Unit)? = null,
    showDock: Boolean = true,
    hasUnsavedChanges: Boolean = false,
    activeProcess: AppProcess? = null,
    onSaveTxd: (() -> Unit)? = null,
    onProcessClick: (() -> Unit)? = null,
    onNavigateHome: (() -> Unit)? = null,
    onOpenImg: (() -> Unit)? = null,
    onOpenWindowMode: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val displayedTextures = remember(textures, searchQuery) {
        if (searchQuery.isBlank()) textures
        else textures.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    LaunchedEffect(statusMessage) {
        if (!statusMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusMessage)
            onClearMessages?.invoke()
        }
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(errorMessage)
            onClearMessages?.invoke()
        }
    }

    // Process tracking
    val processes by ProcessRepositoryLocator.get(context).processes.collectAsState()
    val activeProcess = processes.find {
        (it.targetFile == txdName || it.targetFile.isEmpty()) &&
                (it.status == ProcessStatus.RUNNING || it.status == ProcessStatus.QUEUED)
    } ?: processes.firstOrNull { it.status == ProcessStatus.RUNNING }

    // Multi-Selection State
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedTextures by remember { mutableStateOf(setOf<TxdEntry>()) }

    // TXD View Mode (Gambar Besar vs Gambar Kecil)
    val initialViewMode = remember { AppSettings.cachedTxdViewMode }
    var txdViewMode by rememberSaveable { mutableStateOf(initialViewMode) }
    val toggleViewMode = {
        val nextMode = if (txdViewMode == TxdViewMode.LARGE) TxdViewMode.SMALL else TxdViewMode.LARGE
        txdViewMode = nextMode
        coroutineScope.launch {
            AppSettings.setTxdViewMode(context, nextMode)
        }
    }

    BackHandler(enabled = isSearching || isSelectionMode) {
        when {
            isSearching -> {
                isSearching = false
                searchQuery = ""
            }
            isSelectionMode -> {
                isSelectionMode = false
                selectedTextures = emptySet()
            }
        }
    }

    // Sheets & Dialogs State
    var selectedTextureForDetail by remember { mutableStateOf<TxdEntry?>(null) }
    var showMoreActionsSheet by rememberSaveable { mutableStateOf(false) }
    var showRuntimeSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var selectedProcessForDetail by remember { mutableStateOf<AppProcess?>(null) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    // Action Targets for Dialogs
    var entryToReplace by remember { mutableStateOf<TxdEntry?>(null) }
    var singleReplaceCandidate by remember { mutableStateOf<Triple<TxdEntry, String, Boolean>?>(null) }
    var entryToExport by remember { mutableStateOf<TxdEntry?>(null) }
    var showExportAllPicker by remember { mutableStateOf(false) }
    var showExportSelectedPicker by remember { mutableStateOf(false) }

    // Auto Replace State
    var showAutoReplaceModeChoice by rememberSaveable { mutableStateOf(false) }
    var showAutoReplaceFolderPicker by remember { mutableStateOf(false) }
    var showAutoReplaceMultiFilePicker by remember { mutableStateOf(false) }
    var autoReplaceMatches by remember { mutableStateOf<List<Pair<TxdEntry, String>>?>(null) }
    var isAutoReplaceShizuku by remember { mutableStateOf(false) }
    var showNoMatchesDialog by remember { mutableStateOf(false) }
    var noMatchesSourceMessage by remember { mutableStateOf("") }
    var isScanningFolder by remember { mutableStateOf(false) }

    fun processAutoReplaceFiles(filePaths: List<String>, isShizuku: Boolean) {
        val textureMap = textures.associateBy { it.name.lowercase() }
        val matches = mutableListOf<Pair<TxdEntry, String>>()

        for (path in filePaths) {
            val baseName = File(path).nameWithoutExtension.lowercase()
            val matchedTex = textureMap[baseName]
            if (matchedTex != null) {
                matches.add(matchedTex to path)
            }
        }

        isAutoReplaceShizuku = isShizuku
        if (matches.isEmpty()) {
            noMatchesSourceMessage = "Tidak ditemukan gambar yang namanya cocok dengan tekstur di TXD ini (${filePaths.size} file diperiksa)."
            showNoMatchesDialog = true
        } else {
            autoReplaceMatches = matches
        }
    }

    fun scanFolderForAutoReplace(folderPath: String, isShizuku: Boolean) {
        coroutineScope.launch {
            isScanningFolder = true
            try {
                val imageFiles = withContext(Dispatchers.IO) {
                    val validExts = setOf("png", "jpg", "jpeg", "bmp", "webp")
                    val files = if (isShizuku || ShizukuManager.isRestrictedPath(folderPath)) {
                        ShizukuManager.listFiles(folderPath).map { it.path }
                    } else {
                        File(folderPath).listFiles()?.map { it.absolutePath } ?: emptyList()
                    }
                    files.filter { path ->
                        val ext = File(path).extension.lowercase()
                        ext in validExts
                    }
                }
                processAutoReplaceFiles(imageFiles, isShizuku)
            } catch (e: Exception) {
                noMatchesSourceMessage = "Gagal memindai folder: ${e.localizedMessage}"
                showNoMatchesDialog = true
            } finally {
                isScanningFolder = false
            }
        }
    }

    // Dock Items
    val txdSelectionDockItems = listOf(
        ContextualDockItem(
            id = "export_selected",
            icon = Icons.Outlined.FileDownload,
            contentDescription = "Ekspor tekstur terpilih",
            onClick = { showExportSelectedPicker = true }
        ),
        ContextualDockItem(
            id = "select_all",
            icon = Icons.Outlined.SelectAll,
            contentDescription = "Pilih semua tekstur",
            onClick = { selectedTextures = textures.toSet() }
        ),
        ContextualDockItem(
            id = "cancel_selection",
            icon = Icons.Outlined.Close,
            contentDescription = "Batal seleksi",
            onClick = {
                selectedTextures = emptySet()
                isSelectionMode = false
            }
        )
    )

    val txdStandardDockItems = listOf(
        ContextualDockItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Beranda",
            onClick = {
                if (onNavigateHome != null) {
                    onNavigateHome()
                } else {
                    onClose()
                }
            }
        ),
        ContextualDockItem(
            id = "search",
            icon = Icons.Outlined.Search,
            contentDescription = "Cari tekstur",
            isSelected = isSearching,
            onClick = { isSearching = !isSearching }
        ),
        ContextualDockItem(
            id = "auto_replace",
            icon = Icons.Outlined.AutoFixHigh,
            contentDescription = "Auto Replace Tekstur",
            onClick = { showAutoReplaceModeChoice = true }
        ),
        ContextualDockItem(
            id = "save",
            icon = Icons.Outlined.Save,
            contentDescription = "Simpan Tekstur",
            showDotBadge = hasUnsavedChanges,
            isEnabled = hasUnsavedChanges,
            onClick = { onSaveTxd?.invoke() }
        ),
        ContextualDockItem(
            id = "more",
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = "Opsi lainnya",
            onClick = { showMoreActionsSheet = true }
        )
    )

    // Single Framework Composable: ArchiveScreen
    ArchiveScreen(
        title = txdName.ifBlank { "Tekstur TXD" },
        subtitle = if (textures.isEmpty()) "0 tekstur" else "${textures.size} tekstur RenderWare",
        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
        onShizukuClick = {
            if (!ShizukuManager.isShizukuAvailable()) {
                snackbarHostState.currentSnackbarData?.dismiss()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Shizuku service tidak terdeteksi.")
                }
            } else if (!ShizukuManager.hasPermission()) {
                ShizukuManager.requestPermission()
            }
        },
        items = displayedTextures,
        keyExtractor = { "${it.name}_${it.width}_${it.height}" },
        emptyTitle = "Tidak ada tekstur",
        emptyDescription = if (isLoading) "Memuat tekstur..." else "Tidak ada entri tekstur di dalam file TXD ini.",
        isSearching = isSearching,
        searchQuery = searchQuery,
        searchPlaceholder = "Cari tekstur, misal cj_face...",
        onSearchQueryChange = { searchQuery = it },
        onCloseSearch = {
            isSearching = false
            searchQuery = ""
        },
        activeProcess = activeProcess,
        onProcessPillClick = {
            activeProcess?.let { selectedProcessForDetail = it }
        },
        gridColumns = if (txdViewMode == TxdViewMode.LARGE) 2 else 1,
        headerTrailingContent = {
            IconButton(
                onClick = { toggleViewMode() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (txdViewMode == TxdViewMode.LARGE)
                        Icons.Outlined.ViewCompact
                    else
                        Icons.Outlined.PhotoSizeSelectActual,
                    contentDescription = if (txdViewMode == TxdViewMode.LARGE)
                        "Ubah ke Mode Gambar Kecil"
                    else
                        "Ubah ke Mode Gambar Besar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        itemContent = { entry ->
            ArchiveEntryCard(
                title = entry.name,
                subtitle = if (txdViewMode == TxdViewMode.LARGE)
                    entry.dimensionsLabel
                else
                    "${entry.dimensionsLabel} • ${entry.formatLabel} • ${entry.depth}-bit",
                bitmap = entry.bitmap,
                isSelected = selectedTextures.contains(entry),
                isSelectionMode = isSelectionMode,
                isLargeImage = (txdViewMode == TxdViewMode.LARGE),
                onClick = {
                    if (isSelectionMode) {
                        selectedTextures = if (selectedTextures.contains(entry))
                            selectedTextures - entry
                        else
                            selectedTextures + entry
                    } else {
                        selectedTextureForDetail = entry
                    }
                },
                onLongClick = {
                    isSelectionMode = true
                    selectedTextures = selectedTextures + entry
                }
            )
        },
        dockContent = {
            if (showDock) {
                ContextualPillDock(
                    items = if (isSelectionMode) txdSelectionDockItems else txdStandardDockItems
                )
            }
        },
        snackbarHostState = snackbarHostState
    )

    // --- Floating Sheets & Sub-Dialogs ---

    // 1. Texture Detail Bottom Sheet (Tap Entri TXD: Pratinjau Besar + Ganti + Ekspor)
    selectedTextureForDetail?.let { entry ->
        TextureDetailBottomSheet(
            entry = entry,
            onDismissRequest = { selectedTextureForDetail = null },
            onReplaceTexture = { entryToReplace = entry },
            onExportTexture = { entryToExport = entry }
        )
    }

    // 2. Sheet "Lainnya"
    if (showMoreActionsSheet) {
        MoreActionsBottomSheet(
            onDismissRequest = { showMoreActionsSheet = false },
            fileName = txdName,
            isTxd = true,
            isFileBusy = ProcessRepositoryLocator.get(context).hasRunningProcess(txdName),
            isLargeImage = (txdViewMode == TxdViewMode.LARGE),
            onToggleTxdViewMode = { toggleViewMode() },
            onExportAll = { showExportAllPicker = true },
            onOpenWindowMode = { onOpenWindowMode?.invoke() },
            onOpenSettings = {
                if (onOpenSettings != null) onOpenSettings()
                else showSettingsDialog = true
            },
            onOpenRuntimeSettings = {
                showMoreActionsSheet = false
                showRuntimeSettingsSheet = true
            },
            onOpenAbout = { onOpenAbout?.invoke() },
            onCloseFile = { onClose() },
            onOpenShizuku = {
                if (!ShizukuManager.hasPermission()) {
                    ShizukuManager.requestPermission()
                }
            }
        )
    }

    // 2b. Runtime Settings Bottom Sheet
    if (showRuntimeSettingsSheet) {
        RuntimeSettingsSheet(
            onDismissRequest = { showRuntimeSettingsSheet = false }
        )
    }

    // 3. Process Detail Bottom Sheet
    selectedProcessForDetail?.let { proc ->
        ProcessDetailBottomSheet(
            process = proc,
            onDismissRequest = { selectedProcessForDetail = null },
            onCancelProcess = { id -> ProcessRepositoryLocator.get(context).cancelProcess(id) }
        )
    }

    // 4. File Picker for Export Single
    entryToExport?.let { target ->
        FilePickerDialog(
            title = "Pilih Folder Ekspor untuk ${target.name}.png",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { entryToExport = null },
            onFileSelected = { folderPath, _ ->
                val targetDir = File(folderPath)
                onExportSingle(target, targetDir)
                entryToExport = null
            }
        )
    }

    // 5. File Picker for Export All
    if (showExportAllPicker) {
        FilePickerDialog(
            title = "Pilih Folder untuk Ekspor Semua Tekstur",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showExportAllPicker = false },
            onFileSelected = { folderPath, _ ->
                showExportAllPicker = false
                val targetDir = File(folderPath)
                onExportAll(targetDir)
            }
        )
    }

    // 5b. File Picker for Export Selected
    if (showExportSelectedPicker) {
        val targets = selectedTextures.toList()
        FilePickerDialog(
            title = "Pilih Folder Ekspor (${targets.size} Tekstur Terpilih)",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showExportSelectedPicker = false },
            onFileSelected = { folderPath, _ ->
                showExportSelectedPicker = false
                val targetDir = File(folderPath)
                if (onExportSelected != null) {
                    onExportSelected(targets, targetDir)
                } else {
                    for (entry in targets) {
                        onExportSingle(entry, targetDir)
                    }
                }
                // Lepaskan seleksi setelah aksi dijalankan
                selectedTextures = emptySet()
                isSelectionMode = false
            }
        )
    }

    // 6. File Picker for Replace Single Texture
    entryToReplace?.let { target ->
        FilePickerDialog(
            title = "Pilih Gambar Pengganti untuk ${target.name}",
            mode = PickerMode.PICK_IMAGE,
            onDismissRequest = { entryToReplace = null },
            onFileSelected = { imgPath, isShizuku ->
                val targetEntry = entryToReplace
                entryToReplace = null
                if (targetEntry != null) {
                    singleReplaceCandidate = Triple(targetEntry, imgPath, isShizuku)
                }
            }
        )
    }

    // 6b. Single Replace Thumbnail Comparison Dialog
    singleReplaceCandidate?.let { (target, imgPath, isShizuku) ->
        val replacementFile = File(imgPath)
        AlertDialog(
            onDismissRequest = { singleReplaceCandidate = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Konfirmasi Ganti Tekstur",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Bandingkan tekstur asli dengan gambar baru sebelum menerapkan perubahan:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Tekstur Asli
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val bmp = target.bitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = target.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                Text(
                                    text = target.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = target.dimensionsLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Arrow
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )

                            // Gambar Baru
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                ReplacementThumbnail(
                                    path = imgPath,
                                    modifier = Modifier.size(60.dp)
                                )
                                Text(
                                    text = replacementFile.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Gambar Mod",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        singleReplaceCandidate = null
                        onReplaceTexture(target, imgPath, isShizuku)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Ganti Tekstur", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { singleReplaceCandidate = null },
                    shape = CircleShape
                ) {
                    Text("Batal")
                }
            }
        )
    }

    // 7. Auto Replace Mode Chooser
    if (showAutoReplaceModeChoice) {
        AutoReplaceBottomSheet(
            onDismissRequest = { showAutoReplaceModeChoice = false },
            onSelectFolder = { showAutoReplaceFolderPicker = true },
            onSelectMultiFiles = { showAutoReplaceMultiFilePicker = true }
        )
    }

    // 8. Auto Replace Folder Picker
    if (showAutoReplaceFolderPicker) {
        FilePickerDialog(
            title = "Pilih Folder Sumber Gambar Mod",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showAutoReplaceFolderPicker = false },
            onFileSelected = { folderPath, isShizuku ->
                showAutoReplaceFolderPicker = false
                scanFolderForAutoReplace(folderPath, isShizuku)
            }
        )
    }

    // 9. Auto Replace Multi-File Picker
    if (showAutoReplaceMultiFilePicker) {
        FilePickerDialog(
            title = "Pilih File Gambar Pengganti",
            mode = PickerMode.PICK_MULTIPLE_IMAGES,
            onDismissRequest = { showAutoReplaceMultiFilePicker = false },
            onMultipleFilesSelected = { filePaths, isShizuku ->
                showAutoReplaceMultiFilePicker = false
                processAutoReplaceFiles(filePaths, isShizuku)
            }
        )
    }

    // 10. Auto Replace Confirmation Drawer (70% Screen)
    autoReplaceMatches?.let { matches ->
        AutoReplaceConfirmationSheet(
            matches = matches,
            onDismissRequest = { autoReplaceMatches = null },
            onConfirm = {
                val targets = autoReplaceMatches ?: return@AutoReplaceConfirmationSheet
                autoReplaceMatches = null
                onAutoReplace(targets, isAutoReplaceShizuku)
            }
        )
    }

    // 11. No Matches Warning Dialog
    if (showNoMatchesDialog) {
        AlertDialog(
            onDismissRequest = { showNoMatchesDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = SemanticColors.Warning) },
            title = { Text("Tidak Ada Tekstur Cocok", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = noMatchesSourceMessage.ifEmpty {
                        "Tidak ada file gambar yang cocok dengan nama tekstur di TXD ini. Pastikan nama file gambar sama dengan nama tekstur (contoh: cj_face.png)."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showNoMatchesDialog = false },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Mengerti", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // 12. Settings Bottom Sheet
    if (showSettingsDialog) {
        SettingsBottomSheet(onDismissRequest = { showSettingsDialog = false })
    }
}
