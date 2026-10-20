package com.onlasdan.studio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.core.ImgVersion
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.util.AppIntegrity
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.StorageHelper
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ImgToolViewModel by viewModels()
    private var isTampered by mutableStateOf(false)

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
        enableEdgeToEdge()
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

            handleIntent(intent)
        }

        setContent {
            ImgToolTheme {
                if (isTampered) {
                    TamperedScreen()
                } else {
                    MainScreen(viewModel = viewModel)
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
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null) {
            viewModel.openArchiveFromUri(this, uri)
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
    var showAddFilePicker by remember { mutableStateOf(false) }
    var entryToReplace by remember { mutableStateOf<ImgEntry?>(null) }
    var showExtractSelectedPicker by remember { mutableStateOf(false) }
    var entryToExtract by remember { mutableStateOf<ImgEntry?>(null) }

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
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = uiState.archive?.archiveName ?: "GTA Img Studio",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uiState.isShizukuBacked) {
                                Spacer(modifier = Modifier.width(6.dp))
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Shizuku", style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(12.dp)) },
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
                        val tint = if (ShizukuManager.hasPermission()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        Icon(Icons.Default.Bolt, contentDescription = "Shizuku Status", tint = tint)
                    }

                    if (uiState.archive != null) {
                        // Search button
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }

                        // Rebuild button
                        IconButton(onClick = { showRebuildDialog = true }) {
                            Icon(Icons.Default.Build, contentDescription = "Rebuild Archive")
                        }

                        // Selection Actions
                        if (uiState.isSelecting) {
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                            }
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                            }
                        }
                    }

                    // Open archive button (In-App File Explorer)
                    IconButton(onClick = { showOpenArchivePicker = true }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Archive")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.isSelecting) {
                BottomAppBar {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.selectedEntries.size} selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row {
                            Button(
                                onClick = { showExtractSelectedPicker = true }
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Extract")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { showDeleteSelectedDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (uiState.archive != null && !uiState.isSelecting) {
                FloatingActionButton(
                    onClick = { showAddFilePicker = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add File")
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
                                text = "All Files Access Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Android 11-16 requires permission to edit game archives directly in storage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Button(
                            onClick = {
                                manageStorageLauncher.launch(StorageHelper.getManageStorageIntent(context))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Grant")
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
                    placeholder = { Text("Search by name (e.g. cj.dff, vehicle.txd)...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${uiState.statusMessage ?: "Processing..."} ($current / $total)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "No Archive Opened",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Open a GTA .img archive (VER1 or VER2). With Shizuku, you can access Android/data game folders directly!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { showOpenArchivePicker = true }
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Pilih File IMG")
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
                            onDelete = { viewModel.deleteSelected() }
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
                viewModel.openArchiveFromPath(context, path, isShizuku)
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

    // Rename Dialog
    entryToRename?.let { entry ->
        var newNameText by remember { mutableStateOf(entry.cleanName) }
        AlertDialog(
            onDismissRequest = { entryToRename = null },
            title = { Text("Rename Entry") },
            text = {
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
                    label = { Text("Entry Name (Max 24 chars)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newNameText.isNotBlank()) {
                            viewModel.renameEntry(entry, newNameText.trim())
                            entryToRename = null
                        }
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rebuild Confirm Dialog
    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("Rebuild Archive") },
            text = {
                Text(
                    "Rebuilding compacts all entries sequentially and eliminates fragmented or deleted sector space.\n\n" +
                    (if (uiState.isShizukuBacked) "Perubahan akan otomatis disimpan kembali ke folder game via Shizuku.\n\n" else "") +
                    "Make sure your archive is backed up before rebuilding."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildDialog = false
                        viewModel.rebuildArchive()
                    }
                ) {
                    Text("Rebuild")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Selected Confirm Dialog
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete Selected") },
            text = {
                Text("Are you sure you want to delete ${uiState.selectedEntries.size} entries from the archive?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteSelectedDialog = false
                        viewModel.deleteSelected()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = {
                if (isSelectionMode) {
                    onToggleSelect()
                } else {
                    menuExpanded = true
                }
            },
            onLongClick = { onToggleSelect() }
        ),
        leadingContent = {
            if (isSelectionMode) {
                val isSelected = isSelectedProvider()
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else {
                val icon = when {
                    entry.cleanName.endsWith(".dff", ignoreCase = true) -> Icons.Default.ViewInAr
                    entry.cleanName.endsWith(".txd", ignoreCase = true) -> Icons.Default.Image
                    entry.cleanName.endsWith(".col", ignoreCase = true) -> Icons.Default.Shield
                    entry.cleanName.endsWith(".ipl", ignoreCase = true) -> Icons.Default.Map
                    else -> Icons.Default.Description
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        headlineContent = {
            Text(
                text = entry.cleanName,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val sizeKb = (entry.sizeInBytes) / 1024
            Text(
                text = "Sector: ${entry.offsetInSectors} • ${entry.sizeInSectors} sectors (~$sizeKb KB)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                if (menuExpanded) {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Extract") },
                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onExtract()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Replace") },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onReplace()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
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
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Peringatan Keamanan",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = "Integritas Aplikasi Terlanggar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Aplikasi ini mendeteksi perubahan nama paket (repackaging) atau modifikasi kode yang tidak sah. " +
                               "Demi integritas sistem dan keamanan file game Anda, GTA Img Studio tidak diizinkan berjalan dalam kondisi yang telah diubah.\n\n" +
                               "Aplikasi akan otomatis ditutup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
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
