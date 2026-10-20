package com.onlasdan.studio.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import com.onlasdan.studio.core.ImgVersion
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.ui.theme.SectionHeaderStyle
import com.onlasdan.studio.ui.theme.SemanticColors
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

    private val viewModel: ImgToolViewModel by viewModels()
    private var isTampered by mutableStateOf(false)

    // Unsaved changes handling for onNewIntent
    private var pendingIntentToLoad by mutableStateOf<Intent?>(null)
    private var showUnsavedChangesPrompt by mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (!Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                viewModel.updatePermissionStatus()
            } else {
                // Request Shizuku permission as soon as binder is received
                Shizuku.requestPermission(ShizukuManager.SHIZUKU_REQUEST_CODE)
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        viewModel.updatePermissionStatus()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuManager.SHIZUKU_REQUEST_CODE) {
            viewModel.updatePermissionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
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
                        MainScreen(viewModel = viewModel)

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
            // Already showing this exact archive, focus window
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ImgToolViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val recentFiles by AppSettings.getRecentFilesFlow(context).collectAsState(initial = emptyList())

    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var txdSearchQuery by rememberSaveable { mutableStateOf("") }
    var showQuickMenuSheet by rememberSaveable { mutableStateOf(false) }
    var activeDockTab by rememberSaveable { mutableStateOf(if (uiState.archive != null) "archive" else "home") }
    var sheetEntryName by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }

    var entryToRename by remember { mutableStateOf<ImgEntry?>(null) }
    var showRebuildDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // In-App File Picker Dialog States
    var showOpenArchivePicker by remember { mutableStateOf(false) }
    var showOpenTxdPicker by remember { mutableStateOf(false) }
    var showAddFilePicker by remember { mutableStateOf(false) }
    var entryToReplace by remember { mutableStateOf<ImgEntry?>(null) }
    var showExtractSelectedPicker by remember { mutableStateOf(false) }
    var entryToExtract by remember { mutableStateOf<ImgEntry?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.archive) {
        if (uiState.archive != null) {
            activeDockTab = "archive"
        }
    }

    LaunchedEffect(uiState.isTxdViewerOpen) {
        if (uiState.isTxdViewerOpen) {
            activeDockTab = "txd"
        }
    }

    BackHandler(enabled = isSearchExpanded || uiState.isSelecting || (activeDockTab == "txd" && uiState.isTxdViewerOpen) || (activeDockTab != "home" && uiState.archive == null)) {
        when {
            isSearchExpanded -> {
                isSearchExpanded = false
                viewModel.setSearchQuery("")
            }
            uiState.isSelecting -> {
                viewModel.clearSelection()
            }
            activeDockTab == "txd" && uiState.isTxdViewerOpen -> {
                viewModel.closeTxdViewer()
                activeDockTab = if (uiState.archive != null) "archive" else "home"
            }
            activeDockTab != "home" -> {
                activeDockTab = "home"
            }
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadNextPage()
        }
    }

    LaunchedEffect(uiState.searchQuery, uiState.archive) {
        if (uiState.displayedEntries.isNotEmpty()) {
            listState.scrollToItem(0)
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

    // Permission launcher for MANAGE_EXTERNAL_STORAGE
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.updatePermissionStatus()
    }

    val isShizukuReady = remember { ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission() }

    val dockItems = listOf(
        DockTabItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Beranda",
            isSelected = (activeDockTab == "home"),
            onClick = {
                activeDockTab = "home"
                isSearchExpanded = false
            }
        ),
        DockTabItem(
            id = "archive",
            icon = Icons.Outlined.FolderOpen,
            contentDescription = "Arsip IMG",
            isSelected = (activeDockTab == "archive"),
            onClick = {
                activeDockTab = "archive"
            }
        ),
        DockTabItem(
            id = "txd",
            icon = Icons.Outlined.Palette,
            contentDescription = "Tekstur TXD",
            isSelected = (activeDockTab == "txd"),
            onClick = {
                activeDockTab = "txd"
            }
        ),
        DockTabItem(
            id = "menu",
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = "Menu aplikasi & opsi",
            isSelected = showQuickMenuSheet,
            onClick = {
                showQuickMenuSheet = true
            }
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSearchExpanded && ((activeDockTab == "archive" && uiState.archive != null) || (activeDockTab == "txd" && (uiState.isTxdViewerOpen || uiState.activeTxdName != null)))) {
                        // Expanding pill search bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                isSearchExpanded = false
                                if (activeDockTab == "archive") viewModel.setSearchQuery("") else txdSearchQuery = ""
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Tutup pencarian",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val currentQuery = if (activeDockTab == "archive") uiState.searchQuery else txdSearchQuery
                            OutlinedTextField(
                                value = currentQuery,
                                onValueChange = {
                                    if (activeDockTab == "archive") viewModel.setSearchQuery(it) else txdSearchQuery = it
                                },
                                placeholder = {
                                    Text(if (activeDockTab == "archive") "Cari entri, misal cj.dff..." else "Cari tekstur, misal cj_face...")
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingIcon = {
                                    if (currentQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            if (activeDockTab == "archive") viewModel.setSearchQuery("") else txdSearchQuery = ""
                                        }) {
                                            Icon(Icons.Outlined.Close, contentDescription = "Hapus pencarian")
                                        }
                                    }
                                },
                                shape = CircleShape,
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                            )
                        }
                    } else {
                        // Normal header for Home, Archive, and TXD
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (activeDockTab == "txd" && (uiState.isTxdViewerOpen || uiState.activeTxdName != null)) {
                                IconButton(
                                    onClick = {
                                        viewModel.closeTxdViewer()
                                        activeDockTab = if (uiState.archive != null) "archive" else "home"
                                    },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Tutup editor TXD",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                val titleText = when (activeDockTab) {
                                    "home" -> "ImgTxd Studio"
                                    "archive" -> uiState.archive?.archiveName ?: "Arsip IMG"
                                    else -> uiState.activeTxdName ?: "Tekstur TXD"
                                }
                                val subtitleText = when (activeDockTab) {
                                    "home" -> "Editor arsip IMG & tekstur TXD"
                                    "archive" -> if (uiState.archive != null)
                                        "${uiState.archive?.version} • ${uiState.entries.size} file"
                                    else
                                        "Belum ada arsip terbuka"
                                    else -> if (uiState.txdEntries.isNotEmpty())
                                        "${uiState.txdEntries.size} tekstur RenderWare"
                                    else
                                        "Belum ada tekstur terbuka"
                                }

                                Text(
                                    text = titleText,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = subtitleText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = if ((activeDockTab == "archive" && uiState.archive != null) || (activeDockTab == "txd" && uiState.txdEntries.isNotEmpty())) FontFamily.Monospace else FontFamily.SansSerif,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (activeDockTab == "archive" && uiState.isShizukuBacked) {
                                        AssistChip(
                                            onClick = {},
                                            shape = CircleShape,
                                            label = {
                                                Text(
                                                    "Shizuku",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SemanticColors.OnSuccessContainer
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.Bolt,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(12.dp),
                                                    tint = SemanticColors.Success
                                                )
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = SemanticColors.SuccessContainer
                                            ),
                                            border = BorderStroke(1.dp, SemanticColors.Success.copy(alpha = 0.4f)),
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Shizuku Status Icon Button
                                IconButton(
                                    onClick = {
                                        if (!ShizukuManager.isShizukuAvailable()) {
                                            Toast.makeText(context, "Shizuku service tidak terdeteksi. Buka aplikasi Shizuku terlebih dahulu.", Toast.LENGTH_LONG).show()
                                        } else if (!ShizukuManager.hasPermission()) {
                                            ShizukuManager.requestPermission()
                                        } else {
                                            Toast.makeText(context, "Shizuku aktif: akses penuh Android/data tersedia.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    val tint = if (ShizukuManager.hasPermission()) SemanticColors.Success else SemanticColors.Warning
                                    Icon(Icons.Outlined.Bolt, contentDescription = "Status koneksi Shizuku", tint = tint)
                                }

                                if (activeDockTab == "archive" && uiState.archive != null) {
                                    IconButton(onClick = { isSearchExpanded = true }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Cari entri arsip", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { showRebuildDialog = true }) {
                                        Icon(Icons.Outlined.Build, contentDescription = "Rebuild dan rapikan arsip", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else if (activeDockTab == "txd" && uiState.txdEntries.isNotEmpty()) {
                                    IconButton(onClick = { isSearchExpanded = true }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Cari tekstur", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                IconButton(onClick = { showQuickMenuSheet = true }) {
                                    Icon(Icons.Outlined.MoreHoriz, contentDescription = "Menu cepat & opsi", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (activeDockTab) {
                    "home" -> {
                        HomeScreen(
                            hasStoragePermission = uiState.hasStoragePermission,
                            onGrantStorage = {
                                manageStorageLauncher.launch(StorageHelper.getManageStorageIntent(context))
                            },
                            recentFiles = recentFiles,
                            onOpenImg = { showOpenArchivePicker = true },
                            onOpenTxd = { showOpenTxdPicker = true },
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
                    }
                    "archive" -> {
                        if (uiState.archive == null) {
                            EmptyArchiveScreen(onOpenArchive = { showOpenArchivePicker = true })
                        } else {
                            ArchiveEntriesScreen(
                                uiState = uiState,
                                listState = listState,
                                onToggleSelect = { viewModel.toggleSelection(it) },
                                onShowOptions = { sheetEntryName = it.cleanName },
                                onOpenTxd = {
                                    viewModel.openTxdFromEntry(context, it)
                                    activeDockTab = "txd"
                                }
                            )
                        }
                    }
                    "txd" -> {
                        if (uiState.isTxdViewerOpen || uiState.activeTxdName != null) {
                            TxdEditorScreen(
                                txdName = uiState.activeTxdName ?: "Tekstur TXD",
                                textures = uiState.txdEntries,
                                isLoading = uiState.isTxdLoading,
                                statusMessage = uiState.statusMessage,
                                errorMessage = uiState.errorMessage,
                                onClose = {
                                    viewModel.closeTxdViewer()
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
                                showDock = false,
                                externalSearchQuery = txdSearchQuery
                            )
                        } else {
                            EmptyTxdScreen(onOpenTxd = { showOpenTxdPicker = true })
                        }
                    }
                }
            }
        }

        // Extended FAB Tambah Berkas (Only on archive tab when archive is loaded & not selecting)
        if (activeDockTab == "archive" && uiState.archive != null && !uiState.isSelecting) {
            ExtendedFloatingActionButton(
                onClick = { showAddFilePicker = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Tambah Berkas", fontWeight = FontWeight.SemiBold) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 96.dp)
            )
        }

        // Floating Pill Dock
        FloatingPillDock(
            items = dockItems,
            isSelectionMode = (activeDockTab == "archive" && uiState.isSelecting),
            selectedCount = uiState.selectedEntries.size,
            onExtractSelected = { showExtractSelectedPicker = true },
            onDeleteSelected = { showDeleteSelectedDialog = true },
            onCancelSelection = { viewModel.clearSelection() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // --- Bottom Sheets & Dialogs ---

    // File Options Bottom Sheet
    sheetEntryName?.let { name ->
        val selectedEntry = uiState.entries.find { it.cleanName == name }
        if (selectedEntry != null) {
            FileOptionsBottomSheet(
                entry = selectedEntry,
                onDismissRequest = { sheetEntryName = null },
                onOpenTxd = if (selectedEntry.cleanName.endsWith(".txd", ignoreCase = true)) {
                    { viewModel.openTxdFromEntry(context, selectedEntry) }
                } else null,
                onExtract = { entryToExtract = selectedEntry },
                onReplace = { entryToReplace = selectedEntry },
                onRename = { entryToRename = selectedEntry },
                onDelete = {
                    viewModel.clearSelection()
                    viewModel.toggleSelection(selectedEntry)
                    showDeleteSelectedDialog = true
                }
            )
        }
    }

    // Quick Menu Bottom Sheet
    if (showQuickMenuSheet) {
        QuickMenuBottomSheet(
            onDismissRequest = { showQuickMenuSheet = false },
            hasActiveArchive = uiState.archive != null,
            isShizukuActive = isShizukuReady,
            onOpenArchive = { showOpenArchivePicker = true },
            onOpenTxd = { showOpenTxdPicker = true },
            onAddFile = { showAddFilePicker = true },
            onRebuildArchive = { showRebuildDialog = true },
            onSelectAll = { viewModel.selectAll() },
            onOpenSettings = { showSettingsSheet = true },
            onRequestShizuku = {
                if (!ShizukuManager.isShizukuAvailable()) {
                    Toast.makeText(context, "Shizuku service tidak terdeteksi. Buka aplikasi Shizuku terlebih dahulu.", Toast.LENGTH_LONG).show()
                } else if (!ShizukuManager.hasPermission()) {
                    ShizukuManager.requestPermission()
                } else {
                    Toast.makeText(context, "Shizuku aktif: akses penuh Android/data tersedia.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(onDismissRequest = { showSettingsSheet = false })
    }

    // --- In-App File Pickers ---

    // 1. Open IMG Archive Picker
    if (showOpenArchivePicker) {
        FilePickerDialog(
            title = "Buka Archive IMG",
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

    // 2. Add File Picker
    if (showAddFilePicker) {
        FilePickerDialog(
            title = "Pilih File untuk Ditambahkan",
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showAddFilePicker = false },
            onFileSelected = { path, isShizuku ->
                showAddFilePicker = false
                viewModel.addFileFromPath(context, path, isShizuku)
            }
        )
    }

    // 3. Replace File Picker
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

    // 4. Extract Single Entry Picker
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

    // 5. Extract Selected Entries Picker
    if (showExtractSelectedPicker) {
        FilePickerDialog(
            title = "Ekstrak ${uiState.selectedEntries.size} File ke Folder...",
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

    // 6. Standalone TXD Picker
    if (showOpenTxdPicker) {
        FilePickerDialog(
            title = "Buka File Tekstur (TXD)",
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

    // Rename Dialog (Small AlertDialog: 28dp)
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

    // Rebuild Confirm Dialog (Small AlertDialog: 28dp)
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

    // Delete Selected Confirm Dialog (Small AlertDialog: 28dp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: ImgEntry,
    isSelectedProvider: () -> Boolean,
    isSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onShowOptions: () -> Unit,
    onOpenTxd: (() -> Unit)? = null
) {
    val isSelected = isSelectedProvider()
    val isTxd = entry.cleanName.endsWith(".txd", ignoreCase = true) && onOpenTxd != null

    val icon = when {
        entry.cleanName.endsWith(".dff", ignoreCase = true) -> Icons.Outlined.ViewInAr
        entry.cleanName.endsWith(".txd", ignoreCase = true) -> Icons.Outlined.Image
        entry.cleanName.endsWith(".col", ignoreCase = true) -> Icons.Outlined.Shield
        entry.cleanName.endsWith(".ipl", ignoreCase = true) -> Icons.Outlined.Map
        else -> Icons.Outlined.Description
    }

    val sizeKb = entry.sizeInBytes / 1024

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect()
                    } else if (isTxd) {
                        onOpenTxd?.invoke()
                    } else {
                        onShowOptions()
                    }
                },
                onLongClick = { onToggleSelect() }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            else
                MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else
                MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedColor = MaterialTheme.colorScheme.outline
                    )
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isTxd) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.cleanName,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Sektor: ${entry.offsetInSectors} • ${entry.sizeInSectors} sek (~$sizeKb KB)",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onShowOptions) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Opsi berkas ${entry.cleanName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    hasStoragePermission: Boolean,
    onGrantStorage: () -> Unit,
    recentFiles: List<RecentFileItem>,
    onOpenImg: () -> Unit,
    onOpenTxd: () -> Unit,
    onOpenRecent: (RecentFileItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
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
                                text = "Akses Semua File Diperlukan",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Android memerlukan izin penyimpanan untuk memodifikasi file game secara langsung.",
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
private fun EmptyArchiveScreen(onOpenArchive: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Belum Ada Arsip IMG Terbuka",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Pilih berkas arsip .img untuk melihat dan mengelola entri model 3D dan tekstur game.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onOpenArchive,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buka IMG", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun EmptyTxdScreen(onOpenTxd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                Text(
                    text = "Belum Ada Tekstur TXD Terbuka",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Buka berkas .txd untuk melihat gambar tekstur, ekspor ke format PNG, atau mengganti gambar mod game.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onOpenTxd,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buka TXD", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ArchiveEntriesScreen(
    uiState: ImgToolUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleSelect: (ImgEntry) -> Unit,
    onShowOptions: (ImgEntry) -> Unit,
    onOpenTxd: (ImgEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Loading / Progress bar
        if (uiState.isLoading) {
            if (uiState.progress != null) {
                val (current, total) = uiState.progress!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) current.toFloat() / total else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${uiState.statusMessage ?: "Memproses..."} ($current / $total)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "archive_header_info", contentType = "header_info") {
                val total = uiState.filteredEntries.size
                val shown = uiState.displayedEntries.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.searchQuery.isNotBlank()) "Hasil Pencarian" else "Daftar Berkas",
                        fontFamily = FontFamily.Serif,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (total == 0) "Tidak ada entri"
                        else if (shown < total) "Menampilkan $shown dari $total entri"
                        else "Total $total entri",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            items(
                items = uiState.displayedEntries,
                key = { "${it.cleanName}_${it.offsetInSectors}" },
                contentType = { "img_entry" }
            ) { entry ->
                EntryRow(
                    entry = entry,
                    isSelectedProvider = { uiState.selectedEntries.contains(entry) },
                    isSelectionMode = uiState.isSelecting,
                    onToggleSelect = { onToggleSelect(entry) },
                    onShowOptions = { onShowOptions(entry) },
                    onOpenTxd = { onOpenTxd(entry) }
                )
            }

            if (uiState.hasMorePages) {
                item(key = "pagination_loader", contentType = "loader") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Memuat entri (${uiState.displayedEntries.size} / ${uiState.filteredEntries.size})...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                               "Demi integritas sistem dan keamanan file game Anda, ImgTxd Studio tidak diizinkan berjalan dalam kondisi yang telah diubah.\n\n" +
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
