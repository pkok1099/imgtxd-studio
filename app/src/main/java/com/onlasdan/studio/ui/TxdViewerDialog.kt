package com.onlasdan.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onlasdan.studio.txd.TxdEntry
import java.io.File

@Composable
fun TxdViewerDialog(
    txdName: String,
    textures: List<TxdEntry>,
    isLoading: Boolean,
    statusMessage: String? = null,
    errorMessage: String? = null,
    onDismissRequest: () -> Unit,
    onExportSingle: (entry: TxdEntry, destDir: File) -> Unit,
    onExportAll: (destDir: File) -> Unit,
    onReplaceTexture: (entry: TxdEntry, imagePath: String, isShizuku: Boolean) -> Unit,
    onAutoReplace: (matches: List<Pair<TxdEntry, String>>, isShizuku: Boolean) -> Unit,
    onClearMessages: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TxdEditorScreen(
                txdName = txdName,
                textures = textures,
                isLoading = isLoading,
                statusMessage = statusMessage,
                errorMessage = errorMessage,
                onClose = onDismissRequest,
                onExportSingle = onExportSingle,
                onExportAll = onExportAll,
                onReplaceTexture = onReplaceTexture,
                onAutoReplace = onAutoReplace,
                onClearMessages = onClearMessages
            )
        }
    }
}
