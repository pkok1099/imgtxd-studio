package com.onlasdan.studio.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.workerDataStore: DataStore<Preferences> by preferencesDataStore(name = WorkerContracts.DATASTORE_NAME)

object WorkerContracts {
    const val DATASTORE_NAME = "runtime_process_settings"

    // Preferences keys
    const val PREF_KEY_RUNTIME_ENABLED = "runtime_process_enabled"
    const val PREF_KEY_MEMORY_MODE = "runtime_memory_mode" // "AUTO" or "MANUAL"
    const val PREF_KEY_MANUAL_BUDGET_MB = "runtime_manual_budget_mb"
    const val PREF_KEY_LAST_CRASH_INFO = "runtime_last_crash_info"
    const val PREF_KEY_ACTIVE_JOB_ID = "runtime_active_job_id"
    const val PREF_KEY_ACTIVE_TARGET_PATH = "runtime_active_target_path"

    // Default values
    const val DEFAULT_RUNTIME_ENABLED = true
    const val DEFAULT_MEMORY_MODE = "AUTO"
    const val DEFAULT_MANUAL_BUDGET_MB = 512
    const val MIN_BUDGET_MB = 256

    // File extensions
    const val EXT_LOCK = ".lock"
    const val EXT_CHECKPOINT = ".checkpoint"
    const val EXT_TMP = ".tmp"

    // Notification channel & ID
    const val NOTIFICATION_CHANNEL_ID = "worker_runtime_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Operasi Latar Belakang"
    const val NOTIFICATION_ID = 202610

    // Actions & Extras
    const val ACTION_CANCEL_JOB = "com.onlasdan.studio.worker.action.CANCEL_JOB"
    const val EXTRA_JOB_ID = "extra_job_id"
}
