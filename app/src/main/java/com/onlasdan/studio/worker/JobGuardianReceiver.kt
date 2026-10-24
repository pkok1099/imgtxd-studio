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
                    // Eksplisit + package sendiri: onReceive menolak custom
                    // action dari pengirim asing (receiver memang exported
                    // agar BOOT_COMPLETED selalu terkirim oleh sistem).
                    setPackage(context.packageName)
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
                    setPackage(context.packageName)
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
                    setPackage(context.packageName)
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

        val targetHint = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            WorkerContracts.ACTION_RESUME_INTERRUPTED_JOBS ->
                intent.getStringExtra(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH)
            WorkerContracts.ACTION_WATCHDOG_PING -> null
            else -> return
        }

        // Custom action hanya sah dari aplikasi ini sendiri. Receiver exported
        // karena BOOT_COMPLETED butuh akses sistem; tanpa filter intent untuk
        // custom action, app lain masih bisa mengirimnya ke receiver ini via
        // sendBroadcast(setPackage(...)). Pertahanan utama adalah cross-check
        // targetPath ke workerDataStore di checkAndTriggerResume — app lain
        // tidak bisa membaca DataStore privat kita, jadi tidak bisa menyisipkan
        // targetPath yang cocok.
        //
        // Catatan: BroadcastReceiver.getSendingPackage() ada di API 26 tapi
        // ditandai @SystemApi(MODULE_LIBRARIES) di SDK publik, jadi tidak bisa
        // dipanggil dari app biasa. Pertahanan kita sepenuhnya pada cross-check.
        if (action == WorkerContracts.ACTION_RESUME_INTERRUPTED_JOBS ||
            action == WorkerContracts.ACTION_WATCHDOG_PING
        ) {
            // Cross-check di checkAndTriggerResume menolak targetPath yang tidak
            // cocok workerDataStore, jadi spoofing dari app lain gagal di sana.
            // Tidak ada pemeriksaan tambahan di sini karena getSendingPackage()
            // adalah SystemApi yang tidak accessible.
        }

        // goAsync(): pekerjaan async (DataStore IO + cek checkpoint) harus
        // selesai sebelum proses boleh dibunuh sistem. Tanpa goAsync,
        // coroutine di bawah bisa mati bersama proses setelah onReceive
        // selesai sehingga auto-resume kadang tidak pernah jalan.
        //
        // Catatan: sebelumnya checkAndTriggerResume membuat CoroutineScope
        // baru dan fire-and-forget, sehingga pendingResult.finish() sering
        // dipanggil sebelum DataStore read selesai — kemudian proses dibunuh
        // dan resume tidak pernah jalan. Sekarang checkAndTriggerResume
        // adalah suspend fun yang inline ke outer coroutine ini.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                checkAndTriggerResume(context.applicationContext, targetHint)
            } catch (e: Exception) {
                Log.e(TAG, "Error saat memeriksa pekerjaan untuk resume", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun checkAndTriggerResume(context: Context, targetPathHint: String?) {
        try {
            var targetPath = targetPathHint
            if (targetPath.isNullOrEmpty()) {
                targetPath = context.workerDataStore.data.map { prefs ->
                    prefs[stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH)]
                }.first()
            } else {
                // Cross-check: jika targetPathHint datang dari broadcast extra,
                // pastikan cocok dengan yang tersimpan di workerDataStore.
                // Tanpa ini, app lain bisa menyisipkan extra palsu walau
                // getSendingPackage terlewat (mis. via pendingintent di-share).
                val stored = context.workerDataStore.data.map { prefs ->
                    prefs[stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH)]
                }.first()
                if (stored.isNullOrEmpty() || stored != targetPath) {
                    Log.w(TAG, "targetPathHint '$targetPath' tidak cocok workerDataStore; mengabaikan")
                    return
                }
            }

            if (!targetPath.isNullOrEmpty()) {
                val hasIncomplete = CheckpointManager.hasIncompleteCheckpoint(targetPath)
                if (hasIncomplete) {
                    Log.i(TAG, "Pekerjaan aktif terdeteksi untuk: $targetPath. Membangunkan WorkerService...")
                    val serviceIntent = Intent(context, WorkerService::class.java).apply {
                        this.action = WorkerContracts.ACTION_RESUME_INTERRUPTED_JOBS
                        setPackage(context.packageName)
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
