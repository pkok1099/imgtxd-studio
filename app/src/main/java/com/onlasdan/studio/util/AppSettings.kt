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
        description = "Maksimal 1 jendela IMG dan 1 jendela TXD. File baru menggunakan ulang jendela yang ada. IMG dan TXD bisa berdampingan sebagai dua kartu di Recent Apps."
    ),
    PER_FILE(
        title = "Per File",
        description = "Setiap file dibuka di jendela tersendiri (banyak kartu dokumen di Recent Apps seperti tab browser)."
    ),
    SINGLE_WINDOW(
        title = "Jendela Tunggal",
        description = "Semua file dan tekstur dibuka di dalam satu jendela aplikasi yang sama (perilaku klasik)."
    )
}

enum class TxdViewMode(val title: String) {
    SMALL("Gambar Kecil"),
    LARGE("Gambar Besar")
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
    private val KEY_RECENT_FILES = stringPreferencesKey("recent_files_json")

    @Volatile
    var cachedWindowMode: WindowMode = WindowMode.PER_FEATURE
        private set

    @Volatile
    var cachedTxdViewMode: TxdViewMode = TxdViewMode.SMALL
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
