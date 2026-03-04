package com.bandrecorder.app

import android.app.Application
import android.content.ContentValues
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MicrophoneInfo
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MicrophoneOption(
    val id: Int,
    val displayName: String,
    val typeCode: Int,
    val typeLabel: String,
    val recommended: Boolean,
    val warning: String?,
    val description: String?,
    val locationLabel: String?,
    val directionalityLabel: String?,
    val position: String?,
    val orientation: String?,
    val rawAddress: String?,
    val rawChannelCounts: String?,
    val rawSampleRates: String?,
    val rawEncodings: String?
)

data class MicTestHistoryEntry(
    val timestampIso: String,
    val micLabel: String,
    val durationSec: Int,
    val rmsDb: Float,
    val peakDb: Float,
    val recommended: Boolean,
    val warning: String?
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
    val effectiveMicLabel: String? = null,
    val effectiveMicWarning: String? = null,
    val effectiveMicRecommended: Boolean = true,
    val isAutoMicMode: Boolean = true,
    val micTestResult: String? = null,
    val diagnosticModeEnabled: Boolean = true,
    val showAdvancedInternals: Boolean = false,
    val selectedTestDurationSec: Int = 5,
    val availableTestDurationsSec: List<Int> = listOf(5, 15, 30),
    val testHistory: List<MicTestHistoryEntry> = emptyList(),
    val lastDiagnosticReportPath: String? = null
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = WavRecorderEngine()
    private val settingsStore = AppSettingsStore(app)
    private val historyFile = File(app.filesDir, "mic_test_history.tsv")

    private var pendingTempFile: File? = null
    private var pendingDisplayName: String? = null

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    init {
        val settings = settingsStore.load()
        _uiState.update {
            it.copy(
                storageLocation = settings.storageLocation,
                selectedMicId = settings.selectedMicId,
                diagnosticModeEnabled = settings.diagnosticMode,
                showAdvancedInternals = settings.showAdvancedInternals,
                selectedTestDurationSec = normalizeDuration(settings.testDurationSec),
                testHistory = loadHistory()
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

    fun setDiagnosticMode(enabled: Boolean) {
        settingsStore.setDiagnosticMode(enabled)
        _uiState.update { it.copy(diagnosticModeEnabled = enabled) }
    }

    fun setShowAdvancedInternals(enabled: Boolean) {
        settingsStore.setShowAdvancedInternals(enabled)
        _uiState.update { it.copy(showAdvancedInternals = enabled) }
    }

    fun setTestDuration(seconds: Int) {
        val normalized = normalizeDuration(seconds)
        settingsStore.setTestDurationSec(normalized)
        _uiState.update { it.copy(selectedTestDurationSec = normalized) }
    }

    fun refreshMicrophones() {
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        val devices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }

        val microInfoById = loadMicrophoneInfoById(audioManager)

        val raw = devices.map { device ->
            val info = microInfoById[device.id]
            val baseName = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Microphone"
            MicrophoneOption(
                id = device.id,
                displayName = baseName,
                typeCode = device.type,
                typeLabel = MicrophonePolicy.typeLabel(device.type),
                recommended = MicrophonePolicy.isRecommended(device.type),
                warning = MicrophonePolicy.warningForType(device.type),
                description = info?.description?.takeIf { it.isNotBlank() },
                locationLabel = info?.let { microphoneLocationLabel(it.location) },
                directionalityLabel = info?.let { microphoneDirectionalityLabel(it.directionality) },
                position = info?.position?.let { formatCoordinate(it.x, it.y, it.z) },
                orientation = info?.orientation?.let { formatCoordinate(it.x, it.y, it.z) },
                rawAddress = device.address?.takeIf { it.isNotBlank() },
                rawChannelCounts = device.channelCounts?.takeIf { it.isNotEmpty() }?.joinToString(","),
                rawSampleRates = device.sampleRates?.takeIf { it.isNotEmpty() }?.joinToString(","),
                rawEncodings = device.encodings?.takeIf { it.isNotEmpty() }?.joinToString(",")
            )
        }

        val deduped = dedupeDisplayNames(raw).sortedBy { it.displayName.lowercase(Locale.ROOT) }

        val previousSelected = _uiState.value.selectedMicId
        val selectedStillExists = previousSelected != null && deduped.any { it.id == previousSelected }
        val selectionMissing = previousSelected != null && !selectedStillExists
        val newSelected = if (selectedStillExists) previousSelected else null

        if (previousSelected != newSelected) {
            settingsStore.setSelectedMicId(newSelected)
        }

        val effective = effectiveMic(deduped, newSelected)
        _uiState.update {
            it.copy(
                microphones = deduped,
                selectedMicId = newSelected,
                isAutoMicMode = newSelected == null,
                effectiveMicLabel = effective?.displayName,
                effectiveMicWarning = effective?.warning,
                effectiveMicRecommended = effective?.recommended ?: true,
                status = when {
                    deduped.isEmpty() -> "No microphone input detected"
                    selectionMissing -> "Selected mic unavailable, switched to Auto"
                    else -> it.status
                }
            )
        }
    }

    fun selectMicrophone(micId: Int?) {
        settingsStore.setSelectedMicId(micId)
        val effective = effectiveMic(_uiState.value.microphones, micId)
        _uiState.update {
            it.copy(
                selectedMicId = micId,
                isAutoMicMode = micId == null,
                effectiveMicLabel = effective?.displayName,
                effectiveMicWarning = effective?.warning,
                effectiveMicRecommended = effective?.recommended ?: true,
                status = if (micId == null) "Microphone: Auto" else "Microphone selected"
            )
        }
    }

    fun testSelectedMicrophone() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic) return

        val durationSec = _uiState.value.selectedTestDurationSec

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingMic = true,
                    status = "Mic test (${durationSec}s)",
                    micTestResult = null
                )
            }

            val preferredDevice = resolveEffectiveMicDevice()
            val effective = effectiveMic(_uiState.value.microphones, _uiState.value.selectedMicId)
            val result: CalibrationResult? = engine.runCalibration(
                durationSeconds = durationSec,
                preferredDevice = preferredDevice,
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
                        micTestResult = "Mic test failed"
                    )
                } else {
                    it.copy(
                        isTestingMic = false,
                        status = "Mic test OK",
                        micTestResult = "RMS ${"%.1f".format(result.rmsDb)} dBFS / Peak ${"%.1f".format(result.peakDb)} dBFS"
                    )
                }
            }

            if (result != null && effective != null) {
                val entry = MicTestHistoryEntry(
                    timestampIso = isoNow(),
                    micLabel = effective.displayName,
                    durationSec = durationSec,
                    rmsDb = result.rmsDb,
                    peakDb = result.peakDb,
                    recommended = effective.recommended,
                    warning = effective.warning
                )
                appendHistory(entry)

                if (_uiState.value.diagnosticModeEnabled) {
                    exportDiagnosticReport(
                        event = "mic_test",
                        entry = entry,
                        recordingPath = _uiState.value.lastOutputPath
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
                preferredDevice = resolveEffectiveMicDevice(),
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

        val selectedMic = resolveEffectiveMicDevice()
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
            if (_uiState.value.diagnosticModeEnabled) {
                exportDiagnosticReport(event = "recording_stop", entry = null, recordingPath = _uiState.value.lastOutputPath)
            }
        }
    }

    private fun resolveEffectiveMicDevice(): AudioDeviceInfo? {
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        val options = _uiState.value.microphones
        val effectiveOption = effectiveMic(options, _uiState.value.selectedMicId) ?: return null
        return devices.firstOrNull { it.id == effectiveOption.id }
    }

    private fun effectiveMic(options: List<MicrophoneOption>, selectedId: Int?): MicrophoneOption? {
        if (selectedId != null) {
            return options.firstOrNull { it.id == selectedId }
        }
        return MicrophonePolicy.chooseAutoMicrophone(options)
    }

    private fun dedupeDisplayNames(options: List<MicrophoneOption>): List<MicrophoneOption> {
        val counts = options.groupingBy { it.displayName }.eachCount()
        return options.map {
            if ((counts[it.displayName] ?: 0) > 1) {
                it.copy(displayName = "${it.displayName} #${it.id}")
            } else {
                it
            }
        }
    }

    private fun loadMicrophoneInfoById(audioManager: AudioManager): Map<Int, MicrophoneInfo> {
        val infos = runCatching { audioManager.microphones }
            .recoverCatching {
                if (it is IOException) emptyList() else throw it
            }
            .getOrDefault(emptyList())
        return infos.associateBy { it.id }
    }

    private fun microphoneLocationLabel(location: Int): String = when (location) {
        MicrophoneInfo.LOCATION_MAINBODY -> "Main body"
        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "Main body movable"
        MicrophoneInfo.LOCATION_PERIPHERAL -> "Peripheral"
        else -> "Unknown"
    }

    private fun microphoneDirectionalityLabel(directionality: Int): String = when (directionality) {
        MicrophoneInfo.DIRECTIONALITY_OMNI -> "Omni"
        MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "Bi-directional"
        MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "Cardioid"
        MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "Hyper-cardioid"
        MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "Super-cardioid"
        else -> "Unknown"
    }

    private fun formatCoordinate(x: Float, y: Float, z: Float): String {
        return "x=${"%.2f".format(Locale.US, x)}, y=${"%.2f".format(Locale.US, y)}, z=${"%.2f".format(Locale.US, z)}"
    }

    private fun normalizeDuration(seconds: Int): Int = when (seconds) {
        5, 15, 30 -> seconds
        else -> 5
    }

    private fun loadHistory(): List<MicTestHistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return historyFile.readLines()
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 7) return@mapNotNull null
                runCatching {
                    MicTestHistoryEntry(
                        timestampIso = parts[0],
                        micLabel = parts[1],
                        durationSec = parts[2].toInt(),
                        rmsDb = parts[3].toFloat(),
                        peakDb = parts[4].toFloat(),
                        recommended = parts[5].toBooleanStrictOrNull() ?: false,
                        warning = parts[6].ifBlank { null }
                    )
                }.getOrNull()
            }
            .takeLast(50)
            .reversed()
    }

    private fun appendHistory(entry: MicTestHistoryEntry) {
        historyFile.parentFile?.mkdirs()
        historyFile.appendText(
            listOf(
                entry.timestampIso,
                entry.micLabel.replace("\t", " "),
                entry.durationSec.toString(),
                entry.rmsDb.toString(),
                entry.peakDb.toString(),
                entry.recommended.toString(),
                entry.warning.orEmpty().replace("\t", " ")
            ).joinToString("\t") + "\n"
        )
        _uiState.update {
            it.copy(testHistory = (listOf(entry) + it.testHistory).take(50))
        }
    }

    private fun exportDiagnosticReport(event: String, entry: MicTestHistoryEntry?, recordingPath: String?) {
        val resolver = getApplication<Application>().contentResolver
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "diag_${event}_$now.txt"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Band Recorder/diagnostics")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        val effective = effectiveMic(_uiState.value.microphones, _uiState.value.selectedMicId)

        val content = buildString {
            appendLine("band_recorder_diagnostic_report")
            appendLine("timestamp=${isoNow()}")
            appendLine("event=$event")
            appendLine("status=${_uiState.value.status}")
            appendLine("storage=${_uiState.value.storageLocation}")
            appendLine("selected_mic_id=${_uiState.value.selectedMicId ?: "auto"}")
            appendLine("effective_mic=${effective?.displayName ?: "none"}")
            appendLine("effective_mic_type=${effective?.typeLabel ?: "n/a"}")
            appendLine("effective_mic_recommended=${effective?.recommended ?: false}")
            appendLine("effective_mic_warning=${effective?.warning ?: ""}")
            appendLine("rms_db=${_uiState.value.rmsDb}")
            appendLine("peak_db=${_uiState.value.peakDb}")
            appendLine("headroom_db=${_uiState.value.headroomDb}")
            appendLine("elapsed_ms=${_uiState.value.elapsedMs}")
            appendLine("recording_path=${recordingPath ?: ""}")
            if (entry != null) {
                appendLine("test_duration_sec=${entry.durationSec}")
                appendLine("test_result_rms_db=${entry.rmsDb}")
                appendLine("test_result_peak_db=${entry.peakDb}")
            }
            appendLine("available_mics=${_uiState.value.microphones.size}")
            _uiState.value.microphones.forEachIndexed { index, mic ->
                appendLine("mic[$index].id=${mic.id}")
                appendLine("mic[$index].name=${mic.displayName}")
                appendLine("mic[$index].type=${mic.typeLabel}(${mic.typeCode})")
                appendLine("mic[$index].recommended=${mic.recommended}")
                appendLine("mic[$index].warning=${mic.warning ?: ""}")
                appendLine("mic[$index].description=${mic.description ?: ""}")
                appendLine("mic[$index].location=${mic.locationLabel ?: ""}")
                appendLine("mic[$index].directionality=${mic.directionalityLabel ?: ""}")
                appendLine("mic[$index].position=${mic.position ?: ""}")
                appendLine("mic[$index].orientation=${mic.orientation ?: ""}")
                appendLine("mic[$index].raw.address=${mic.rawAddress ?: ""}")
                appendLine("mic[$index].raw.channel_counts=${mic.rawChannelCounts ?: ""}")
                appendLine("mic[$index].raw.sample_rates=${mic.rawSampleRates ?: ""}")
                appendLine("mic[$index].raw.encodings=${mic.rawEncodings ?: ""}")
            }
        }

        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> out.write(content.toByteArray()) }
                ?: error("Cannot open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }

        if (ok) {
            _uiState.update {
                it.copy(lastDiagnosticReportPath = "Downloads/Band Recorder/diagnostics/$displayName")
            }
        }
    }

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

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

    override fun onCleared() {
        engine.stopRecording()
        super.onCleared()
    }
}
