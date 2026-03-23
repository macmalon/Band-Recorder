package com.bandrecorder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

internal object PostProcessExportNotifier {
    private const val CHANNEL_ID_RUNNING = "post_process_export_running"
    private const val CHANNEL_ID_FINISHED = "post_process_export_finished"
    private const val NOTIFICATION_ID_RUNNING = 3101
    private const val NOTIFICATION_ID_FINISHED = 3102

    fun showRunning(context: Context, progressPercent: Int, sourceDisplayName: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        manager.notify(
            NOTIFICATION_ID_RUNNING,
            NotificationCompat.Builder(context, CHANNEL_ID_RUNNING)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Export audio en cours")
                .setContentText("Export en cours... ${progressPercent.coerceIn(0, 100)}%")
                .setSubText(sourceDisplayName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setProgress(100, progressPercent.coerceIn(0, 100), false)
                .setContentIntent(mainActivityPendingIntent(context))
                .build()
        )
    }

    fun showFinished(context: Context, message: String, sourceDisplayName: String?) {
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

    fun cancelRunning(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_RUNNING)
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
