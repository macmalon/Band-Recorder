package com.bandrecorder.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bandrecorder.core.audio.WavRecorderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = WavRecorderEngine()
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var collectorJob: Job? = null
    private var sessionBaseName: String? = null
    private var pendingTempFile: File? = null
    private var pendingDisplayName: String? = null
    private var uiOutputPath: String? = null
    private var splitOnSilenceEnabled: Boolean = false
    private var ignoreSilenceEnabled: Boolean = false
    private var silenceDurationSec: Int = 8
    private var didFinalizeStop = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        observeRecordingStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (engine.statusFlow().value.isRecording) {
                    didFinalizeStop = false
                    RecordingCoordinator.publish(
                        RecordingServiceState.Stopping(
                            sessionBaseName = sessionBaseName,
                            uiOutputPath = uiOutputPath,
                            message = "Stopping..."
                        )
                    )
                    engine.stopRecording()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_START -> {
                startRecordingFromIntent(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectorJob?.cancel()
        runCatching { engine.stopRecording() }
        releaseRecordingWakeLock()
        scope.cancel()
        super.onDestroy()
    }

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

    @SuppressLint("MissingPermission")
    private fun startRecordingFromIntent(intent: Intent) {
        if (engine.statusFlow().value.isRecording) return
        val storageLocation = intent.getStringExtra(EXTRA_STORAGE_LOCATION)
            ?.let { runCatching { StorageLocation.valueOf(it) }.getOrNull() }
            ?: StorageLocation.DOWNLOADS
        val preferredMicId = if (intent.hasExtra(EXTRA_PREFERRED_MIC_ID)) intent.getIntExtra(EXTRA_PREFERRED_MIC_ID, -1) else -1
        val requestedChannels = intent.getIntExtra(EXTRA_REQUESTED_CHANNELS, 1)
        val swapStereoChannels = intent.getBooleanExtra(EXTRA_SWAP_STEREO_CHANNELS, false)
        val inputGainDb = intent.getFloatExtra(EXTRA_INPUT_GAIN_DB, 0f)
        splitOnSilenceEnabled = intent.getBooleanExtra(EXTRA_SPLIT_ON_SILENCE_ENABLED, false)
        ignoreSilenceEnabled = intent.getBooleanExtra(EXTRA_IGNORE_SILENCE_ENABLED, false)
        silenceDurationSec = intent.getIntExtra(EXTRA_SILENCE_DURATION_SEC, 8)
        val target = when (storageLocation) {
            StorageLocation.DOWNLOADS -> buildTempOutputFile()
            StorageLocation.APP_PRIVATE -> buildAppPrivateOutputFile()
        }
        pendingTempFile = if (storageLocation == StorageLocation.DOWNLOADS) target.file else null
        pendingDisplayName = if (storageLocation == StorageLocation.DOWNLOADS) target.displayName else null
        sessionBaseName = target.sessionBaseName
        uiOutputPath = if (storageLocation == StorageLocation.DOWNLOADS) {
            "Downloads/Band Recorder/${target.displayName}"
        } else {
            target.file.absolutePath
        }
        didFinalizeStop = false
        acquireRecordingWakeLock()
        startForegroundNotification("Enregistrement en cours")
        val preferredDevice = resolvePreferredMic(preferredMicId)
        engine.startRecording(
            output = target.file,
            preferredDevice = preferredDevice,
            requestedChannelCount = requestedChannels,
            swapStereoChannels = swapStereoChannels,
            inputGainDb = inputGainDb
        )
        if (!engine.statusFlow().value.isRecording) {
            releaseRecordingWakeLock()
            RecordingCoordinator.publish(RecordingServiceState.Failed("Impossible de démarrer l'enregistrement"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        private const val EXTRA_STORAGE_LOCATION = "extra_storage_location"
        private const val EXTRA_PREFERRED_MIC_ID = "extra_preferred_mic_id"
        private const val EXTRA_REQUESTED_CHANNELS = "extra_requested_channels"
        private const val EXTRA_SWAP_STEREO_CHANNELS = "extra_swap_stereo_channels"
        private const val EXTRA_INPUT_GAIN_DB = "extra_input_gain_db"
        private const val EXTRA_IGNORE_SILENCE_ENABLED = "extra_ignore_silence_enabled"
        private const val EXTRA_SPLIT_ON_SILENCE_ENABLED = "extra_split_on_silence_enabled"
        private const val EXTRA_SILENCE_DURATION_SEC = "extra_silence_duration_sec"

        internal fun start(
            context: Context,
            storageLocation: StorageLocation,
            preferredMicId: Int?,
            requestedChannels: Int,
            swapStereoChannels: Boolean,
            inputGainDb: Float,
            ignoreSilenceEnabled: Boolean,
            splitOnSilenceEnabled: Boolean,
            silenceDurationSec: Int
        ) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STORAGE_LOCATION, storageLocation.name)
                if (preferredMicId != null) {
                    putExtra(EXTRA_PREFERRED_MIC_ID, preferredMicId)
                }
                putExtra(EXTRA_REQUESTED_CHANNELS, requestedChannels)
                putExtra(EXTRA_SWAP_STEREO_CHANNELS, swapStereoChannels)
                putExtra(EXTRA_INPUT_GAIN_DB, inputGainDb)
                putExtra(EXTRA_IGNORE_SILENCE_ENABLED, ignoreSilenceEnabled)
                putExtra(EXTRA_SPLIT_ON_SILENCE_ENABLED, splitOnSilenceEnabled)
                putExtra(EXTRA_SILENCE_DURATION_SEC, silenceDurationSec)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun startForegroundNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Band Recorder")
            .setContentText(message)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfoCompat.microphoneType()
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeRecordingStatus() {
        collectorJob = scope.launch {
            var wasRecording = false
            engine.statusFlow().collectLatest { status ->
                if (status.isRecording) {
                    wasRecording = true
                    val currentSession = sessionBaseName ?: RecordingFileNaming.sessionBaseName()
                    val currentOutput = uiOutputPath ?: status.outputPath ?: ""
                    RecordingCoordinator.publish(
                        RecordingServiceState.Running(
                            status = status,
                            sessionBaseName = currentSession,
                            uiOutputPath = currentOutput
                        )
                    )
                } else if (wasRecording && !didFinalizeStop) {
                    didFinalizeStop = true
                    finalizeStop()
                }
            }
        }
    }

    private suspend fun finalizeStop() {
        RecordingCoordinator.publish(
            RecordingServiceState.Stopping(
                sessionBaseName = sessionBaseName,
                uiOutputPath = uiOutputPath,
                message = "Stopping..."
            )
        )
        delay(150)
        val segmentFiles = if (ignoreSilenceEnabled && splitOnSilenceEnabled) {
            withContext(Dispatchers.Default) { createSegmentsIfNeededForLastRecording() }
        } else {
            emptyList()
        }
        val completion = if (pendingTempFile != null) {
            exportPendingRecordingToDownloads(segmentFiles)
        } else {
            RecordingCompletion(
                message = if (segmentFiles.isNotEmpty()) "Stopped • ${segmentFiles.size} morceaux" else "Stopped",
                lastOutputPath = uiOutputPath,
                segmentCount = segmentFiles.size
            )
        }
        releaseRecordingWakeLock()
        RecordingCoordinator.publish(
            RecordingServiceState.Completed(
                sessionBaseName = sessionBaseName,
                lastOutputPath = completion.lastOutputPath,
                message = completion.message,
                segmentCount = completion.segmentCount
            )
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandRecorder:RecordingWakeLock")
        lock.setReferenceCounted(false)
        runCatching {
            lock.acquire(4L * 60L * 60L * 1000L)
            recordingWakeLock = lock
        }.onFailure {
            runCatching { lock.release() }
            recordingWakeLock = null
        }
    }

    private fun releaseRecordingWakeLock() {
        val lock = recordingWakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
        recordingWakeLock = null
    }

    private fun resolvePreferredMic(preferredMicId: Int): AudioDeviceInfo? {
        if (preferredMicId < 0) return null
        val audioManager = getSystemService(AudioManager::class.java)
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.id == preferredMicId }
    }

    private data class RecordingOutputTarget(
        val displayName: String,
        val sessionBaseName: String,
        val file: File
    )

    private data class RecordingCompletion(
        val message: String,
        val lastOutputPath: String?,
        val segmentCount: Int
    )

    private fun buildTempOutputFile(): RecordingOutputTarget {
        val root = File(cacheDir, "band_recordings_tmp")
        root.mkdirs()
        val baseName = RecordingFileNaming.sessionBaseName()
        val displayName = RecordingFileNaming.rawFileName(baseName)
        return RecordingOutputTarget(displayName, baseName, File(root, displayName))
    }

    private fun buildAppPrivateOutputFile(): RecordingOutputTarget {
        val root = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        val folder = File(root, "band_recordings")
        folder.mkdirs()
        val baseName = RecordingFileNaming.sessionBaseName()
        val displayName = RecordingFileNaming.rawFileName(baseName)
        return RecordingOutputTarget(displayName, baseName, File(folder, displayName))
    }

    private fun exportPendingRecordingToDownloads(segmentFiles: List<File>): RecordingCompletion {
        val source = pendingTempFile ?: return RecordingCompletion("Stopped", uiOutputPath, 0)
        val displayName = pendingDisplayName ?: source.name
        val exportedRaw = exportSingleWavToDownloads(source, displayName)
        val exportedSegments = segmentFiles.count { exportSingleWavToDownloads(it, it.name) }
        return if (exportedRaw) {
            runCatching { source.delete() }
            segmentFiles.forEach { runCatching { it.delete() } }
            pendingTempFile = null
            pendingDisplayName = null
            RecordingCompletion(
                message = if (segmentFiles.isNotEmpty()) "Stopped • $exportedSegments morceaux" else "Stopped",
                lastOutputPath = "Downloads/Band Recorder/$displayName",
                segmentCount = exportedSegments
            )
        } else {
            RecordingCompletion("Export failed", uiOutputPath, exportedSegments)
        }
    }

    private fun exportSingleWavToDownloads(
        source: File,
        displayName: String,
        relativePath: String = "${Environment.DIRECTORY_DOWNLOADS}/Band Recorder"
    ): Boolean {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: error("Cannot open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun createSegmentsIfNeededForLastRecording(): List<File> {
        val sourceFile = pendingTempFile ?: return emptyList()
        val currentSession = sessionBaseName ?: return emptyList()
        val outDir = sourceFile.parentFile ?: return emptyList()
        return splitWavOnSilence(sourceFile, currentSession, outDir, silenceDurationSec)
    }

    private fun splitWavOnSilence(
        sourceFile: File,
        sessionBase: String,
        outputDir: File,
        silenceDurationSec: Int
    ): List<File> {
        val analysis = analyzeWavBySilence(sourceFile, 0f, silenceDurationSec) ?: return emptyList()
        if (analysis.segments.isEmpty()) return emptyList()
        outputDir.mkdirs()
        val created = mutableListOf<File>()
        analysis.segments.forEachIndexed { idx, segment ->
            val out = File(outputDir, RecordingFileNaming.morceauFileName(sessionBase, idx + 1))
            runCatching {
                writeWavSegment(sourceFile, analysis.info, segment, out)
                created += out
            }
        }
        return created
    }
}
