package com.onlasdan.studio.ui

import com.onlasdan.studio.Constants

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.core.NativeCore
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ToolPageScaffold
import com.onlasdan.studio.ui.components.BusySheet
import com.onlasdan.studio.ui.components.InputSheet
import com.onlasdan.studio.ui.theme.ImgToolTheme
import com.onlasdan.studio.ui.view3d.Dff3dMesh
import com.onlasdan.studio.ui.view3d.Dff3dViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DFF inspector: RenderWare structure report (engine: Rust) plus per-texture
 * rename inside the DFF (F4 parity), with a pristine .dff.bak kept next to
 * the file. When the DFF came from a protected folder, it is staged locally
 * first and can be applied back from the top bar.
 */
class DffInspectorActivity : ComponentActivity() {
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
        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val isShizuku = intent.getBooleanExtra(EXTRA_IS_SHIZUKU, false)
        setContent {
            ImgToolTheme {
                DffInspectorScreen(
                    sourcePath = path,
                    isShizuku = isShizuku,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = Constants.IntentExtra.DFF_FILE_PATH
        const val EXTRA_IS_SHIZUKU = Constants.IntentExtra.DFF_IS_SHIZUKU
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DffInspectorScreen(sourcePath: String?, isShizuku: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localPath by remember { mutableStateOf<String?>(null) }
    var originalPath by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var textures by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var mesh3d by remember { mutableStateOf<Dff3dMesh?>(null) }
    var is3dViewMode by remember { mutableStateOf(true) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // Stage protected files locally (same pattern as the texdb browser).
    LaunchedEffect(sourcePath) {
        if (sourcePath == null) {
            error = context.getString(R.string.dff_file_not_provided)
            return@LaunchedEffect
        }
        val src = File(sourcePath)
        if (!isShizuku && src.canRead()) {
            localPath = sourcePath
            return@LaunchedEffect
        }
        busyText = context.getString(R.string.dff_copying_busy)
        val dest = File(context.cacheDir, "dff_stage/${src.name}")
        dest.parentFile?.mkdirs()
        val (code, _) = withContext(Dispatchers.IO) {
            ShizukuManager.exec("cp \"$sourcePath\" \"${dest.absolutePath}\"")
        }
        busyText = null
        if (code == 0) {
            localPath = dest.absolutePath
            originalPath = sourcePath
        } else {
            error = context.getString(R.string.dff_shizuku_copy_failed, code)
        }
    }

    LaunchedEffect(localPath, tick) {
        val path = localPath ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { NativeCore.dffInspect(path) }
        when {
            result == null -> error = context.getString(R.string.dff_rust_unavailable)
            result.startsWith("OK") -> {
                val text = result.removePrefix("OK\n")
                report = text
                textures = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("- ") }
                    .map { it.removePrefix("- ") }
                    .distinct()
                    .toList()
                error = null
            }
            else -> error = result.removePrefix("ERROR ")
        }

        // Extract 3D Mesh in background
        withContext(Dispatchers.IO) {
            val meshBytes = NativeCore.dffExtractMesh(path)
            if (meshBytes != null) {
                mesh3d = Dff3dMesh.fromBytes(meshBytes)
            }
        }
    }

    fun applyToOriginal() {
        val from = localPath ?: return
        val to = originalPath ?: return
        busyText = context.getString(R.string.datalines_applying_busy)
        scope.launch {
            val (code, _) = withContext(Dispatchers.IO) {
                ShizukuManager.exec("cp \"$from\" \"$to\"")
            }
            busyText = null
            if (code == 0) toast(context.getString(R.string.dff_applied_success))
            else toast(context.getString(R.string.datalines_apply_failed, code))
        }
    }

    ToolPageScaffold(
        title = stringResource(R.string.dff_inspector_title),
        subtitle = sourcePath?.let { File(it).name } ?: stringResource(R.string.dff_structure_report),
        isShizukuActive = ShizukuManager.isShizukuAvailable() && ShizukuManager.hasPermission(),
        onShizukuClick = {
            if (!ShizukuManager.isShizukuAvailable()) {
                toast(context.getString(R.string.shizuku_not_detected_short))
            } else if (!ShizukuManager.hasPermission()) {
                ShizukuManager.requestPermission()
            }
        },
        dockItems = listOf(
            ContextualDockItem(
                id = "home",
                icon = Icons.Outlined.Home,
                contentDescription = stringResource(R.string.home),
                onClick = onBack
            ),
            ContextualDockItem(
                id = "mode",
                icon = if (is3dViewMode) Icons.Outlined.Info else Icons.Outlined.ViewInAr,
                contentDescription = if (is3dViewMode) stringResource(R.string.dff_dock_info_textures) else stringResource(R.string.dff_dock_viewport),
                onClick = { is3dViewMode = !is3dViewMode }
            ),
            ContextualDockItem(
                id = "apply",
                icon = Icons.Outlined.Save,
                contentDescription = stringResource(R.string.datalines_apply_original_desc),
                isEnabled = originalPath != null,
                onClick = { applyToOriginal() }
            )
        )
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (is3dViewMode) {
                if (mesh3d != null) {
                    Dff3dViewport(
                        mesh = mesh3d,
                        modifier = Modifier.fillMaxSize().padding(bottom = 90.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        if (error != null) {
                            Text(stringResource(R.string.dff_mesh_unavailable, error!!))
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 108.dp)
                ) {
                    when {
                        error != null -> Text(stringResource(R.string.dff_cannot_inspect, error!!))
                        report == null -> CircularProgressIndicator()
                        else -> {
                            Text(
                                text = report!!.substringBefore("Tekstur dirujuk"),
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.dff_referenced_textures, textures.size),
                                fontWeight = FontWeight.SemiBold
                            )
                            textures.forEach { name ->
                                ListItem(
                                    headlineContent = { Text(name) },
                                    modifier = Modifier.clickable { renameTarget = name }
                                )
                                HorizontalDivider()
                            }
                            Text(
                                stringResource(R.string.dff_renaming_backup_note),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (busyText != null) {
        BusySheet(busyText!!)
    }

    renameTarget?.let { old ->
        InputSheet(
            title = stringResource(R.string.dff_rename_title),
            label = stringResource(R.string.dff_rename_label, old),
            initial = old,
            onDismissRequest = { renameTarget = null },
            onConfirm = { newName ->
                val path = localPath
                renameTarget = null
                if (newName != old && path != null) {
                    busyText = context.getString(R.string.dff_renaming_busy)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            NativeCore.dffRenameTexture(path, old, newName)
                        }
                        busyText = null
                        when {
                            result == null -> toast(context.getString(R.string.dff_rust_unavailable))
                            result.startsWith("OK") -> {
                                toast(context.getString(R.string.dff_renamed_success, old, newName))
                                tick++
                            }
                            else -> toast(context.getString(R.string.dff_renamed_failed, result.removePrefix("ERROR ")))
                        }
                    }
                }
            }
        )
    }
}
