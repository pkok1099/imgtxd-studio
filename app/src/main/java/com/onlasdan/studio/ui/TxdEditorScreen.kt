package com.onlasdan.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.txd.TxdEntry
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.ShizukuManager
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
    onReplaceTexture: (entry: TxdEntry, imagePath: String, isShizuku: Boolean) -> Unit,
    onAutoReplace: (matches: List<Pair<TxdEntry, String>>, isShizuku: Boolean) -> Unit,
    onClearMessages: (() -> Unit)? = null,
    showDock: Boolean = true,
    externalSearchQuery: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val effectiveSearchQuery = externalSearchQuery ?: searchQuery

    val displayedTextures = remember(textures, effectiveSearchQuery) {
        if (effectiveSearchQuery.isBlank()) textures
        else textures.filter { it.name.contains(effectiveSearchQuery.trim(), ignoreCase = true) }
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

    var activeTextureName by remember { mutableStateOf(textures.firstOrNull()?.name) }
    LaunchedEffect(textures) {
        if (activeTextureName == null || textures.none { it.name == activeTextureName }) {
            activeTextureName = textures.firstOrNull()?.name
        }
    }
    val previewEntry = textures.find { it.name == activeTextureName } ?: textures.firstOrNull()

    // Dialog & Picker states
    var entryToReplace by remember { mutableStateOf<TxdEntry?>(null) }
    var entryToExport by remember { mutableStateOf<TxdEntry?>(null) }
    var showExportAllPicker by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Auto Replace states
    var showAutoReplaceModeChoice by remember { mutableStateOf(false) }
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

    val txdDockItems = listOf(
        DockTabItem(
            id = "textures",
            icon = Icons.Outlined.PhotoLibrary,
            contentDescription = "Daftar Tekstur",
            isSelected = true,
            onClick = {}
        ),
        DockTabItem(
            id = "auto_replace",
            icon = Icons.Outlined.AutoFixHigh,
            contentDescription = "Auto Replace Tekstur",
            isSelected = showAutoReplaceModeChoice,
            onClick = { showAutoReplaceModeChoice = true }
        ),
        DockTabItem(
            id = "export_all",
            icon = Icons.Outlined.FileDownload,
            contentDescription = "Ekspor Semua PNG",
            isSelected = false,
            onClick = { showExportAllPicker = true }
        ),
        DockTabItem(
            id = "settings",
            icon = Icons.Outlined.Settings,
            contentDescription = "Pengaturan",
            isSelected = showSettingsDialog,
            onClick = { showSettingsDialog = true }
        )
    )

    val mainContent = @Composable { contentPadding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            if (textures.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isLoading) "Memuat tekstur..." else "Tidak ada tekstur di dalam file TXD ini",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Sub-header (konsisten dengan ArchiveEntriesScreen)
                    item(key = "txd_header_info", contentType = "header_info") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (effectiveSearchQuery.isNotBlank()) "Hasil Pencarian" else "Daftar Tekstur",
                                fontFamily = FontFamily.Serif,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (displayedTextures.size < textures.size) "Menampilkan ${displayedTextures.size} dari ${textures.size} tekstur"
                                else "Total ${textures.size} tekstur",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    // 2. Hero Preview Card for Active Texture (Gaya Pill Capsule dari Comic Reader)
                    previewEntry?.let { active ->
                        item(key = "txd_hero_preview", contentType = "hero_preview") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bmp = active.bitmap
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = active.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Text(
                                                text = "Preview tidak tersedia untuk format ini",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))

                                    Column {
                                        Text(
                                            text = active.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${active.dimensionsLabel} • ${active.formatLabel} • ${active.depth}-bit",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(Modifier.height(14.dp))

                                    // Action Pill Buttons side-by-side (Pill capsule)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { entryToReplace = active },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.SwapHoriz,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Ganti Tekstur",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = { entryToExport = active },
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.FileDownload,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Ekspor PNG",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Texture list rows (identik dengan EntryRow di ArchiveEntriesScreen)
                    items(
                        items = displayedTextures,
                        key = { "${it.name}_${it.width}_${it.height}" },
                        contentType = { "texture_entry" }
                    ) { entry ->
                        val isSelected = (previewEntry?.name == entry.name)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activeTextureName = entry.name },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else
                                    MaterialTheme.colorScheme.surfaceContainer
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
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
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val bmp = entry.bitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = entry.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${entry.dimensionsLabel} • ${entry.formatLabel} • ${entry.depth}-bit",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(onClick = { entryToReplace = entry }) {
                                    Icon(
                                        imageVector = Icons.Outlined.SwapHoriz,
                                        contentDescription = "Ganti tekstur ${entry.name}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Extended FAB Auto Replace (Above Dock)
            if (textures.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAutoReplaceModeChoice = true },
                    icon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                    text = { Text("Auto Replace", fontWeight = FontWeight.SemiBold) },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 96.dp)
                )
            }

            // Floating Pill Dock (khusus mode standalone Window)
            if (showDock) {
                FloatingPillDock(
                    items = txdDockItems,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            if (isLoading || isScanningFolder) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isScanningFolder) "Memindai gambar..." else "Memproses tekstur...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDock) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSearchExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                isSearchExpanded = false
                                searchQuery = ""
                            }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Tutup pencarian")
                            }
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Cari tekstur, misal cj_face...") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Outlined.Close, contentDescription = "Hapus pencarian")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = CircleShape,
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = onClose, modifier = Modifier.padding(end = 4.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = txdName.ifEmpty { "TXD Editor" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (textures.isEmpty()) "0 Tekstur" else "${textures.size} Tekstur RenderWare",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (textures.isNotEmpty()) {
                                    IconButton(onClick = { isSearchExpanded = true }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Cari tekstur", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { showExportAllPicker = true }) {
                                        Icon(Icons.Outlined.FileDownload, contentDescription = "Ekspor semua PNG", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "Pengaturan", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            mainContent(padding)
        }
    } else {
        mainContent(PaddingValues(0.dp))
    }

    // --- Sub-Dialogs & Pickers ---

    // 1. Pick folder for Export Single
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

    // 2. Pick folder for Export All
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

    // 3. Pick image to Replace Texture (Single)
    entryToReplace?.let { target ->
        FilePickerDialog(
            title = "Pilih Gambar untuk ${target.name}",
            mode = PickerMode.PICK_IMAGE,
            onDismissRequest = { entryToReplace = null },
            onFileSelected = { imgPath, isShizuku ->
                val targetEntry = entryToReplace
                entryToReplace = null
                if (targetEntry != null) {
                    onReplaceTexture(targetEntry, imgPath, isShizuku)
                }
            }
        )
    }

    // 4. Auto Replace Mode Chooser (Bottom Sheet)
    if (showAutoReplaceModeChoice) {
        AutoReplaceBottomSheet(
            onDismissRequest = { showAutoReplaceModeChoice = false },
            onSelectFolder = {
                showAutoReplaceFolderPicker = true
            },
            onSelectMultiFiles = {
                showAutoReplaceMultiFilePicker = true
            }
        )
    }

    // 5. Auto Replace Folder Picker
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

    // 6. Auto Replace Multi-File Picker
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

    // 7. Auto Replace Confirmation Dialog (Small AlertDialog: 28dp)
    autoReplaceMatches?.let { matches ->
        AlertDialog(
            onDismissRequest = { autoReplaceMatches = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Konfirmasi Auto-Replace", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Ditemukan ${matches.size} tekstur yang cocok. Tekstur berikut akan otomatis diganti:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(matches) { (tex, path) ->
                                val fileName = File(path).name
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = tex.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "(${tex.dimensionsLabel})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("➔", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targets = autoReplaceMatches ?: return@Button
                        autoReplaceMatches = null
                        onAutoReplace(targets, isAutoReplaceShizuku)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Ganti Sekarang (${matches.size})", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { autoReplaceMatches = null },
                    shape = CircleShape
                ) {
                    Text("Batal")
                }
            }
        )
    }

    // 8. No Matches Warning Dialog (Small AlertDialog: 28dp)
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

    // 9. Settings Bottom Sheet
    if (showSettingsDialog) {
        SettingsBottomSheet(onDismissRequest = { showSettingsDialog = false })
    }
}
