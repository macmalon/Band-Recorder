package com.bandrecorder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AnalysisForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private var analysisJob: Job? = null
    private var analysisWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                analysisJob?.cancel()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_START -> {
                val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH) ?: return START_NOT_STICKY
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: File(sourcePath).name
                val sourceKind = intent.getStringExtra(EXTRA_SOURCE_KIND)
                    ?.let { runCatching { ImportedAudioKind.valueOf(it) }.getOrNull() }
                    ?: ImportedAudioKind.UNSUPPORTED
                val silenceThresholdDb = intent.getFloatExtra(EXTRA_THRESHOLD_DB, 0f)
                val silenceDurationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 8)
                startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification("Préparation de l'analyse..."))
                analysisJob?.cancel()
                analysisJob = serviceScope.launch {
                    runAnalysis(
                        sourcePath = sourcePath,
                        displayName = displayName,
                        sourceKind = sourceKind,
                        silenceThresholdDb = silenceThresholdDb,
                        silenceDurationSec = silenceDurationSec
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        analysisJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runAnalysis(
        sourcePath: String,
        displayName: String,
        sourceKind: ImportedAudioKind,
        silenceThresholdDb: Float,
        silenceDurationSec: Int
    ) {
        acquireWakeLock()
        AnalysisCoordinator.publish(AnalysisServiceState.Running(sourcePath, "Préparation de l'analyse..."))
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            finishFailure(sourcePath, "Analyse impossible: fichier introuvable.")
            return
        }

        val workingFile = withContext(Dispatchers.IO) {
            normalizeImportedAudio(
                context = applicationContext,
                sourceFile = sourceFile,
                displayName = displayName,
                kind = sourceKind
            )?.file
        }
        if (workingFile == null || !workingFile.exists()) {
            finishFailure(sourcePath, "Analyse impossible: le fichier audio n'a pas pu être préparé.")
            return
        }

        AnalysisCoordinator.publish(AnalysisServiceState.Running(sourcePath, "Analyse en cours..."))
        updateRunningNotification("Analyse en cours...")
        val analysis = withContext(Dispatchers.Default) {
            analyzeWavBySilence(
                sourceFile = workingFile,
                silenceThresholdDb = silenceThresholdDb,
                silenceDurationSec = silenceDurationSec
            )
        }
        if (analysis == null) {
            finishFailure(sourcePath, "Analyse impossible: le fichier audio n'a pas pu être préparé.")
            return
        }

        AnalysisCoordinator.publish(
            AnalysisServiceState.Completed(
                sourcePath = sourcePath,
                workingFilePath = workingFile.absolutePath,
                analysis = analysis
            )
        )
        showFinishedNotification(
            if (analysis.segments.isEmpty()) "Analyse terminée: aucun segment utile."
            else "Analyse terminée: ${analysis.segments.size} segment(s) détecté(s)."
        )
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishFailure(sourcePath: String, message: String) {
        AnalysisCoordinator.publish(AnalysisServiceState.Failed(sourcePath, message))
        showFinishedNotification(message)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (analysisWakeLock?.isHeld == true) return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandRecorder:AnalysisWakeLock")
        lock.setReferenceCounted(false)
        runCatching {
            lock.acquire(4L * 60L * 60L * 1000L)
            analysisWakeLock = lock
        }.onFailure {
            runCatching { lock.release() }
            analysisWakeLock = null
        }
    }

    private fun releaseWakeLock() {
        val lock = analysisWakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        analysisWakeLock = null
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_RUNNING,
                "Band Recorder Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient l'analyse audio active en arrière-plan."
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_FINISHED,
                "Band Recorder Analysis Done",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Informe quand une analyse audio est terminée."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildRunningNotification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID_RUNNING)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Band Recorder")
        .setContentText(message)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(mainActivityPendingIntent())
        .build()

    private fun updateRunningNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_RUNNING, buildRunningNotification(message))
    }

    private fun showFinishedNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID_FINISHED,
            NotificationCompat.Builder(this, CHANNEL_ID_FINISHED)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Band Recorder")
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(mainActivityPendingIntent())
                .build()
        )
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 2001, intent, flags)
    }

    companion object {
        private const val CHANNEL_ID_RUNNING = "analysis_foreground"
        private const val CHANNEL_ID_FINISHED = "analysis_finished"
        private const val NOTIFICATION_ID_RUNNING = 1101
        private const val NOTIFICATION_ID_FINISHED = 1102
        private const val ACTION_START = "com.bandrecorder.app.action.START_ANALYSIS_FOREGROUND"
        private const val ACTION_STOP = "com.bandrecorder.app.action.STOP_ANALYSIS_FOREGROUND"
        private const val EXTRA_SOURCE_PATH = "extra_source_path"
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_SOURCE_KIND = "extra_source_kind"
        private const val EXTRA_THRESHOLD_DB = "extra_threshold_db"
        private const val EXTRA_DURATION_SEC = "extra_duration_sec"

        internal fun start(
            context: Context,
            sourcePath: String,
            displayName: String,
            sourceKind: ImportedAudioKind,
            silenceThresholdDb: Float,
            silenceDurationSec: Int
        ) {
            val intent = Intent(context, AnalysisForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_SOURCE_KIND, sourceKind.name)
                putExtra(EXTRA_THRESHOLD_DB, silenceThresholdDb)
                putExtra(EXTRA_DURATION_SEC, silenceDurationSec)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun stop(context: Context) {
            val intent = Intent(context, AnalysisForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
