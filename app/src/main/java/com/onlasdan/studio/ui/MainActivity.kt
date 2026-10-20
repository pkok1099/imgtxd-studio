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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.core.ImgVersion
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.AppIntegrity
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.LauncherFileType
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.StorageHelper
import com.onlasdan.studio.util.WindowLauncher
import kotlinx.coroutines.delay
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
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                title = { Text("Perubahan Belum Disimpan") },
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
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text("Simpan & Buka")
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
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        Text("Buang")
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
    val uiState by viewModel.uiState.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
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
    var showSettingsDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = uiState.archive?.archiveName ?: "ImgTxd Studio",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (uiState.isShizukuBacked) {
                                Spacer(modifier = Modifier.width(6.dp))
                                AssistChip(
                                    onClick = {},
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
                        uiState.archive?.let { archive ->
                            Text(
                                text = "${archive.version} • ${uiState.entries.size} files" +
                                        (if (uiState.originalPath != null) " • ${File(uiState.originalPath!!).parent}" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    // Shizuku Status Icon / Quick Permission Request
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

                    if (uiState.archive != null) {
                        // Search button
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Cari entri arsip")
                        }

                        // Rebuild button
                        IconButton(onClick = { showRebuildDialog = true }) {
                            Icon(Icons.Outlined.Build, contentDescription = "Rebuild dan rapikan arsip")
                        }

                        // Selection Actions
                        if (uiState.isSelecting) {
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(Icons.Outlined.SelectAll, contentDescription = "Pilih semua entri")
                            }
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Batal pilih")
                            }
                        }
                    }

                    // Open TXD button (Standalone Texture Viewer)
                    IconButton(onClick = { showOpenTxdPicker = true }) {
                        Icon(Icons.Outlined.Palette, contentDescription = "Buka file tekstur TXD")
                    }

                    // Open archive button (In-App File Explorer)
                    IconButton(onClick = { showOpenArchivePicker = true }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Buka arsip IMG")
                    }

                    // Settings button
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Pengaturan aplikasi")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.isSelecting) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.selectedEntries.size} terpilih",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row {
                            Button(
                                onClick = { showExtractSelectedPicker = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Ekstrak")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { showDeleteSelectedDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Hapus")
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (uiState.archive != null && !uiState.isSelecting) {
                FloatingActionButton(
                    onClick = { showAddFilePicker = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Tambah file ke arsip")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Storage permission warning banner
            if (!uiState.hasStoragePermission) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
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
                            onClick = {
                                manageStorageLauncher.launch(StorageHelper.getManageStorageIntent(context))
                            },
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

            // Search bar
            AnimatedVisibility(visible = showSearch) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Cari berdasarkan nama (misal cj.dff, vehicle.txd)...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Hapus pencarian")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    singleLine = true
                )
            }

            // Loading / Progress bar
            if (uiState.isLoading) {
                if (uiState.progress != null) {
                    val (current, total) = uiState.progress!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { if (total > 0) current.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${uiState.statusMessage ?: "Memproses..."} ($current / $total)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }

            // Main Content
            if (uiState.archive == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "ImgTxd Studio",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Buka dan kelola arsip .img serta tekstur .txd untuk seri Grand Theft Auto (SA, VC, III) dan Bully. Akses langsung folder Android/data menggunakan Shizuku tanpa root!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(20.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { showOpenArchivePicker = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Buka IMG")
                                }
                                OutlinedButton(
                                    onClick = { showOpenTxdPicker = true },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Icon(Icons.Outlined.Palette, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Buka TXD")
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item(key = "archive_header_info", contentType = "header_info") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val total = uiState.filteredEntries.size
                                val shown = uiState.displayedEntries.size
                                Text(
                                    text = if (total == 0) "Tidak ada entri"
                                    else if (shown < total) "Menampilkan $shown dari $total entri"
                                    else "Total $total entri",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${uiState.archive?.version ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                            onToggleSelect = { viewModel.toggleSelection(entry) },
                            onExtract = { entryToExtract = entry },
                            onReplace = { entryToReplace = entry },
                            onRename = { entryToRename = entry },
                            onDelete = { viewModel.deleteSelected() },
                            onOpenTxd = { viewModel.openTxdFromEntry(context, entry) }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
                                        strokeWidth = 2.dp
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
                    }
                )
            }
        )
    }

    // 7. TXD Texture Viewer Dialog
    if (uiState.isTxdViewerOpen) {
        TxdViewerDialog(
            txdName = uiState.activeTxdName ?: "Tekstur TXD",
            textures = uiState.txdEntries,
            isLoading = uiState.isTxdLoading,
            onDismissRequest = { viewModel.closeTxdViewer() },
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
            }
        )
    }

    // Rename Dialog
    entryToRename?.let { entry ->
        var newNameText by remember { mutableStateOf(entry.cleanName) }
        AlertDialog(
            onDismissRequest = { entryToRename = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Ubah Nama Entri") },
            text = {
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
                    label = { Text("Nama Berkas (Maks 24 karakter)") },
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToRename = null }) {
                    Text("Batal")
                }
            }
        )
    }

    // Rebuild Confirm Dialog
    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Rebuild Arsip IMG") },
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Rebuild Sekarang")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Delete Selected Confirm Dialog
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Hapus Entri Terpilih") },
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Hapus (${uiState.selectedEntries.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // 8. Settings Dialog
    if (showSettingsDialog) {
        SettingsDialog(onDismissRequest = { showSettingsDialog = false })
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    entry: ImgEntry,
    isSelectedProvider: () -> Boolean,
    isSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onExtract: () -> Unit,
    onReplace: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenTxd: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = {
                if (isSelectionMode) {
                    onToggleSelect()
                } else if (entry.cleanName.endsWith(".txd", ignoreCase = true) && onOpenTxd != null) {
                    onOpenTxd()
                } else {
                    menuExpanded = true
                }
            },
            onLongClick = { onToggleSelect() }
        ),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        leadingContent = {
            if (isSelectionMode) {
                val isSelected = isSelectedProvider()
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
                val icon = when {
                    entry.cleanName.endsWith(".dff", ignoreCase = true) -> Icons.Outlined.ViewInAr
                    entry.cleanName.endsWith(".txd", ignoreCase = true) -> Icons.Outlined.Image
                    entry.cleanName.endsWith(".col", ignoreCase = true) -> Icons.Outlined.Shield
                    entry.cleanName.endsWith(".ipl", ignoreCase = true) -> Icons.Outlined.Map
                    else -> Icons.Outlined.Description
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        headlineContent = {
            Text(
                text = entry.cleanName,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val sizeKb = (entry.sizeInBytes) / 1024
            Text(
                text = "Sektor: ${entry.offsetInSectors} • ${entry.sizeInSectors} sektor (~$sizeKb KB)",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Opsi untuk ${entry.cleanName}")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    if (entry.cleanName.endsWith(".txd", ignoreCase = true) && onOpenTxd != null) {
                        DropdownMenuItem(
                            text = { Text("Buka Tekstur (TXD)") },
                            leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                            onClick = {
                                menuExpanded = false
                                onOpenTxd()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Ekstrak") },
                        leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onExtract()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ganti") },
                        leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onReplace()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ubah Nama") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    )
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
