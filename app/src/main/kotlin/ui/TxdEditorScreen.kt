package com.onlasdan.studio.ui
import com.onlasdan.studio.ui.components.ConfirmSheet
import com.onlasdan.studio.ui.components.SheetTitle

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.DefaultProcessRepository
import com.onlasdan.studio.process.ProcessRepositoryLocator
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.txd.MipFilter
import com.onlasdan.studio.txd.TextureImportOptions
import com.onlasdan.studio.txd.TextureNames
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
    onReplaceTexture: (entry: TxdEntry, imagePath: String, isShizuku: Boolean, options: TextureImportOptions) -> Unit,
    onAutoReplace: (matches: List<Pair<TxdEntry, String>>, isShizuku: Boolean, options: TextureImportOptions) -> Unit,
    onAddTextures: ((images: List<Pair<String, Boolean>>, policy: TextureNames.ConflictPolicy, options: TextureImportOptions) -> Unit)? = null,
    onImportFromTxd: ((sourcePath: String, isSourceShizuku: Boolean, policy: TextureNames.ConflictPolicy) -> Unit)? = null,
    onDeleteTextures: ((indices: List<Int>) -> Unit)? = null,
    onRenameTexture: ((index: Int, newName: String) -> Unit)? = null,
    onUpdateProperties: ((index: Int, maskName: String, filterFlags: Int) -> Unit)? = null,
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
    var entryToRename by remember { mutableStateOf<TxdEntry?>(null) }
    var entryToDelete by remember { mutableStateOf<TxdEntry?>(null) }
    var entryToEditProps by remember { mutableStateOf<TxdEntry?>(null) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var showExportAllPicker by remember { mutableStateOf(false) }
    var showExportSelectedPicker by remember { mutableStateOf(false) }

    // Import options shared by replace / auto-replace / add-textures (TXD Tool Import Wizard model)
    var importOptions by remember { mutableStateOf(TextureImportOptions(generateMipmaps = true)) }

    // Add Textures State (Import Wizard "add" path)
    var showAddTexturesPicker by remember { mutableStateOf(false) }
    var pendingAddImages by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var pendingAddConflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var addConflictPolicy by remember { mutableStateOf(TextureNames.ConflictPolicy.JUST_ADD) }

    // Import from TXD Archive State
    var showImportTxdPicker by remember { mutableStateOf(false) }
    var pendingImportTxdSource by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var importTxdConflictPolicy by remember { mutableStateOf(TextureNames.ConflictPolicy.JUST_ADD) }

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
            noMatchesSourceMessage = "No image file matched any texture name in this TXD (${filePaths.size} files checked)."
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
                noMatchesSourceMessage = "Folder scan failed: ${e.localizedMessage}"
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
            id = "delete_selected",
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.dock_delete_selected),
            onClick = { showDeleteSelectedConfirm = true }
        ),
        ContextualDockItem(
            id = "select_all",
            icon = Icons.Outlined.SelectAll,
            contentDescription = "Select all textures",
            onClick = { selectedTextures = textures.toSet() }
        ),
        ContextualDockItem(
            id = "cancel_selection",
            icon = Icons.Outlined.Close,
            contentDescription = "Clear selection",
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
        if (textures.isEmpty()) {
            ContextualDockItem(
                id = "add_textures",
                icon = Icons.Outlined.AddPhotoAlternate,
                contentDescription = stringResource(R.string.add_new_texture),
                onClick = { showAddTexturesPicker = true }
            )
        } else {
            ContextualDockItem(
                id = "auto_replace",
                icon = Icons.Outlined.AutoFixHigh,
                contentDescription = "Auto Replace Tekstur",
                onClick = { showAutoReplaceModeChoice = true }
            )
        },
        ContextualDockItem(
            id = "save",
            icon = Icons.Outlined.Save,
            contentDescription = "Save Texture",
            showDotBadge = hasUnsavedChanges,
            isEnabled = hasUnsavedChanges,
            onClick = { onSaveTxd?.invoke() }
        ),
        ContextualDockItem(
            id = "more",
            icon = Icons.Outlined.Settings,
            contentDescription = "Opsi lainnya",
            onClick = { showMoreActionsSheet = true }
        )
    )

    // Single Framework Composable: ArchiveScreen
    ArchiveScreen(
        title = txdName.ifBlank { stringResource(R.string.txd_textures_title) },
        subtitle = if (textures.isEmpty()) stringResource(R.string.txd_no_textures) else stringResource(R.string.txd_textures_count, textures.size),
        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
        onShizukuClick = {
            if (!ShizukuManager.isShizukuAvailable()) {
                snackbarHostState.currentSnackbarData?.dismiss()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.shizuku_not_detected_short))
                }
            } else if (!ShizukuManager.hasPermission()) {
                ShizukuManager.requestPermission()
            }
        },
        items = displayedTextures,
        keyExtractor = { "${it.name}_${it.width}_${it.height}" },
        emptyTitle = if (textures.isEmpty() && !isLoading) stringResource(R.string.txd_maker_empty_cta_title) else stringResource(R.string.txd_no_textures),
        emptyDescription = when {
            isLoading -> stringResource(R.string.txd_loading_textures)
            textures.isEmpty() -> stringResource(R.string.txd_maker_empty_cta_desc)
            else -> stringResource(R.string.txd_empty_desc)
        },
        emptyActionLabel = if (textures.isEmpty() && !isLoading && onAddTextures != null) stringResource(R.string.txd_maker_empty_cta_add) else null,
        onEmptyAction = if (textures.isEmpty() && !isLoading && onAddTextures != null) ({ showAddTexturesPicker = true }) else null,
        isSearching = isSearching,
        searchQuery = searchQuery,
        searchPlaceholder = stringResource(R.string.txd_search_placeholder),
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

    // 1. Texture Detail Bottom Sheet (Tap Entri TXD: Pratinjau Besar + Ganti + Ekspor + Rename + Hapus)
    selectedTextureForDetail?.let { entry ->
        TextureDetailBottomSheet(
            entry = entry,
            onDismissRequest = { selectedTextureForDetail = null },
            onReplaceTexture = { entryToReplace = entry },
            onExportTexture = { entryToExport = entry },
            onRenameTexture = { entryToRename = entry },
            onDeleteTexture = { entryToDelete = entry },
            onEditProperties = { entryToEditProps = entry }
        )
    }

    // 2. Sheet "Lainnya" (Contextual TXD Options)
    if (showMoreActionsSheet) {
        MoreActionsBottomSheet(
            onDismissRequest = { showMoreActionsSheet = false },
            fileName = txdName,
            isTxd = true,
            isFileBusy = ProcessRepositoryLocator.get(context).hasRunningProcess(txdName),
            isLargeImage = (txdViewMode == TxdViewMode.LARGE),
            onToggleTxdViewMode = { toggleViewMode() },
            onAddTextures = if (onAddTextures != null) ({ showAddTexturesPicker = true }) else null,
            onImportFromTxd = if (onImportFromTxd != null) ({ showImportTxdPicker = true }) else null,
            onExportAll = { showExportAllPicker = true },
            onCloseFile = { onClose() }
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
            title = stringResource(R.string.txd_pick_export_folder, target.name),
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
            title = stringResource(R.string.txd_pick_export_all_folder),
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
            title = stringResource(R.string.txd_pick_export_selected_folder, targets.size),
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

    // 5c. Delete Selected Confirmation Dialog
    if (showDeleteSelectedConfirm) {
        val targets = selectedTextures.toList()
        ConfirmSheet(
            title = stringResource(R.string.txd_delete_selected_title),
            message = stringResource(R.string.txd_delete_selected_msg, targets.size),
            confirmLabel = stringResource(R.string.delete),
            danger = true,
            onDismissRequest = { showDeleteSelectedConfirm = false },
            onConfirm = {
                showDeleteSelectedConfirm = false
                val indices = targets.mapNotNull { target ->
                    val idx = textures.indexOf(target)
                    if (idx >= 0) idx else null
                }
                onDeleteTextures?.invoke(indices)
                selectedTextures = emptySet()
                isSelectionMode = false
            }
        )
    }

    // 5d. Delete Single Texture Confirmation Dialog
    entryToDelete?.let { target ->
        ConfirmSheet(
            title = stringResource(R.string.txd_delete_texture_title),
            message = stringResource(R.string.txd_delete_texture_msg, target.name),
            confirmLabel = stringResource(R.string.delete),
            danger = true,
            onDismissRequest = { entryToDelete = null },
            onConfirm = {
                val idx = textures.indexOf(target)
                entryToDelete = null
                if (idx >= 0) {
                    onDeleteTextures?.invoke(listOf(idx))
                }
            }
        )
    }

    // 5e. Rename Single Texture Dialog
    entryToRename?.let { target ->
        var newNameInput by remember(target) { mutableStateOf(target.name) }
        var inputError by remember { mutableStateOf<String?>(null) }

        FloatingSheet(onDismissRequest = { entryToRename = null }) {
            SheetTitle(stringResource(R.string.txd_rename_texture_title))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.txd_rename_dff_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = newNameInput,
                onValueChange = {
                    if (it.length <= 31) {
                        newNameInput = it
                        inputError = null
                    }
                },
                label = { Text(stringResource(R.string.txd_rename_input_label)) },
                isError = inputError != null,
                supportingText = inputError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { entryToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = newNameInput.trim()
                        if (trimmed.isEmpty()) {
                            inputError = "Name cannot be empty"
                        } else if (textures.any { it != target && it.name.equals(trimmed, ignoreCase = true) }) {
                            inputError = "Texture '$trimmed' already exists"
                        } else {
                            val idx = textures.indexOf(target)
                            entryToRename = null
                            if (idx >= 0) {
                                onRenameTexture?.invoke(idx, trimmed)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    // 5f. Edit Texture Properties Dialog (Mask Name + Filter Flags)
    entryToEditProps?.let { target ->
        var maskNameInput by remember(target) { mutableStateOf(target.maskName) }
        // filterFlags: bits 0..7 = filter mode (1=nearest, 2=linear, 3=mip_nearest, 4=mip_linear, etc.)
        // bits 8..11 = wrap U (1=wrap, 2=mirror, 3=clamp)
        // bits 12..15 = wrap V (1=wrap, 2=mirror, 3=clamp)
        val initialFilter = remember(target) { target.filterFlags and 0xFF }
        val initialWrapU = remember(target) { (target.filterFlags ushr 8) and 0xF }
        val initialWrapV = remember(target) { (target.filterFlags ushr 12) and 0xF }

        var filterMode by remember(target) { mutableIntStateOf(if (initialFilter in 1..6) initialFilter else 2) }
        var wrapU by remember(target) { mutableStateOf(initialWrapU != 3) } // true=wrap, false=clamp
        var wrapV by remember(target) { mutableStateOf(initialWrapV != 3) } // true=wrap, false=clamp

        FloatingSheet(onDismissRequest = { entryToEditProps = null }) {
            SheetTitle(stringResource(R.string.txd_edit_properties_title))
            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = maskNameInput,
                onValueChange = { if (it.length <= 31) maskNameInput = it },
                label = { Text(stringResource(R.string.txd_mask_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.txd_filter_mode_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            val filterOptions = listOf(
                1 to stringResource(R.string.txd_filter_nearest),
                2 to stringResource(R.string.txd_filter_linear),
                3 to stringResource(R.string.txd_filter_mip_nearest),
                4 to stringResource(R.string.txd_filter_mip_linear)
            )

            filterOptions.forEach { (mode, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    RadioButton(
                        selected = filterMode == mode,
                        onClick = { filterMode = mode }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.txd_wrap_u_label),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(checked = wrapU, onCheckedChange = { wrapU = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.txd_wrap_v_label),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(checked = wrapV, onCheckedChange = { wrapV = it })
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { entryToEditProps = null }) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val idx = textures.indexOf(target)
                        entryToEditProps = null
                        if (idx >= 0) {
                            val uVal = if (wrapU) 1 else 3
                            val vVal = if (wrapV) 1 else 3
                            val newFilterFlags = (filterMode and 0xFF) or (uVal shl 8) or (vVal shl 12)
                            onUpdateProperties?.invoke(idx, maskNameInput.trim(), newFilterFlags)
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    // 6. File Picker for Replace Single Texture
    entryToReplace?.let { target ->
        FilePickerDialog(
            title = stringResource(R.string.txd_pick_replace_image, target.name),
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
        FloatingSheet(onDismissRequest = { singleReplaceCandidate = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                SheetTitle("Confirm Texture Replace")
            }
            Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Compare the original texture with the new image before applying:",
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

                    // Opsi impor (berlaku juga untuk Auto Replace & Tambah Tekstur)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = importOptions.generateMipmaps,
                                onCheckedChange = { checked ->
                                    importOptions = importOptions.copy(generateMipmaps = checked)
                                }
                            )
                            Text(
                                text = "Buat mipmaps",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (importOptions.generateMipmaps) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MipFilter.values().forEach { filter ->
                                    FilterChip(
                                        selected = importOptions.mipFilter == filter,
                                        onClick = { importOptions = importOptions.copy(mipFilter = filter) },
                                        label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Batasi ukuran maksimum",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val sizeChoices = listOf<Int?>(null, 2048, 1024, 512, 256)
                            sizeChoices.forEach { choice ->
                                FilterChip(
                                    selected = importOptions.maxDimension == choice,
                                    onClick = { importOptions = importOptions.copy(maxDimension = choice) },
                                    label = {
                                        Text(
                                            choice?.toString() ?: "Asli",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { singleReplaceCandidate = null }) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        singleReplaceCandidate = null
                        onReplaceTexture(target, imgPath, isShizuku, importOptions)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Replace Texture", fontWeight = FontWeight.Bold)
                }
            }
        }
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
            title = stringResource(R.string.txd_pick_mod_source_folder),
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
            title = stringResource(R.string.txd_pick_replacement_image_file),
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
                onAutoReplace(targets, isAutoReplaceShizuku, importOptions)
            }
        )
    }

    // 11. No Matches Warning Sheet
    if (showNoMatchesDialog) {
        ConfirmSheet(
            title = stringResource(R.string.txd_no_matching_texture_title),
            message = noMatchesSourceMessage.ifEmpty {
                stringResource(R.string.txd_no_matching_texture_desc)
            },
            confirmLabel = stringResource(R.string.txd_understand),
            dismissLabel = null,
            onDismissRequest = { showNoMatchesDialog = false },
            onConfirm = { showNoMatchesDialog = false }
        )
    }

    // 12. Settings Bottom Sheet
    if (showSettingsDialog) {
        SettingsBottomSheet(onDismissRequest = { showSettingsDialog = false })
    }

    // 13. File Picker for Add Textures (Import Wizard "add" path)
    if (showAddTexturesPicker && onAddTextures != null) {
        FilePickerDialog(
            title = stringResource(R.string.txd_pick_images_to_add),
            mode = PickerMode.PICK_MULTIPLE_IMAGES,
            onDismissRequest = { showAddTexturesPicker = false },
            onMultipleFilesSelected = { filePaths, isShizuku ->
                showAddTexturesPicker = false
                val picked = filePaths.map { it to isShizuku }
                val existingNames = textures.map { it.name.lowercase() }.toHashSet()
                val conflicts = filePaths.filter {
                    File(it).nameWithoutExtension.lowercase() in existingNames
                }
                if (conflicts.isEmpty()) {
                    onAddTextures(picked, TextureNames.ConflictPolicy.JUST_ADD, importOptions)
                } else {
                    pendingAddImages = picked
                    pendingAddConflicts = conflicts.map { File(it).nameWithoutExtension }
                    addConflictPolicy = TextureNames.ConflictPolicy.JUST_ADD
                }
            }
        )
    }

    // 14. Add Textures Name-Conflict Dialog (TXD Tool: Just add / Overwrite)
    if (pendingAddImages != null && onAddTextures != null) {
        FloatingSheet(onDismissRequest = { pendingAddImages = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                SheetTitle("Texture Name Already Exists")
            }
            Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${pendingAddConflicts.size} image names collide with existing textures " +
                            "(${pendingAddConflicts.take(4).joinToString(", ")}${if (pendingAddConflicts.size > 4) ", …" else ""}). " +
                            "Pick how to handle the conflicting names:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextureNames.ConflictPolicy.values().forEach { policy ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = addConflictPolicy == policy,
                                onClick = { addConflictPolicy = policy }
                            )
                            Column {
                                Text(
                                    text = policy.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (policy == TextureNames.ConflictPolicy.JUST_ADD) {
                                        "Tetap ditambahkan, nama diberi akhiran _1, _2, …"
                                    } else {
                                        "Replace the old texture with the new image"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { pendingAddImages = null }) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val images = pendingAddImages ?: return@Button
                        pendingAddImages = null
                        onAddTextures(images, addConflictPolicy, importOptions)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Impor", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 15. File Picker for Import From TXD Archive
    if (showImportTxdPicker && onImportFromTxd != null) {
        FilePickerDialog(
            title = stringResource(R.string.pick_source_txd_title),
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showImportTxdPicker = false },
            onFileSelected = { filePath, isShizuku ->
                showImportTxdPicker = false
                if (filePath.endsWith(".txd", ignoreCase = true)) {
                    pendingImportTxdSource = filePath to isShizuku
                    importTxdConflictPolicy = TextureNames.ConflictPolicy.JUST_ADD
                }
            }
        )
    }

    // 16. Import From TXD Conflict Policy Dialog (TXD Tool: Just add / Overwrite)
    if (pendingImportTxdSource != null && onImportFromTxd != null) {
        val (sourcePath, isSourceShizuku) = pendingImportTxdSource!!
        FloatingSheet(onDismissRequest = { pendingImportTxdSource = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                SheetTitle(stringResource(R.string.import_from_txd))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Importing textures from ${File(sourcePath).name}. Choose how to resolve texture name conflicts with the current TXD:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextureNames.ConflictPolicy.values().forEach { policy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = importTxdConflictPolicy == policy,
                            onClick = { importTxdConflictPolicy = policy }
                        )
                        Column {
                            Text(
                                text = policy.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (policy == TextureNames.ConflictPolicy.JUST_ADD) {
                                    "Tetap ditambahkan, nama diberi akhiran _1, _2, …"
                                } else {
                                    "Overwrite existing textures with imported ones"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { pendingImportTxdSource = null }) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val src = pendingImportTxdSource ?: return@Button
                        pendingImportTxdSource = null
                        onImportFromTxd(src.first, src.second, importTxdConflictPolicy)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Impor", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
