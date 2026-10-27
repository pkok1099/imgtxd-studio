package com.onlasdan.studio.ui

import com.onlasdan.studio.Constants

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.onlasdan.studio.R
import com.onlasdan.studio.core.NativeCore
import com.onlasdan.studio.util.ShizukuManager
import com.onlasdan.studio.ui.framework.ContextualDockItem
import com.onlasdan.studio.ui.framework.ToolPageScaffold
import com.onlasdan.studio.ui.components.BusySheet
import com.onlasdan.studio.ui.theme.ImgToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Data-line helper for mod installs: paste handling.cfg / carcols.dat /
 * vehicles.ide lines from a mod's readme, preview what will be replaced or
 * appended in the chosen data file, apply with a one-time .bak backup.
 *
 * Safety rules: lines are matched by their first token (the vehicle/entry
 * id). Files with section markers ("end", e.g. vehicles.ide) run in
 * replace-only mode — appending into the wrong section would break the
 * file, so new ids are reported and skipped instead of appended.
 *
 * The merge itself runs in the Rust engine (`rust-core/src/datalines.rs`,
 * RUST100 migration 2026-10-23; behavior pinned by golden fixtures from
 * the old Kotlin implementation). This file only stages the file
 * (Shizuku), calls the engine, and writes the merged text it returns.
 */
class DataLinesActivity : ComponentActivity() {
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
                DataLinesScreen(sourcePath = path, isShizuku = isShizuku, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = Constants.IntentExtra.DATA_FILE_PATH
        const val EXTRA_IS_SHIZUKU = Constants.IntentExtra.DATA_IS_SHIZUKU
    }
}

/** Parsed form of the engine's data-lines wire block (see NativeCore). */
private data class EnginePlan(
    val changed: Int,
    val fileLineCount: Int,
    val sectioned: Boolean,
    val notes: String,
    val mergedText: String
)

private fun parseEnginePlan(wire: String?): EnginePlan? {
    if (wire == null) return null
    val nl = wire.indexOf('\n')
    if (nl < 0) return null
    val head = wire.substring(0, nl).split('\t')
    if (head.size < 5 || head[0] != "OK") return null
    val notesCount = head[4].toIntOrNull() ?: return null
    var rest = wire.substring(nl + 1)
    val notes = StringBuilder()
    repeat(notesCount) {
        val e = rest.indexOf('\n')
        if (e < 0) return null
        notes.append(rest, 0, e + 1)
        rest = rest.substring(e + 1)
    }
    return EnginePlan(
        changed = head[1].toIntOrNull() ?: 0,
        fileLineCount = head[2].toIntOrNull() ?: 0,
        sectioned = head[3] == "1",
        notes = notes.toString(),
        mergedText = rest
    )
}

private suspend fun enginePlan(fileText: String, pasted: String): EnginePlan? =
    withContext(Dispatchers.IO) { parseEnginePlan(NativeCore.dataLinesPlan(fileText, pasted)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataLinesScreen(sourcePath: String?, isShizuku: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localPath by remember { mutableStateOf<String?>(null) }
    var originalPath by remember { mutableStateOf<String?>(null) }
    var fileText by remember { mutableStateOf("") }
    var fileLineCount by remember { mutableIntStateOf(0) }
    var sectioned by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<String?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    /** Refreshes the header facts (line count, sectioned) for [text]. */
    suspend fun refreshMeta(text: String): Boolean {
        val meta = enginePlan(text, "") ?: return false
        fileLineCount = meta.fileLineCount
        sectioned = meta.sectioned
        return true
    }

    LaunchedEffect(sourcePath) {
        if (sourcePath == null) {
            error = context.getString(R.string.datalines_file_not_provided)
            return@LaunchedEffect
        }
        val src = File(sourcePath)
        val readable = !isShizuku && src.canRead()
        val effective = if (readable) {
            sourcePath
        } else {
            busyText = context.getString(R.string.datalines_copying_busy)
            val dest = File(context.cacheDir, "data_stage/${src.name}")
            dest.parentFile?.mkdirs()
            val (code, _) = ShizukuManager.exec("cp \"$sourcePath\" \"${dest.absolutePath}\"")
            busyText = null
            if (code != 0) {
                error = context.getString(R.string.datalines_shizuku_copy_failed, code)
                return@LaunchedEffect
            }
            originalPath = sourcePath
            dest.absolutePath
        }
        localPath = effective
        fileText = withContext(Dispatchers.IO) { File(effective).readText() }
        if (!refreshMeta(fileText)) {
            error = context.getString(R.string.datalines_rust_unavailable)
        }
    }

    fun previewPlan() {
        scope.launch {
            val p = enginePlan(fileText, pasted)
            plan = p?.notes ?: context.getString(R.string.datalines_rust_unavailable)
        }
    }

    fun applyChanges() {
        val target = File(localPath ?: return)
        scope.launch {
            val p = enginePlan(fileText, pasted) ?: run {
                toast(context.getString(R.string.datalines_rust_unavailable))
                return@launch
            }
            if (p.changed == 0) {
                toast(context.getString(R.string.datalines_no_changes))
                return@launch
            }
            busyText = context.getString(R.string.datalines_writing_busy)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bak = File(target.path + ".bak")
                    if (!bak.exists()) target.copyTo(bak)
                    target.writeText(p.mergedText)
                    true
                }.getOrDefault(false)
            }
            busyText = null
            if (ok) {
                fileText = p.mergedText
                refreshMeta(p.mergedText)
                toast(context.getString(R.string.datalines_saved_success, p.changed))
            } else {
                toast(context.getString(R.string.datalines_failed_write))
            }
        }
    }

    fun applyToOriginal() {
        val from = localPath ?: return
        val to = originalPath ?: return
        busyText = context.getString(R.string.datalines_applying_busy)
        scope.launch {
            val (code, _) = ShizukuManager.exec("cp \"$from\" \"$to\"")
            busyText = null
            if (code == 0) toast(context.getString(R.string.datalines_applied_success))
            else toast(context.getString(R.string.datalines_apply_failed, code))
        }
    }

    ToolPageScaffold(
        title = stringResource(R.string.datalines_title),
        subtitle = localPath?.let { stringResource(R.string.datalines_subtitle_format, File(it).name, fileLineCount) } ?: stringResource(R.string.datalines_default_subtitle),
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
                id = "preview",
                icon = Icons.Outlined.Visibility,
                contentDescription = stringResource(R.string.datalines_preview_plan_desc),
                isEnabled = localPath != null,
                onClick = { previewPlan() }
            ),
            ContextualDockItem(
                id = "apply_changes",
                icon = Icons.Outlined.Save,
                contentDescription = stringResource(R.string.datalines_apply_changes_desc),
                isEnabled = localPath != null,
                onClick = { applyChanges() }
            ),
            ContextualDockItem(
                id = "apply_original",
                icon = Icons.Outlined.Upload,
                contentDescription = stringResource(R.string.datalines_apply_original_desc),
                isEnabled = originalPath != null,
                onClick = { applyToOriginal() }
            )
        )
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 108.dp)
        ) {
            when {
                error != null -> Text(stringResource(R.string.datalines_cant_open_file, error!!))
                localPath == null -> CircularProgressIndicator()
                else -> {
                    Text(
                        stringResource(R.string.datalines_target_info, File(localPath!!).name, fileLineCount) +
                            if (sectioned) stringResource(R.string.datalines_section_warn) else ""
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val clipboardManager = LocalClipboardManager.current
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text(stringResource(R.string.datalines_paste_label)) },
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipText = clipboardManager.getText()?.text
                                if (!clipText.isNullOrEmpty()) {
                                    pasted = clipText
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentPaste,
                                    contentDescription = "Paste from Clipboard"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
                    )
                    plan?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(it, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    if (busyText != null) {
        BusySheet(busyText!!)
    }
}
