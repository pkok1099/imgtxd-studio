package com.onlasdan.studio.ui

import com.onlasdan.studio.Constants

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoSizeSelectActual
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewCompact
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.core.NativeCore
import com.onlasdan.studio.texdb.TexdbCache
import com.onlasdan.studio.texdb.TexdbTexture
import com.onlasdan.studio.txd.TxdStore
import com.onlasdan.studio.util.AppSettings
import com.onlasdan.studio.util.TxdViewMode
import com.onlasdan.studio.ui.framework.ArchiveEntryCard
import com.onlasdan.studio.ui.framework.ArchiveScreen
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ContextualPillDock
import com.onlasdan.studio.ui.components.BusySheet
import com.onlasdan.studio.ui.sheets.TexdbMoreActionsBottomSheet
import com.onlasdan.studio.ui.components.ChoiceSheet
import com.onlasdan.studio.ui.components.ConfirmSheet
import com.onlasdan.studio.ui.components.InputSheet
import com.onlasdan.studio.ui.components.MessageSheet
import com.onlasdan.studio.ui.components.SheetChoice
import com.onlasdan.studio.ui.components.SheetTitle
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.util.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Texdb cache browser + editor (Fase 2 read-only, Fase 3 editing).
 *
 * Editing runs on the Rust engine through [NativeCore] with TXD Tool's
 * import-wizard semantics: import from images / from TXD archives with a
 * format choice and a name-conflict policy, cache rebuild, and DFF checks.
 * When the cache was staged from a Shizuku-protected game folder
 * (EXTRA_GAME_DIR set), edits land in the staged copy and can be applied
 * back to the game folder from the menu.
 */
class TexdbBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 16 (targetSdk 36) memaksa edge-to-edge tanpa opt-out;
        // panggil eksplisit agar tampilan system bar konsisten di API 31+.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        val dirPath = intent.getStringExtra(EXTRA_CACHE_DIR)
        val gameDir = intent.getStringExtra(EXTRA_GAME_DIR)
        if (dirPath != null) {
            com.onlasdan.studio.util.ActiveSessionManager.setTexdbActive(dirPath, gameDir)
        }
        setContent {
            ImgToolTheme {
                TexdbBrowserScreen(
                    cacheDirPath = dirPath,
                    gameDirPath = gameDir,
                    onBack = {
                        // Kembali ke Home tanpa membunuh proses cache (tetap persisten di memori)
                        val homeIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(homeIntent)
                    },
                    onClose = {
                        com.onlasdan.studio.util.ActiveSessionManager.setTexdbClosed()
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            com.onlasdan.studio.util.ActiveSessionManager.setTexdbClosed()
        }
    }

    companion object {
        const val EXTRA_CACHE_DIR = Constants.IntentExtra.CACHE_DIR
        const val EXTRA_GAME_DIR = Constants.IntentExtra.GAME_DIR
    }
}

private data class WizardOptions(
    val format: Int = 0,
    val conflict: Int = 0,
    val siblingModel: String = "",
    val fastOverwrite: Boolean = false,
    val reduceEnabled: Boolean = true,
    val reduceTo: Int = 2048,
    val camnorm: Boolean = true,
    val hashCheck: Boolean = true,
    val correctCaps: Boolean = false,
    val useRle: Boolean = true,
    val genMips: Boolean = true,
    val mipFilter: Int = 1
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TexdbBrowserScreen(
    cacheDirPath: String?,
    gameDirPath: String?,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cache by remember { mutableStateOf<TexdbCache?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<TexdbTexture?>(null) }
    var showDestPicker by remember { mutableStateOf(false) }
    var showPngDestPicker by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }
    var showTxdPicker by remember { mutableStateOf(false) }
    var showDffPicker by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Pair<List<Pair<String, ByteArray>>, Boolean>?>(null) }
    var showImportSourceSheet by remember { mutableStateOf(false) }
    var showFormatSheet by remember { mutableStateOf(false) }
    var importFormat by remember { mutableStateOf(0) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var dffResult by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var sortMode by remember { mutableIntStateOf(0) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var namePrompt by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    var deleteTarget by remember { mutableStateOf<TexdbTexture?>(null) }
    var repackTarget by remember { mutableStateOf<Int?>(null) }
    var previewBg by remember { mutableIntStateOf(0) }
    var fullscreenPreview by remember { mutableStateOf(false) }
    // Same two preview modes as the TXD editor, sharing its saved preference.
    var viewMode by remember { mutableStateOf(AppSettings.cachedTxdViewMode) }
    // Bounded thumbnail cache: 1/8 of the app heap, sized by Bitmap bytes.
    // Unbounded maps grow to hundreds of MB when a 12k-entry cache is fully
    // scrolled; LruCache evicts least-recently-used thumbs instead, and a
    // re-appearing card simply re-decodes (launched per card below).
    val thumbCache = remember {
        object : LruCache<Int, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun toggleViewMode() {
        val next = if (viewMode == TxdViewMode.LARGE) TxdViewMode.SMALL else TxdViewMode.LARGE
        viewMode = next
        scope.launch { AppSettings.setTxdViewMode(context, next) }
    }

    suspend fun loadThumb(index: Int): Bitmap? {
        thumbCache.get(index)?.let { return it }
        if (cacheDirPath == null) return null
        val bmp = withContext(Dispatchers.IO) {
            val arr = NativeCore.texdbTexturePixels(cacheDirPath, index) ?: return@withContext null
            if (arr.size < 3) return@withContext null
            val w = arr[0]
            val h = arr[1]
            if (w <= 0 || h <= 0 || arr.size < 2 + w * h) return@withContext null
            // setPixels reads the header-prefixed array in place — one fewer
            // full-image copy than copyOfRange + createBitmap(int[], ...).
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.setPixels(arr, 2, w, 0, 0, w, h)
            }
        }
        if (bmp != null) thumbCache.put(index, bmp)
        return bmp
    }

    fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun reload() {
        reloadTick++
    }

    /** Runs one Rust cache operation with busy overlay + result toast. */
    fun runEngine(label: String, call: (String) -> String?) {
        if (cacheDirPath == null) return
        busyText = label
        scope.launch {
            val result = withContext(Dispatchers.IO) { call(cacheDirPath) }
            busyText = null
            when {
                result == null -> toast("Rust engine unavailable")
                result.startsWith("OK") -> {
                    toast("Done: ${result.removePrefix("OK ").replace(";", " · ")}")
                    selected = null
                    reload()
                }
                else -> toast("Failed: ${result.removePrefix("ERROR ")}")
            }
        }
    }

    LaunchedEffect(cacheDirPath, reloadTick) {
        if (cacheDirPath == null) {
            error = "Cache folder not provided"
            return@LaunchedEffect
        }
        thumbCache.evictAll()
        try {
            cache = withContext(Dispatchers.IO) {
                val dir = File(cacheDirPath)
                // The picker hands us the .txt file (or the folder itself)
                val cacheDir = if (dir.isFile) dir.parentFile ?: dir else dir
                TexdbCache.load(cacheDir)
            }
        } catch (e: Exception) {
            error = (e.localizedMessage ?: e.javaClass.simpleName) +
                "\nPick the cache folder directly (e.g. the 'txd' folder inside texdb) via Quick Menu -> Open Texdb Cache."
        }
    }

    // Preview for the selected texture, decoded by the Rust engine.
    LaunchedEffect(selected, cache) {
        preview = null
        val tex = selected ?: return@LaunchedEffect
        val loaded = cache ?: return@LaunchedEffect
        val index = loaded.textures.indexOfFirst { it === tex }
        if (index < 0 || cacheDirPath == null) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            val arr = NativeCore.texdbTexturePixels(cacheDirPath, index) ?: return@withContext null
            if (arr.size < 3) return@withContext null
            val w = arr[0]
            val h = arr[1]
            if (w <= 0 || h <= 0 || arr.size < 2 + w * h) return@withContext null
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.setPixels(arr, 2, w, 0, 0, w, h)
            }
        }
    }

    suspend fun stageIfShizuku(path: String, isShizuku: Boolean, stageDir: File): File {
        val src = File(path)
        if (!isShizuku && src.canRead()) return src
        stageDir.mkdirs()
        val dest = File(stageDir, src.name)
        withContext(Dispatchers.IO) {
            val (code, _) = ShizukuManager.exec("cp \"${src.absolutePath}\" \"${dest.absolutePath}\"")
            if (code != 0) throw IllegalStateException("Failed to copy file via Shizuku: ${src.name}")
        }
        return dest
    }

    fun runImport(images: List<Pair<String, ByteArray>>, options: WizardOptions) {
        if (cacheDirPath == null) return
        busyText = "Importing ${images.size} textures…"
        scope.launch {
            // Fast overwrite (TXD Tool) is folded into the import call
            // itself: the engine writes a clean, defragmented layout and
            // syncs once — no separate rebuild pass afterwards.
            val fastOverwrite = options.fastOverwrite && options.conflict == 1
            val result = withContext(Dispatchers.IO) {
                NativeCore.texdbImportImages(
                    cacheDirPath, images, options.format, options.conflict,
                    options.siblingModel, options.reduceTo, options.camnorm,
                    options.hashCheck, options.correctCaps, options.useRle, options.genMips, options.mipFilter,
                    defragment = fastOverwrite
                )
            }
            val rebuildNote = if (
                result != null && result.startsWith("OK") && fastOverwrite
            ) {
                " · cache dibangun ulang"
            } else {
                ""
            }
            busyText = null
            when {
                result == null -> toast("Rust engine unavailable on this device")
                result.startsWith("OK") -> {
                    toast("Import done: ${result.removePrefix("OK ").replace(";", " · ")}$rebuildNote")
                    reload()
                }
                else -> toast("Import failed: ${result.removePrefix("ERROR ")}")
            }
        }
    }

    val loadedCache = cache
    val categories = remember(loadedCache) {
        loadedCache?.textures?.map { it.category }?.distinct() ?: emptyList()
    }
    val filtered = remember(query, categoryFilter, sortMode, loadedCache) {
        val base = loadedCache?.textures?.filter {
            (categoryFilter == null || it.category == categoryFilter) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        } ?: emptyList()
        when (sortMode) {
            1 -> base.sortedByDescending { it.size }
            2 -> base.sortedByDescending { it.width * it.height }
            else -> base.sortedBy { it.name.lowercase() }
        }
    }
    val indexedFiltered = remember(filtered, loadedCache) {
        val loaded = loadedCache
        if (loaded == null) {
            emptyList()
        } else {
            filtered.map { tex -> loaded.textures.indexOfFirst { it === tex } to tex }
        }
    }

    ArchiveScreen(
        title = loadedCache?.let { stringResource(R.string.texdb_cache_name_format, it.name) } ?: stringResource(R.string.texdb_browser_title),
        subtitle = when {
            error != null -> stringResource(R.string.texdb_cache_failed_open)
            loadedCache == null -> stringResource(R.string.texdb_loading)
            else -> stringResource(R.string.texdb_subtitle_format, loadedCache.textures.size, loadedCache.variant)
        },
        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
        onShizukuClick = {
            if (!ShizukuManager.isShizukuAvailable()) {
                toast(context.getString(R.string.shizuku_not_detected_short))
            } else if (!ShizukuManager.hasPermission()) {
                ShizukuManager.requestPermission()
            }
        },
        items = indexedFiltered,
        keyExtractor = { (index, _) -> index.toString() },
        emptyTitle = when {
            error != null -> stringResource(R.string.texdb_empty_title_cant_open)
            query.isNotBlank() || categoryFilter != null -> stringResource(R.string.texdb_empty_title_no_results)
            else -> stringResource(R.string.texdb_empty_title_none)
        },
        emptyDescription = error ?: if (query.isNotBlank() || categoryFilter != null) {
            stringResource(R.string.texdb_empty_desc_filter)
        } else {
            stringResource(R.string.texdb_empty_desc_none)
        },
        isSearching = isSearching,
        searchQuery = query,
        searchPlaceholder = stringResource(R.string.texdb_search_placeholder),
        onSearchQueryChange = { query = it },
        onCloseSearch = {
            isSearching = false
            query = ""
        },
        gridColumns = if (viewMode == TxdViewMode.LARGE) 2 else 1,
        dockContent = {
            ContextualPillDock(
                items = listOf(
                    ContextualDockItem(
                        id = "home",
                        icon = Icons.Outlined.Home,
                        contentDescription = stringResource(R.string.home),
                        onClick = onBack
                    ),
                    ContextualDockItem(
                        id = "search",
                        icon = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.texdb_dock_search_desc),
                        isSelected = isSearching,
                        isEnabled = loadedCache != null,
                        onClick = { isSearching = !isSearching }
                    ),
                    ContextualDockItem(
                        id = "import",
                        icon = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.texdb_dock_import_desc),
                        isEnabled = loadedCache != null,
                        onClick = { showImportSourceSheet = true }
                    ),
                    ContextualDockItem(
                        id = "view",
                        icon = if (viewMode == TxdViewMode.LARGE)
                            Icons.Outlined.ViewCompact
                        else
                            Icons.Outlined.PhotoSizeSelectActual,
                        contentDescription = if (viewMode == TxdViewMode.LARGE)
                            stringResource(R.string.texdb_dock_switch_small)
                        else
                            stringResource(R.string.texdb_dock_switch_large),
                        onClick = { toggleViewMode() }
                    ),
                    ContextualDockItem(
                        id = "more",
                        icon = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.texdb_dock_more_desc),
                        isEnabled = loadedCache != null,
                        onClick = { menuOpen = true }
                    )
                )
            )
        },
        itemContent = { (index, tex) ->
            TexdbEntryCard(
                index = index,
                tex = tex,
                large = viewMode == TxdViewMode.LARGE,
                reloadTick = reloadTick,
                cachedThumb = thumbCache[index],
                onNeedThumb = { loadThumb(it) },
                onClick = { selected = tex }
            )
        }
    )

    if (menuOpen) {
        TexdbMoreActionsBottomSheet(
            onDismissRequest = { menuOpen = false },
            folderName = File(cacheDirPath ?: "").name.ifBlank { "Texdb Cache" },
            onImportImages = { showFormatSheet = true },
            onImportTxd = { showTxdPicker = true },
            onRebuildCache = {
                if (cacheDirPath != null) {
                    busyText = "Rebuilding cache…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            NativeCore.texdbRebuildCache(cacheDirPath)
                        }
                        busyText = null
                        when {
                            result == null -> toast("Rust engine unavailable")
                            result.startsWith("OK") -> {
                                toast("Rebuild done: ${result.removePrefix("OK ").replace(";", " · ")}")
                                reload()
                            }
                            else -> toast("Rebuild failed: ${result.removePrefix("ERROR ")}")
                        }
                    }
                }
            },
            onRepackDxt = { repackTarget = 0 },
            onRepackEtc = { repackTarget = 1 },
            onRepackRaw = { repackTarget = 2 },
            onCheckDff = { showDffPicker = true },
            onApplyToGame = if (gameDirPath != null) {
                {
                    busyText = "Copying cache to game folder…"
                    scope.launch {
                        val (code, _) = withContext(Dispatchers.IO) {
                            ShizukuManager.exec(
                                "cp -r \"$cacheDirPath/.\" \"$gameDirPath/\""
                            )
                        }
                        busyText = null
                        if (code == 0) toast("Cache applied to game folder")
                        else toast("Apply to game folder failed (code $code)")
                    }
                }
            } else null,
            onCacheInfo = { infoOpen = true },
            onSelectCategory = if (categories.size > 1) {
                { showCategorySheet = true }
            } else null,
            categoryName = categoryFilter,
            sortMode = sortMode,
            onSetSortMode = { sortMode = it },
            onCloseCache = onClose
        )
    }

    if (showCategorySheet) {
        val catChoices = buildList {
            add(SheetChoice("All" + if (categoryFilter == null) " ✓" else "") { categoryFilter = null })
            categories.forEach { cat ->
                add(SheetChoice(cat + if (categoryFilter == cat) " ✓" else "") { categoryFilter = cat })
            }
        }
        ChoiceSheet(
            title = "Category",
            choices = catChoices,
            onDismissRequest = { showCategorySheet = false }
        )
    }

    if (busyText != null) {
        BusySheet(busyText!!)
    }

    selected?.let { tex ->
        FloatingSheet(onDismissRequest = { selected = null }) {
            SheetTitle(tex.name)
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
val bgColors = listOf(
    MaterialTheme.colorScheme.surfaceVariant,
    Color.White, Color.Black, Color.Gray,
    MaterialTheme.colorScheme.primary
)
preview?.let { bmp ->
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp)
            .background(bgColors[previewBg])
            .clickable { fullscreenPreview = true }
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = tex.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        bgColors.forEachIndexed { i, c ->
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape)
                    .background(c)
                    .clickable { previewBg = i }
            )
        }
        Text(
            "Tap image for fullscreen",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
                val flagText = if (tex.flags.isNotEmpty()) stringResource(R.string.texdb_flag_format, tex.flags.entries.joinToString { "${it.key}=${it.value}" }) else ""
                Text(
                    stringResource(
                        R.string.texdb_detail_info_format,
                        tex.width,
                        tex.height,
                        tex.size,
                        tex.offset,
                        "%08x".format(tex.hash),
                        if (tex.hasMipmaps) "ya" else "tidak",
                        tex.pngCrc ?: "-",
                        tex.imgCrc ?: "-",
                        flagText
                    )
                )
                
            }
Row {
    TextButton(onClick = { showPngDestPicker = true }) {
        Text(stringResource(R.string.texdb_export_png))
    }
    TextButton(onClick = { showDestPicker = true }) {
        Text(stringResource(R.string.texdb_extract_raw))
    }
}
val affiliateTarget = tex.flags["affiliate"]
Row {
    if (affiliateTarget != null) {
        TextButton(onClick = {
            selected = cache?.textures?.firstOrNull {
                it.name.equals(affiliateTarget, ignoreCase = true)
            } ?: selected
        }) { Text(stringResource(R.string.texdb_go_to_original)) }
    } else {
        TextButton(onClick = {
            namePrompt = Triple(context.getString(R.string.texdb_rename_texture_title), tex.name) { newName ->
                val idx = cache?.textures?.indexOfFirst { it === tex } ?: -1
                if (idx >= 0) runEngine(context.getString(R.string.texdb_rename_busy)) { dir ->
                    NativeCore.texdbRenameTexture(dir, idx, newName)
                }
            }
        }) { Text(stringResource(R.string.rename)) }
        TextButton(onClick = {
            namePrompt = Triple(context.getString(R.string.texdb_create_alias_title), "${tex.name}_alias") { aliasName ->
                val idx = cache?.textures?.indexOfFirst { it === tex } ?: -1
                if (idx >= 0) runEngine(context.getString(R.string.texdb_create_alias_busy)) { dir ->
                    NativeCore.texdbAddAlias(dir, idx, aliasName)
                }
            }
        }) { Text(stringResource(R.string.texdb_create_alias)) }
    }
    TextButton(onClick = { deleteTarget = tex }) {
        Text(stringResource(R.string.texdb_delete), color = MaterialTheme.colorScheme.error)
    }
}
                
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { selected = null },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.texdb_close), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (showDestPicker && selected != null && cache != null) {
        FilePickerDialog(
            title = stringResource(R.string.texdb_pick_extract_dest),
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showDestPicker = false },
            onFileSelected = { folderPath, _ ->
                showDestPicker = false
                val tex = selected!!
                val loaded = cache!!
                scope.launch {
                    try {
                        val out = File(folderPath, "${tex.name}.bin")
                        withContext(Dispatchers.IO) { loaded.extractEntry(tex, out) }
                        toast("Entri diekstrak: ${out.name} (${out.length()} byte)")
                    } catch (e: Exception) {
                        toast("Extract failed: ${e.localizedMessage}")
                    }
                    selected = null
                }
            }
        )
    }

    if (showPngDestPicker && selected != null) {
        FilePickerDialog(
            title = stringResource(R.string.texdb_pick_png_dest),
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showPngDestPicker = false },
            onFileSelected = { folderPath, _ ->
                showPngDestPicker = false
                val tex = selected!!
                val bmp = preview
                scope.launch {
                    try {
                        if (bmp == null) throw IllegalStateException(context.getString(R.string.preview_not_available))
                        val out = File(folderPath, "${tex.name}.png")
                        withContext(Dispatchers.IO) {
                            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        toast(context.getString(R.string.texdb_png_exported, out.name))
                    } catch (e: Exception) {
                        toast(context.getString(R.string.texdb_png_export_failed, e.localizedMessage ?: ""))
                    }
                    selected = null
                }
            }
        )
    }

    if (showImagePicker) {
        FilePickerDialog(
            title = stringResource(R.string.texdb_pick_texture_image),
            mode = PickerMode.PICK_MULTIPLE_IMAGES,
            onDismissRequest = { showImagePicker = false },
            onMultipleFilesSelected = { paths, isShizuku ->
                showImagePicker = false
                busyText = context.getString(R.string.texdb_reading_images_busy, paths.size)
                scope.launch {
                    try {
                        val stageDir = File(context.cacheDir, "texdb_import_stage")
                        val jobs = withContext(Dispatchers.IO) {
                            paths.map { path ->
                                val file = stageIfShizuku(path, isShizuku, stageDir)
                                file.nameWithoutExtension to file.readBytes()
                            }
                        }
                        busyText = null
                        pendingImport = jobs to false
                    } catch (e: Exception) {
                        busyText = null
                        toast(context.getString(R.string.texdb_failed_read_image, e.localizedMessage ?: ""))
                    }
                }
            }
        )
    }

    if (showTxdPicker) {
        FilePickerDialog(
            title = stringResource(R.string.texdb_pick_txd_archive),
            mode = PickerMode.PICK_MULTIPLE_TXD,
            onDismissRequest = { showTxdPicker = false },
            onMultipleFilesSelected = { paths, isShizuku ->
                showTxdPicker = false
                busyText = context.getString(R.string.texdb_reading_txd_busy, paths.size)
                scope.launch {
                    try {
                        val stageDir = File(context.cacheDir, "texdb_import_stage")
                        val jobs = withContext(Dispatchers.IO) {
                            val out = ArrayList<Pair<String, ByteArray>>()
                            for (path in paths) {
                                val file = stageIfShizuku(path, isShizuku, stageDir)
                                val parser = TxdStore.load(file)
                                for (entry in parser.entries) {
                                    val bmp = entry.bitmap ?: continue
                                    val stream = java.io.ByteArrayOutputStream()
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                    out.add(entry.name to stream.toByteArray())
                                }
                            }
                            out
                        }
                        busyText = null
                        if (jobs.isEmpty()) toast(context.getString(R.string.texdb_no_texture_from_txd))
                        else pendingImport = jobs to true
                    } catch (e: Exception) {
                        busyText = null
                        toast(context.getString(R.string.texdb_failed_read_txd, e.localizedMessage ?: ""))
                    }
                }
            }
        )
    }

    if (showDffPicker) {
        FilePickerDialog(
            title = stringResource(R.string.texdb_pick_model_file),
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showDffPicker = false },
            onFileSelected = { path, isShizuku ->
                showDffPicker = false
                if (cacheDirPath != null) {
                    busyText = "Checking DFF textures…"
                    scope.launch {
                        try {
                            val stageDir = File(context.cacheDir, "texdb_import_stage")
                            val dff = withContext(Dispatchers.IO) { stageIfShizuku(path, isShizuku, stageDir) }
                            val result = withContext(Dispatchers.IO) {
                                NativeCore.texdbCheckDff(cacheDirPath, dff.absolutePath)
                            }
                            busyText = null
                            dffResult = when {
                                result == null -> "Rust engine unavailable"
                                result.startsWith("OK") -> {
                                    val missing = result.removePrefix("OK").trim()
                                    if (missing.isEmpty()) "All textures the model needs are in the cache."
                                    else "Textures not yet in the cache:\n$missing"
                                }
                                else -> "Check failed: ${result.removePrefix("ERROR ")}"
                            }
                        } catch (e: Exception) {
                            busyText = null
                            dffResult = "Check failed: ${e.localizedMessage}"
                        }
                    }
                }
            }
        )
    }

    if (showImportSourceSheet) {
        ChoiceSheet(
            title = "Import Source",
            choices = listOf(
                SheetChoice("From TXD Archives") {
                    showTxdPicker = true
                },
                SheetChoice("From Images") {
                    showFormatSheet = true
                }
            ),
            onDismissRequest = { showImportSourceSheet = false }
        )
    }

    if (showFormatSheet) {
        val formatLabels = listOf(
            stringResource(R.string.texdb_format_with_alpha),
            stringResource(R.string.texdb_format_without_alpha),
            stringResource(R.string.texdb_format_uncompressed_with_alpha),
            stringResource(R.string.texdb_format_uncompressed_without_alpha),
            stringResource(R.string.texdb_format_uncompressed_32bpp),
            stringResource(R.string.texdb_format_uncompressed_low_mem)
        )
        ChoiceSheet(
            title = stringResource(R.string.texdb_import_format_title),
            choices = formatLabels.mapIndexed { index, label ->
                SheetChoice(label) {
                    importFormat = index
                    showImagePicker = true
                }
            },
            onDismissRequest = { showFormatSheet = false }
        )
    }

    pendingImport?.let { (jobs, fromTxd) ->
        ImportWizardSheet(
            count = jobs.size,
            fromTxd = fromTxd,
            initialFormat = importFormat,
            onDismiss = { pendingImport = null },
            onConfirm = { options ->
                pendingImport = null
                runImport(
                    jobs,
                    options.copy(reduceTo = if (options.reduceEnabled) options.reduceTo else 0)
                )
            }
        )
    }

    if (infoOpen && cache != null) {
        val loaded = cache!!
        MessageSheet(
            title = stringResource(R.string.texdb_info_title, loaded.name),
            message = stringResource(
                R.string.texdb_info_message_format,
                loaded.variant,
                loaded.variants.joinToString(),
                loaded.textures.size,
                loaded.datFile.name,
                loaded.datFile.length() / 1024,
                loaded.directory.absolutePath
            ),
            onDismissRequest = { infoOpen = false }
        )
    }

    dffResult?.let { result ->
        MessageSheet(
            title = stringResource(R.string.texdb_dff_check_result),
            message = result,
            onDismissRequest = { dffResult = null }
        )
    }

    namePrompt?.let { (title, initial, action) ->
        InputSheet(
            title = title,
            label = stringResource(R.string.texdb_name_label),
            initial = initial,
            onDismissRequest = { namePrompt = null },
            onConfirm = { v ->
                namePrompt = null
                action(v)
            }
        )
    }

    deleteTarget?.let { tex ->
        ConfirmSheet(
            title = stringResource(R.string.texdb_delete_dialog_title),
            message = stringResource(R.string.texdb_delete_confirm_msg, tex.name),
            confirmLabel = stringResource(R.string.texdb_delete),
            danger = true,
            onDismissRequest = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                selected = null
                val idx = cache?.textures?.indexOfFirst { it === tex } ?: -1
                if (idx >= 0) runEngine(context.getString(R.string.texdb_deleting_busy)) { dir ->
                    NativeCore.texdbDeleteTextures(dir, intArrayOf(idx))
                }
            }
        )
    }

    repackTarget?.let { target ->
        val targetName = listOf("DXT", "ETC", "Mentah (565/4444)")[target]
        val etcWarning = if (target == 1) stringResource(R.string.texdb_repack_etc_warning) else ""
        ConfirmSheet(
            title = stringResource(R.string.texdb_repack_dialog_title, targetName),
            message = stringResource(R.string.texdb_repack_confirm_msg, targetName, etcWarning),
            confirmLabel = stringResource(R.string.texdb_repack_confirm),
            onDismissRequest = { repackTarget = null },
            onConfirm = {
                repackTarget = null
                runEngine(context.getString(R.string.texdb_repack_busy, targetName)) { dir ->
                    NativeCore.texdbRepackCache(dir, target)
                }
            }
        )
    }

    if (fullscreenPreview && preview != null) {
        val bgColors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            Color.White, Color.Black, Color.Gray,
            MaterialTheme.colorScheme.primary
        )
        FloatingSheet(onDismissRequest = { fullscreenPreview = false }) {
            Box(
                modifier = Modifier.fillMaxWidth().height(320.dp)
                    .background(bgColors[previewBg])
                    .clickable { fullscreenPreview = false }
            ) {
                Image(
                    bitmap = preview!!.asImageBitmap(),
                    contentDescription = selected?.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bgColors.forEachIndexed { i, c ->
                    Box(
                        modifier = Modifier.size(22.dp).clip(CircleShape)
                            .background(c)
                            .clickable { previewBg = i }
                    )
                }
                Text(
                    "Tap image to close",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ImportWizardSheet(
    count: Int,
    fromTxd: Boolean,
    initialFormat: Int,
    onDismiss: () -> Unit,
    onConfirm: (WizardOptions) -> Unit
) {
    var options by remember { mutableStateOf(WizardOptions(format = initialFormat)) }
    val conflicts = listOf(
        stringResource(R.string.texdb_wizard_conflict_add),
        stringResource(R.string.texdb_wizard_conflict_overwrite),
        stringResource(R.string.texdb_wizard_conflict_sibling)
    )
    val reduceChoices = listOf(4096, 2048, 1024, 512, 256, 128)
    FloatingSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.texdb_import_wizard_title))
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.texdb_existing_texture_label), fontWeight = FontWeight.SemiBold)
                conflicts.forEachIndexed { index, label ->
                    Row(modifier = Modifier.clickable { options = options.copy(conflict = index) }) {
                        RadioButton(
                            selected = options.conflict == index,
                            onClick = { options = options.copy(conflict = index) }
                        )
                        Text(label, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                if (options.conflict == 2) {
                    OutlinedTextField(
                        value = options.siblingModel,
                        onValueChange = { options = options.copy(siblingModel = it) },
                        label = { Text(stringResource(R.string.texdb_dff_model_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                WizardCheckbox(
                    stringResource(R.string.texdb_wizard_fast_overwrite),
                    options.fastOverwrite
                ) { options = options.copy(fastOverwrite = it) }
                WizardCheckbox(
                    stringResource(R.string.texdb_wizard_reduce_size),
                    options.reduceEnabled
                ) { options = options.copy(reduceEnabled = it) }
                if (options.reduceEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        reduceChoices.take(3).forEach { size ->
                            FilterChip(
                                selected = options.reduceTo == size,
                                onClick = { options = options.copy(reduceTo = size) },
                                label = { Text(stringResource(R.string.texdb_px_format, size)) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        reduceChoices.drop(3).forEach { size ->
                            FilterChip(
                                selected = options.reduceTo == size,
                                onClick = { options = options.copy(reduceTo = size) },
                                label = { Text(stringResource(R.string.texdb_px_format, size)) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                WizardCheckbox(stringResource(R.string.texdb_wizard_camnorm), options.camnorm) { options = options.copy(camnorm = it) }
                WizardCheckbox(stringResource(R.string.texdb_wizard_hash_check), options.hashCheck) { options = options.copy(hashCheck = it) }
                WizardCheckbox(stringResource(R.string.texdb_wizard_correct_caps), options.correctCaps) { options = options.copy(correctCaps = it) }
                WizardCheckbox(stringResource(R.string.texdb_wizard_rle), options.useRle) { options = options.copy(useRle = it) }
                WizardCheckbox(stringResource(R.string.texdb_wizard_generate_mipmaps), options.genMips) { options = options.copy(genMips = it) }
                if (options.genMips) {
                    Text(stringResource(R.string.texdb_mipmap_filter_label), fontWeight = FontWeight.SemiBold)
                    val filterNames = listOf("Nearest", "Box", "Linear", "Cubic", "Gaussian", "Lanczos4")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        filterNames.take(3).forEachIndexed { index, label ->
                            FilterChip(
                                selected = options.mipFilter == index,
                                onClick = { options = options.copy(mipFilter = index) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        filterNames.drop(3).forEachIndexed { i, label ->
                            val index = i + 3
                            FilterChip(
                                selected = options.mipFilter == index,
                                onClick = { options = options.copy(mipFilter = index) },
                                label = { Text(label) }
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onConfirm(options) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.texdb_import_button), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun WizardCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}


/** One cache texture as an ArchiveEntryCard — the same card the TXD editor
 * uses, in both its modes (small list row / large 2-column gallery). The
 * thumbnail is decoded lazily by the Rust engine when the card appears. */
@Composable
private fun TexdbEntryCard(
    index: Int,
    tex: TexdbTexture,
    large: Boolean,
    reloadTick: Int,
    cachedThumb: Bitmap?,
    onNeedThumb: suspend (Int) -> Bitmap?,
    onClick: () -> Unit
) {
    var bmp by remember(index, reloadTick) { mutableStateOf(cachedThumb) }
    LaunchedEffect(index, reloadTick) {
        if (bmp == null) bmp = onNeedThumb(index)
    }
    val affiliate = tex.flags["affiliate"]
    ArchiveEntryCard(
        title = tex.name,
        subtitle = if (large) {
            "${tex.width}×${tex.height}"
        } else {
            (if (affiliate != null) "Alias → $affiliate · " else "") +
                "${tex.width}×${tex.height} · ${tex.size / 1024} KB" +
                if (tex.hasMipmaps) " · mipmap" else ""
        },
        bitmap = bmp,
        isLargeImage = large,
        onClick = onClick,
        onLongClick = {}
    )
}
