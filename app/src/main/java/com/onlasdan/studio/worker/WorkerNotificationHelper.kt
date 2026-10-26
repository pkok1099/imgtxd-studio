package com.onlasdan.studio.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.onlasdan.studio.R
import com.onlasdan.studio.worker.model.JobProgress
import com.onlasdan.studio.worker.model.JobResult

object WorkerNotificationHelper {

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val existingChannel = notificationManager.getNotificationChannel(WorkerContracts.NOTIFICATION_CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    WorkerContracts.NOTIFICATION_CHANNEL_ID,
                    WorkerContracts.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "ImgTxd Studio background operation status"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    fun createNotificationChannel(context: Context) {
        ensureNotificationChannel(context)
    }

    fun buildForegroundNotification(
        context: Context,
        currentJob: JobProgress?,
        queueSize: Int
    ): Notification {
        ensureNotificationChannel(context)

        val title = currentJob?.message ?: "Processing files..."
        val currentItem = currentJob?.currentItem ?: 0
        val totalItems = currentJob?.totalItems ?: 0
        val percent = ((currentJob?.percent ?: 0f) * 100).toInt().coerceIn(0, 100)
        val contentText = "Item $currentItem/$totalItems ($percent%)"

        val isIndeterminate = totalItems == 0

        val builder = NotificationCompat.Builder(context, WorkerContracts.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(100, percent, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Action button "Batal" (Cancel)
        val cancelIntent = Intent(context, WorkerService::class.java).apply {
            action = WorkerContracts.ACTION_CANCEL_JOB
            if (currentJob != null) {
                putExtra(WorkerContracts.EXTRA_JOB_ID, currentJob.id)
            }
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            currentJob?.id?.hashCode() ?: 0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            cancelPendingIntent
        )

        return builder.build()
    }

    fun sendFinishedNotification(context: Context, result: JobResult) {
        ensureNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val title = if (result.success) "Operation Complete" else "Operation Failed"
        val message = result.message?.ifBlank { null }
            ?: if (result.success) {
                "All files processed successfully"
            } else {
                "An error occurred while processing files"
            }

        val notification = NotificationCompat.Builder(context, WorkerContracts.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            notificationManager.notify(result.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission might be revoked by user
        }
    }

    fun sendFailedNotification(context: Context, errorMsg: String) {
        ensureNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val notification = NotificationCompat.Builder(context, WorkerContracts.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Operation Failed")
            .setContentText(errorMsg)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        try {
            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        } catch (_: SecurityException) {
            // Notification permission might be revoked by user
        }
    }
}
