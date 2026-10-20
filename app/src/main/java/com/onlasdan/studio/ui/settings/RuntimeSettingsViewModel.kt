package com.onlasdan.studio.ui.settings

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onlasdan.studio.worker.WorkerContracts
import com.onlasdan.studio.worker.workerDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val Context.runtimeDataStore: DataStore<Preferences> get() = this.workerDataStore

data class RuntimeSettingsUiState(
    val isRuntimeEnabled: Boolean = WorkerContracts.DEFAULT_RUNTIME_ENABLED,
    val memoryMode: String = WorkerContracts.DEFAULT_MEMORY_MODE,
    val manualBudgetMb: Int = WorkerContracts.DEFAULT_MANUAL_BUDGET_MB,
    val availableDeviceMemMb: Int = 0,
    val totalDeviceMemMb: Int = 0,
    val maxHeapMb: Int = 0
)

class RuntimeSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    companion object {
        val KEY_RUNTIME_ENABLED = booleanPreferencesKey(WorkerContracts.PREF_KEY_RUNTIME_ENABLED)
        val KEY_MEMORY_MODE = stringPreferencesKey(WorkerContracts.PREF_KEY_MEMORY_MODE)
        val KEY_MANUAL_BUDGET_MB = intPreferencesKey(WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB)
    }

    private val _availableDeviceMemMb = MutableStateFlow(queryAvailableMemoryMb())
    val availableDeviceMemMb: StateFlow<Int> = _availableDeviceMemMb.asStateFlow()

    private val _maxHeapMb = MutableStateFlow(queryMaxHeapMb())
    val maxHeapMb: StateFlow<Int> = _maxHeapMb.asStateFlow()

    val isRuntimeEnabled: StateFlow<Boolean> = context.runtimeDataStore.data.map { prefs ->
        prefs[KEY_RUNTIME_ENABLED] ?: WorkerContracts.DEFAULT_RUNTIME_ENABLED
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WorkerContracts.DEFAULT_RUNTIME_ENABLED
    )

    val memoryMode: StateFlow<String> = context.runtimeDataStore.data.map { prefs ->
        prefs[KEY_MEMORY_MODE] ?: WorkerContracts.DEFAULT_MEMORY_MODE
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WorkerContracts.DEFAULT_MEMORY_MODE
    )

    val manualBudgetMb: StateFlow<Int> = context.runtimeDataStore.data.map { prefs ->
        prefs[KEY_MANUAL_BUDGET_MB] ?: WorkerContracts.DEFAULT_MANUAL_BUDGET_MB
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WorkerContracts.DEFAULT_MANUAL_BUDGET_MB
    )

    val uiState: StateFlow<RuntimeSettingsUiState> = combine(
        context.runtimeDataStore.data,
        _availableDeviceMemMb,
        _maxHeapMb
    ) { prefs, availMem, maxHeap ->
        RuntimeSettingsUiState(
            isRuntimeEnabled = prefs[KEY_RUNTIME_ENABLED] ?: WorkerContracts.DEFAULT_RUNTIME_ENABLED,
            memoryMode = prefs[KEY_MEMORY_MODE] ?: WorkerContracts.DEFAULT_MEMORY_MODE,
            manualBudgetMb = prefs[KEY_MANUAL_BUDGET_MB] ?: WorkerContracts.DEFAULT_MANUAL_BUDGET_MB,
            availableDeviceMemMb = availMem,
            totalDeviceMemMb = queryTotalMemoryMb(),
            maxHeapMb = maxHeap
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RuntimeSettingsUiState(
            availableDeviceMemMb = queryAvailableMemoryMb(),
            totalDeviceMemMb = queryTotalMemoryMb(),
            maxHeapMb = queryMaxHeapMb()
        )
    )

    fun setRuntimeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            context.runtimeDataStore.edit { preferences ->
                preferences[KEY_RUNTIME_ENABLED] = enabled
            }
        }
    }

    fun setMemoryMode(mode: String) {
        val validMode = if (mode.equals("MANUAL", ignoreCase = true)) "MANUAL" else "AUTO"
        viewModelScope.launch(Dispatchers.IO) {
            context.runtimeDataStore.edit { preferences ->
                preferences[KEY_MEMORY_MODE] = validMode
            }
        }
    }

    fun setManualBudget(budgetMb: Int) {
        val clamped = maxOf(WorkerContracts.MIN_BUDGET_MB, budgetMb)
        viewModelScope.launch(Dispatchers.IO) {
            context.runtimeDataStore.edit { preferences ->
                preferences[KEY_MANUAL_BUDGET_MB] = clamped
            }
        }
    }

    fun refreshMemoryInfo() {
        _availableDeviceMemMb.value = queryAvailableMemoryMb()
        _maxHeapMb.value = queryMaxHeapMb()
    }

    private fun queryAvailableMemoryMb(): Int {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            (memInfo.availMem / (1024L * 1024L)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun queryTotalMemoryMb(): Int {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            (memInfo.totalMem / (1024L * 1024L)).toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun queryMaxHeapMb(): Int {
        return try {
            (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
        } catch (e: Exception) {
            0
        }
    }
}
