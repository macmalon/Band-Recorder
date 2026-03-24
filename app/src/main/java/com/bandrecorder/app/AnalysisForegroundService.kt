package com.bandrecorder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var analysisJob: Job? = null
    private var analysisWakeLock: PowerManager.WakeLock? = null
    private var runningStartedAtMs: Long = 0L
    private var lastPublishedProgressPercent: Int = -1
    private var lastPublishedMessage: String = ""

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
                val sourceUriString = intent.getStringExtra(EXTRA_SOURCE_URI)
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: File(sourcePath).name
                val sourceKind = intent.getStringExtra(EXTRA_SOURCE_KIND)
                    ?.let { runCatching { ImportedAudioKind.valueOf(it) }.getOrNull() }
                    ?: ImportedAudioKind.UNSUPPORTED
                val cachedWorkingFilePath = intent.getStringExtra(EXTRA_CACHED_WORKING_FILE_PATH)
                val sourceDurationMs = intent.getLongExtra(EXTRA_SOURCE_DURATION_MS, -1L).takeIf { it >= 0L }
                val sourceSampleRateHz = intent.getIntExtra(EXTRA_SOURCE_SAMPLE_RATE_HZ, -1).takeIf { it > 0 }
                val sourceChannels = intent.getIntExtra(EXTRA_SOURCE_CHANNELS, -1).takeIf { it > 0 }
                val silenceThresholdDb = intent.getFloatExtra(EXTRA_THRESHOLD_DB, 0f)
                val silenceDurationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 8)
                runningStartedAtMs = System.currentTimeMillis()
                lastPublishedProgressPercent = -1
                lastPublishedMessage = ""
                startForegroundCompat(buildRunningNotification("Préparation de l'analyse...", displayName, 0))
                analysisJob?.cancel()
                analysisJob = serviceScope.launch {
                    runAnalysis(
                        sourcePath = sourcePath,
                        sourceUriString = sourceUriString,
                        displayName = displayName,
                        sourceKind = sourceKind,
                        cachedWorkingFilePath = cachedWorkingFilePath,
                        sourceDurationMs = sourceDurationMs,
                        sourceSampleRateHz = sourceSampleRateHz,
                        sourceChannels = sourceChannels,
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
        sourceUriString: String?,
        displayName: String,
        sourceKind: ImportedAudioKind,
        cachedWorkingFilePath: String?,
        sourceDurationMs: Long?,
        sourceSampleRateHz: Int?,
        sourceChannels: Int?,
        silenceThresholdDb: Float,
        silenceDurationSec: Int
    ) {
        acquireWakeLock()
        publishRunningState(sourcePath, displayName, "Préparation de l'analyse...", 0)
        val sourceUri = sourceUriString?.let(Uri::parse)
        val sourceFile = sourceUri?.let { null } ?: File(sourcePath).takeIf { it.exists() }
        if (sourceFile == null && sourceUri == null) {
            finishFailure(sourcePath, "Analyse impossible: fichier introuvable.")
            return
        }

        if (sourceKind == ImportedAudioKind.M4A) {
            val preflightFailure = buildImportPreflightMessage(
                context = applicationContext,
                sourceFile = sourceFile,
                sourceUri = sourceUri,
                displayName = displayName,
                kind = sourceKind,
                durationMsHint = sourceDurationMs,
                sampleRateHzHint = sourceSampleRateHz,
                channelsHint = sourceChannels
            )
            if (preflightFailure != null) {
                finishFailure(sourcePath, preflightFailure)
                return
            }

            val decoded = withContext(Dispatchers.IO) {
                decodeAudioFileToAnalysisCache(
                    context = applicationContext,
                    sourceFile = sourceFile,
                    sourceUri = sourceUri,
                    onProgress = { stepProgress ->
                        val progress = ((stepProgress.coerceIn(0, 100) * 99L) / 100L).toInt()
                        publishRunningState(
                            sourcePath = sourcePath,
                            displayName = displayName,
                            message = "Décodage et analyse... $progress%",
                            progressPercent = progress
                        )
                    }
                )
            }
            if (decoded == null) {
                finishFailure(sourcePath, "Analyse impossible: le décodage audio a échoué.")
                return
            }

            publishRunningState(sourcePath, displayName, "Décodage et analyse... 99%", 99)
            val analysis = withContext(Dispatchers.Default) {
                analyzeSignalWindows(
                    info = decoded.info,
                    windows = decoded.windows,
                    silenceThresholdDb = silenceThresholdDb,
                    silenceDurationSec = silenceDurationSec
                )
            }
            if (analysis == null) {
                finishFailure(sourcePath, "Analyse impossible: le signal audio n'a pas pu être interprété.")
                return
            }

            finishSuccess(sourcePath, null, analysis, displayName)
            return
        }

        val cachedWorkingFile = cachedWorkingFilePath?.let(::File)?.takeIf { it.exists() }
        val workingFile = if (cachedWorkingFile != null) {
            publishRunningState(sourcePath, displayName, "WAV préparé réutilisé... 35%", 35)
            cachedWorkingFile
        } else {
            withContext(Dispatchers.IO) {
                normalizeImportedAudio(
                    context = applicationContext,
                    sourceFile = sourceFile,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    kind = sourceKind,
                    onProgress = { stepProgress ->
                        publishRunningState(
                            sourcePath = sourcePath,
                            displayName = displayName,
                            message = "Préparation du fichier... ${stepProgress.coerceIn(0, 100)}%",
                            progressPercent = combineProgress(preparationPercent = stepProgress, analysisPercent = null)
                        )
                    }
                )?.file
            }
        }
        if (workingFile == null || !workingFile.exists()) {
            finishFailure(sourcePath, "Analyse impossible: le fichier audio n'a pas pu être préparé.")
            return
        }

        publishRunningState(sourcePath, displayName, "Analyse du signal... 35%", 35)
        val analysis = withContext(Dispatchers.Default) {
            analyzeWavBySilence(
                sourceFile = workingFile,
                silenceThresholdDb = silenceThresholdDb,
                silenceDurationSec = silenceDurationSec,
                onProgress = { stepProgress ->
                    val combined = combineProgress(preparationPercent = 100, analysisPercent = stepProgress)
                    publishRunningState(
                        sourcePath = sourcePath,
                        displayName = displayName,
                        message = "Analyse du signal... $combined%",
                        progressPercent = combined
                    )
                }
            )
        }
        if (analysis == null) {
            finishFailure(sourcePath, "Analyse impossible: le fichier audio n'a pas pu être préparé.")
            return
        }

        finishSuccess(sourcePath, workingFile.absolutePath, analysis, displayName)
    }

    private fun finishSuccess(
        sourcePath: String,
        workingFilePath: String?,
        analysis: WavAnalysisResult,
        displayName: String
    ) {
        AnalysisCoordinator.publish(
            AnalysisServiceState.Completed(
                sourcePath = sourcePath,
                workingFilePath = workingFilePath,
                analysis = analysis
            )
        )
        showFinishedNotification(
            if (analysis.segments.isEmpty()) "Analyse terminée: aucun segment utile."
            else "Analyse terminée: ${analysis.segments.size} segment(s) détecté(s).",
            displayName
        )
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishFailure(sourcePath: String, message: String) {
        AnalysisCoordinator.publish(AnalysisServiceState.Failed(sourcePath, message))
        showFinishedNotification(message, null)
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
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_RUNNING,
                "Band Recorder Analysis",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Maintient l'analyse audio active en arrière-plan."
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_FINISHED,
                "Band Recorder Analysis Done",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Informe quand une analyse audio est terminée."
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun buildRunningNotification(
        message: String,
        displayName: String?,
        progressPercent: Int
    ) = NotificationCompat.Builder(this, CHANNEL_ID_RUNNING)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Analyse audio en cours")
        .setContentText(message)
        .setSubText(displayName)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setProgress(100, progressPercent.coerceIn(0, 100), false)
        .setUsesChronometer(true)
        .setWhen(runningStartedAtMs)
        .setShowWhen(true)
        .setContentIntent(mainActivityPendingIntent())
        .build()

    private fun updateRunningNotification(message: String, displayName: String?, progressPercent: Int) {
        notificationManager.notify(
            NOTIFICATION_ID_RUNNING,
            buildRunningNotification(message, displayName, progressPercent)
        )
    }

    private fun publishRunningState(
        sourcePath: String,
        displayName: String,
        message: String,
        progressPercent: Int
    ) {
        val boundedProgress = progressPercent.coerceIn(0, 100)
        if (message == lastPublishedMessage && boundedProgress == lastPublishedProgressPercent) return
        lastPublishedMessage = message
        lastPublishedProgressPercent = boundedProgress
        AnalysisCoordinator.publish(
            AnalysisServiceState.Running(
                sourcePath = sourcePath,
                message = message,
                progressPercent = boundedProgress
            )
        )
        updateRunningNotification(message, displayName, boundedProgress)
    }

    private fun combineProgress(preparationPercent: Int? = null, analysisPercent: Int? = null): Int {
        val prep = preparationPercent?.coerceIn(0, 100)
        val analysis = analysisPercent?.coerceIn(0, 100)
        return when {
            prep != null && analysis == null -> ((prep * 35L) / 100L).toInt()
            analysis != null -> 35 + ((analysis * 65L) / 100L).toInt()
            else -> 0
        }.coerceIn(0, 100)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            startForeground(
                NOTIFICATION_ID_RUNNING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID_RUNNING, notification)
        }
    }

    private fun showFinishedNotification(message: String, displayName: String?) {
        notificationManager.notify(
            NOTIFICATION_ID_FINISHED,
            NotificationCompat.Builder(this, CHANNEL_ID_FINISHED)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Analyse audio terminée")
                .setContentText(message)
                .setSubText(displayName)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
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
        private const val EXTRA_SOURCE_URI = "extra_source_uri"
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_SOURCE_KIND = "extra_source_kind"
        private const val EXTRA_CACHED_WORKING_FILE_PATH = "extra_cached_working_file_path"
        private const val EXTRA_SOURCE_DURATION_MS = "extra_source_duration_ms"
        private const val EXTRA_SOURCE_SAMPLE_RATE_HZ = "extra_source_sample_rate_hz"
        private const val EXTRA_SOURCE_CHANNELS = "extra_source_channels"
        private const val EXTRA_THRESHOLD_DB = "extra_threshold_db"
        private const val EXTRA_DURATION_SEC = "extra_duration_sec"

        internal fun start(
            context: Context,
            sourcePath: String,
            sourceUriString: String?,
            displayName: String,
            sourceKind: ImportedAudioKind,
            cachedWorkingFilePath: String?,
            sourceDurationMs: Long?,
            sourceSampleRateHz: Int?,
            sourceChannels: Int?,
            silenceThresholdDb: Float,
            silenceDurationSec: Int
        ) {
            val intent = Intent(context, AnalysisForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
                putExtra(EXTRA_SOURCE_URI, sourceUriString)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_SOURCE_KIND, sourceKind.name)
                putExtra(EXTRA_CACHED_WORKING_FILE_PATH, cachedWorkingFilePath)
                putExtra(EXTRA_SOURCE_DURATION_MS, sourceDurationMs)
                putExtra(EXTRA_SOURCE_SAMPLE_RATE_HZ, sourceSampleRateHz)
                putExtra(EXTRA_SOURCE_CHANNELS, sourceChannels)
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
