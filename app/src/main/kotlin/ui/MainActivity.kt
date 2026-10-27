package com.onlasdan.studio.ui

import com.onlasdan.studio.Constants

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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.onlasdan.studio.R
import com.onlasdan.studio.core.ImgEntry
import com.onlasdan.studio.process.AppProcess
import com.onlasdan.studio.process.DefaultProcessRepository
import com.onlasdan.studio.process.ProcessRepositoryLocator
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.ui.components.BusySheet
import com.onlasdan.studio.ui.components.ConfirmSheet
import com.onlasdan.studio.ui.components.InputSheet
import com.onlasdan.studio.ui.components.MessageSheet
import com.onlasdan.studio.ui.components.SheetTitle
import com.onlasdan.studio.ui.settings.RuntimeSettingsSheet
import com.onlasdan.studio.ui.framework.ArchiveEntryCard
import com.onlasdan.studio.ui.framework.ArchiveScreen
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ContextualPillDock
import com.onlasdan.studio.ui.framework.rememberPhysicsDockScrollBehavior
import com.onlasdan.studio.ui.sheets.AboutBottomSheet
import com.onlasdan.studio.ui.sheets.ImgEntryBottomSheet
import com.onlasdan.studio.ui.sheets.MoreActionsBottomSheet
import com.onlasdan.studio.ui.sheets.OperationsHistorySheet
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import com.onlasdan.studio.core.NativeCore
import rikka.shizuku.Shizuku
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAVIGATE_TAB = Constants.IntentExtra.NAVIGATE_TAB
    }

    private val viewModel: ImgToolViewModel by viewModels()
    private var isTampered by mutableStateOf(false)
    private var requestedTab by mutableStateOf<String?>(null)

    // Unsaved changes handling for onNewIntent
    private var pendingIntentToLoad by mutableStateOf<Intent?>(null)
    private var showUnsavedChangesPrompt by mutableStateOf(false)

    // Android 13+ (API 33): notifikasi progres/hasil job butuh izin runtime
    // POST_NOTIFICATIONS (sebelumnya hanya di-declare di manifest, jadi di
    // Android 13+ notifikasi "Operasi Selesai/Gagal" di-drop diam-diam).
    // Diminta SEKALI pada peluncuran pertama; penolakan dibiarkan karena
    // helper notifikasi sudah menangkap SecurityException.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("imgtxd_runtime_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        try {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
        }
    }

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
        ProcessRepositoryLocator.init(this)
        AppSettings.init(this)

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

            maybeRequestNotificationPermission()
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
                            FloatingSheet(onDismissRequest = {
                                showUnsavedChangesPrompt = false
                                pendingIntentToLoad = null
                            }) {
                                SheetTitle("Unsaved Changes")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "This IMG archive has unsaved changes. Save them before opening another file?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(20.dp))
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
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.save_and_open), fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
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
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.discard), fontWeight = FontWeight.SemiBold)
                                }
                            }
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun MainScreen(
    viewModel: ImgToolViewModel,
    requestedTab: String? = null,
    onTabConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val currentProcess by ProcessRepositoryLocator.get(context).currentProcess.collectAsState()
    val recentFiles by AppSettings.getRecentFilesFlow(context).collectAsState(initial = emptyList())
    val isExternalTxdOpen by ActiveSessionManager.isTxdOpen.collectAsState()
    val isTexdbOpen by ActiveSessionManager.isTexdbOpen.collectAsState()
    val activeTexdbDir by ActiveSessionManager.activeTexdbDir.collectAsState()
    val isAnyTxdOpen = uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty() || isExternalTxdOpen || isTexdbOpen

    // Tabs: "home", "archive", "txd"
    var activeDockTab by rememberSaveable { mutableStateOf(if (uiState.archive != null) "archive" else "home") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showRuntimeSettingsSheet by rememberSaveable { mutableStateOf(false) }

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
    var showHistorySheet by rememberSaveable { mutableStateOf(false) }
    var showMultiTxdPicker by remember { mutableStateOf(false) }
    var showOswPicker by remember { mutableStateOf(false) }
    var showTexdbPicker by remember { mutableStateOf(false) }
    var showDffInspectPicker by remember { mutableStateOf(false) }
    var showOpenTxdPicker by remember { mutableStateOf(false) }
    var showCreateTxdDialog by remember { mutableStateOf(false) }
    var createTxdTargetFolder by remember {
        mutableStateOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                ?: "/sdcard/Download"
        )
    }
    var isCreateTxdFolderShizuku by remember { mutableStateOf(false) }
    var showCreateTxdFolderPicker by remember { mutableStateOf(false) }
    var imgVerifyReport by remember { mutableStateOf<String?>(null) }
    var showAuditFolderPicker by remember { mutableStateOf(false) }
    var auditReport by remember { mutableStateOf<String?>(null) }
    var auditBusy by remember { mutableStateOf(false) }
    var showDataFilePicker by remember { mutableStateOf(false) }
    var pendingMultiTxdFiles by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var multiTxdSubfolders by remember { mutableStateOf(true) }
    var showMultiTxdDestPicker by remember { mutableStateOf(false) }
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

            // Rule 4: Navigasi hierarkis berkas & layar
            activeDockTab == "settings" -> {
                activeDockTab = "home"
            }
            activeDockTab == "history" -> {
                activeDockTab = "home"
            }
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
            isSelected = activeDockTab == "home",
            onClick = {
                if (activeDockTab != "home") {
                    activeDockTab = "home"
                } else {
                    coroutineScope.launch { homeListState.animateScrollToItem(0) }
                }
            }
        ),
        ContextualDockItem(
            id = "archive",
            icon = Icons.Outlined.FolderOpen,
            contentDescription = "IMG Archive",
            showDotBadge = isImgOpen,
            isSelected = activeDockTab == "archive",
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
            icon = Icons.Outlined.Layers,
            contentDescription = "Texdb",
            showDotBadge = isAnyTxdOpen,
            isSelected = activeDockTab == "txd",
            onClick = {
                if (uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty()) {
                    activeDockTab = "txd"
                } else if (isTexdbOpen) {
                    val intent = Intent(context, TexdbBrowserActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } else if (isExternalTxdOpen) {
                    val intent = Intent(context, TxdEditorActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } else {
                    showTexdbPicker = true
                }
            }
        ),
        ContextualDockItem(
            id = "history",
            icon = Icons.Outlined.History,
            contentDescription = "Operations History",
            isSelected = activeDockTab == "history",
            onClick = {
                activeDockTab = "history"
            }
        ),
        ContextualDockItem(
            id = "settings",
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            isSelected = activeDockTab == "settings",
            onClick = {
                activeDockTab = "settings"
            }
        )
    )

    // DOCK ITEMS: HISTORY SCREEN
    val historyDockItems = listOf(
        ContextualDockItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Beranda",
            isSelected = false,
            onClick = {
                activeDockTab = "home"
            }
        ),
        ContextualDockItem(
            id = "archive",
            icon = Icons.Outlined.FolderOpen,
            contentDescription = "IMG Archive",
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
            icon = Icons.Outlined.Layers,
            contentDescription = "Texdb",
            showDotBadge = isAnyTxdOpen,
            onClick = {
                if (uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty()) {
                    activeDockTab = "txd"
                } else if (isTexdbOpen) {
                    val intent = Intent(context, TexdbBrowserActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } else if (isExternalTxdOpen) {
                    val intent = Intent(context, TxdEditorActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } else {
                    showTexdbPicker = true
                }
            }
        ),
        ContextualDockItem(
            id = "history",
            icon = Icons.Outlined.History,
            contentDescription = "Operations History",
            isSelected = true,
            onClick = { /* Already on history */ }
        ),
        ContextualDockItem(
            id = "settings",
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            isSelected = false,
            onClick = {
                activeDockTab = "settings"
            }
        )
    )

    // DOCK ITEMS: SETTINGS SCREEN
    val settingsDockItems = listOf(
        ContextualDockItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Beranda",
            isSelected = false,
            onClick = {
                activeDockTab = "home"
            }
        ),
        ContextualDockItem(
            id = "archive",
            icon = Icons.Outlined.FolderOpen,
            contentDescription = "IMG Archive",
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
            icon = Icons.Outlined.Layers,
            contentDescription = "Texdb",
            showDotBadge = isAnyTxdOpen,
            onClick = {
                if (uiState.isTxdViewerOpen || uiState.txdEntries.isNotEmpty()) {
                    activeDockTab = "txd"
                } else if (isTexdbOpen) {
                    val intent = Intent(context, TexdbBrowserActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } else if (isExternalTxdOpen) {
                    val intent = Intent(context, TxdEditorActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } else {
                    showTexdbPicker = true
                }
            }
        ),
        ContextualDockItem(
            id = "history",
            icon = Icons.Outlined.History,
            contentDescription = "Operations History",
            isSelected = false,
            onClick = {
                activeDockTab = "history"
            }
        ),
        ContextualDockItem(
            id = "settings",
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            isSelected = true,
            onClick = { /* Already on settings */ }
        )
    )

    // DOCK ITEMS: IMG NORMAL
    val archiveStandardDockItems = listOf(
        ContextualDockItem(
            id = "home",
            icon = Icons.Outlined.Home,
            contentDescription = "Back to Home",
            onClick = { activeDockTab = "home" }
        ),
        ContextualDockItem(
            id = "search",
            icon = Icons.Outlined.Search,
            contentDescription = "Search archive entries",
            isSelected = isSearchExpanded,
            onClick = { isSearchExpanded = !isSearchExpanded }
        ),
        ContextualDockItem(
            id = "add",
            icon = Icons.Outlined.Add,
            contentDescription = "Add files to archive",
            onClick = { showAddFilePicker = true }
        ),
        ContextualDockItem(
            id = "save",
            icon = Icons.Outlined.Save,
            contentDescription = "Save IMG archive",
            showDotBadge = uiState.hasUnsavedChanges,
            isEnabled = uiState.hasUnsavedChanges,
            onClick = { viewModel.rebuildArchive() }
        ),
        ContextualDockItem(
            id = "more",
            icon = Icons.Outlined.Settings,
            contentDescription = "Opsi lainnya",
            onClick = { showMoreActionsSheet = true }
        )
    )

    // DOCK ITEMS: IMG SELECTION MODE
    val archiveSelectionDockItems = listOf(
        ContextualDockItem(
            id = "export",
            icon = Icons.Outlined.FileDownload,
            contentDescription = "Export selected files",
            onClick = { showExtractSelectedPicker = true }
        ),
        ContextualDockItem(
            id = "delete",
            icon = Icons.Outlined.Delete,
            contentDescription = "Delete selected files",
            onClick = { showDeleteSelectedDialog = true }
        ),
        ContextualDockItem(
            id = "select_all",
            icon = Icons.Outlined.Checklist,
            contentDescription = "Select all entries",
            onClick = { viewModel.selectAll() }
        ),
        ContextualDockItem(
            id = "cancel",
            icon = Icons.Outlined.Close,
            contentDescription = "Batalkan seleksi",
            onClick = { viewModel.clearSelection() }
        )
    )

    // Opens a texdb cache folder in the browser, staging it locally first when
    // it lives behind Shizuku/Android-data. Shared by the folder picker and by
    // folder entries in the home recents list.
    val openTexdbFolder: (String, Boolean) -> Unit = { path, isShizuku ->
        val openBrowser: (String, String?) -> Unit = { cacheDir, gameDir ->
            val intent = Intent(context, TexdbBrowserActivity::class.java).apply {
                putExtra(TexdbBrowserActivity.EXTRA_CACHE_DIR, cacheDir)
                if (gameDir != null) {
                    putExtra(TexdbBrowserActivity.EXTRA_GAME_DIR, gameDir)
                }
            }
            context.startActivity(intent)
        }
        if (isShizuku || ShizukuManager.isRestrictedPath(path)) {
            // Stage the whole cache folder locally (browser reads via java.io.File).
            // The original folder travels as EXTRA_GAME_DIR so edits made to the
            // staged copy can be applied back from the browser's menu.
            coroutineScope.launch {
                Toast.makeText(context, "Copying cache folder via Shizuku…", Toast.LENGTH_SHORT).show()
                val srcDir = path.trimEnd('/')
                val destDir = File(context.cacheDir, "texdb_stage/" + File(srcDir).name)
                destDir.deleteRecursively()
                destDir.mkdirs()
                val (code, _) = ShizukuManager.exec("cp -r \"$srcDir/.\" \"${destDir.absolutePath}/\"")
                if (code == 0) {
                    openBrowser(destDir.absolutePath, srcDir)
                } else {
                    Toast.makeText(context, "Copy cache via Shizuku failed", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            openBrowser(path.trimEnd('/'), null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = activeDockTab,
            transitionSpec = {
                (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                        scaleIn(initialScale = 0.98f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                    .togetherWith(
                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                scaleOut(targetScale = 0.98f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    )
            },
            label = "activeDockTabContent"
        ) { currentTab ->
            when (currentTab) {
                "home" -> {
                    val homeDockScrollBehavior = rememberPhysicsDockScrollBehavior(maxOffsetPx = with(LocalDensity.current) { 150.dp.toPx() })
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(homeDockScrollBehavior.nestedScrollConnection)
                    ) {
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
                            onOpenTxdEditor = {
                                showOpenTxdPicker = true
                            },
                            onCreateNewTxd = {
                                showCreateTxdDialog = true
                            },
                            onInspectDff = {
                                showDffInspectPicker = true
                            },
                            onOpenTexdb = {
                                showTexdbPicker = true
                            },
                            onGameAudit = { showAuditFolderPicker = true },
                            onDataLines = { showDataFilePicker = true },
                            onFixAudio = { showOswPicker = true },
                            onExportMultipleTxd = { showMultiTxdPicker = true },
                            onOpenHistory = { activeDockTab = "history" },
                            onOpenRecent = { item ->
                                if (item.type == LauncherFileType.FOLDER) {
                                    openTexdbFolder(item.path, item.isShizuku)
                                } else {
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
                            }
                        )

                        ContextualPillDock(
                            items = homeDockItems,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .graphicsLayer {
                                translationY = homeDockScrollBehavior.offsetPx
                            }
                        )
                    }
                }

                "history" -> {
                    OperationsHistoryScreen(
                        dockItems = historyDockItems,
                        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
                        onShizukuClick = {
                            if (!ShizukuManager.isShizukuAvailable()) {
                                Toast.makeText(context, "Shizuku service not detected. Open the Shizuku app first.", Toast.LENGTH_LONG).show()
                            } else if (!ShizukuManager.hasPermission()) {
                                ShizukuManager.requestPermission()
                            } else {
                                Toast.makeText(context, "Shizuku active: full Android/data access available.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                "settings" -> {
                    SettingsScreen(
                        dockItems = settingsDockItems,
                        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
                        onShizukuClick = {
                            if (!ShizukuManager.isShizukuAvailable()) {
                                Toast.makeText(context, "Shizuku service not detected. Open the Shizuku app first.", Toast.LENGTH_LONG).show()
                            } else if (!ShizukuManager.hasPermission()) {
                                ShizukuManager.requestPermission()
                            } else {
                                Toast.makeText(context, "Shizuku active: full Android/data access available.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                "archive" -> {
                    val activeArchiveProcess = if (currentProcess?.targetFile == uiState.archive?.archiveName) currentProcess else null

                    ArchiveScreen(
                        title = uiState.archive?.archiveName ?: stringResource(R.string.img_archive_default_title),
                        subtitle = if (uiState.archive != null)
                            stringResource(R.string.archive_subtitle_format, uiState.archive?.version?.name ?: "IMG", uiState.entries.size)
                        else
                            stringResource(R.string.no_archive_open),
                        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
                        onShizukuClick = {
                            if (!ShizukuManager.isShizukuAvailable()) {
                                Toast.makeText(context, context.getString(R.string.shizuku_not_detected_open_app), Toast.LENGTH_LONG).show()
                            } else if (!ShizukuManager.hasPermission()) {
                                ShizukuManager.requestPermission()
                            } else {
                                Toast.makeText(context, context.getString(R.string.shizuku_active_toast), Toast.LENGTH_SHORT).show()
                            }
                        },
                        items = uiState.displayedEntries,
                        keyExtractor = { "${it.cleanName}_${it.offsetInSectors}" },
                        listState = archiveListState,
                        emptyTitle = stringResource(R.string.archive_empty_title_none),
                        emptyDescription = stringResource(R.string.archive_empty_desc_manage),
                        emptyActionLabel = stringResource(R.string.archive_empty_action_open),
                        onEmptyAction = { showOpenArchivePicker = true },
                        isSearching = isSearchExpanded,
                        searchQuery = uiState.searchQuery,
                        searchPlaceholder = stringResource(R.string.archive_search_placeholder_img),
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        onCloseSearch = {
                            isSearchExpanded = false
                            viewModel.setSearchQuery("")
                        },
                        activeProcess = activeArchiveProcess,
                        isLoading = uiState.isLoading,
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
                                subtitle = stringResource(R.string.img_sector_format, entry.offsetInSectors, entry.sizeInSectors, sizeKb),
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
                    val activeTxdProcess = if (currentProcess?.targetFile == (uiState.activeTxdName ?: "TXD Textures")) currentProcess else null

                    TxdEditorScreen(
                        txdName = uiState.activeTxdName ?: "TXD Textures",
                        textures = uiState.txdEntries,
                        isLoading = uiState.isTxdLoading,
                        statusMessage = uiState.statusMessage,
                        errorMessage = uiState.errorMessage,
                        hasUnsavedChanges = uiState.hasTxdUnsavedChanges,
                        activeProcess = activeTxdProcess,
                        onSaveTxd = {
                            viewModel.saveActiveTxd()
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
                        onExportSelected = { entries, destDir ->
                            viewModel.exportSelectedTextures(entries, destDir)
                        },
                        onReplaceTexture = { entry, imagePath, isShizuku, options ->
                            viewModel.replaceTexture(context, entry, imagePath, isShizuku, options)
                        },
                        onAutoReplace = { matches, isShizuku, options ->
                            viewModel.autoReplaceTextures(context, matches, isShizuku, options)
                        },
                        onAddTextures = { images, policy, options ->
                            viewModel.addTextures(context, images, policy, options)
                        },
                        onDeleteTextures = { indices ->
                            viewModel.deleteTextures(indices)
                        },
                        onRenameTexture = { index, newName ->
                            viewModel.renameTexture(index, newName)
                        },
                        onUpdateProperties = { index, maskName, filterFlags ->
                            viewModel.updateProperties(index, maskName, filterFlags)
                        },
                        onImportFromTxd = { sourcePath, isSourceShizuku, policy ->
                            viewModel.importFromTxd(context, sourcePath, isSourceShizuku, policy)
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

    // 2. Sheet "Lainnya" untuk IMG (Contextual IMG Archive Options)
    if (showMoreActionsSheet) {
        MoreActionsBottomSheet(
            onDismissRequest = { showMoreActionsSheet = false },
            fileName = uiState.archive?.archiveName ?: "IMG Archive",
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
            onCloseFile = {
                showMoreActionsSheet = false
                if (uiState.hasUnsavedChanges) {
                    showUnsavedClosePrompt = true
                } else {
                    viewModel.closeArchive()
                    activeDockTab = "home"
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
            onAddFile = { showAddFilePicker = true },
            onRebuildArchive = { showRebuildDialog = true },
            onSelectAll = { viewModel.selectAll() },
            onOpenSettings = { showSettingsSheet = true },
            onExportMultipleTxd = { showMultiTxdPicker = true },
            onOpenHistory = { activeDockTab = "history" },
            onFixAudio = { showOswPicker = true },
            onOpenTexdbCache = { showTexdbPicker = true },
            onOpenTxdEditor = { showOpenTxdPicker = true },
            onCreateNewTxd = { showCreateTxdDialog = true },
            onInspectDff = { showDffInspectPicker = true },
            onVerifyArchive = {
                val imgPath = uiState.archive?.imgFile?.absolutePath
                if (imgPath == null) {
                    Toast.makeText(context, "No archive open", Toast.LENGTH_SHORT).show()
                } else {
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) { NativeCore.imgVerify(imgPath) }
                        imgVerifyReport = when {
                            result == null -> "Rust engine unavailable"
                            result.startsWith("OK") -> result.removePrefix("OK\n")
                            else -> result.removePrefix("ERROR ")
                        }
                    }
                }
            },
            onGameAudit = { showAuditFolderPicker = true },
            onDataLines = { showDataFilePicker = true },
            onRequestShizuku = {
                if (!ShizukuManager.isShizukuAvailable()) {
                    Toast.makeText(context, "Shizuku service not detected.", Toast.LENGTH_SHORT).show()
                } else if (!ShizukuManager.hasPermission()) {
                    ShizukuManager.requestPermission()
                } else {
                    Toast.makeText(context, "Shizuku active: full Android/data access available.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 3b. Operations History Bottom Sheet
    if (showHistorySheet) {
        OperationsHistorySheet(
            onDismissRequest = { showHistorySheet = false },
            onClearAll = { }
        )
    }

    // 3b2. SA Audio Fixer: pick an .osw archive, regenerate its .idx
    if (showOswPicker) {
        FilePickerDialog(
            title = stringResource(R.string.pick_osw_audio),
            mode = PickerMode.PICK_OSW,
            onDismissRequest = { showOswPicker = false },
            onFileSelected = { path, isShizuku ->
                showOswPicker = false
                viewModel.fixAudioIdx(context, path, isShizuku)
            }
        )
    }

    // 3b2a. IMG health report sheet
    imgVerifyReport?.let { report ->
        MessageSheet(
            title = stringResource(R.string.img_verify_health_title),
            message = report,
            monospace = true,
            onDismissRequest = { imgVerifyReport = null }
        )
    }

    // 3b2a2. Game texture audit: pick the GTA files/ folder, run, show report
    if (showAuditFolderPicker) {
        FilePickerDialog(
            title = stringResource(R.string.pick_gta_files_folder),
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showAuditFolderPicker = false },
            onFileSelected = { path, _ ->
                showAuditFolderPicker = false
                auditBusy = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) { NativeCore.gameAudit(path) }
                    auditBusy = false
                    auditReport = when {
                        result == null -> context.getString(R.string.dff_rust_unavailable)
                        result.startsWith("OK") -> result.removePrefix("OK\n")
                        else -> result.removePrefix("ERROR ")
                    }
                }
            }
        )
    }
    if (auditBusy) {
        BusySheet(stringResource(R.string.audit_checking_busy))
    }
    auditReport?.let { report ->
        MessageSheet(
            title = stringResource(R.string.audit_report_title),
            message = report,
            monospace = true,
            onDismissRequest = { auditReport = null }
        )
    }

    // 3b2a3. Data lines helper: pick a data file, open the activity
    if (showDataFilePicker) {
        FilePickerDialog(
            title = stringResource(R.string.pick_data_file),
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showDataFilePicker = false },
            onFileSelected = { path, isShizuku ->
                showDataFilePicker = false
                val intent = Intent(context, DataLinesActivity::class.java).apply {
                    putExtra(DataLinesActivity.EXTRA_FILE_PATH, path)
                    putExtra(DataLinesActivity.EXTRA_IS_SHIZUKU, isShizuku)
                }
                context.startActivity(intent)
            }
        )
    }

    // 3b2b. DFF inspector: pick a .dff, open the inspector activity
    if (showDffInspectPicker) {
        FilePickerDialog(
            title = stringResource(R.string.pick_dff_model_file),
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showDffInspectPicker = false },
            onFileSelected = { path, isShizuku ->
                showDffInspectPicker = false
                val intent = Intent(context, DffInspectorActivity::class.java).apply {
                    putExtra(DffInspectorActivity.EXTRA_FILE_PATH, path)
                    putExtra(DffInspectorActivity.EXTRA_IS_SHIZUKU, isShizuku)
                }
                context.startActivity(intent)
            }
        )
    }

    // 3b2c. TXD Editor: pick a .txd file, open TxdEditorActivity
    if (showOpenTxdPicker) {
        FilePickerDialog(
            title = stringResource(R.string.pick_txd_file),
            mode = PickerMode.PICK_FILE,
            onDismissRequest = { showOpenTxdPicker = false },
            onFileSelected = { path, isShizuku ->
                showOpenTxdPicker = false
                val intent = Intent(context, TxdEditorActivity::class.java).apply {
                    putExtra(WindowLauncher.EXTRA_FILE_PATH, path)
                    putExtra("is_shizuku", isShizuku)
                }
                context.startActivity(intent)
            }
        )
    }

    // 3b2d. TXD Maker: dialog pembuatan file TXD baru
    if (showCreateTxdDialog) {
        var newTxdName by remember { mutableStateOf("") }
        var selectedVersion by remember { mutableStateOf(0x1803FFFF) } // RW_VERSION_SA
        AlertDialog(
            onDismissRequest = { showCreateTxdDialog = false },
            title = { Text(stringResource(R.string.txd_maker_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.txd_maker_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newTxdName,
                        onValueChange = { newTxdName = it },
                        label = { Text(stringResource(R.string.txd_maker_file_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Folder tujuan penyimpanan TXD baru
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.txd_maker_target_folder_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = createTxdTargetFolder,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { showCreateTxdFolderPicker = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.txd_maker_change_folder), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = if (newTxdName.endsWith(".txd", ignoreCase = true)) {
                            newTxdName.trim()
                        } else {
                            "${newTxdName.trim()}.txd"
                        }
                        if (cleanName.isNotBlank() && cleanName != ".txd") {
                            showCreateTxdDialog = false
                            val destFolder = File(createTxdTargetFolder)
                            destFolder.mkdirs()
                            val targetFile = File(destFolder, cleanName)
                            val res = NativeCore.txdCreate(targetFile.absolutePath, selectedVersion)
                            if (res != null && res.startsWith("OK")) {
                                Toast.makeText(context, context.getString(R.string.txd_created_toast, cleanName), Toast.LENGTH_SHORT).show()
                                val intent = Intent(context, TxdEditorActivity::class.java).apply {
                                    putExtra(WindowLauncher.EXTRA_FILE_PATH, targetFile.absolutePath)
                                    putExtra("is_shizuku", isCreateTxdFolderShizuku)
                                }
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, context.getString(R.string.txd_create_failed_toast, res ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = newTxdName.isNotBlank()
                ) {
                    Text(stringResource(R.string.txd_maker_create_and_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTxdDialog = false }) {
                    Text(stringResource(R.string.cancel_indonesian))
                }
            }
        )
    }

    if (showCreateTxdFolderPicker) {
        FilePickerDialog(
            title = stringResource(R.string.extract_all_title),
            mode = PickerMode.SELECT_FOLDER,
            onDismissRequest = { showCreateTxdFolderPicker = false },
            onFileSelected = { path, isShizuku ->
                showCreateTxdFolderPicker = false
                createTxdTargetFolder = path
                isCreateTxdFolderShizuku = isShizuku
            }
        )
    }

    // 3b3. Texdb cache browser/editor: pick the cache FOLDER itself
    // (user feedback 2026-10-23: folder, not the manifest file).
    if (showTexdbPicker) {
        FilePickerDialog(
            title = stringResource(R.string.cache_folder_pick_title),
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showTexdbPicker = false },
            onFileSelected = { path, isShizuku ->
                showTexdbPicker = false
                coroutineScope.launch {
                    AppSettings.addRecentFile(
                        context,
                        path.trimEnd('/'),
                        LauncherFileType.FOLDER,
                        isShizuku
                    )
                }
                openTexdbFolder(path, isShizuku)
            }
        )
    }

    // 3c. Multi-TXD Export: pick TXD files, options dialog, destination folder
    if (showMultiTxdPicker) {
        FilePickerDialog(
            title = "Pick TXD Files to Export",
            mode = PickerMode.PICK_MULTIPLE_TXD,
            onDismissRequest = { showMultiTxdPicker = false },
            onMultipleFilesSelected = { filePaths, isShizuku ->
                showMultiTxdPicker = false
                pendingMultiTxdFiles = filePaths.map { it to isShizuku }
            }
        )
    }

    pendingMultiTxdFiles?.let { files ->
        FloatingSheet(onDismissRequest = { pendingMultiTxdFiles = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FileCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                SheetTitle("Export from ${files.size} TXDs")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All textures from ${files.size} TXD files will be exported as PNG.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = multiTxdSubfolders,
                    onCheckedChange = { multiTxdSubfolders = it }
                )
                Text(
                    text = "Subfolder per TXD",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { pendingMultiTxdFiles = null }) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showMultiTxdDestPicker = true },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Pick Destination Folder", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showMultiTxdDestPicker) {
        val files = pendingMultiTxdFiles
        FilePickerDialog(
            title = "Pick Multi-TXD Export Destination Folder",
            mode = PickerMode.PICK_FOLDER,
            onDismissRequest = { showMultiTxdDestPicker = false },
            onFileSelected = { folderPath, _ ->
                showMultiTxdDestPicker = false
                pendingMultiTxdFiles = null
                if (files != null) {
                    viewModel.exportMultipleTxds(context, files, File(folderPath), multiTxdSubfolders)
                }
            }
        )
    }

    // 4. Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(onDismissRequest = { showSettingsSheet = false })
    }

    // 4b. Runtime Settings Bottom Sheet (:worker process & memory budget)
    if (showRuntimeSettingsSheet) {
        RuntimeSettingsSheet(onDismissRequest = { showRuntimeSettingsSheet = false })
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
            onCancelProcess = { id -> ProcessRepositoryLocator.get(context).cancelProcess(id) }
        )
    }

    // 7. Busy Process Sheet (Back Navigation Rule 3)
    if (showBusyProcessDialog) {
        ConfirmSheet(
            title = "Process Running",
            message = "Process '${currentProcess?.title}' is running in the background.\n\nYou can go back to Home while it runs, or stay on this screen.",
            confirmLabel = "Run in Background",
            dismissLabel = "Stay Here",
            onDismissRequest = { showBusyProcessDialog = false },
            onConfirm = {
                showBusyProcessDialog = false
                activeDockTab = "home"
            }
        )
    }

    // 8. Unsaved Close Prompt Sheet
    if (showUnsavedClosePrompt) {
        ConfirmSheet(
            title = "Close Without Saving?",
            message = "This archive has changes not yet rebuilt/saved to disk. Closing now discards them.",
            confirmLabel = "Close Anyway",
            dismissLabel = "Cancel",
            danger = true,
            onDismissRequest = { showUnsavedClosePrompt = false },
            onConfirm = {
                showUnsavedClosePrompt = false
                viewModel.closeArchive()
                activeDockTab = "home"
            }
        )
    }

    // --- File Pickers ---

    // Open IMG Archive Picker
    if (showOpenArchivePicker) {
        FilePickerDialog(
            title = stringResource(R.string.menu_open_img),
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

    // Add File Picker
    if (showAddFilePicker) {
        FilePickerDialog(
            title = stringResource(R.string.pick_files_to_add),
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
            title = stringResource(R.string.replace_entry_title, entry.cleanName),
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
            title = stringResource(R.string.extract_entry_title, entry.cleanName),
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
            title = stringResource(R.string.extract_selected_title, uiState.selectedEntries.size),
            mode = PickerMode.SELECT_FOLDER_OR_IMG,
            onDismissRequest = { showExtractSelectedPicker = false },
            onFileSelected = { path, isShizuku ->
                showExtractSelectedPicker = false
                val selectedFile = File(path)
                if (selectedFile.isFile && path.endsWith(".img", ignoreCase = true)) {
                    viewModel.exportSelectedToImgArchive(
                        targetImgFile = selectedFile,
                        isShizuku = isShizuku
                    )
                } else {
                    viewModel.extractSelected(
                        destinationDir = if (selectedFile.isDirectory) selectedFile else (selectedFile.parentFile ?: File(path)),
                        isShizuku = isShizuku,
                        destRestrictedPath = if (isShizuku) path else null
                    )
                }
            }
        )
    }

    // Extract All Entries Picker
    if (showExtractAllPicker) {
        FilePickerDialog(
            title = stringResource(R.string.extract_all_title),
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

    // Rename Sheet
    entryToRename?.let { entry ->
        InputSheet(
            title = stringResource(R.string.rename_entry_dialog_title),
            label = stringResource(R.string.rename_entry_label),
            initial = entry.cleanName,
            onDismissRequest = { entryToRename = null },
            onConfirm = { newName ->
                viewModel.renameEntry(entry, newName)
                entryToRename = null
            }
        )
    }

    // Rebuild Confirm Sheet
    if (showRebuildDialog) {
        val shizukuNote = if (uiState.isShizukuBacked) stringResource(R.string.rebuild_dialog_shizuku_note) else ""
        ConfirmSheet(
            title = stringResource(R.string.rebuild_dialog_title),
            message = stringResource(R.string.rebuild_dialog_msg_extended, shizukuNote),
            confirmLabel = stringResource(R.string.rebuild_now),
            onDismissRequest = { showRebuildDialog = false },
            onConfirm = {
                showRebuildDialog = false
                viewModel.rebuildArchive()
            }
        )
    }

    // Delete Selected Confirm Sheet
    if (showDeleteSelectedDialog) {
        ConfirmSheet(
            title = stringResource(R.string.delete_selected_dialog_title),
            message = stringResource(R.string.delete_selected_confirm_msg, uiState.selectedEntries.size),
            confirmLabel = stringResource(R.string.delete_selected_button_count, uiState.selectedEntries.size),
            danger = true,
            onDismissRequest = { showDeleteSelectedDialog = false },
            onConfirm = {
                showDeleteSelectedDialog = false
                viewModel.deleteSelected()
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
    onOpenTxdEditor: () -> Unit,
    onCreateNewTxd: () -> Unit,
    onInspectDff: () -> Unit,
    onOpenTexdb: () -> Unit,
    onGameAudit: () -> Unit,
    onDataLines: () -> Unit,
    onFixAudio: () -> Unit,
    onExportMultipleTxd: () -> Unit,
    onOpenHistory: () -> Unit,
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
                                text = "All Files Access Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Android needs storage permission to modify game files in place.",
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

        // Hero Card & App Title
        item(key = "home_hero_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), CircleShape),
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
                        text = "ImgTxd Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Archive manager & texture editor for GTA (SA, VC, III) & Bully. High-speed Rust core with direct Shizuku support.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Section: PRIMARY TOOLS & EDITORS
        item(key = "home_core_tools_header") {
            Text(
                text = "PRIMARY TOOLS & EDITORS",
                style = SectionHeaderStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        item(key = "home_core_tools_grid") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Row 1: Primary actions (Open IMG & TXD Editor)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FeatureActionCard(
                        title = stringResource(R.string.home_card_open_img_title),
                        subtitle = stringResource(R.string.home_card_open_img_subtitle),
                        icon = Icons.Outlined.FolderOpen,
                        modifier = Modifier.weight(1f),
                        isPrimary = true,
                        onClick = onOpenImg
                    )
                    FeatureActionCard(
                        title = stringResource(R.string.home_card_txd_editor_title),
                        subtitle = stringResource(R.string.home_card_txd_editor_subtitle),
                        icon = Icons.Outlined.Image,
                        modifier = Modifier.weight(1f),
                        isPrimary = true,
                        onClick = onOpenTxdEditor
                    )
                }

                // Row 2: Secondary creation & inspection (TXD Maker, DFF View, Texdb)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FeatureActionCard(
                        title = stringResource(R.string.home_card_txd_maker_title),
                        subtitle = stringResource(R.string.home_card_txd_maker_subtitle),
                        icon = Icons.Outlined.CreateNewFolder,
                        modifier = Modifier.weight(1f),
                        onClick = onCreateNewTxd
                    )
                    FeatureActionCard(
                        title = stringResource(R.string.home_card_dff_title),
                        subtitle = stringResource(R.string.home_card_dff_subtitle),
                        icon = Icons.Outlined.ViewInAr,
                        modifier = Modifier.weight(1f),
                        onClick = onInspectDff
                    )
                    FeatureActionCard(
                        title = stringResource(R.string.home_card_texdb_title),
                        subtitle = stringResource(R.string.home_card_texdb_subtitle),
                        icon = Icons.Outlined.Layers,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenTexdb
                    )
                }
            }
        }

        // Section: MODDING & UTILITIES
        item(key = "home_modding_header") {
            Text(
                text = stringResource(R.string.modding_utilities_section),
                style = SectionHeaderStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }

        item(key = "home_modding_tools_grid") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HomeToolRow(
                        title = stringResource(R.string.menu_texture_audit),
                        subtitle = stringResource(R.string.home_modding_audit_subtitle),
                        icon = Icons.Outlined.Checklist,
                        onClick = onGameAudit
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    HomeToolRow(
                        title = stringResource(R.string.menu_paste_data_lines),
                        subtitle = stringResource(R.string.home_modding_datalines_subtitle),
                        icon = Icons.Outlined.FileCopy,
                        onClick = onDataLines
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    HomeToolRow(
                        title = stringResource(R.string.menu_fix_audio),
                        subtitle = stringResource(R.string.home_modding_fix_audio_subtitle),
                        icon = Icons.Outlined.Audiotrack,
                        onClick = onFixAudio
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    HomeToolRow(
                        title = stringResource(R.string.menu_export_multi_txd),
                        subtitle = stringResource(R.string.home_modding_multi_txd_subtitle),
                        icon = Icons.Outlined.Collections,
                        onClick = onExportMultipleTxd
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    HomeToolRow(
                        title = stringResource(R.string.menu_operations_history),
                        subtitle = stringResource(R.string.home_history_subtitle),
                        icon = Icons.Outlined.History,
                        onClick = onOpenHistory
                    )
                }
            }
        }

        // Section: FILE TERBARU
        item(key = "home_recent_header") {
            Text(
                text = stringResource(R.string.recent_files_section),
                style = SectionHeaderStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
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
                            text = "No recently opened files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = recentFiles,
                key = { it.path },
                contentType = { "recent_file" }
            ) { item ->
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
private fun FeatureActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPrimary) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeToolRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
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
                    imageVector = when (item.type) {
                        LauncherFileType.IMG -> Icons.Outlined.Folder
                        LauncherFileType.TXD -> Icons.Outlined.Palette
                        LauncherFileType.FOLDER -> Icons.Outlined.FolderOpen
                    },
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

            if (item.type == LauncherFileType.FOLDER) {
                AssistChip(
                    onClick = {},
                    shape = CircleShape,
                    label = { Text(stringResource(R.string.badge_folder), style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(24.dp)
                )
            }

            if (item.isShizuku) {
                AssistChip(
                    onClick = {},
                    shape = CircleShape,
                    label = { Text(stringResource(R.string.badge_shizuku), style = MaterialTheme.typography.labelSmall) },
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
                        contentDescription = stringResource(R.string.app_security_warning_cd),
                        tint = SemanticColors.Error,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = stringResource(R.string.app_integrity_violation_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.app_integrity_violation_desc),
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
                        Text(stringResource(R.string.close_app_button), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
