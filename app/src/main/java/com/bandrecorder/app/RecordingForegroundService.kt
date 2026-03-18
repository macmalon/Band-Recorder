package com.bandrecorder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class RecordingForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                ServiceCompat.start(this)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Band Recorder",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintient l'enregistrement actif quand l'application passe en arrière-plan."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private object ServiceCompat {
        fun start(service: RecordingForegroundService) {
            val notification = NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Band Recorder")
                .setContentText("Enregistrement en cours")
                .setOngoing(true)
                .setSilent(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfoCompat.microphoneType()
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private object ServiceInfoCompat {
        fun microphoneType(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "recording_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.bandrecorder.app.action.START_RECORDING_FOREGROUND"
        private const val ACTION_STOP = "com.bandrecorder.app.action.STOP_RECORDING_FOREGROUND"

        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
