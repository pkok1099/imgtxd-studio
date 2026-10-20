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

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "imgtxd_settings")

object AppSettings {

    private val KEY_WINDOW_MODE = stringPreferencesKey("window_mode")

    @Volatile
    var cachedWindowMode: WindowMode = WindowMode.PER_FEATURE
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
}
