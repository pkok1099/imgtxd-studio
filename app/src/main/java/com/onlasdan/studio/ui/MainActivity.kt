package com.onlasdan.studio.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.DefaultProcessRepository
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.ui.framework.ArchiveEntryCard
import com.onlasdan.studio.ui.framework.ArchiveScreen
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ContextualPillDock
import com.onlasdan.studio.ui.sheets.AboutBottomSheet
import com.onlasdan.studio.ui.sheets.ImgEntryBottomSheet
import com.onlasdan.studio.ui.sheets.MoreActionsBottomSheet
import com.onlasdan.studio.ui.sheets.ProcessDetailBottomSheet
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.ui.theme.SectionHeaderStyle
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.ActiveSessionManager
import com.onlasdan.studio.util.AppIntegrity
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.LauncherFileType
import com.onlasdan.studio.util.RecentFileItem
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.StorageHelper
import com.onlasdan.studio.util.WindowLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAVIGATE_TAB = "com.onlasdan.studio.extra.NAVIGATE_TAB"
    }

    private val viewModel: ImgToolViewModel by viewModels()
    private var isTampered by mutableStateOf(false)
    private var requestedTab by mutableStateOf<String?>(null)

    // Unsaved changes handling for onNewIntent
    private var pendingIntentToLoad by mutableStateOf<Intent?>(null)
    private var showUnsavedChangesPrompt by mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (!Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                viewModel.updatePermissionStatus()
            } else {
                Shizuku.requestPermission(ShizukuManager.SHIZUKU_REQUEST_CODE)
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        viewModel.updatePermissionStatus()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == ShizukuManager.SHIZUKU_REQUEST_CODE) {
            viewModel.updatePermissionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        if (!AppIntegrity.checkIntegrity(this)) {
            isTampered = true
        } else {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            handleIntent(intent, isNewIntent = false)
        }

        setContent {
            ImgToolTheme {
                if (isTampered) {
                    TamperedScreen()
                } else {
                    val uiState by viewModel.uiState.collectAsState()

                    LaunchedEffect(uiState.archive) {
                        val name = uiState.archive?.archiveName
                        if (!name.isNullOrBlank()) {
                            WindowLauncher.updateTaskDescription(
                                this@MainActivity,
                                "$name - ImgTxd Studio"
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            viewModel = viewModel,
                            requestedTab = requestedTab,
                            onTabConsumed = { requestedTab = null }
                        )

                        if (showUnsavedChangesPrompt) {
                            AlertDialog(
                                onDismissRequest = {
                                    showUnsavedChangesPrompt = false
                                    pendingIntentToLoad = null
                                },
                                shape = RoundedCornerShape(28.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                title = { Text("Perubahan Belum Disimpan", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
                                text = {
                                    Text(
                                        "Arsip IMG saat ini memiliki perubahan yang belum disimpan. Apakah Anda ingin menyimpan sebelum membuka file baru?",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showUnsavedChangesPrompt = false
                                            val next = pendingIntentToLoad
                                            pendingIntentToLoad = null
                                            if (next != null) {
                                                executeLoadIntent(next)
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text("Simpan & Buka", fontWeight = FontWeight.SemiBold)
                                    }
                                },
                                dismissButton = {
                                    OutlinedButton(
                                        onClick = {
                                            showUnsavedChangesPrompt = false
                                            val next = pendingIntentToLoad
                                            pendingIntentToLoad = null
                                            if (next != null) {
                                                executeLoadIntent(next)
                                            }
                                        },
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Text("Buang", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveSessionManager.setImgClosed()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isTampered) return
        viewModel.updatePermissionStatus()
        if (ShizukuManager.isShizukuAvailable() && !ShizukuManager.hasPermission()) {
            ShizukuManager.requestPermission()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isNewIntent = true)
    }

    private fun handleIntent(intent: Intent?, isNewIntent: Boolean) {
        if (intent == null) return

        val navTab = intent.getStringExtra(EXTRA_NAVIGATE_TAB)
        if (navTab != null) {
            requestedTab = navTab
            return
        }

        val uri: Uri? = intent.data
        val filePath: String? = intent.getStringExtra(WindowLauncher.EXTRA_FILE_PATH)
        val isShizuku = intent.getBooleanExtra(WindowLauncher.EXTRA_IS_SHIZUKU, false)

        val targetName = filePath ?: uri?.toString() ?: ""
        if (targetName.endsWith(".txd", ignoreCase = true)) {
            WindowLauncher.open(
                context = this,
                uri = uri,
                filePath = filePath,
                fileType = LauncherFileType.TXD,
                isShizuku = isShizuku,
                onSingleWindowAction = {
                    if (filePath != null) {
                        viewModel.openStandaloneTxd(this, filePath, isShizuku)
                    } else if (uri != null) {
                        viewModel.openFileFromUri(this, uri)
                    }
                }
            )
            return
        }

        val curState = viewModel.uiState.value
        val curPath = curState.originalPath ?: curState.archive?.imgFile?.absolutePath
        val isSamePath = filePath != null && curPath != null && filePath == curPath
        val isSameUri = uri != null && curPath != null && uri.toString() == curPath

        if (isNewIntent && (isSamePath || isSameUri)) {
            return
        }

        if (isNewIntent && curState.hasUnsavedChanges) {
            pendingIntentToLoad = intent
            showUnsavedChangesPrompt = true
            return
        }

        executeLoadIntent(intent)
    }

    private fun executeLoadIntent(intent: Intent) {
        val uri: Uri? = intent.data
        val filePath: String? = intent.getStringExtra(WindowLauncher.EXTRA_FILE_PATH)
        val isShizuku = intent.getBooleanExtra(WindowLauncher.EXTRA_IS_SHIZUKU, false)

        if (filePath != null) {
            viewModel.openArchiveFromPath(this, filePath, isShizuku)
        } else if (uri != null) {
            viewModel.openFileFromUri(this, uri)
        }
    }
}

@Composable
fun MainScreen(
    viewModel: ImgToolViewModel,
    requestedTab: String? = null,
    onTabConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val currentProcess by DefaultProcessRepository.currentProcess.collectAsState()
    val recentFiles by AppSettings.getRecentFilesFlow(context).collectAsState(initial = emptyList())
    val isExternalTxdOpen by ActiveSessionManager.isTxdOpen.collectAsState()
    val isAnyTxdOpen = uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty() || isExternalTxdOpen

    // Tabs: "home", "archive", "txd"
    var activeDockTab by rememberSaveable { mutableStateOf(if (uiState.archive != null) "archive" else "home") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            activeDockTab = it
            onTabConsumed()
        }
    }

    // Lazy list states
    val archiveListState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Sub-sheets & Dialogs
    var selectedImgEntryForSheet by remember { mutableStateOf<ImgEntry?>(null) }
    var showMoreActionsSheet by rememberSaveable { mutableStateOf(false) }
    var showQuickMenuSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showAboutSheet by rememberSaveable { mutableStateOf(false) }
    var selectedProcessForDetail by remember { mutableStateOf<AppProcess?>(null) }
    var showBusyProcessDialog by remember { mutableStateOf(false) }
    var showUnsavedClosePrompt by remember { mutableStateOf(false) }

    // Sub-operations dialogs / pickers
    var entryToRename by remember { mutableStateOf<ImgEntry?>(null) }
    var showRebuildDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // In-App File Picker Dialog States
    var showOpenArchivePicker by remember { mutableStateOf(false) }
    var showOpenTxdPicker by remember { mutableStateOf(false) }
    var showAddFilePicker by remember { mutableStateOf(false) }
    var entryToReplace by remember { mutableStateOf<ImgEntry?>(null) }
    var showExtractSelectedPicker by remember { mutableStateOf(false) }
    var showExtractAllPicker by remember { mutableStateOf(false) }
    var entryToExtract by remember { mutableStateOf<ImgEntry?>(null) }

    LaunchedEffect(uiState.archive) {
        if (uiState.archive != null) {
            activeDockTab = "archive"
            ActiveSessionManager.setImgActive(uiState.archive!!.archiveName)
        } else {
            ActiveSessionManager.setImgClosed()
        }
    }

    LaunchedEffect(uiState.isTxdViewerOpen, uiState.activeTxdName) {
        if (uiState.isTxdViewerOpen && uiState.activeTxdName != null) {
            activeDockTab = "txd"
            ActiveSessionManager.setTxdActive(uiState.activeTxdName!!)
        }
    }

    // Pagination for archive list
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = archiveListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && uiState.hasMorePages) {
            viewModel.loadNextPage()
        }
    }

    LaunchedEffect(uiState.searchQuery, uiState.archive) {
        if (uiState.displayedEntries.isNotEmpty()) {
            archiveListState.scrollToItem(0)
        }
    }

    LaunchedEffect(uiState.statusMessage, uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearMessages()
        }
        uiState.statusMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessages()
        }
    }

    // Storage permission launcher
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.updatePermissionStatus()
    }

    // Check if there is an active running process on current window
    val isCurrentFileBusy = currentProcess != null && currentProcess?.status == ProcessStatus.RUNNING

    // BACK NAVIGATION RULES (Priority 1 -> 5)
    val isAnySheetOpen = selectedImgEntryForSheet != null ||
            showMoreActionsSheet ||
            showQuickMenuSheet ||
            showSettingsSheet ||
            showAboutSheet ||
            selectedProcessForDetail != null ||
            showBusyProcessDialog ||
            showUnsavedClosePrompt

    val canHandleBack = isAnySheetOpen ||
            isSearchExpanded ||
            uiState.isSelecting ||
            (isCurrentFileBusy && activeDockTab != "home") ||
            activeDockTab != "home"

    BackHandler(enabled = canHandleBack) {
        when {
            // Rule 1: Sheet terbuka -> tutup sheet
            selectedProcessForDetail != null -> selectedProcessForDetail = null
            selectedImgEntryForSheet != null -> selectedImgEntryForSheet = null
            showMoreActionsSheet -> showMoreActionsSheet = false
            showQuickMenuSheet -> showQuickMenuSheet = false
            showSettingsSheet -> showSettingsSheet = false
            showAboutSheet -> showAboutSheet = false
            showBusyProcessDialog -> showBusyProcessDialog = false
            showUnsavedClosePrompt -> showUnsavedClosePrompt = false

            // Rule 2: Mode seleksi aktif / search aktif -> keluar seleksi / tutup search
            isSearchExpanded -> {
                isSearchExpanded = false
                viewModel.setSearchQuery("")
            }
            uiState.isSelecting -> {
                viewModel.clearSelection()
            }

            // Rule 3: Proses sedang berjalan -> Tampilkan dialog konfirmasi (JANGAN batalkan)
            isCurrentFileBusy -> {
                showBusyProcessDialog = true
            }

            // Rule 4: Navigasi hierarkis berkas
            activeDockTab == "txd" -> {
                if (uiState.activeTxdSourceEntry != null) {
                    // TXD dibuka dari dalam IMG -> kembali ke IMG (TIDAK menutup TXD dari memori)
                    activeDockTab = "archive"
                } else {
                    // TXD mandiri -> kembali ke Beranda (berkas tetap di memori)
                    activeDockTab = "home"
                }
            }
            activeDockTab == "archive" -> {
                // Dari IMG -> kembali ke Beranda (berkas tetap di memori)
                activeDockTab = "home"
            }
            // Rule 5: Dari Home -> BackHandler disabled, keluar aplikasi
        }
    }

    // DOCK ITEMS: HOME
    val isImgOpen = uiState.archive != null

    val homeDockItems = listOf(
        ContextualDockItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Beranda",
            isSelected = true,
            onClick = {
                coroutineScope.launch { homeListState.animateScrollToItem(0) }
            }
        ),
        ContextualDockItem(
            id = "archive",
            icon = Icons.Outlined.FolderOpen,
            contentDescription = "Arsip IMG",
            showDotBadge = isImgOpen,
            onClick = {
                if (isImgOpen) {
                    activeDockTab = "archive"
                } else {
                    showOpenArchivePicker = true
                }
            }
        ),
        ContextualDockItem(
            id = "txd",
            icon = Icons.Outlined.Palette,
            contentDescription = "Tekstur TXD",
            showDotBadge = isAnyTxdOpen,
            onClick = {
                if (uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty()) {
                    activeDockTab = "txd"
                } else if (isExternalTxdOpen) {
                    val intent = Intent(context, TxdEditorActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } else {
                    showOpenTxdPicker = true
                }
            }
        ),
        ContextualDockItem(
            id = "recent",
            icon = Icons.Outlined.History,
            contentDescription = "Berkas terbaru",
            onClick = {
                coroutineScope.launch { homeListState.animateScrollToItem(2) }
            }
        ),
        ContextualDockItem(
            id = "menu",
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = "Menu aplikasi & opsi",
            onClick = {
                showQuickMenuSheet = true
            }
        )
    )

    // DOCK ITEMS: IMG NORMAL
    val archiveStandardDockItems = listOf(
        ContextualDockItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Kembali ke Beranda",
            onClick = { activeDockTab = "home" }
        ),
        ContextualDockItem(
            id = "search",
            icon = Icons.Outlined.Search,
            contentDescription = "Cari entri arsip",
            isSelected = isSearchExpanded,
            onClick = { isSearchExpanded = !isSearchExpanded }
        ),
        ContextualDockItem(
            id = "add",
            icon = Icons.Outlined.Add,
            contentDescription = "Tambah berkas ke arsip",
            onClick = { showAddFilePicker = true }
        ),
        ContextualDockItem(
            id = "save",
            icon = Icons.Outlined.Save,
            contentDescription = "Simpan arsip IMG",
            showDotBadge = uiState.hasUnsavedChanges,
            isEnabled = uiState.hasUnsavedChanges,
            onClick = { viewModel.rebuildArchive() }
        ),
        ContextualDockItem(
            id = "more",
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = "Opsi lainnya",
            onClick = { showMoreActionsSheet = true }
        )
    )

    // DOCK ITEMS: IMG SELECTION MODE
    val archiveSelectionDockItems = listOf(
        ContextualDockItem(
            id = "export",
            icon = Icons.Outlined.FileDownload,
            contentDescription = "Ekspor berkas terpilih",
            onClick = { showExtractSelectedPicker = true }
        ),
        ContextualDockItem(
            id = "delete",
            icon = Icons.Outlined.Delete,
            contentDescription = "Hapus berkas terpilih",
            onClick = { showDeleteSelectedDialog = true }
        ),
        ContextualDockItem(
            id = "select_all",
            icon = Icons.Outlined.Checklist,
            contentDescription = "Pilih semua entri",
            onClick = { viewModel.selectAll() }
        ),
        ContextualDockItem(
            id = "cancel",
            icon = Icons.Outlined.Close,
            contentDescription = "Batalkan seleksi",
            onClick = { viewModel.clearSelection() }
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (activeDockTab) {
            "home" -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        listState = homeListState,
                        hasStoragePermission = uiState.hasStoragePermission,
                        onGrantStorage = {
                            manageStorageLauncher.launch(StorageHelper.getManageStorageIntent(context))
                        },
                        recentFiles = recentFiles,
                        onOpenImg = {
                            if (uiState.archive != null) {
                                activeDockTab = "archive"
                            } else {
                                showOpenArchivePicker = true
                            }
                        },
                        onOpenTxd = {
                            if (uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty()) {
                                activeDockTab = "txd"
                            } else if (isExternalTxdOpen) {
                                val intent = Intent(context, TxdEditorActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } else {
                                showOpenTxdPicker = true
                            }
                        },
                        onOpenRecent = { item ->
                            WindowLauncher.open(
                                context = context,
                                filePath = item.path,
                                fileType = item.type,
                                isShizuku = item.isShizuku,
                                onSingleWindowAction = {
                                    if (item.type == LauncherFileType.IMG) {
                                        viewModel.openArchiveFromPath(context, item.path, item.isShizuku)
                                        activeDockTab = "archive"
                                    } else {
                                        viewModel.openStandaloneTxd(context, item.path, item.isShizuku)
                                        activeDockTab = "txd"
                                    }
                                }
                            )
                        }
                    )

                    ContextualPillDock(
                        items = homeDockItems,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }

            "archive" -> {
                val activeArchiveProcess = if (currentProcess?.targetFile == uiState.archive?.archiveName) currentProcess else null

                ArchiveScreen(
                    title = uiState.archive?.archiveName ?: "Arsip IMG",
                    subtitle = if (uiState.archive != null)
                        "${uiState.archive?.version?.name ?: "IMG"} • ${uiState.entries.size} file"
                    else
                        "Belum ada arsip terbuka",
                    isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
                    onShizukuClick = {
                        if (!ShizukuManager.isShizukuAvailable()) {
                            Toast.makeText(context, "Shizuku service tidak terdeteksi. Buka aplikasi Shizuku terlebih dahulu.", Toast.LENGTH_LONG).show()
                        } else if (!ShizukuManager.hasPermission()) {
                            ShizukuManager.requestPermission()
                        } else {
                            Toast.makeText(context, "Shizuku aktif: akses penuh Android/data tersedia.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    items = uiState.displayedEntries,
                    keyExtractor = { "${it.cleanName}_${it.offsetInSectors}" },
                    listState = archiveListState,
                    emptyTitle = "Belum Ada Arsip IMG Terbuka",
                    emptyDescription = "Pilih berkas arsip .img untuk melihat dan mengelola entri model 3D dan tekstur game.",
                    emptyActionLabel = "Buka IMG",
                    onEmptyAction = { showOpenArchivePicker = true },
                    isSearching = isSearchExpanded,
                    searchQuery = uiState.searchQuery,
                    searchPlaceholder = "Cari entri, misal cj.dff...",
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onCloseSearch = {
                        isSearchExpanded = false
                        viewModel.setSearchQuery("")
                    },
                    activeProcess = activeArchiveProcess,
                    onProcessPillClick = {
                        activeArchiveProcess?.let { selectedProcessForDetail = it }
                    },
                    dockContent = {
                        ContextualPillDock(
                            items = if (uiState.isSelecting) archiveSelectionDockItems else archiveStandardDockItems
                        )
                    },
                    itemContent = { entry ->
                        val isTxd = entry.cleanName.endsWith(".txd", ignoreCase = true)
                        val icon = when {
                            entry.cleanName.endsWith(".dff", ignoreCase = true) -> Icons.Outlined.ViewInAr
                            entry.cleanName.endsWith(".txd", ignoreCase = true) -> Icons.Outlined.Image
                            entry.cleanName.endsWith(".col", ignoreCase = true) -> Icons.Outlined.Shield
                            entry.cleanName.endsWith(".ipl", ignoreCase = true) -> Icons.Outlined.Map
                            else -> Icons.Outlined.Description
                        }
                        val sizeKb = entry.sizeInBytes / 1024

                        ArchiveEntryCard(
                            title = entry.cleanName,
                            subtitle = "Sektor: ${entry.offsetInSectors} • ${entry.sizeInSectors} sek (~$sizeKb KB)",
                            icon = icon,
                            bitmap = null,
                            isSelected = uiState.selectedEntries.contains(entry),
                            isSelectionMode = uiState.isSelecting,
                            onClick = {
                                if (uiState.isSelecting) {
                                    viewModel.toggleSelection(entry)
                                } else {
                                    selectedImgEntryForSheet = entry
                                }
                            },
                            onLongClick = {
                                viewModel.toggleSelection(entry)
                            }
                        )
                    },
                    snackbarHostState = snackbarHostState
                )
            }

            "txd" -> {
                val activeTxdProcess = if (currentProcess?.targetFile == (uiState.activeTxdName ?: "Tekstur TXD")) currentProcess else null

                TxdEditorScreen(
                    txdName = uiState.activeTxdName ?: "Tekstur TXD",
                    textures = uiState.txdEntries,
                    isLoading = uiState.isTxdLoading,
                    statusMessage = uiState.statusMessage,
                    errorMessage = uiState.errorMessage,
                    hasUnsavedChanges = uiState.hasTxdUnsavedChanges,
                    activeProcess = activeTxdProcess,
                    onSaveTxd = {
                        // In single window mode, TXD saved inside IMG is rebuilt automatically
                    },
                    onProcessClick = {
                        activeTxdProcess?.let { selectedProcessForDetail = it }
                    },
                    onClose = {
                        viewModel.closeTxdViewer()
                        ActiveSessionManager.setTxdClosed()
                        activeDockTab = if (uiState.archive != null) "archive" else "home"
                    },
                    onExportSingle = { entry, destDir ->
                        viewModel.exportSingleTexture(entry, destDir)
                    },
                    onExportAll = { destDir ->
                        viewModel.exportAllTextures(destDir)
                    },
                    onReplaceTexture = { entry, imagePath, isShizuku ->
                        viewModel.replaceTexture(context, entry, imagePath, isShizuku)
                    },
                    onAutoReplace = { matches, isShizuku ->
                        viewModel.autoReplaceTextures(context, matches, isShizuku)
                    },
                    onClearMessages = { viewModel.clearMessages() },
                    showDock = true,
                    onNavigateHome = { activeDockTab = "home" },
                    onOpenImg = { activeDockTab = "archive" },
                    onOpenWindowMode = { showSettingsSheet = true },
                    onOpenSettings = { showSettingsSheet = true },
                    onOpenAbout = { showAboutSheet = true }
                )
            }
        }
    }

    // --- Bottom Sheets & Dialogs ---

    // 1. Img Entry Bottom Sheet (Tap Entri IMG)
    selectedImgEntryForSheet?.let { entry ->
        ImgEntryBottomSheet(
            entry = entry,
            onDismissRequest = { selectedImgEntryForSheet = null },
            onOpenTxd = if (entry.cleanName.endsWith(".txd", ignoreCase = true)) {
                {
                    selectedImgEntryForSheet = null
                    viewModel.openTxdFromEntry(context, entry)
                    activeDockTab = "txd"
                }
            } else null,
            onExtract = {
                entryToExtract = entry
                selectedImgEntryForSheet = null
            },
            onReplace = {
                entryToReplace = entry
                selectedImgEntryForSheet = null
            },
            onRename = {
                entryToRename = entry
                selectedImgEntryForSheet = null
            },
            onDelete = {
                selectedImgEntryForSheet = null
                viewModel.clearSelection()
                viewModel.toggleSelection(entry)
                showDeleteSelectedDialog = true
            }
        )
    }

    // 2. Sheet "Lainnya" untuk IMG
    if (showMoreActionsSheet) {
        MoreActionsBottomSheet(
            onDismissRequest = { showMoreActionsSheet = false },
            fileName = uiState.archive?.archiveName ?: "Arsip IMG",
            isTxd = false,
            isFileBusy = isCurrentFileBusy,
            onExportAll = {
                showMoreActionsSheet = false
                showExtractAllPicker = true
            },
            onRenameFile = null,
            onRebuildArchive = {
                showMoreActionsSheet = false
                showRebuildDialog = true
            },
            onOpenWindowMode = {
                showMoreActionsSheet = false
                showSettingsSheet = true
            },
            onOpenSettings = {
                showMoreActionsSheet = false
                showSettingsSheet = true
            },
            onOpenAbout = {
                showMoreActionsSheet = false
                showAboutSheet = true
            },
            onCloseFile = {
                showMoreActionsSheet = false
                if (uiState.hasUnsavedChanges) {
                    showUnsavedClosePrompt = true
                } else {
                    viewModel.closeArchive()
                    activeDockTab = "home"
                }
            },
            onOpenShizuku = {
                if (!ShizukuManager.isShizukuAvailable()) {
                    Toast.makeText(context, "Shizuku service tidak terdeteksi.", Toast.LENGTH_SHORT).show()
                } else if (!ShizukuManager.hasPermission()) {
                    ShizukuManager.requestPermission()
                } else {
                    Toast.makeText(context, "Shizuku aktif: akses penuh Android/data tersedia.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 3. Quick Menu Bottom Sheet (Dari Home)
    if (showQuickMenuSheet) {
        QuickMenuBottomSheet(
            onDismissRequest = { showQuickMenuSheet = false },
            hasActiveArchive = uiState.archive != null,
            isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
            onOpenArchive = { showOpenArchivePicker = true },
            onOpenTxd = { showOpenTxdPicker = true },
            onAddFile = { showAddFilePicker = true },
            onRebuildArchive = { showRebuildDialog = true },
            onSelectAll = { viewModel.selectAll() },
            onOpenSettings = { showSettingsSheet = true },
            onRequestShizuku = {
                if (!ShizukuManager.isShizukuAvailable()) {
                    Toast.makeText(context, "Shizuku service tidak terdeteksi.", Toast.LENGTH_SHORT).show()
                } else if (!ShizukuManager.hasPermission()) {
                    ShizukuManager.requestPermission()
                } else {
                    Toast.makeText(context, "Shizuku aktif: akses penuh Android/data tersedia.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 4. Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(onDismissRequest = { showSettingsSheet = false })
    }

    // 5. About Bottom Sheet
    if (showAboutSheet) {
        AboutBottomSheet(onDismissRequest = { showAboutSheet = false })
    }

    // 6. Process Detail Bottom Sheet
    selectedProcessForDetail?.let { proc ->
        ProcessDetailBottomSheet(
            process = proc,
            onDismissRequest = { selectedProcessForDetail = null },
            onCancelProcess = { id -> DefaultProcessRepository.cancelProcess(id) }
        )
    }

    // 7. Busy Process Dialog (Back Navigation Rule 3)
    if (showBusyProcessDialog) {
        AlertDialog(
            onDismissRequest = { showBusyProcessDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Proses Sedang Berjalan",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Proses '${currentProcess?.title}' saat ini sedang berjalan di latar belakang.\n\nAnda dapat kembali ke Beranda sementara proses terus berjalan, atau tetap berada di layar ini.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBusyProcessDialog = false
                        activeDockTab = "home"
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Lanjut di Latar Belakang", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showBusyProcessDialog = false },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Tetap di Sini", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // 8. Unsaved Close Prompt Dialog
    if (showUnsavedClosePrompt) {
        AlertDialog(
            onDismissRequest = { showUnsavedClosePrompt = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Tutup Tanpa Menyimpan?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Arsip ini memiliki perubahan yang belum direbuild/disimpan ke file. Menutup sekarang akan membatalkan perubahan yang belum tersimpan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedClosePrompt = false
                        viewModel.closeArchive()
                        activeDockTab = "home"
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Tutup Saja", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showUnsavedClosePrompt = false },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Batal")
                }
            }
        )
    }

    // --- File Pickers ---

    // Open IMG Archive Picker
    if (showOpenArchivePicker) {
        FilePickerDialog(
            title = "Buka Arsip IMG",
            mode = PickerMode.OPEN_IMG,
            onDismissRequest = { showOpenArchivePicker = false },
            onFileSelected = { path, isShizuku ->
                showOpenArchivePicker = false
                WindowLauncher.open(
                    context = context,
                    filePath = path,
                    fileType = LauncherFileType.IMG,
                    isShizuku = isShizuku,
                    onSingleWindowAction = {
                        viewModel.openArchiveFromPath(context, path, isShizuku)
                    }
                )
            }
        )
    }

    // Open TXD Picker
    if (showOpenTxdPicker) {
        FilePickerDialog(
            title = "Buka Berkas Tekstur (TXD)",
            mode = PickerMode.OPEN_TXD,
            onDismissRequest = { showOpenTxdPicker = false },
            onFileSelected = { path, isShizuku ->
                showOpenTxdPicker = false
                WindowLauncher.open(
                    context = context,
                    filePath = path,
                    fileType = LauncherFileType.TXD,
                    isShizuku = isShizuku,
                    onSingleWindowAction = {
                        viewModel.openStandaloneTxd(context, path, isShizuku)
                        activeDockTab = "txd"
                    }
                )
            }
        )
    }

    // Add File Picker
    if (showAddFilePicker) {
        FilePickerDialog(
            title = "Pilih Berkas untuk Ditambahkan",
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showAddFilePicker = false },
            onFileSelected = { path, isShizuku ->
                showAddFilePicker = false
                viewModel.addFileFromPath(context, path, isShizuku)
            }
        )
    }

    // Replace File Picker
    entryToReplace?.let { entry ->
        FilePickerDialog(
            title = "Ganti ${entry.cleanName}",
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { entryToReplace = null },
            onFileSelected = { path, isShizuku ->
                val targetEntry = entryToReplace
                entryToReplace = null
                if (targetEntry != null) {
                    viewModel.replaceEntryFromPath(context, targetEntry, path, isShizuku)
                }
            }
        )
    }

    // Extract Single Entry Picker
    entryToExtract?.let { entry ->
        FilePickerDialog(
            title = "Ekstrak ${entry.cleanName} ke Folder...",
            mode = PickerMode.SELECT_FOLDER,
            onDismissRequest = { entryToExtract = null },
            onFileSelected = { path, isShizuku ->
                val targetEntry = entryToExtract
                entryToExtract = null
                if (targetEntry != null) {
                    viewModel.extractSingle(
                        entry = targetEntry,
                        destinationDir = File(path),
                        isShizuku = isShizuku,
                        destRestrictedPath = if (isShizuku) path else null
                    )
                }
            }
        )
    }

    // Extract Selected Entries Picker
    if (showExtractSelectedPicker) {
        FilePickerDialog(
            title = "Ekstrak ${uiState.selectedEntries.size} Berkas ke Folder...",
            mode = PickerMode.SELECT_FOLDER,
            onDismissRequest = { showExtractSelectedPicker = false },
            onFileSelected = { path, isShizuku ->
                showExtractSelectedPicker = false
                viewModel.extractSelected(
                    destinationDir = File(path),
                    isShizuku = isShizuku,
                    destRestrictedPath = if (isShizuku) path else null
                )
            }
        )
    }

    // Extract All Entries Picker
    if (showExtractAllPicker) {
        FilePickerDialog(
            title = "Ekstrak Seluruh Berkas Arsip ke Folder...",
            mode = PickerMode.SELECT_FOLDER,
            onDismissRequest = { showExtractAllPicker = false },
            onFileSelected = { path, isShizuku ->
                showExtractAllPicker = false
                viewModel.extractAll(
                    destinationDir = File(path),
                    isShizuku = isShizuku,
                    destRestrictedPath = if (isShizuku) path else null
                )
            }
        )
    }

    // Rename Dialog (28dp)
    entryToRename?.let { entry ->
        var newNameText by remember { mutableStateOf(entry.cleanName) }
        AlertDialog(
            onDismissRequest = { entryToRename = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Ubah Nama Entri", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
                    label = { Text("Nama Berkas (Maks 24 karakter)") },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newNameText.isNotBlank()) {
                            viewModel.renameEntry(entry, newNameText.trim())
                            entryToRename = null
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Simpan", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToRename = null }) {
                    Text("Batal")
                }
            }
        )
    }

    // Rebuild Confirm Dialog (28dp)
    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Rebuild Arsip IMG", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Rebuild akan menyusun ulang seluruh entri secara berurutan dan membersihkan ruang sektor kosong yang terfragmentasi.\n\n" +
                            (if (uiState.isShizukuBacked) "Perubahan akan otomatis disimpan kembali ke folder game via Shizuku.\n\n" else "") +
                            "Pastikan arsip memiliki cadangan sebelum memulai rebuild.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRebuildDialog = false
                        viewModel.rebuildArchive()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Rebuild Sekarang", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Delete Selected Confirm Dialog (28dp)
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Hapus Entri Terpilih", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Apakah Anda yakin ingin menghapus ${uiState.selectedEntries.size} entri dari arsip ini?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteSelectedDialog = false
                        viewModel.deleteSelected()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Hapus (${uiState.selectedEntries.size})", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    listState: androidx.compose.foundation.lazy.LazyListState,
    hasStoragePermission: Boolean,
    onGrantStorage: () -> Unit,
    recentFiles: List<RecentFileItem>,
    onOpenImg: () -> Unit,
    onOpenTxd: () -> Unit,
    onOpenRecent: (RecentFileItem) -> Unit
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = statusBarPadding + 16.dp,
                bottom = navBarPadding + 108.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Storage permission warning banner
        if (!hasStoragePermission) {
            item(key = "storage_permission_banner") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Akses Semua Berkas Diperlukan",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Android memerlukan izin penyimpanan untuk memodifikasi berkas game secara langsung.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Button(
                            onClick = onGrantStorage,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Izinkan")
                        }
                    }
                }
            }
        }

        // Hero Card
        item(key = "home_hero_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "ImgTxd Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Buka dan kelola arsip .img serta tekstur .txd. Kompatibel dengan format game seri Grand Theft Auto (SA, VC, III) dan Bully. Akses langsung folder Android/data menggunakan Shizuku tanpa root!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Tidak berafiliasi dengan Rockstar Games / Take-Two.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onOpenImg,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buka IMG", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onOpenTxd,
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buka TXD", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Section: FILE TERBARU
        item(key = "home_recent_header") {
            Text(
                text = "FILE TERBARU",
                style = SectionHeaderStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        if (recentFiles.isEmpty()) {
            item(key = "home_recent_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Belum ada berkas yang baru dibuka.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(recentFiles, key = { it.path }) { item ->
                RecentFileRow(item = item, onClick = { onOpenRecent(item) })
            }
        }
    }

    // Status bar protection gradient so system icons remain clear and legible when scrolled
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarPadding + 8.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        Color.Transparent
                    )
                )
            )
    )
}
}

@Composable
private fun RecentFileRow(item: RecentFileItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.type == LauncherFileType.IMG) Icons.Outlined.Folder else Icons.Outlined.Palette,
                    contentDescription = null,
                    tint = if (item.type == LauncherFileType.IMG) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.isShizuku) {
                AssistChip(
                    onClick = {},
                    shape = CircleShape,
                    label = { Text("Shizuku", style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = SemanticColors.SuccessContainer,
                        labelColor = SemanticColors.OnSuccessContainer
                    ),
                    border = BorderStroke(1.dp, SemanticColors.Success.copy(alpha = 0.4f)),
                    modifier = Modifier.height(24.dp)
                )
            }
        }
    }
}

@Composable
fun TamperedScreen() {
    BackHandler {
        AppIntegrity.terminate()
    }

    LaunchedEffect(Unit) {
        delay(6000)
        AppIntegrity.terminate()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = "Peringatan Keamanan",
                        tint = SemanticColors.Error,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = "Integritas Aplikasi Terlanggar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Aplikasi ini mendeteksi perubahan nama paket (repackaging) atau modifikasi kode yang tidak sah. " +
                                "Demi integritas sistem dan keamanan berkas game Anda, ImgTxd Studio tidak diizinkan berjalan dalam kondisi yang telah diubah.\n\n" +
                                "Aplikasi akan otomatis ditutup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { AppIntegrity.terminate() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Tutup Aplikasi", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
