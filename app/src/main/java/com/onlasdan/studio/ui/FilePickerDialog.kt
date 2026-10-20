package com.onlasdan.studio.ui

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.util.ShizukuFileItem
import com.onlasdan.studio.util.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PickerMode {
    OPEN_IMG,
    PICK_FILE,
    SELECT_FOLDER
}

data class UnifiedFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val isShizuku: Boolean = false
)

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
    onFileSelected: (path: String, isShizuku: Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val defaultRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    var currentPath by remember { mutableStateOf(initialPath ?: defaultRoot) }
    var fileItems by remember { mutableStateOf<List<UnifiedFileItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shizukuGranted by remember { mutableStateOf(ShizukuManager.hasPermission()) }

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
                    PickerMode.PICK_FILE -> items
                    PickerMode.SELECT_FOLDER -> items.filter { it.isDirectory }
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
                    alpha = (animProgress * 0.55f).coerceIn(0f, 0.55f)
                }
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() }
                )
        )

        // Bottom Drawer Surface
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(2.5.dp)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { dismissWithAnimation() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
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
                            label = { Text(label) },
                            leadingIcon = {
                                if (label.startsWith("GTA")) {
                                    Icon(Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }

                // Shizuku status banner
                if (isCurrentPathRestricted) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (shizukuGranted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (shizukuGranted) Icons.Default.Bolt else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (shizukuGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
                                    color = if (shizukuGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
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
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                val displayedItems = remember(fileItems, searchQuery) {
                    if (searchQuery.isBlank()) fileItems
                    else fileItems.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
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
                            Icon(Icons.Default.FolderOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
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
                                bottom = if (mode == PickerMode.SELECT_FOLDER) 8.dp else 48.dp
                            )
                        ) {
                            items(
                                items = displayedItems,
                                key = { it.path },
                                contentType = { if (it.isDirectory) "dir" else "file" }
                            ) { item ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        if (item.isDirectory) {
                                            currentPath = item.path
                                        } else {
                                            selectWithAnimation(item.path, item.isShizuku)
                                        }
                                    },
                                    leadingContent = {
                                        val icon = when {
                                            item.isDirectory -> Icons.Default.Folder
                                            item.name.endsWith(".img", ignoreCase = true) -> Icons.Default.Archive
                                            item.name.endsWith(".dff", ignoreCase = true) -> Icons.Default.ViewInAr
                                            item.name.endsWith(".txd", ignoreCase = true) -> Icons.Default.Image
                                            else -> Icons.Default.InsertDriveFile
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = item.name,
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
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        if (item.isShizuku) {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text("Shizuku", style = MaterialTheme.typography.labelSmall) },
                                                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // If folder picker mode
                if (mode == PickerMode.SELECT_FOLDER) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { dismissWithAnimation() }) {
                            Text("Batal")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { selectWithAnimation(currentPath, isCurrentPathRestricted && shizukuGranted) }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pilih Folder Ini")
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}
