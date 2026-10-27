package com.onlasdan.studio.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class WindowMode(val title: String, val description: String) {
    PER_FEATURE(
        title = "Per Fitur (Default)",
        description = "At most 1 IMG window and 1 TXD window. New files reuse the existing window. IMG and TXD sit side by side as two cards in Recent Apps."
    ),
    PER_FILE(
        title = "Per File",
        description = "Each file opens in its own window (multiple document cards in Recent Apps, like browser tabs)."
    ),
    SINGLE_WINDOW(
        title = "Single Window",
        description = "All files and textures open inside the same app window (classic behavior)."
    )
}

enum class TxdViewMode(val title: String) {
    SMALL("Gambar Kecil"),
    LARGE("Gambar Besar")
}

enum class TxdBgColor(val label: String, val argb: Long) {
    DEFAULT("Theme", 0L),
    BLACK("Black", 0xFF000000),
    WHITE("White", 0xFFFFFFFF),
    GRAY("Gray", 0xFF808080),
    RED("Red", 0xFFFF0000),
    GREEN("Green", 0xFF00FF00),
    BLUE("Blue", 0xFF0000FF),
    YELLOW("Yellow", 0xFFFFFF00),
    CYAN("Cyan", 0xFF00FFFF),
    MAGENTA("Magenta", 0xFFFF00FF)
}

data class RecentFileItem(
    val path: String,
    val name: String,
    val type: LauncherFileType,
    val isShizuku: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "imgtxd_settings")

object AppSettings {

    private val KEY_WINDOW_MODE = stringPreferencesKey("window_mode")
    private val KEY_TXD_VIEW_MODE = stringPreferencesKey("txd_view_mode")
    private val KEY_TXD_BG_COLOR = stringPreferencesKey("txd_bg_color")
    private val KEY_RECENT_FILES = stringPreferencesKey("recent_files_json")
    private val KEY_LAST_FILE_DIR = stringPreferencesKey("picker_last_file_dir")
    private val KEY_LAST_FOLDER_DIR = stringPreferencesKey("picker_last_folder_dir")

    @Volatile
    var cachedWindowMode: WindowMode = WindowMode.PER_FEATURE
        private set

    @Volatile
    var cachedTxdViewMode: TxdViewMode = TxdViewMode.SMALL
        private set

    @Volatile
    var cachedTxdBgColor: TxdBgColor = TxdBgColor.DEFAULT
        private set

    @Volatile
    var cachedLastFileDir: String? = null
        private set

    @Volatile
    var cachedLastFolderDir: String? = null
        private set

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        CoroutineScope(Dispatchers.IO).launch {
            getWindowModeFlow(context).collect { mode ->
                cachedWindowMode = mode
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            getTxdViewModeFlow(context).collect { mode ->
                cachedTxdViewMode = mode
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            getTxdBgColorFlow(context).collect { color ->
                cachedTxdBgColor = color
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            getLastDirFlow(context, folderPicker = false).collect { dir ->
                cachedLastFileDir = dir
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            getLastDirFlow(context, folderPicker = true).collect { dir ->
                cachedLastFolderDir = dir
            }
        }
    }

    // --- Last visited picker directory (per picker context) ---

    fun getLastDirFlow(context: Context, folderPicker: Boolean): Flow<String?> {
        val key = if (folderPicker) KEY_LAST_FOLDER_DIR else KEY_LAST_FILE_DIR
        return context.appDataStore.data.map { preferences -> preferences[key] }
    }

    suspend fun setLastDir(context: Context, folderPicker: Boolean, path: String) {
        if (folderPicker) cachedLastFolderDir = path else cachedLastFileDir = path
        val key = if (folderPicker) KEY_LAST_FOLDER_DIR else KEY_LAST_FILE_DIR
        context.appDataStore.edit { preferences ->
            preferences[key] = path
        }
    }

    /**
     * Start directory for the file picker: the folder the user visited last in
     * the same picker context, validated so a deleted/moved folder falls back
     * to its nearest existing ancestor (or [fallback] when nothing survives).
     * Shizuku-restricted paths cannot be stat'ed with java.io.File, so they are
     * trusted as-is (the picker surfaces an error if they are really gone).
     */
    fun pickerStartDir(folderPicker: Boolean, fallback: String): String {
        val remembered = (if (folderPicker) cachedLastFolderDir else cachedLastFileDir)
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return fallback
        if (ShizukuManager.isRestrictedPath(remembered)) return remembered
        var candidate: String? = remembered
        while (!candidate.isNullOrBlank() && candidate != "/") {
            if (File(candidate).isDirectory) return candidate
            candidate = File(candidate).parent
        }
        return fallback
    }

    fun getWindowModeFlow(context: Context): Flow<WindowMode> {
        return context.appDataStore.data.map { preferences ->
            val modeStr = preferences[KEY_WINDOW_MODE] ?: WindowMode.PER_FEATURE.name
            try {
                WindowMode.valueOf(modeStr)
            } catch (e: Exception) {
                WindowMode.PER_FEATURE
            }
        }
    }

    suspend fun setWindowMode(context: Context, mode: WindowMode) {
        cachedWindowMode = mode
        context.appDataStore.edit { preferences ->
            preferences[KEY_WINDOW_MODE] = mode.name
        }
    }

    fun getTxdViewModeFlow(context: Context): Flow<TxdViewMode> {
        return context.appDataStore.data.map { preferences ->
            val modeStr = preferences[KEY_TXD_VIEW_MODE] ?: TxdViewMode.SMALL.name
            try {
                TxdViewMode.valueOf(modeStr)
            } catch (e: Exception) {
                TxdViewMode.SMALL
            }
        }
    }

    suspend fun setTxdViewMode(context: Context, mode: TxdViewMode) {
        cachedTxdViewMode = mode
        context.appDataStore.edit { preferences ->
            preferences[KEY_TXD_VIEW_MODE] = mode.name
        }
    }

    fun getTxdBgColorFlow(context: Context): Flow<TxdBgColor> {
        return context.appDataStore.data.map { preferences ->
            val colorStr = preferences[KEY_TXD_BG_COLOR] ?: TxdBgColor.DEFAULT.name
            try {
                TxdBgColor.valueOf(colorStr)
            } catch (e: Exception) {
                TxdBgColor.DEFAULT
            }
        }
    }

    suspend fun setTxdBgColor(context: Context, color: TxdBgColor) {
        cachedTxdBgColor = color
        context.appDataStore.edit { preferences ->
            preferences[KEY_TXD_BG_COLOR] = color.name
        }
    }

    fun getRecentFilesFlow(context: Context): Flow<List<RecentFileItem>> {
        return context.appDataStore.data.map { preferences ->
            val jsonStr = preferences[KEY_RECENT_FILES] ?: "[]"
            parseRecentFiles(jsonStr)
        }
    }

    suspend fun addRecentFile(
        context: Context,
        path: String,
        type: LauncherFileType,
        isShizuku: Boolean
    ) {
        val fileName = File(path).name
        context.appDataStore.edit { preferences ->
            val jsonStr = preferences[KEY_RECENT_FILES] ?: "[]"
            val existingList = parseRecentFiles(jsonStr).toMutableList()
            existingList.removeAll { it.path == path }
            existingList.add(
                0,
                RecentFileItem(
                    path = path,
                    name = fileName,
                    type = type,
                    isShizuku = isShizuku,
                    timestamp = System.currentTimeMillis()
                )
            )
            val trimmed = existingList.take(10)
            preferences[KEY_RECENT_FILES] = serializeRecentFiles(trimmed)
        }
    }

    private fun parseRecentFiles(jsonStr: String): List<RecentFileItem> {
        val list = mutableListOf<RecentFileItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val path = obj.optString("path")
                val name = obj.optString("name", File(path).name)
                val typeStr = obj.optString("type", "IMG")
                val isShizuku = obj.optBoolean("isShizuku", false)
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val type = try {
                    LauncherFileType.valueOf(typeStr)
                } catch (e: Exception) {
                    if (path.endsWith(".txd", ignoreCase = true)) LauncherFileType.TXD else LauncherFileType.IMG
                }
                if (path.isNotBlank()) {
                    list.add(RecentFileItem(path, name, type, isShizuku, timestamp))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun serializeRecentFiles(items: List<RecentFileItem>): String {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("path", item.path)
                put("name", item.name)
                put("type", item.type.name)
                put("isShizuku", item.isShizuku)
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
}
