package com.onlasdan.studio.ui

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.ShizukuFileItem
import com.onlasdan.studio.util.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PickerMode {
    OPEN_IMG,
    OPEN_TXD,
    PICK_FILE,
    PICK_IMAGE,
    PICK_MULTIPLE_IMAGES,
    PICK_FOLDER,
    SELECT_FOLDER,
    SELECT_FOLDER_OR_IMG
}

data class UnifiedFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val isShizuku: Boolean = false
)

private fun isImageFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
           lower.endsWith(".bmp") || lower.endsWith(".webp")
}

private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1.0f)
private val EaseInCubic = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

private suspend fun animateFloatTo(
    from: Float,
    to: Float,
    durationMs: Long,
    easing: CubicBezierEasing = EaseOutCubic,
    onFrame: (Float) -> Unit
) {
    val durationNanos = durationMs * 1_000_000L
    var startNanos = -1L
    while (true) {
        val isDone = withFrameNanos { now ->
            if (startNanos < 0L) startNanos = now
            val elapsed = now - startNanos
            val rawFraction = (elapsed.toFloat() / durationNanos).coerceIn(0f, 1f)
            val eased = easing.transform(rawFraction)
            val value = from + (to - from) * eased
            onFrame(value)
            rawFraction >= 1f
        }
        if (isDone) break
    }
}

@Composable
fun FilePickerDialog(
    title: String,
    mode: PickerMode,
    initialPath: String? = null,
    onDismissRequest: () -> Unit,
    onFileSelected: (path: String, isShizuku: Boolean) -> Unit = { _, _ -> },
    onMultipleFilesSelected: (paths: List<String>, isShizuku: Boolean) -> Unit = { _, _ -> }
) {
    val coroutineScope = rememberCoroutineScope()
    val defaultRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    var currentPath by remember { mutableStateOf(initialPath ?: defaultRoot) }
    var fileItems by remember { mutableStateOf<List<UnifiedFileItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shizukuGranted by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }

    var animProgress by remember { mutableFloatStateOf(0f) }
    var isClosing by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
    }

    fun dismissWithAnimation() {
        if (isClosing) return
        isClosing = true
        coroutineScope.launch {
            animateFloatTo(
                from = animProgress,
                to = 0f,
                durationMs = 200,
                easing = EaseInCubic
            ) { animProgress = it }
            onDismissRequest()
        }
    }

    fun selectWithAnimation(path: String, isShizuku: Boolean) {
        if (isClosing) return
        isClosing = true
        coroutineScope.launch {
            animateFloatTo(
                from = animProgress,
                to = 0f,
                durationMs = 180,
                easing = EaseInCubic
            ) { animProgress = it }
            onFileSelected(path, isShizuku)
        }
    }

    fun selectMultipleWithAnimation(paths: List<String>, isShizuku: Boolean) {
        if (isClosing) return
        isClosing = true
        coroutineScope.launch {
            animateFloatTo(
                from = animProgress,
                to = 0f,
                durationMs = 180,
                easing = EaseInCubic
            ) { animProgress = it }
            onMultipleFilesSelected(paths, isShizuku)
        }
    }

    LaunchedEffect(Unit) {
        animateFloatTo(
            from = 0f,
            to = 1f,
            durationMs = 260,
            easing = EaseOutCubic
        ) { animProgress = it }
    }

    val isCurrentPathRestricted = remember(currentPath) {
        ShizukuManager.isRestrictedPath(currentPath)
    }

    fun loadDirectory(path: String) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val items = mutableListOf<UnifiedFileItem>()
                val isRestricted = ShizukuManager.isRestrictedPath(path)

                if (isRestricted) {
                    if (ShizukuManager.hasPermission()) {
                        val shizukuFiles = ShizukuManager.listFiles(path)
                        items.addAll(shizukuFiles.map {
                            UnifiedFileItem(
                                name = it.name,
                                path = it.path,
                                isDirectory = it.isDirectory,
                                size = it.size,
                                isShizuku = true
                            )
                        })
                    } else {
                        errorMessage = "Akses direktori ini memerlukan izin Shizuku."
                    }
                } else {
                    val dir = File(path)
                    val files = withContext(Dispatchers.IO) {
                        dir.listFiles()?.toList() ?: emptyList()
                    }
                    if (files.isEmpty() && ShizukuManager.hasPermission()) {
                        val shizukuFiles = ShizukuManager.listFiles(path)
                        items.addAll(shizukuFiles.map {
                            UnifiedFileItem(
                                name = it.name,
                                path = it.path,
                                isDirectory = it.isDirectory,
                                size = it.size,
                                isShizuku = true
                            )
                        })
                    } else {
                        items.addAll(files.map {
                            UnifiedFileItem(
                                name = it.name,
                                path = it.absolutePath,
                                isDirectory = it.isDirectory,
                                size = if (it.isDirectory) 0 else it.length(),
                                isShizuku = false
                            )
                        })
                    }
                }

                val filtered = when (mode) {
                    PickerMode.OPEN_IMG -> items.filter {
                        it.isDirectory || it.name.endsWith(".img", ignoreCase = true) || it.name.endsWith(".dir", ignoreCase = true)
                    }
                    PickerMode.OPEN_TXD -> items.filter {
                        it.isDirectory || it.name.endsWith(".txd", ignoreCase = true)
                    }
                    PickerMode.PICK_IMAGE, PickerMode.PICK_MULTIPLE_IMAGES -> items.filter {
                        it.isDirectory || isImageFile(it.name)
                    }
                    PickerMode.PICK_FILE -> items
                    PickerMode.PICK_FOLDER, PickerMode.SELECT_FOLDER -> items.filter { it.isDirectory }
                    PickerMode.SELECT_FOLDER_OR_IMG -> items.filter { it.isDirectory || it.name.endsWith(".img", ignoreCase = true) }
                }

                fileItems = filtered.sortedWith(compareBy<UnifiedFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                errorMessage = "Gagal membaca direktori: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuManager.SHIZUKU_REQUEST_CODE) {
                shizukuGranted = (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        }
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        onDispose {
            try {
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(currentPath, shizukuGranted) {
        loadDirectory(currentPath)
    }

    BackHandler(enabled = true) {
        val parent = File(currentPath).parent
        if (currentPath != defaultRoot && currentPath != "/" && parent != null) {
            currentPath = parent
        } else {
            dismissWithAnimation()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Scrim (Darkened backdrop)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (animProgress * 0.6f).coerceIn(0f, 0.6f)
                }
                .background(MaterialTheme.colorScheme.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() }
                )
        )

        // Bottom Drawer Surface (Unified Gray Background)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    val slideOffset = (1f - animProgress) * size.height
                    translationY = slideOffset + dragOffsetY
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .imePadding()
            ) {
                // Drag Handle area with draggable gesture
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                        .draggable(
                            state = draggableState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                if (dragOffsetY > 200f || velocity > 1000f) {
                                    dismissWithAnimation()
                                } else {
                                    coroutineScope.launch {
                                        animateFloatTo(dragOffsetY, 0f, 150) { dragOffsetY = it }
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.5.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    )
                }

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canGoUp = currentPath.length > 1 && File(currentPath).parent != null
                    IconButton(
                        onClick = {
                            val parent = File(currentPath).parent
                            if (parent != null) {
                                currentPath = parent
                            }
                        },
                        enabled = canGoUp
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali ke folder sebelumnya")
                    }

                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentPath.replace(defaultRoot, "Storage"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { dismissWithAnimation() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Tutup pemilih berkas")
                    }
                }

                // Shortcut chips
                val shortcuts = listOf(
                    "GTA SA texdb" to "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files/texdb",
                    "GTA SA files" to "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files",
                    "GTA VC" to "/storage/emulated/0/Android/data/com.rockstargames.gtavc/files",
                    "GTA III" to "/storage/emulated/0/Android/data/com.rockstargames.gta3/files",
                    "Downloads" to "/storage/emulated/0/Download",
                    "Storage" to defaultRoot
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    shortcuts.forEach { (label, targetPath) ->
                        AssistChip(
                            onClick = { currentPath = targetPath },
                            shape = CircleShape,
                            label = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                if (label.startsWith("GTA")) {
                                    Icon(Icons.Outlined.SportsEsports, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }

                // Shizuku status banner
                if (isCurrentPathRestricted) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (shizukuGranted)
                                SemanticColors.SuccessContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (shizukuGranted) SemanticColors.Success.copy(alpha = 0.4f) else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (shizukuGranted) Icons.Outlined.Bolt else Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = if (shizukuGranted) SemanticColors.Success else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (shizukuGranted)
                                        "Mode Shizuku Aktif — Akses Penuh Android/data"
                                    else
                                        "Akses Android/data memerlukan Shizuku",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (shizukuGranted) SemanticColors.OnSuccessContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (!shizukuGranted) {
                                    Text(
                                        text = "Beri izin Shizuku untuk membaca dan memodifikasi file game.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            if (!shizukuGranted) {
                                Button(
                                    onClick = {
                                        ShizukuManager.requestPermission()
                                        shizukuGranted = ShizukuManager.hasPermission()
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("Izinkan")
                                }
                            }
                        }
                    }
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cari nama file...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Hapus pencarian")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                val displayedItems = remember(fileItems, searchQuery) {
                    if (searchQuery.isBlank()) fileItems
                    else fileItems.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
                }

                if (mode == PickerMode.PICK_MULTIPLE_IMAGES) {
                    val selectableFiles = remember(displayedItems) { displayedItems.filter { !it.isDirectory } }
                    if (selectableFiles.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedPaths.size} dari ${selectableFiles.size} gambar dipilih",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    val allSelected = selectableFiles.all { selectedPaths.contains(it.path) }
                                    if (allSelected) {
                                        val toRemove = selectableFiles.map { it.path }.toSet()
                                        selectedPaths = selectedPaths - toRemove
                                    } else {
                                        val toAdd = selectableFiles.map { it.path }
                                        selectedPaths = selectedPaths + toAdd
                                    }
                                }
                            ) {
                                val allSelected = selectableFiles.all { selectedPaths.contains(it.path) }
                                Text(if (allSelected) "Batal Semua" else "Pilih Semua")
                            }
                        }
                    }
                }

                // File list
                Box(modifier = Modifier.weight(1f)) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (errorMessage != null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.FolderOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (displayedItems.isEmpty()) {
                        Text(
                            text = "Direktori kosong atau tidak ada file yang cocok.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = if (mode == PickerMode.SELECT_FOLDER || mode == PickerMode.PICK_FOLDER || mode == PickerMode.SELECT_FOLDER_OR_IMG || mode == PickerMode.PICK_MULTIPLE_IMAGES) 8.dp else 48.dp
                            )
                        ) {
                            items(
                                items = displayedItems,
                                key = { it.path },
                                contentType = { if (it.isDirectory) "dir" else "file" }
                            ) { item ->
                                val isItemSelected = selectedPaths.contains(item.path)
                                ListItem(
                                    modifier = Modifier.clickable {
                                        if (item.isDirectory) {
                                            currentPath = item.path
                                        } else if (mode == PickerMode.PICK_MULTIPLE_IMAGES) {
                                            selectedPaths = if (isItemSelected) {
                                                selectedPaths - item.path
                                            } else {
                                                selectedPaths + item.path
                                            }
                                        } else {
                                            selectWithAnimation(item.path, item.isShizuku)
                                        }
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isItemSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        else
                                            Color.Transparent,
                                        headlineColor = MaterialTheme.colorScheme.onSurface,
                                        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    leadingContent = {
                                        if (mode == PickerMode.PICK_MULTIPLE_IMAGES && !item.isDirectory) {
                                            Checkbox(
                                                checked = isItemSelected,
                                                onCheckedChange = { checked ->
                                                    selectedPaths = if (checked) selectedPaths + item.path else selectedPaths - item.path
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = MaterialTheme.colorScheme.primary,
                                                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                                    uncheckedColor = MaterialTheme.colorScheme.outline
                                                )
                                            )
                                        } else {
                                            val icon = when {
                                                item.isDirectory -> Icons.Outlined.Folder
                                                item.name.endsWith(".img", ignoreCase = true) -> Icons.Outlined.Archive
                                                item.name.endsWith(".dff", ignoreCase = true) -> Icons.Outlined.ViewInAr
                                                item.name.endsWith(".txd", ignoreCase = true) -> Icons.Outlined.Image
                                                isImageFile(item.name) -> Icons.Outlined.Image
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
                                            text = item.name,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    supportingContent = {
                                        if (!item.isDirectory) {
                                            val sizeKb = item.size / 1024
                                            Text(
                                                text = if (sizeKb > 1024) "${sizeKb / 1024} MB" else "$sizeKb KB",
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        if (item.isShizuku) {
                                            AssistChip(
                                                onClick = {},
                                                shape = CircleShape,
                                                label = { Text("Shizuku", style = MaterialTheme.typography.labelSmall, color = SemanticColors.OnSuccessContainer) },
                                                leadingIcon = { Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(12.dp), tint = SemanticColors.Success) },
                                                colors = AssistChipDefaults.assistChipColors(containerColor = SemanticColors.SuccessContainer),
                                                border = BorderStroke(1.dp, SemanticColors.Success.copy(alpha = 0.4f))
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom actions bar
                if (mode == PickerMode.PICK_MULTIPLE_IMAGES) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedPaths.size} file dipilih",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row {
                            TextButton(
                                onClick = { dismissWithAnimation() },
                                shape = CircleShape
                            ) {
                                Text("Batal")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (selectedPaths.isNotEmpty()) {
                                        selectMultipleWithAnimation(selectedPaths.toList(), isCurrentPathRestricted && shizukuGranted)
                                    }
                                },
                                enabled = selectedPaths.isNotEmpty(),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lanjutkan (${selectedPaths.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else if (mode == PickerMode.SELECT_FOLDER || mode == PickerMode.PICK_FOLDER || mode == PickerMode.SELECT_FOLDER_OR_IMG) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { dismissWithAnimation() },
                            shape = CircleShape
                        ) {
                            Text("Batal")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { selectWithAnimation(currentPath, isCurrentPathRestricted && shizukuGranted) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pilih Folder Ini", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}
