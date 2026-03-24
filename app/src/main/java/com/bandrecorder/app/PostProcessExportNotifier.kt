package com.bandrecorder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat

internal object PostProcessExportNotifier {
    private const val CHANNEL_ID_RUNNING = "post_process_export_running"
    private const val CHANNEL_ID_FINISHED = "post_process_export_finished"
    private const val NOTIFICATION_ID_RUNNING = 3101
    private const val NOTIFICATION_ID_FINISHED = 3102
    private const val MIN_UPDATE_INTERVAL_MS = 500L

    private var lastRunningProgressPercent: Int? = null
    private var lastRunningUpdateAtMs: Long = 0L

    fun showRunning(context: Context, progressPercent: Int, sourceDisplayName: String?) {
        val boundedProgress = progressPercent.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        val shouldSkip = synchronized(this) {
            val lastProgress = lastRunningProgressPercent
            val progressChanged = lastProgress != boundedProgress
            val enoughTimeElapsed = now - lastRunningUpdateAtMs >= MIN_UPDATE_INTERVAL_MS
            val shouldEmit = lastProgress == null || boundedProgress >= 100 || progressChanged || enoughTimeElapsed
            if (shouldEmit) {
                lastRunningProgressPercent = boundedProgress
                lastRunningUpdateAtMs = now
            }
            !shouldEmit
        }
        if (shouldSkip) return

        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        manager.notify(
            NOTIFICATION_ID_RUNNING,
            NotificationCompat.Builder(context, CHANNEL_ID_RUNNING)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Export audio en cours")
                .setContentText("Export en cours... $boundedProgress%")
                .setSubText(sourceDisplayName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setProgress(100, boundedProgress, false)
                .setContentIntent(mainActivityPendingIntent(context))
                .build()
        )
    }

    fun showFinished(context: Context, message: String, sourceDisplayName: String?) {
        synchronized(this) {
            lastRunningProgressPercent = null
            lastRunningUpdateAtMs = 0L
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        manager.cancel(NOTIFICATION_ID_RUNNING)
        manager.notify(
            NOTIFICATION_ID_FINISHED,
            NotificationCompat.Builder(context, CHANNEL_ID_FINISHED)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Export audio terminé")
                .setContentText(message)
                .setSubText(sourceDisplayName)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(mainActivityPendingIntent(context))
                .build()
        )
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_RUNNING,
                "Band Recorder Export",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Affiche la progression de l'export audio."
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_FINISHED,
                "Band Recorder Export Done",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Informe quand un export audio est terminé."
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun mainActivityPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            3003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
