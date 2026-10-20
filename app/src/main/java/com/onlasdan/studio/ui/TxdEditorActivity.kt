package com.onlasdan.studio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.util.AppIntegrity
import com.onlasdan.studio.util.ParentArchiveInfo
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.util.WindowLauncher
import rikka.shizuku.Shizuku

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

            handleIntent(intent, isNewIntent = false)
        }

        setContent {
            ImgToolTheme {
                if (isTampered) {
                    TamperedScreen()
                } else {
                    val uiState by viewModel.uiState.collectAsState()

                    LaunchedEffect(uiState.txdName) {
                        if (uiState.txdName.isNotBlank()) {
                            WindowLauncher.updateTaskDescription(
                                this@TxdEditorActivity,
                                "${uiState.txdName} - ImgTxd Studio"
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        TxdEditorScreen(
                            txdName = uiState.txdName,
                            textures = uiState.txdEntries,
                            isLoading = uiState.isLoading || uiState.isSaving,
                            statusMessage = uiState.statusMessage,
                            errorMessage = uiState.errorMessage,
                            onClose = { finish() },
                            onExportSingle = { entry, destDir ->
                                viewModel.exportSingleTexture(entry, destDir)
                            },
                            onExportAll = { destDir ->
                                viewModel.exportAllTextures(destDir)
                            },
                            onReplaceTexture = { entry, imagePath, isShizuku ->
                                viewModel.replaceTexture(this@TxdEditorActivity, entry, imagePath, isShizuku)
                            },
                            onAutoReplace = { matches, isShizuku ->
                                viewModel.autoReplaceTextures(this@TxdEditorActivity, matches, isShizuku)
                            },
                            onClearMessages = {
                                viewModel.clearMessages()
                            }
                        )

                        // Unsaved Changes Prompt Dialog on New File Request
                        if (showUnsavedChangesPrompt) {
                            AlertDialog(
                                onDismissRequest = {
                                    showUnsavedChangesPrompt = false
                                    pendingIntentToLoad = null
                                },
                                title = { Text("Perubahan Belum Disimpan") },
                                text = {
                                    Text("File TXD saat ini memiliki perubahan yang belum disimpan. Apakah Anda ingin menyimpan perubahan sebelum membuka file baru?")
                                },
                                confirmButton = {
                                    Button(onClick = {
                                        showUnsavedChangesPrompt = false
                                        val next = pendingIntentToLoad
                                        pendingIntentToLoad = null
                                        if (next != null) {
                                            executeLoadIntent(next)
                                        }
                                    }) {
                                        Text("Simpan & Buka")
                                    }
                                },
                                dismissButton = {
                                    OutlinedButton(onClick = {
                                        showUnsavedChangesPrompt = false
                                        val next = pendingIntentToLoad
                                        pendingIntentToLoad = null
                                        if (next != null) {
                                            executeLoadIntent(next)
                                        }
                                    }) {
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
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
