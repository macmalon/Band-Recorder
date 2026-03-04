package com.bandrecorder.app

import android.app.Application
import android.content.ContentValues
import android.media.AudioDeviceInfo
import android.media.AudioManager
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

data class MicrophoneOption(
    val id: Int,
    val label: String
)

data class RecorderUiState(
    val isRecording: Boolean = false,
    val isCalibrating: Boolean = false,
    val isTestingMic: Boolean = false,
    val calibrationProgress: Int = 0,
    val rmsDb: Float = -90f,
    val peakDb: Float = -90f,
    val headroomDb: Float = 90f,
    val elapsedMs: Long = 0L,
    val status: String = "Ready",
    val calibrationRmsDb: Float? = null,
    val calibrationPeakDb: Float? = null,
    val recommendedGainDb: Float? = null,
    val lastOutputPath: String? = null,
    val storageLocation: StorageLocation = StorageLocation.DOWNLOADS,
    val microphones: List<MicrophoneOption> = emptyList(),
    val selectedMicId: Int? = null,
    val micTestResult: String? = null
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = WavRecorderEngine()
    private val settingsStore = AppSettingsStore(app)

    private var pendingTempFile: File? = null
    private var pendingDisplayName: String? = null

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    init {
        val settings = settingsStore.load()
        _uiState.update {
            it.copy(
                storageLocation = settings.storageLocation,
                selectedMicId = settings.selectedMicId
            )
        }
        refreshMicrophones()

        viewModelScope.launch {
            engine.statusFlow().collect { status ->
                _uiState.update {
                    it.copy(
                        isRecording = status.isRecording,
                        rmsDb = status.rmsDb,
                        peakDb = status.peakDb,
                        headroomDb = status.headroomDb,
                        elapsedMs = status.elapsedMs,
                        status = if (status.isRecording) "Recording" else it.status
                    )
                }
            }
        }
    }

    fun setStorageLocation(location: StorageLocation) {
        settingsStore.setStorageLocation(location)
        _uiState.update { it.copy(storageLocation = location) }
    }

    fun refreshMicrophones() {
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        val devices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }

        val options = devices.map { d ->
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Mic"
            val type = deviceTypeLabel(d.type)
            MicrophoneOption(d.id, "$name ($type)")
        }.sortedBy { it.label }

        val selected = _uiState.value.selectedMicId
        val selectedStillExists = selected != null && options.any { it.id == selected }
        val newSelected = if (selectedStillExists) selected else null
        if (selected != newSelected) {
            settingsStore.setSelectedMicId(newSelected)
        }

        _uiState.update {
            it.copy(
                microphones = options,
                selectedMicId = newSelected,
                status = if (options.isEmpty()) "No microphone input detected" else it.status
            )
        }
    }

    fun selectMicrophone(micId: Int?) {
        settingsStore.setSelectedMicId(micId)
        _uiState.update { it.copy(selectedMicId = micId) }
    }

    fun testSelectedMicrophone() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingMic = true,
                    status = "Mic test (5s)",
                    micTestResult = null
                )
            }

            val result: CalibrationResult? = engine.runCalibration(
                durationSeconds = 5,
                preferredDevice = resolveSelectedMic(),
                onProgress = { progress: CalibrationProgress ->
                    _uiState.update { state ->
                        state.copy(
                            rmsDb = progress.rmsDb,
                            peakDb = progress.peakDb,
                            headroomDb = -progress.peakDb
                        )
                    }
                }
            )

            _uiState.update {
                if (result == null) {
                    it.copy(
                        isTestingMic = false,
                        status = "Mic test failed",
                        micTestResult = "Echec du test micro"
                    )
                } else {
                    it.copy(
                        isTestingMic = false,
                        status = "Mic test OK",
                        micTestResult = "RMS ${"%.1f".format(result.rmsDb)} dBFS / Peak ${"%.1f".format(result.peakDb)} dBFS"
                    )
                }
            }
        }
    }

    fun runCalibration() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCalibrating = true,
                    calibrationProgress = 0,
                    status = "Calibration (30s)"
                )
            }

            val result: CalibrationResult? = engine.runCalibration(
                durationSeconds = 30,
                preferredDevice = resolveSelectedMic(),
                onProgress = { progress: CalibrationProgress ->
                    _uiState.update { state ->
                        state.copy(
                            calibrationProgress = progress.progressPercent,
                            rmsDb = progress.rmsDb,
                            peakDb = progress.peakDb,
                            headroomDb = -progress.peakDb
                        )
                    }
                }
            )

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
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic) return

        val selectedMic = resolveSelectedMic()
        when (_uiState.value.storageLocation) {
            StorageLocation.DOWNLOADS -> {
                val (displayName, output) = buildTempOutputFile()
                pendingTempFile = output
                pendingDisplayName = displayName
                engine.startRecording(output, preferredDevice = selectedMic)
                _uiState.update {
                    it.copy(
                        status = "Recording",
                        elapsedMs = 0L,
                        lastOutputPath = "Downloads/Band Recorder/$displayName"
                    )
                }
            }

            StorageLocation.APP_PRIVATE -> {
                val output = buildAppPrivateOutputFile()
                engine.startRecording(output, preferredDevice = selectedMic)
                _uiState.update {
                    it.copy(
                        status = "Recording",
                        elapsedMs = 0L,
                        lastOutputPath = output.absolutePath
                    )
                }
            }
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
            if (pendingTempFile != null) {
                exportPendingRecordingToDownloads()
            } else {
                _uiState.update { it.copy(status = "Stopped") }
            }
        }
    }

    private fun resolveSelectedMic(): AudioDeviceInfo? {
        val selectedId = _uiState.value.selectedMicId ?: return null
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == selectedId }
    }

    private fun buildTempOutputFile(): Pair<String, File> {
        val root = File(getApplication<Application>().cacheDir, "band_recordings_tmp")
        root.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "session_$timestamp.wav"
        return displayName to File(root, displayName)
    }

    private fun buildAppPrivateOutputFile(): File {
        val root = getApplication<Application>()
            .getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: getApplication<Application>().filesDir
        val folder = File(root, "band_recordings")
        folder.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(folder, "session_$timestamp.wav")
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

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        else -> "Type $type"
    }

    override fun onCleared() {
        engine.stopRecording()
        super.onCleared()
    }
}
