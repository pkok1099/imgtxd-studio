package com.onlasdan.studio.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.onlasdan.studio.worker.engine.JobEngineImpl
import com.onlasdan.studio.worker.memory.MemoryBudgetProviderImpl
import com.onlasdan.studio.worker.model.JobProgress
import com.onlasdan.studio.worker.model.JobRequest
import com.onlasdan.studio.worker.model.JobResult
import com.onlasdan.studio.worker.shizuku.ShizukuFileBridgeImpl
import com.onlasdan.studio.worker.shizuku.WorkerShizukuManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WorkerService : Service() {

    companion object {
        private const val TAG = "WorkerService"
        private const val WAKELOCK_TAG = "ImgTxdStudio:WorkerWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 hours safety limit

        @Volatile
        var customJobEngine: JobEngine? = null

        @Volatile
        var customMemoryBudgetProvider: MemoryBudgetProvider? = null

        @Volatile
        var jobEngineFactory: ((Context) -> JobEngine)? = null

        @Volatile
        var memoryBudgetProviderFactory: ((Context) -> MemoryBudgetProvider)? = null
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val queueLock = Any()
    private val jobQueue = ArrayDeque<JobRequest>()
    private val activeJobProgressMap = LinkedHashMap<String, JobProgress>()

    @Volatile
    private var currentJob: JobRequest? = null

    @Volatile
    private var isProcessing = false

    @Volatile
    private var isForeground = false

    private var processingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val memoryBudgetProvider: MemoryBudgetProvider by lazy {
        customMemoryBudgetProvider
            ?: memoryBudgetProviderFactory?.invoke(this)
            ?: createDefaultMemoryBudgetProvider()
    }

    private val jobEngine: JobEngine by lazy {
        customJobEngine
            ?: jobEngineFactory?.invoke(this)
            ?: createDefaultJobEngine()
    }

    private val callbacks = RemoteCallbackList<IWorkerCallback>()
    private val callbackLock = Any()

    @Volatile
    private var isTimingOut = false

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WorkerContracts.ACTION_CANCEL_JOB) {
                val jobId = intent.getStringExtra(WorkerContracts.EXTRA_JOB_ID)
                if (!jobId.isNullOrEmpty()) {
                    cancelJobInternal(jobId)
                } else {
                    currentJob?.id?.let { cancelJobInternal(it) }
                }
            }
        }
    }

    private val binder = object : IWorkerService.Stub() {
        override fun submitJob(request: JobRequest?): String {
            if (request == null) return ""
            return enqueueJob(request)
        }

        override fun cancelJob(jobId: String?): Boolean {
            if (jobId.isNullOrEmpty()) return false
            return cancelJobInternal(jobId)
        }

        override fun getActiveJobs(): List<JobProgress> {
            return getActiveJobsInternal()
        }

        override fun registerCallback(callback: IWorkerCallback?) {
            if (callback != null) {
                callbacks.register(callback)
            }
        }

        override fun unregisterCallback(callback: IWorkerCallback?) {
            if (callback != null) {
                callbacks.unregister(callback)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WorkerService onCreate")
        WorkerNotificationHelper.ensureNotificationChannel(this)
        WorkerShizukuManager.init(applicationContext)

        val filter = IntentFilter(WorkerContracts.ACTION_CANCEL_JOB)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cancelReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                WorkerContracts.ACTION_CANCEL_JOB -> {
                    val jobId = intent.getStringExtra(WorkerContracts.EXTRA_JOB_ID)
                    if (!jobId.isNullOrEmpty()) {
                        cancelJobInternal(jobId)
                    } else {
                        currentJob?.id?.let { cancelJobInternal(it) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "WorkerService onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "WorkerService onUnbind")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.d(TAG, "WorkerService onRebind")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "WorkerService onTrimMemory level: $level")
        try {
            memoryBudgetProvider.onTrimMemory(level)
        } catch (e: Throwable) {
            Log.w(TAG, "Error forwarding onTrimMemory", e)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleSystemTimeout(startId, fgsType)
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleSystemTimeout(startId, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "WorkerService onDestroy")
        try {
            unregisterReceiver(cancelReceiver)
        } catch (_: Exception) {}

        serviceJob.cancel()
        releaseWakeLock()
        stopForegroundService()
        callbacks.kill()
    }

    // --- Queue Management ---

    private fun enqueueJob(request: JobRequest): String {
        val id = request.id
        val initialProgress = JobProgress(
            id = id,
            percent = 0f,
            currentItem = 0,
            totalItems = 0,
            status = "QUEUED",
            currentItemName = null,
            message = "Menunggu antrean..."
        )

        synchronized(queueLock) {
            jobQueue.addLast(request)
            activeJobProgressMap[id] = initialProgress
        }

        notifyProgress(initialProgress)
        notifyQueueChanged()

        startProcessingIfNeeded()
        return id
    }

    private fun cancelJobInternal(jobId: String): Boolean {
        var foundAndCancelled = false

        // Case 1: Currently running job
        val running = currentJob
        if (running != null && running.id == jobId) {
            foundAndCancelled = true
            try {
                jobEngine.cancel(jobId)
            } catch (e: Throwable) {
                Log.w(TAG, "Error cancelling job in JobEngine: $jobId", e)
            }
            val cancelledProgress = JobProgress(
                id = jobId,
                status = "CANCELLED",
                message = "Dibatalkan oleh pengguna"
            )
            synchronized(queueLock) {
                activeJobProgressMap[jobId] = cancelledProgress
            }
            notifyProgress(cancelledProgress)
        }

        // Case 2: Job queued in waiting list
        var removedFromQueue = false
        synchronized(queueLock) {
            val iterator = jobQueue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.id == jobId) {
                    iterator.remove()
                    activeJobProgressMap.remove(jobId)
                    removedFromQueue = true
                    foundAndCancelled = true
                    break
                }
            }
        }

        if (removedFromQueue) {
            val cancelledProgress = JobProgress(
                id = jobId,
                status = "CANCELLED",
                message = "Dibatalkan dari antrean"
            )
            notifyProgress(cancelledProgress)
            notifyFinished(
                JobResult(
                    id = jobId,
                    success = false,
                    message = "Dibatalkan dari antrean"
                )
            )
            notifyQueueChanged()
        }

        checkQueueEmptyAndShutdown()
        return foundAndCancelled
    }

    private fun getActiveJobsInternal(): List<JobProgress> {
        synchronized(queueLock) {
            return ArrayList(activeJobProgressMap.values)
        }
    }

    private fun startProcessingIfNeeded() {
        synchronized(queueLock) {
            if (isProcessing) return
            if (jobQueue.isEmpty() && currentJob == null) return
            isProcessing = true
        }

        // Ensure service has started lifecycle independent of binder
        try {
            val serviceIntent = Intent(applicationContext, WorkerService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to startService explicitly", e)
        }

        processingJob = serviceScope.launch {
            acquireWakeLock()
            try {
                while (isActive) {
                    val nextRequest = synchronized(queueLock) {
                        jobQueue.removeFirstOrNull()?.also {
                            currentJob = it
                        }
                    } ?: break

                    val totalRemaining = synchronized(queueLock) { jobQueue.size + 1 }
                    val runningProgress = JobProgress(
                        id = nextRequest.id,
                        percent = 0f,
                        currentItem = 0,
                        totalItems = 0,
                        status = "RUNNING",
                        currentItemName = null,
                        message = "Memulai proses..."
                    )

                    synchronized(queueLock) {
                        activeJobProgressMap[nextRequest.id] = runningProgress
                    }

                    notifyProgress(runningProgress)
                    notifyQueueChanged()

                    // Simpan penanda pekerjaan aktif ke DataStore untuk pemulihan orphan jika terjadi LMK / crash
                    try {
                        applicationContext.workerDataStore.edit { preferences ->
                            preferences[stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_JOB_ID)] = nextRequest.id
                            preferences[stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH)] = nextRequest.targetPath
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal mencatat status pekerjaan aktif ke DataStore", e)
                    }

                    val foregroundNotif = WorkerNotificationHelper.buildForegroundNotification(
                        this@WorkerService,
                        runningProgress,
                        totalRemaining
                    )
                    startForegroundServiceInternal(foregroundNotif)

                    val budget = memoryBudgetProvider.getCurrentBudget()
                    var lastNotifUpdateTime = 0L

                    val result = try {
                        jobEngine.execute(nextRequest, budget) { progress ->
                            synchronized(queueLock) {
                                activeJobProgressMap[nextRequest.id] = progress
                            }
                            notifyProgress(progress)

                            val now = SystemClock.uptimeMillis()
                            if (now - lastNotifUpdateTime > 250L || progress.percent >= 1f || progress.status != "RUNNING") {
                                lastNotifUpdateTime = now
                                val updatedNotif = WorkerNotificationHelper.buildForegroundNotification(
                                    this@WorkerService,
                                    progress,
                                    synchronized(queueLock) { jobQueue.size + 1 }
                                )
                                updateNotification(updatedNotif)
                            }
                        }
                    } catch (e: CancellationException) {
                        JobResult(
                            id = nextRequest.id,
                            success = false,
                            message = "Dibatalkan"
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "Exception during job execution: ${nextRequest.id}", e)
                        JobResult(
                            id = nextRequest.id,
                            success = false,
                            message = e.localizedMessage ?: "Terjadi kesalahan pada engine"
                        )
                    } finally {
                        // Hapus penanda aktif di DataStore setelah pekerjaan selesai dieksekusi
                        try {
                            applicationContext.workerDataStore.edit { preferences ->
                                preferences.remove(stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_JOB_ID))
                                preferences.remove(stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Gagal membersihkan penanda aktif dari DataStore", e)
                        }
                    }

                    synchronized(queueLock) {
                        currentJob = null
                        activeJobProgressMap.remove(nextRequest.id)
                    }

                    notifyFinished(result)
                    notifyQueueChanged()

                    if (result.success) {
                        WorkerNotificationHelper.sendFinishedNotification(this@WorkerService, result)
                    } else {
                        WorkerNotificationHelper.sendFailedNotification(
                            this@WorkerService,
                            result.message ?: "Operasi gagal"
                        )
                    }
                }
            } finally {
                val shouldStop = synchronized(queueLock) {
                    if (jobQueue.isEmpty()) {
                        isProcessing = false
                        currentJob = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldStop) {
                    releaseWakeLock()
                    stopForegroundService()
                    stopSelf()
                } else {
                    startProcessingIfNeeded()
                }
            }
        }
    }

    private fun checkQueueEmptyAndShutdown() {
        val shouldStop = synchronized(queueLock) {
            jobQueue.isEmpty() && currentJob == null
        }
        if (shouldStop) {
            releaseWakeLock()
            stopForegroundService()
            stopSelf()
        }
    }

    // --- Foreground & Notification Management ---

    private fun startForegroundServiceInternal(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    WorkerContracts.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(WorkerContracts.NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground", e)
        }
    }

    private fun updateNotification(notification: Notification) {
        try {
            notificationManager.notify(WorkerContracts.NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun stopForegroundService() {
        if (isForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stopForeground", e)
            } finally {
                isForeground = false
            }
        }
        try {
            notificationManager.cancel(WorkerContracts.NOTIFICATION_ID)
        } catch (_: Throwable) {}
    }

    // --- WakeLock Management ---

    @Synchronized
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKELOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
    }

    // --- System Timeout (Android 15+ API 35) ---

    private fun handleSystemTimeout(startId: Int, fgsType: Int) {
        synchronized(queueLock) {
            if (isTimingOut) return
            isTimingOut = true
        }

        Log.w(TAG, "System timeout triggered for startId: $startId, fgsType: $fgsType")
        processingJob?.cancel()

        val active = currentJob
        if (active != null) {
            val activeId = active.id
            try {
                jobEngine.cancel(activeId)
            } catch (e: Throwable) {
                Log.e(TAG, "Error cancelling job on timeout", e)
            }

            trySaveCheckpoint(active)

            // Bersihkan penanda aktif dari DataStore saat timeout sistem
            try {
                serviceScope.launch {
                    applicationContext.workerDataStore.edit { preferences ->
                        preferences.remove(stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_JOB_ID))
                        preferences.remove(stringPreferencesKey(WorkerContracts.PREF_KEY_ACTIVE_TARGET_PATH))
                    }
                }
            } catch (_: Exception) {}

            val timeoutProgress = JobProgress(
                id = activeId,
                status = "FAILED",
                message = "Operasi dihentikan: Batas waktu background dataSync tercapai"
            )
            notifyProgress(timeoutProgress)
            notifyFinished(
                JobResult(
                    id = activeId,
                    success = false,
                    message = "Waktu eksekusi latar belakang habis oleh sistem Android"
                )
            )
        }

        synchronized(queueLock) {
            jobQueue.clear()
            activeJobProgressMap.clear()
            currentJob = null
            isProcessing = false
        }
        notifyQueueChanged()

        WorkerNotificationHelper.sendFailedNotification(
            this,
            "Operasi dihentikan: Batas waktu latar belakang tercapai"
        )

        releaseWakeLock()
        stopForegroundService()
        stopSelf()
    }

    private fun trySaveCheckpoint(job: JobRequest) {
        try {
            val checkpointClass = Class.forName("com.onlasdan.studio.worker.engine.CheckpointManager")
            val instance = checkpointClass.getDeclaredConstructor().newInstance()
            val method = checkpointClass.methods.firstOrNull {
                it.name.contains("Checkpoint", ignoreCase = true)
            }
            Log.d(TAG, "CheckpointManager available for job ${job.id}")
        } catch (_: Throwable) {
            Log.d(TAG, "CheckpointManager not active or handled directly in JobEngine")
        }
    }

    // --- Callbacks Dispatching ---

    private fun notifyProgress(progress: JobProgress) {
        synchronized(callbackLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        callbacks.getBroadcastItem(i).onProgress(progress)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in onProgress callback", e)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun notifyFinished(result: JobResult) {
        synchronized(callbackLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        callbacks.getBroadcastItem(i).onFinished(result)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in onFinished callback", e)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun notifyQueueChanged() {
        val currentQueue = getActiveJobsInternal()
        synchronized(callbackLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        callbacks.getBroadcastItem(i).onQueueChanged(currentQueue)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in onQueueChanged callback", e)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    // --- Engine & Budget Factory Fallbacks ---

    private fun createDefaultMemoryBudgetProvider(): MemoryBudgetProvider {
        return try {
            MemoryBudgetProviderImpl(applicationContext)
        } catch (e: Throwable) {
            Log.d(TAG, "Using fallback MemoryBudgetProvider: ${e.message}")
            object : MemoryBudgetProvider {
                private val defaultBudget = MemoryBudget()
                override fun getCurrentBudget(): MemoryBudget = defaultBudget
                override fun onTrimMemory(level: Int) {}
            }
        }
    }

    private fun createDefaultJobEngine(): JobEngine {
        return try {
            val bridge = ShizukuFileBridgeImpl(applicationContext)
            JobEngineImpl(bridge)
        } catch (e: Throwable) {
            Log.d(TAG, "Using fallback JobEngine: ${e.message}")
            object : JobEngine {
                override suspend fun execute(
                    request: JobRequest,
                    budget: MemoryBudget,
                    onProgress: (JobProgress) -> Unit
                ): JobResult {
                    return JobResult(
                        id = request.id,
                        success = false,
                        message = "JobEngine implementation not available: ${e.message}"
                    )
                }

                override fun cancel(jobId: String): Boolean = false
            }
        }
    }
}
