package com.onlasdan.studio.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.process.DefaultProcessRepository
import com.onlasdan.studio.process.ProcessRepositoryLocator
import com.onlasdan.studio.process.ProcessStatus
import com.onlasdan.studio.ui.components.ConfirmSheet
import com.onlasdan.studio.ui.components.SheetTitle
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.util.ActiveSessionManager
import com.onlasdan.studio.util.AppIntegrity
import com.onlasdan.studio.util.ParentArchiveInfo
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.WindowLauncher
import rikka.shizuku.Shizuku

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class TxdEditorActivity : ComponentActivity() {

    private val viewModel: TxdEditorViewModel by viewModels()
    private var isTampered by mutableStateOf(false)

    // Unsaved changes handling for onNewIntent
    private var pendingIntentToLoad by mutableStateOf<Intent?>(null)
    private var showUnsavedChangesPrompt by mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (!Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(ShizukuManager.SHIZUKU_REQUEST_CODE)
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {}

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        ProcessRepositoryLocator.init(this)

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
                    val currentProcess by ProcessRepositoryLocator.get(this@TxdEditorActivity).currentProcess.collectAsState()
                    var showBusyProcessDialog by remember { mutableStateOf(false) }
                    val isFileBusy = currentProcess != null && currentProcess?.targetFile == uiState.txdName && currentProcess?.status == ProcessStatus.RUNNING

                    LaunchedEffect(uiState.txdName) {
                        if (uiState.txdName.isNotBlank()) {
                            ActiveSessionManager.setTxdActive(
                                uiState.txdName,
                                uiState.originalPath ?: uiState.activeTxdFile?.absolutePath
                            )
                            WindowLauncher.updateTaskDescription(
                                this@TxdEditorActivity,
                                "${uiState.txdName} - ImgTxd Studio"
                            )
                        }
                    }

                    BackHandler(enabled = true) {
                        when {
                            isFileBusy -> {
                                showBusyProcessDialog = true
                            }
                            uiState.parentArchiveInfo != null -> {
                                val intent = Intent(this@TxdEditorActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                                    putExtra(MainActivity.EXTRA_NAVIGATE_TAB, "archive")
                                }
                                startActivity(intent)
                            }
                            else -> {
                                val intent = Intent(this@TxdEditorActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                                    putExtra(MainActivity.EXTRA_NAVIGATE_TAB, "home")
                                }
                                startActivity(intent)
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        TxdEditorScreen(
                            txdName = uiState.txdName,
                            textures = uiState.txdEntries,
                            isLoading = uiState.isLoading || uiState.isSaving,
                            statusMessage = uiState.statusMessage,
                            errorMessage = uiState.errorMessage,
                            hasUnsavedChanges = uiState.hasUnsavedChanges,
                            activeProcess = if (isFileBusy) currentProcess else null,
                            onClose = {
                                ActiveSessionManager.setTxdClosed()
                                finish()
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
                                viewModel.replaceTexture(this@TxdEditorActivity, entry, imagePath, isShizuku, options)
                            },
                            onAutoReplace = { matches, isShizuku, options ->
                                viewModel.autoReplaceTextures(this@TxdEditorActivity, matches, isShizuku, options)
                            },
                            onAddTextures = { images, policy, options ->
                                viewModel.addTextures(this@TxdEditorActivity, images, policy, options)
                            },
                            onClearMessages = {
                                viewModel.clearMessages()
                            },
                            showDock = true,
                            onNavigateHome = {
                                val intent = Intent(this@TxdEditorActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                                    putExtra(MainActivity.EXTRA_NAVIGATE_TAB, "home")
                                }
                                startActivity(intent)
                            }
                        )

                        // Busy Process Sheet
                        if (showBusyProcessDialog) {
                            ConfirmSheet(
                                title = "Proses Sedang Berjalan",
                                message = "Proses '${currentProcess?.title}' saat ini sedang berjalan di latar belakang.\n\nAnda dapat kembali ke Beranda sementara proses terus berjalan, atau tetap berada di layar ini.",
                                confirmLabel = "Lanjut di Latar Belakang",
                                dismissLabel = "Tetap di Sini",
                                onDismissRequest = { showBusyProcessDialog = false },
                                onConfirm = {
                                    showBusyProcessDialog = false
                                    val intent = Intent(this@TxdEditorActivity, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                                        putExtra(MainActivity.EXTRA_NAVIGATE_TAB, "home")
                                    }
                                    startActivity(intent)
                                }
                            )
                        }

                        // Unsaved Changes Prompt Sheet on New File Request
                        if (showUnsavedChangesPrompt) {
                            FloatingSheet(onDismissRequest = {
                                showUnsavedChangesPrompt = false
                                pendingIntentToLoad = null
                            }) {
                                SheetTitle("Perubahan Belum Disimpan")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "File TXD saat ini memiliki perubahan yang belum disimpan. Apakah Anda ingin menyimpan perubahan sebelum membuka file baru?",
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
                                    Text("Simpan & Buka", fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = {
                                        showUnsavedChangesPrompt = false
                                        pendingIntentToLoad = null
                                    }) {
                                        Text("Batal")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
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
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isNewIntent = true)
    }

    private fun handleIntent(intent: Intent?, isNewIntent: Boolean) {
        if (intent == null) return

        val incomingUri: Uri? = intent.data
        val incomingPath: String? = intent.getStringExtra(WindowLauncher.EXTRA_FILE_PATH)

        // If checking same file in already open window
        val curState = viewModel.uiState.value
        val curPath = curState.originalPath ?: curState.activeTxdFile?.absolutePath
        val isSamePath = incomingPath != null && curPath != null && incomingPath == curPath
        val isSameUri = incomingUri != null && incomingUri.toString() == curPath

        if (isNewIntent && (isSamePath || isSameUri)) {
            // Already showing this exact file, just focus window
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

        val parentArchivePath = intent.getStringExtra(WindowLauncher.EXTRA_PARENT_ARCHIVE_PATH)
        val parentEntryName = intent.getStringExtra(WindowLauncher.EXTRA_PARENT_ENTRY_NAME)
        val parentRestrictedPath = intent.getStringExtra(WindowLauncher.EXTRA_PARENT_RESTRICTED_PATH)
        val parentIsShizuku = intent.getBooleanExtra(WindowLauncher.EXTRA_PARENT_IS_SHIZUKU, false)

        val parentInfo = if (parentArchivePath != null && parentEntryName != null) {
            ParentArchiveInfo(
                archivePath = parentArchivePath,
                entryName = parentEntryName,
                originalRestrictedPath = parentRestrictedPath,
                isShizuku = parentIsShizuku
            )
        } else null

        if (filePath != null || uri != null) {
            viewModel.loadTxd(
                context = this,
                uri = uri,
                filePath = filePath,
                isShizuku = isShizuku,
                parentInfo = parentInfo
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (isTampered) return
        if (ShizukuManager.isShizukuAvailable() && !ShizukuManager.hasPermission()) {
            ShizukuManager.requestPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveSessionManager.setTxdClosed()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
