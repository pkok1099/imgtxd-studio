package com.onlasdan.studio.ui

import android.os.Environment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.onlasdan.studio.R
import com.onlasdan.studio.ui.theme.SemanticColors
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.ShizukuFileItem
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.StorageHelper
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
    PICK_MULTIPLE_TXD,
    PICK_OSW,
    PICK_TEXDB_TXT,
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

private val pickerThumbnailCache = object : LruCache<String, Bitmap>(64) {
    override fun sizeOf(key: String, value: Bitmap): Int = 1
}

@Composable
private fun PickerImageThumbnail(
    path: String,
    isShizuku: Boolean,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(path) { mutableStateOf(pickerThumbnailCache.get(path)) }

    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sampleSize = 1
                    val req = 96
                    while (bounds.outWidth / (sampleSize * 2) >= req && bounds.outHeight / (sampleSize * 2) >= req) {
                        sampleSize *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val loaded = BitmapFactory.decodeFile(file.absolutePath, opts)
                    if (loaded != null) {
                        pickerThumbnailCache.put(path, loaded)
                        withContext(Dispatchers.Main) {
                            bitmap = loaded
                        }
                    }
                } else if (isShizuku && ShizukuManager.isRestrictedPath(path) && ShizukuManager.hasPermission()) {
                    val temp = File.createTempFile("picker_thumb_", ".tmp")
                    if (ShizukuManager.copyToLocal(path, temp)) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(temp.absolutePath, bounds)
                        var sampleSize = 1
                        val req = 96
                        while (bounds.outWidth / (sampleSize * 2) >= req && bounds.outHeight / (sampleSize * 2) >= req) {
                            sampleSize *= 2
                        }
                        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        val loaded = BitmapFactory.decodeFile(temp.absolutePath, opts)
                        temp.delete()
                        if (loaded != null) {
                            pickerThumbnailCache.put(path, loaded)
                            withContext(Dispatchers.Main) {
                                bitmap = loaded
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore thumbnail decode error, fallback to vector icon
            }
        }
    }

    val currentBmp = bitmap
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            modifier = modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    val context = LocalContext.current
    val defaultRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
    val isFolderPicker = mode == PickerMode.PICK_FOLDER ||
        mode == PickerMode.SELECT_FOLDER ||
        mode == PickerMode.SELECT_FOLDER_OR_IMG

    var currentPath by remember {
        mutableStateOf(initialPath ?: AppSettings.pickerStartDir(isFolderPicker, defaultRoot))
    }
    var fileItems by remember { mutableStateOf<List<UnifiedFileItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shizukuGranted by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    var allFilesAccess by remember { mutableStateOf(StorageHelper.hasManageStoragePermission()) }
    var needsStorageAccess by remember { mutableStateOf(false) }

    // Refresh the All Files Access state when the user returns from Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = StorageHelper.hasManageStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Remember the last visited folder per picker context (file vs folder).
    LaunchedEffect(currentPath) {
        AppSettings.setLastDir(context, isFolderPicker, currentPath)
    }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    val isMultiSelectMode = mode == PickerMode.PICK_MULTIPLE_IMAGES || mode == PickerMode.PICK_MULTIPLE_TXD

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
                    needsStorageAccess = false
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
                        errorMessage = context.getString(R.string.picker_err_need_shizuku)
                    }
                } else {
                    val dir = File(path)
                    // null means the File API could not read the directory at
                    // all (no All Files Access on shared storage) — distinct
                    // from a genuinely empty directory.
                    val listed = withContext(Dispatchers.IO) {
                        dir.listFiles()?.toList()
                    }
                    when {
                        listed != null && listed.isNotEmpty() -> {
                            needsStorageAccess = false
                            items.addAll(listed.map {
                                UnifiedFileItem(
                                    name = it.name,
                                    path = it.absolutePath,
                                    isDirectory = it.isDirectory,
                                    size = if (it.isDirectory) 0 else it.length(),
                                    isShizuku = false
                                )
                            })
                        }
                        ShizukuManager.hasPermission() -> {
                            needsStorageAccess = false
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
                        }
                        listed == null && !allFilesAccess -> {
                            // Offer the All Files Access grant in place instead
                            // of a misleading "empty directory" state.
                            needsStorageAccess = true
                        }
                        listed == null -> {
                            errorMessage = context.getString(R.string.picker_err_cant_read)
                        }
                        else -> {
                            // Genuinely empty directory.
                            needsStorageAccess = false
                        }
                    }
                }

                val filtered = when (mode) {
                    PickerMode.OPEN_IMG -> items.filter {
                        it.isDirectory || it.name.endsWith(".img", ignoreCase = true) || it.name.endsWith(".dir", ignoreCase = true)
                    }
                    PickerMode.OPEN_TXD, PickerMode.PICK_MULTIPLE_TXD -> items.filter {
                        it.isDirectory || it.name.endsWith(".txd", ignoreCase = true)
                    }
                    PickerMode.PICK_OSW -> items.filter {
                        it.isDirectory || it.name.endsWith(".osw", ignoreCase = true)
                    }
                    PickerMode.PICK_TEXDB_TXT -> items.filter {
                        it.isDirectory || it.name.endsWith(".txt", ignoreCase = true)
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
                errorMessage = context.getString(R.string.picker_err_read_failed, e.localizedMessage ?: "")
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

    LaunchedEffect(currentPath, shizukuGranted, allFilesAccess) {
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
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.picker_back_desc))
                    }

                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentPath.replace(defaultRoot, stringResource(R.string.picker_root_storage)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { dismissWithAnimation() }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.picker_close_desc))
                    }
                }

                // Shortcut chips
                val shortcuts = listOf(
                    "SA texdb" to "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files/texdb",
                    "SA files" to "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files",
                    "VC" to "/storage/emulated/0/Android/data/com.rockstargames.gtavc/files",
                    "III" to "/storage/emulated/0/Android/data/com.rockstargames.gta3/files",
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
                                if (targetPath.contains("com.rockstargames")) {
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
                                        stringResource(R.string.picker_shizuku_mode_active)
                                    else
                                        stringResource(R.string.picker_shizuku_mode_needed),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (shizukuGranted) SemanticColors.OnSuccessContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (!shizukuGranted) {
                                    Text(
                                        text = stringResource(R.string.picker_shizuku_needed_desc),
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
                                    Text(stringResource(R.string.picker_permission_allow))
                                }
                            }
                        }
                    }
                }

                // All Files Access banner: direct File access to shared
                // folders (Download, mod workspaces) is impossible without it.
                if (needsStorageAccess && !allFilesAccess) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.picker_all_files_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.picker_all_files_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Button(
                                onClick = {
                                    context.startActivity(StorageHelper.getManageStorageIntent(context))
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(stringResource(R.string.grant_permission))
                            }
                        }
                    }
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.picker_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.archive_clear_search))
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

                if (isMultiSelectMode) {
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
                                text = stringResource(R.string.picker_selection_counter, selectedPaths.size, selectableFiles.size),
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
                                Text(if (allSelected) stringResource(R.string.picker_select_none) else stringResource(R.string.picker_select_all))
                            }
                        }
                    }
                }

                // File list
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            text = stringResource(R.string.picker_folder_empty),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = if (mode == PickerMode.SELECT_FOLDER || mode == PickerMode.PICK_FOLDER || mode == PickerMode.SELECT_FOLDER_OR_IMG || isMultiSelectMode) 8.dp else 48.dp
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
                                        } else if (isMultiSelectMode) {
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
                                        if (isMultiSelectMode && !item.isDirectory) {
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
                                        } else if (isImageFile(item.name)) {
                                            PickerImageThumbnail(
                                                path = item.path,
                                                isShizuku = item.isShizuku
                                            )
                                        } else {
                                            val icon = when {
                                                item.isDirectory -> Icons.Outlined.Folder
                                                item.name.endsWith(".img", ignoreCase = true) -> Icons.Outlined.Archive
                                                item.name.endsWith(".dff", ignoreCase = true) -> Icons.Outlined.ViewInAr
                                                item.name.endsWith(".txd", ignoreCase = true) -> Icons.Outlined.Image
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
                                                text = if (sizeKb > 1024) stringResource(R.string.mb_format, sizeKb / 1024) else stringResource(R.string.picker_kb_format, sizeKb),
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
                                                label = { Text(stringResource(R.string.picker_shizuku_badge), style = MaterialTheme.typography.labelSmall, color = SemanticColors.OnSuccessContainer) },
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
                if (isMultiSelectMode) {
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
                            text = stringResource(R.string.picker_selected_count_bottom, selectedPaths.size),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row {
                            TextButton(
                                onClick = { dismissWithAnimation() },
                                shape = CircleShape
                            ) {
                                Text(stringResource(R.string.cancel))
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
                                Text(stringResource(R.string.picker_continue_count, selectedPaths.size), fontWeight = FontWeight.SemiBold)
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
                            Text(stringResource(R.string.cancel))
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
                            Text(stringResource(R.string.picker_pick_this_folder), fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}
