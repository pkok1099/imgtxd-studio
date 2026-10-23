package com.onlasdan.studio.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.onlasdan.studio.worker.engine.CheckpointManager
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watchdog Guardian Receiver yang bertugas menjaga ketahanan operasi latar belakang:
 * 1. Memulihkan pekerjaan setelah perangkat dinyalakan kembali (BOOT_COMPLETED).
 * 2. Menghidupkan kembali WorkerService secara instan saat siklus onTimeout FGS selesai.
 * 3. Menjalankan watchdog berkala via AlarmManager untuk memastikan tidak ada pekerjaan terhenti.
 */
class JobGuardianReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JobGuardianReceiver"
        private const val WATCHDOG_REQUEST_CODE = 202611
        private const val RESUME_REQUEST_CODE = 202612

        /**
         * Menjadwalkan auto-resume instan (default 3 detik) setelah onTimeout terjadi.
         */
        fun scheduleImmediateResume(context: Context, delayMs: Long = 3000L, targetPath: String? = null) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, JobGuardianReceiver::class.java).apply {
                    action = WorkerContracts.ACTION_RESUME_INTERRUPTED_JOBS
                    if (targetPath != null) {
                        putExtra(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH, targetPath)
                    }
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    RESUME_REQUEST_CODE,
                    intent,
                    flags
                )

                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Android 12+: exact alarm hanya bila izinnya aktif
                    // (SCHEDULE_EXACT_ALARM bisa dicabut user); bila tidak,
                    // jatuh ke setAndAllowWhileIdle — resume tetap jalan,
                    // hanya kurang presisi.
                    val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()
                    if (exactAllowed) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Menjadwalkan auto-resume dalam ${delayMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menjadwalkan auto-resume", e)
            }
        }

        /**
         * Menjadwalkan ping watchdog berkala (default 15 menit) saat operasi aktif berjalan.
         */
        fun scheduleWatchdog(context: Context, intervalMs: Long = 15 * 60 * 1000L) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, JobGuardianReceiver::class.java).apply {
                    action = WorkerContracts.ACTION_WATCHDOG_PING
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    intent,
                    flags
                )

                val triggerAt = SystemClock.elapsedRealtime() + intervalMs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal memasang watchdog alarm", e)
            }
        }

        /**
         * Membatalkan alarm watchdog ketika semua pekerjaan telah selesai.
         */
        fun cancelWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, JobGuardianReceiver::class.java).apply {
                    action = WorkerContracts.ACTION_WATCHDOG_PING
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_NO_CREATE
                }
                val pendingIntent = PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, flags)
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Menerima aksi guardian: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            WorkerContracts.ACTION_RESUME_INTERRUPTED_JOBS -> {
                val targetPath = intent.getStringExtra(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH)
                checkAndTriggerResume(context.applicationContext, targetPath)
            }
            WorkerContracts.ACTION_WATCHDOG_PING -> {
                checkAndTriggerResume(context.applicationContext, null)
            }
        }
    }

    private fun checkAndTriggerResume(context: Context, targetPathHint: String?) {
        val receiverScope = CoroutineScope(Dispatchers.IO)
        receiverScope.launch {
            try {
                var targetPath = targetPathHint
                if (targetPath.isNullOrEmpty()) {
                    targetPath = context.workerDataStore.data.map { prefs ->
                        prefs[stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH)]
                    }.first()
                }

                if (!targetPath.isNullOrEmpty()) {
                    val hasIncomplete = CheckpointManager.hasIncompleteCheckpoint(targetPath)
                    if (hasIncomplete) {
                        Log.i(TAG, "Pekerjaan aktif terdeteksi untuk: $targetPath. Membangunkan WorkerService...")
                        val serviceIntent = Intent(context, WorkerService::class.java).apply {
                            this.action = WorkerContracts.ACTION_RESUME_INTERRUPTED_JOBS
                            putExtra(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH, targetPath)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saat memeriksa pekerjaan untuk resume", e)
            }
        }
    }
}
