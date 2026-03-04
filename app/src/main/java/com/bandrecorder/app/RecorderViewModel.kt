package com.bandrecorder.app

import android.app.Application
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandrecorder.core.audio.CalibrationProgress
import com.bandrecorder.core.audio.CalibrationResult
import com.bandrecorder.core.audio.WavRecorderEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderUiState(
    val isRecording: Boolean = false,
    val isCalibrating: Boolean = false,
    val calibrationProgress: Int = 0,
    val rmsDb: Float = -90f,
    val peakDb: Float = -90f,
    val headroomDb: Float = 90f,
    val elapsedMs: Long = 0L,
    val status: String = "Ready",
    val calibrationRmsDb: Float? = null,
    val calibrationPeakDb: Float? = null,
    val recommendedGainDb: Float? = null,
    val lastOutputPath: String? = null
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = WavRecorderEngine()
    private var pendingTempFile: File? = null
    private var pendingDisplayName: String? = null

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engine.statusFlow().collect { status ->
                _uiState.update {
                    it.copy(
                        isRecording = status.isRecording,
                        rmsDb = status.rmsDb,
                        peakDb = status.peakDb,
                        headroomDb = status.headroomDb,
                        elapsedMs = status.elapsedMs,
                        lastOutputPath = it.lastOutputPath,
                        status = if (status.isRecording) "Recording" else it.status
                    )
                }
            }
        }
    }

    fun runCalibration() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCalibrating = true,
                    calibrationProgress = 0,
                    status = "Calibration (30s)"
                )
            }

            val result: CalibrationResult? = engine.runCalibration(durationSeconds = 30) { progress: CalibrationProgress ->
                _uiState.update { state ->
                    state.copy(
                        calibrationProgress = progress.progressPercent,
                        rmsDb = progress.rmsDb,
                        peakDb = progress.peakDb,
                        headroomDb = -progress.peakDb
                    )
                }
            }

            _uiState.update {
                if (result == null) {
                    it.copy(
                        isCalibrating = false,
                        status = "Calibration failed"
                    )
                } else {
                    it.copy(
                        isCalibrating = false,
                        calibrationProgress = 100,
                        calibrationRmsDb = result.rmsDb,
                        calibrationPeakDb = result.peakDb,
                        recommendedGainDb = result.recommendedGainDb,
                        status = "Calibrated"
                    )
                }
            }
        }
    }

    fun startRecording() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating) return

        val (displayName, output) = buildTempOutputFile()
        pendingTempFile = output
        pendingDisplayName = displayName
        engine.startRecording(output)
        _uiState.update {
            it.copy(
                status = "Recording",
                elapsedMs = 0L,
                lastOutputPath = "Downloads/Band Recorder/$displayName"
            )
        }
    }

    fun stopRecording() {
        engine.stopRecording()
        _uiState.update { it.copy(status = "Stopping...") }

        viewModelScope.launch {
            while (engine.statusFlow().value.isRecording) {
                delay(50)
            }
            delay(150)
            exportPendingRecordingToDownloads()
        }
    }

    private fun buildTempOutputFile(): Pair<String, File> {
        val root = File(getApplication<Application>().cacheDir, "band_recordings_tmp")
        root.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "session_$timestamp.wav"
        return displayName to File(root, displayName)
    }

    private fun exportPendingRecordingToDownloads() {
        val source = pendingTempFile ?: run {
            _uiState.update { it.copy(status = "Stopped") }
            return
        }
        val displayName = pendingDisplayName ?: source.name

        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Band Recorder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
        if (uri == null) {
            _uiState.update { it.copy(status = "Export failed") }
            return
        }

        val exported = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Cannot open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }

        if (exported) {
            _uiState.update {
                it.copy(
                    status = "Stopped",
                    lastOutputPath = "Downloads/Band Recorder/$displayName"
                )
            }
            runCatching { source.delete() }
        } else {
            _uiState.update { it.copy(status = "Export failed") }
        }

        pendingTempFile = null
        pendingDisplayName = null
    }

    override fun onCleared() {
        engine.stopRecording()
        super.onCleared()
    }
}
