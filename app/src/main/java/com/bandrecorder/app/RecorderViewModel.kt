package com.bandrecorder.app

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MicrophoneInfo
import android.provider.OpenableColumns
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandrecorder.core.audio.CalibrationProgress
import com.bandrecorder.core.audio.CalibrationResult
import com.bandrecorder.core.audio.DspOutputMode
import com.bandrecorder.core.audio.GlobalBalanceConfig
import com.bandrecorder.core.audio.InputDiagnostics
import com.bandrecorder.core.audio.MixProfile
import com.bandrecorder.core.audio.SignalFeatures
import com.bandrecorder.core.audio.StereoProbeResult
import com.bandrecorder.core.audio.StereoWindowMeasurement
import com.bandrecorder.core.audio.WavRecorderEngine
import com.bandrecorder.core.audio.computeAdaptiveThresholds
import com.bandrecorder.core.audio.evaluateSilence
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

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

data class RecordingListItem(
    val key: String,
    val title: String,
    val dateLabel: String,
    val durationMs: Long,
    val sourceUri: Uri?,
    val filePath: String?,
    val isFavorite: Boolean,
    val sessionGroupKey: String? = null,
    val segmentIndex: Int? = null
)

enum class PostProcessMode {
    CLEAN_SINGLE_FILE,
    SPLIT_MULTIPLE_TRACKS
}

data class PostProcessSegmentPreview(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long
)

data class PostProcessCutPreview(
    val index: Int,
    val cutMs: Long,
    val beforeStartMs: Long,
    val beforeEndMs: Long,
    val afterStartMs: Long,
    val afterEndMs: Long
)

data class PostProcessEnvelopePoint(
    val startMs: Long,
    val endMs: Long,
    val rmsDb: Float,
    val peakNorm: Float,
    val speechLikelihood: Float
)

enum class GuidedStereoStep {
    IDLE,
    PREP_LEFT,
    CAPTURE_LEFT,
    PREP_RIGHT,
    CAPTURE_RIGHT,
    PREP_CENTER,
    CAPTURE_CENTER,
    DONE
}

data class RecorderUiState(
    val isRecording: Boolean = false,
    val isCalibrating: Boolean = false,
    val isTestingMic: Boolean = false,
    val isRunningABTest: Boolean = false,
    val isRunningStereoGuidedTest: Boolean = false,
    val calibrationProgress: Int = 0,
    val isRunningLevelBalance: Boolean = false,
    val balanceProgress: Int = 0,
    val balanceDurationSec: Int = 30,
    val availableBalanceDurationsSec: List<Int> = listOf(30, 60, 90),
    val balancePeakMaxDb: Float = -90f,
    val balanceDecisionLabel: String? = null,
    val balanceRecommendationText: String? = null,
    val rmsDb: Float = -90f,
    val peakDb: Float = -90f,
    val leftVu: Float = 0f,
    val rightVu: Float = 0f,
    val headroomDb: Float = 90f,
    val elapsedMs: Long = 0L,
    val isSilenceDetected: Boolean = false,
    val currentSessionName: String? = null,
    val currentSessionSegments: List<String> = emptyList(),
    val status: String = "Ready",
    val calibrationRmsDb: Float? = null,
    val calibrationPeakDb: Float? = null,
    val recommendedGainDb: Float? = null,
    val lastOutputPath: String? = null,
    val storageLocation: StorageLocation = StorageLocation.DOWNLOADS,
    val ignoreSilenceEnabled: Boolean = false,
    val splitOnSilenceEnabled: Boolean = false,
    val silenceThresholdDb: Float = 0f,
    val silenceDurationSec: Int = 8,
    val silenceAutoThresholdDb: Float? = null,
    val silenceEffectiveThresholdDb: Float? = null,
    val silenceDetectionSummary: String? = null,
    val recordingInputGainDb: Float = 0f,
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
    val lastDiagnosticReportPath: String? = null,
    val stereoModeRequested: Boolean = false,
    val stereoChannelsSwapped: Boolean = false,
    val uiVintageV2Enabled: Boolean = false,
    val isAutoMicScanRunning: Boolean = false,
    val isAutoStereoProbeRunning: Boolean = false,
    val autoMicSetupMessage: String? = null,
    val stereoProbeDone: Boolean = false,
    val stereoSupported: Boolean = false,
    val stereoActualChannels: Int = 1,
    val stereoProbeMessage: String = "Stereo not tested yet",
    val stereoGuidedTestResult: String? = null,
    val guidedStereoPassed: Boolean = false,
    val guidedStereoStep: GuidedStereoStep = GuidedStereoStep.IDLE,
    val inputSourceLabel: String = "Unknown",
    val inputPreferredDeviceSet: Boolean = false,
    val inputRoutedDevice: String = "Unknown",
    val inputProcessingSummary: String = "AGC/NS/AEC unknown",
    val isGlobalDspAvailable: Boolean = true,
    val isGlobalDspRunning: Boolean = false,
    val isGlobalDspCpuGuardActive: Boolean = false,
    val isNearClipping: Boolean = false,
    val compGainReductionDb: Float = 0f,
    val deEsserGainReductionDb: Float = 0f,
    val globalDspLimiterHits: Int = 0,
    val dspStatusMessage: String? = null,
    val globalBalanceConfig: GlobalBalanceConfig = GlobalBalanceConfig(),
    val playerFxConfig: PlayerFxConfig = PlayerFxConfig(),
    val playerRecordings: List<RecordingListItem> = emptyList(),
    val favoriteRecordingKeys: Set<String> = emptySet(),
    val playerStatusMessage: String = "Prêt",
    val abTestResult: String? = null,
    val postProcessSourceName: String? = null,
    val postProcessSourcePath: String? = null,
    val postProcessSourceDurationMs: Long? = null,
    val postProcessSourceSampleRateHz: Int? = null,
    val postProcessSourceChannels: Int? = null,
    val postProcessMode: PostProcessMode = PostProcessMode.CLEAN_SINGLE_FILE,
    val postProcessIsAnalyzing: Boolean = false,
    val postProcessIsExporting: Boolean = false,
    val postProcessSegments: List<PostProcessSegmentPreview> = emptyList(),
    val postProcessCuts: List<PostProcessCutPreview> = emptyList(),
    val postProcessEnvelope: List<PostProcessEnvelopePoint> = emptyList(),
    val postProcessStatusMessage: String = "Importe un WAV ou M4A",
    val postProcessLastExportLabel: String? = null
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = WavRecorderEngine()
    private val settingsStore = AppSettingsStore(app)
    private val historyFile = File(app.filesDir, "mic_test_history.tsv")

    private var activeSessionBaseName: String? = null
    private var silenceStartElapsedMs: Long? = null
    private var autoStopOnSilenceRequested: Boolean = false
    private var lastCpuGuardState: Boolean = false
    private var postProcessSourceFile: File? = null
    private var postProcessWorkingFile: File? = null
    private var postProcessSourceKind: ImportedAudioKind = ImportedAudioKind.UNSUPPORTED
    private var postProcessAnalysis: WavAnalysisResult? = null
    private var postProcessReanalyzeJob: Job? = null
    private val liveDetectionHistory = ArrayDeque<SignalFeatures>()
    private var liveDetectorInSilence = false

    private val silenceVisualHoldMs = 700L

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    init {
        val settings = settingsStore.load()
        _uiState.update {
            it.copy(
                storageLocation = settings.storageLocation,
                ignoreSilenceEnabled = settings.ignoreSilenceEnabled,
                splitOnSilenceEnabled = settings.splitOnSilenceEnabled,
                silenceThresholdDb = settings.silenceThresholdDb,
                silenceDurationSec = settings.silenceDurationSec,
                recordingInputGainDb = settings.recordingInputGainDb,
                selectedMicId = settings.selectedMicId,
                diagnosticModeEnabled = settings.diagnosticMode,
                showAdvancedInternals = settings.showAdvancedInternals,
                selectedTestDurationSec = normalizeDuration(settings.testDurationSec),
                balanceDurationSec = normalizeBalanceDuration(settings.balanceDurationSec),
                stereoModeRequested = settings.stereoModeRequested,
                stereoChannelsSwapped = settings.stereoChannelsSwapped,
                uiVintageV2Enabled = settings.uiVintageV2Enabled,
                globalBalanceConfig = settings.globalBalanceConfig,
                playerFxConfig = settings.playerFxConfig,
                favoriteRecordingKeys = settings.favoriteRecordingKeys,
                testHistory = loadHistory()
            )
        }

        refreshMicrophones()
        refreshPlayerRecordings()
        observeAnalysisService()
        observeRecordingService()
    }

    fun setStorageLocation(location: StorageLocation) {
        settingsStore.setStorageLocation(location)
        _uiState.update { it.copy(storageLocation = location) }
    }

    fun setIgnoreSilenceEnabled(enabled: Boolean) {
        settingsStore.setIgnoreSilenceEnabled(enabled)
        if (!enabled) {
            settingsStore.setSplitOnSilenceEnabled(false)
        }
        _uiState.update {
            it.copy(
                ignoreSilenceEnabled = enabled,
                splitOnSilenceEnabled = if (enabled) it.splitOnSilenceEnabled else false
            )
        }
    }

    fun setSplitOnSilenceEnabled(enabled: Boolean) {
        val canEnable = _uiState.value.ignoreSilenceEnabled
        val finalValue = enabled && canEnable
        settingsStore.setSplitOnSilenceEnabled(finalValue)
        _uiState.update { it.copy(splitOnSilenceEnabled = finalValue) }
    }

    fun setSilenceThresholdDb(value: Float) {
        val bounded = value.coerceIn(-18f, 18f)
        settingsStore.setSilenceThresholdDb(bounded)
        _uiState.update { it.copy(silenceThresholdDb = bounded) }
    }

    fun setSilenceDurationSec(value: Int) {
        val bounded = value.coerceIn(2, 20)
        settingsStore.setSilenceDurationSec(bounded)
        _uiState.update { it.copy(silenceDurationSec = bounded) }
    }

    fun setRecordingInputGainDb(value: Float) {
        val bounded = value.coerceIn(-24f, 24f)
        settingsStore.setRecordingInputGainDb(bounded)
        _uiState.update { it.copy(recordingInputGainDb = bounded) }
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

    fun setBalanceDuration(seconds: Int) {
        val normalized = normalizeBalanceDuration(seconds)
        settingsStore.setBalanceDurationSec(normalized)
        _uiState.update { it.copy(balanceDurationSec = normalized) }
    }

    fun runLevelBalance() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest || _uiState.value.isRunningLevelBalance) return

        val durationSec = _uiState.value.balanceDurationSec
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunningLevelBalance = true,
                    balanceProgress = 0,
                    balancePeakMaxDb = -90f,
                    balanceDecisionLabel = null,
                    balanceRecommendationText = null,
                    status = "Balance niveau (${durationSec}s)"
                )
            }

            val result = engine.runCalibration(
                durationSeconds = durationSec,
                preferredDevice = resolveEffectiveMicDevice(),
                onProgress = { progress ->
                    _uiState.update { st ->
                        st.copy(
                            balanceProgress = progress.progressPercent,
                            rmsDb = progress.rmsDb,
                            peakDb = progress.peakDb,
                            headroomDb = -progress.peakDb,
                            balancePeakMaxDb = maxOf(st.balancePeakMaxDb, progress.peakDb)
                        )
                    }
                }
            )

            if (result == null) {
                _uiState.update {
                    it.copy(
                        isRunningLevelBalance = false,
                        status = "Balance niveau impossible"
                    )
                }
                return@launch
            }

            val peak = result.peakDb
            val decision = decisionFromPeak(peak)
            val autoAppliedGainDb = result.recommendedGainDb.coerceIn(-24f, 24f)
            settingsStore.setRecordingInputGainDb(autoAppliedGainDb)
            _uiState.update {
                it.copy(
                    isRunningLevelBalance = false,
                    balanceProgress = 100,
                    balancePeakMaxDb = peak,
                    balanceDecisionLabel = decision.first,
                    balanceRecommendationText = decision.second,
                    recommendedGainDb = result.recommendedGainDb,
                    recordingInputGainDb = autoAppliedGainDb,
                    status = "Balance terminée • gain auto ${if (autoAppliedGainDb >= 0f) "+" else ""}${"%.1f".format(autoAppliedGainDb)} dB"
                )
            }

            if (_uiState.value.diagnosticModeEnabled) {
                exportDiagnosticReport(
                    event = "balance_level_check",
                    entry = null,
                    recordingPath = _uiState.value.lastOutputPath,
                    extraLines = listOf(
                        "balance.duration_sec=$durationSec",
                        "balance.peak_db=$peak",
                        "balance.rms_db=${result.rmsDb}",
                        "balance.peak_zone=${decision.first}",
                        "balance.recommendation=${decision.second}"
                    )
                )
            }
        }
    }

    fun setStereoModeRequested(enabled: Boolean) {
        settingsStore.setStereoModeRequested(enabled)
        _uiState.update { it.copy(stereoModeRequested = enabled) }
    }

    fun setStereoChannelsSwapped(enabled: Boolean) {
        settingsStore.setStereoChannelsSwapped(enabled)
        _uiState.update { it.copy(stereoChannelsSwapped = enabled) }
    }

    fun setUiVintageV2Enabled(enabled: Boolean) {
        settingsStore.setUiVintageV2Enabled(enabled)
        _uiState.update { it.copy(uiVintageV2Enabled = enabled) }
    }

    fun setAutoBalance(enabled: Boolean) {
        updateGlobalBalanceConfig { it.copy(autoBalanceEnabled = enabled) }
    }

    fun setCompression(enabled: Boolean) {
        updateGlobalBalanceConfig { it.copy(compressionEnabled = enabled) }
    }

    fun setDeEsser(enabled: Boolean) {
        updateGlobalBalanceConfig { it.copy(deEsserEnabled = enabled) }
    }

    fun setDspOutputMode(mode: DspOutputMode) {
        updateGlobalBalanceConfig { it.copy(dspOutputMode = mode) }
    }

    fun setMixProfile(profile: MixProfile) {
        updateGlobalBalanceConfig { it.copy(mixProfile = profile) }
    }

    fun updateAdvancedBalanceConfig(config: GlobalBalanceConfig) {
        updateGlobalBalanceConfig { config }
    }

    fun resetBalanceProfile() {
        updateGlobalBalanceConfig { GlobalBalanceConfig() }
    }

    fun setPlayerPreset(preset: PlayerFxPreset) {
        updatePlayerFxConfig { it.copy(preset = preset) }
    }

    fun setPlayerEqEnabled(enabled: Boolean) {
        updatePlayerFxConfig { it.copy(eqEnabled = enabled) }
    }

    fun setPlayerCompressionEnabled(enabled: Boolean) {
        updatePlayerFxConfig { it.copy(compressionEnabled = enabled) }
    }

    fun setPlayerDeEsserEnabled(enabled: Boolean) {
        updatePlayerFxConfig { it.copy(deEsserEnabled = enabled) }
    }

    fun setPlayerEqIntensity(value: Float) {
        updatePlayerFxConfig { it.copy(eqIntensity = value) }
    }

    fun setPlayerCompressionIntensity(value: Float) {
        updatePlayerFxConfig { it.copy(compressionIntensity = value) }
    }

    fun setPlayerDeEsserIntensity(value: Float) {
        updatePlayerFxConfig { it.copy(deEsserIntensity = value) }
    }

    fun setPlayerBoostIntensity(value: Float) {
        updatePlayerFxConfig { it.copy(boostIntensity = value) }
    }

    fun setPlayerStatusMessage(message: String) {
        _uiState.update { it.copy(playerStatusMessage = message) }
    }

    fun setPostProcessMode(mode: PostProcessMode) {
        _uiState.update { it.copy(postProcessMode = mode) }
    }

    fun setPostProcessSilenceDurationSec(value: Int) {
        setSilenceDurationSec(value)
        markPostProcessPreviewDirty()
        schedulePostProcessReanalysis()
    }

    fun setPostProcessSilenceThresholdDb(value: Float) {
        setSilenceThresholdDb(value)
        markPostProcessPreviewDirty()
        schedulePostProcessReanalysis()
    }

    fun importPostProcessSource(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { importPostProcessSourceInternal(uri) }
            if (imported == null) {
                _uiState.update {
                    it.copy(
                        postProcessStatusMessage = "Import impossible ou format audio non supporté.",
                        postProcessLastExportLabel = null
                    )
                }
                return@launch
            }

            postProcessSourceFile?.takeIf { it != imported.file }?.let { old ->
                runCatching { old.delete() }
            }
            postProcessWorkingFile?.takeIf { it != postProcessSourceFile }?.let { old ->
                runCatching { old.delete() }
            }
            postProcessSourceFile = imported.file
            postProcessWorkingFile = null
            postProcessSourceKind = imported.kind
            postProcessAnalysis = null
            _uiState.update {
                it.copy(
                    postProcessSourceName = imported.displayName,
                    postProcessSourcePath = imported.file.absolutePath,
                    postProcessSourceDurationMs = imported.durationMs,
                    postProcessSourceSampleRateHz = imported.sampleRateHz,
                    postProcessSourceChannels = imported.channels,
                    postProcessSegments = emptyList(),
                    postProcessCuts = emptyList(),
                    postProcessEnvelope = emptyList(),
                    postProcessStatusMessage = if (imported.kind == ImportedAudioKind.M4A) {
                        "Fichier importé. Le décodage WAV sera fait à l'analyse."
                    } else {
                        "Fichier importé. Lance l'analyse."
                    },
                    postProcessLastExportLabel = null
                )
            }
        }
    }

    fun analyzePostProcessSource() {
        val sourceFile = postProcessSourceFile
        if (sourceFile == null || !sourceFile.exists()) {
            _uiState.update { it.copy(postProcessStatusMessage = "Importe un WAV ou M4A avant l'analyse.") }
            return
        }
        postProcessAnalysis = null
        _uiState.update {
            it.copy(
                postProcessIsAnalyzing = true,
                postProcessStatusMessage = "Analyse en cours...",
                postProcessLastExportLabel = null
            )
        }
        AnalysisForegroundService.start(
            context = getApplication(),
            sourcePath = sourceFile.absolutePath,
            displayName = _uiState.value.postProcessSourceName ?: sourceFile.name,
            sourceKind = postProcessSourceKind,
            silenceThresholdDb = _uiState.value.silenceThresholdDb,
            silenceDurationSec = _uiState.value.silenceDurationSec
        )
    }

    fun runPostProcessExport() {
        val sourceFile = postProcessWorkingFile
        val analysis = postProcessAnalysis
        if (sourceFile == null || analysis == null || analysis.segments.isEmpty()) {
            _uiState.update { it.copy(postProcessStatusMessage = "Analyse d'abord le fichier avant l'export.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    postProcessIsExporting = true,
                    postProcessStatusMessage = "Export en cours...",
                    postProcessLastExportLabel = null
                )
            }

            val exportResult = withContext(Dispatchers.IO) {
                exportPostProcessedFiles(
                    sourceFile = sourceFile,
                    analysis = analysis,
                    mode = _uiState.value.postProcessMode,
                    sourceDisplayName = _uiState.value.postProcessSourceName ?: sourceFile.name
                )
            }

            _uiState.update {
                it.copy(
                    postProcessIsExporting = false,
                    postProcessStatusMessage = exportResult ?: "Export impossible.",
                    postProcessLastExportLabel = exportResult
                )
            }
            if (exportResult != null) {
                setPlayerStatusMessage(exportResult)
                refreshPlayerRecordings()
            }
        }
    }

    fun refreshPlayerRecordings() {
        viewModelScope.launch {
            val favorites = _uiState.value.favoriteRecordingKeys
            val items = mutableListOf<RecordingListItem>()
            items += queryDownloadsRecordings(favorites)
            items += queryAppPrivateRecordings(favorites)
            val sorted = sortRecordings(items)
            _uiState.update { it.copy(playerRecordings = sorted) }
            refreshCurrentSessionSegments()
        }
    }

    fun toggleRecordingFavorite(key: String) {
        val newSet = _uiState.value.favoriteRecordingKeys.toMutableSet().apply {
            if (!add(key)) remove(key)
        }.toSet()
        settingsStore.setFavoriteRecordingKeys(newSet)
        _uiState.update {
            it.copy(
                favoriteRecordingKeys = newSet,
                playerRecordings = it.playerRecordings.map { rec -> if (rec.key == key) rec.copy(isFavorite = key in newSet) else rec }
            )
        }
    }

    fun deleteRecording(key: String) {
        val item = _uiState.value.playerRecordings.firstOrNull { it.key == key } ?: return
        viewModelScope.launch {
            val deleted = runCatching {
                when {
                    item.sourceUri != null -> {
                        val resolver = getApplication<Application>().contentResolver
                        resolver.delete(item.sourceUri, null, null) > 0
                    }
                    !item.filePath.isNullOrBlank() -> File(item.filePath).delete()
                    else -> false
                }
            }.getOrDefault(false)

            val newFavorites = _uiState.value.favoriteRecordingKeys - key
            settingsStore.setFavoriteRecordingKeys(newFavorites)
            _uiState.update {
                it.copy(
                    favoriteRecordingKeys = newFavorites,
                    playerStatusMessage = if (deleted) {
                        "Supprimé: ${item.title}"
                    } else {
                        "Suppression impossible: ${item.title}"
                    }
                )
            }

            if (deleted) {
                refreshPlayerRecordings()
            }
        }
    }

    fun markAutoSetupPermissionDenied() {
        _uiState.update {
            it.copy(
                isAutoMicScanRunning = false,
                isAutoStereoProbeRunning = false,
                autoMicSetupMessage = "Impossible de vérifier maintenant: permission micro manquante."
            )
        }
    }

    private fun updateGlobalBalanceConfig(transform: (GlobalBalanceConfig) -> GlobalBalanceConfig) {
        val updated = transform(_uiState.value.globalBalanceConfig).bounded()
        settingsStore.setGlobalBalanceConfig(updated)
        _uiState.update {
            it.copy(globalBalanceConfig = updated)
        }
        if (_uiState.value.diagnosticModeEnabled) {
            exportDiagnosticReport(
                event = "global_balance_config_changed",
                entry = null,
                recordingPath = _uiState.value.lastOutputPath,
                extraLines = buildGlobalDspDiagnosticLines(updated)
            )
        }
    }

    private fun updatePlayerFxConfig(transform: (PlayerFxConfig) -> PlayerFxConfig) {
        val updated = transform(_uiState.value.playerFxConfig).bounded()
        settingsStore.setPlayerFxConfig(updated)
        _uiState.update { it.copy(playerFxConfig = updated) }
        if (_uiState.value.diagnosticModeEnabled) {
            exportDiagnosticReport(
                event = "player_fx_config_changed",
                entry = null,
                recordingPath = _uiState.value.lastOutputPath,
                extraLines = listOf(
                    "player_fx.preset=${updated.preset}",
                    "player_fx.eq_enabled=${updated.eqEnabled}",
                    "player_fx.comp_enabled=${updated.compressionEnabled}",
                    "player_fx.deesser_enabled=${updated.deEsserEnabled}",
                    "player_fx.eq_intensity=${updated.eqIntensity}",
                    "player_fx.comp_intensity=${updated.compressionIntensity}",
                    "player_fx.deesser_intensity=${updated.deEsserIntensity}",
                    "player_fx.boost_intensity=${updated.boostIntensity}"
                )
            )
        }
    }

    fun retryAutoMicSetup() {
        runAutoMicSetup()
    }

    fun runAutoMicSetup() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) {
            _uiState.update {
                it.copy(autoMicSetupMessage = "Impossible de vérifier maintenant: action audio déjà en cours.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAutoMicScanRunning = true,
                    isAutoStereoProbeRunning = false,
                    autoMicSetupMessage = "Vérification automatique des micros en cours..."
                )
            }

            refreshMicrophones()

            _uiState.update {
                it.copy(
                    isAutoMicScanRunning = false,
                    isAutoStereoProbeRunning = true,
                    autoMicSetupMessage = "Vérification de la compatibilité stéréo en cours..."
                )
            }

            runStereoProbeInternal()

            _uiState.update {
                it.copy(
                    isAutoStereoProbeRunning = false,
                    autoMicSetupMessage = null
                )
            }
        }
    }

    fun probeStereoCapability() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) return

        viewModelScope.launch {
            runStereoProbeInternal()
        }
    }

    private suspend fun runStereoProbeInternal() {
        _uiState.update { it.copy(status = "Probing stereo capability...") }
        val probe: StereoProbeResult = engine.probeStereoCapability(preferredDevice = resolveEffectiveMicDevice())
        _uiState.update {
            val diag = probe.diagnostics
            it.copy(
                stereoProbeDone = true,
                stereoSupported = probe.supported,
                stereoActualChannels = probe.actualChannelCount,
                stereoProbeMessage = probe.message,
                status = if (probe.supported) "Stereo supported" else "Stereo not supported",
                inputSourceLabel = diag?.sourceLabel ?: it.inputSourceLabel,
                inputPreferredDeviceSet = diag?.preferredDeviceSet ?: it.inputPreferredDeviceSet,
                inputRoutedDevice = diag?.let { d ->
                    val type = d.routedDeviceTypeLabel ?: "type?"
                    val id = d.routedDeviceId?.toString() ?: "unknown"
                    val addr = d.routedDeviceAddress ?: "n/a"
                    "$type#$id ($addr)"
                } ?: it.inputRoutedDevice,
                inputProcessingSummary = diag?.let { d ->
                    "AGC=${d.agcEnabled ?: "n/a"} NS=${d.nsEnabled ?: "n/a"} AEC=${d.aecEnabled ?: "n/a"}"
                } ?: it.inputProcessingSummary
            )
        }
    }

    fun runGuidedStereoTest() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) return

        viewModelScope.launch {
            val preferredDevice = resolveEffectiveMicDevice()
            _uiState.update {
                it.copy(
                    isRunningStereoGuidedTest = true,
                    stereoGuidedTestResult = null,
                    guidedStereoPassed = false,
                    guidedStereoStep = GuidedStereoStep.PREP_LEFT,
                    status = "Guided stereo test: prepare LEFT side (2s)"
                )
            }
            delay(2000)
            _uiState.update { it.copy(guidedStereoStep = GuidedStereoStep.CAPTURE_LEFT, status = "Guided stereo test: capture LEFT (3s)") }
            val left = engine.measureStereoWindow(durationSeconds = 3, preferredDevice = preferredDevice)

            _uiState.update { it.copy(guidedStereoStep = GuidedStereoStep.PREP_RIGHT, status = "Guided stereo test: prepare RIGHT side (2s)") }
            delay(2000)
            _uiState.update { it.copy(guidedStereoStep = GuidedStereoStep.CAPTURE_RIGHT, status = "Guided stereo test: capture RIGHT (3s)") }
            val right = engine.measureStereoWindow(durationSeconds = 3, preferredDevice = preferredDevice)

            _uiState.update { it.copy(guidedStereoStep = GuidedStereoStep.PREP_CENTER, status = "Guided stereo test: prepare CENTER (2s)") }
            delay(2000)
            _uiState.update { it.copy(guidedStereoStep = GuidedStereoStep.CAPTURE_CENTER, status = "Guided stereo test: capture CENTER (3s)") }
            val center = engine.measureStereoWindow(durationSeconds = 3, preferredDevice = preferredDevice)

            if (left == null || right == null || center == null) {
                _uiState.update {
                    it.copy(
                        isRunningStereoGuidedTest = false,
                        stereoGuidedTestResult = "Guided stereo test failed: stereo capture route unavailable.",
                        guidedStereoPassed = false,
                        guidedStereoStep = GuidedStereoStep.DONE,
                        status = "Guided stereo test failed"
                    )
                }
                return@launch
            }

            val leftDominanceDb = left.leftRmsDb - left.rightRmsDb
            val rightDominanceDb = right.rightRmsDb - right.leftRmsDb
            val centerDiffDb = abs(center.leftRmsDb - center.rightRmsDb)

            val leftOk = leftDominanceDb >= 1.5f
            val rightOk = rightDominanceDb >= 1.5f
            val leftSwappedOk = leftDominanceDb <= -1.5f
            val rightSwappedOk = rightDominanceDb <= -1.5f
            val centerOk = centerDiffDb <= 3.0f
            val normalOrientation = leftOk && rightOk
            val swappedOrientation = leftSwappedOk && rightSwappedOk
            val pass = (normalOrientation || swappedOrientation) && centerOk

            val channelCount = minOf(left.actualChannelCount, right.actualChannelCount, center.actualChannelCount)
            val orientationLabel = when {
                normalOrientation -> "normal"
                swappedOrientation -> "swapped"
                else -> "inconsistent"
            }
            val inputDiag = left.diagnostics ?: right.diagnostics ?: center.diagnostics
            val autoSwap = pass && swappedOrientation
            val routedMicId = inputDiag?.routedDeviceId
            val routedMicOption = routedMicId?.let { rid -> _uiState.value.microphones.firstOrNull { it.id == rid } }
            val chosenMicId = if (pass && routedMicOption != null) routedMicOption.id else _uiState.value.selectedMicId
            val chosenMic = effectiveMic(_uiState.value.microphones, chosenMicId)

            if (pass) {
                settingsStore.setStereoModeRequested(true)
                settingsStore.setStereoChannelsSwapped(autoSwap)
                settingsStore.setSelectedMicId(chosenMicId)
            }

            val resultText = buildString {
                val verdict = if (pass) {
                    if (swappedOrientation) "Guided stereo test: PASS (channels swapped)"
                    else "Guided stereo test: PASS"
                } else {
                    "Guided stereo test: FAIL"
                }
                appendLine(verdict)
                appendLine("LEFT step: L-R = ${"%.1f".format(leftDominanceDb)} dB ${if (leftOk) "OK" else "LOW"}")
                appendLine("RIGHT step: R-L = ${"%.1f".format(rightDominanceDb)} dB ${if (rightOk) "OK" else "LOW"}")
                appendLine("CENTER step: |L-R| = ${"%.1f".format(centerDiffDb)} dB ${if (centerOk) "OK" else "HIGH"}")
                appendLine("Orientation: $orientationLabel")
                appendLine("Channels seen: $channelCount")
                if (pass) {
                    appendLine("Profile applied: stereo ON, swap=${if (autoSwap) "ON" else "OFF"}, mic=${chosenMic?.displayName ?: "auto"}")
                }
                append("Tip: make louder, closer, and side-specific sounds during LEFT/RIGHT steps.")
            }

            _uiState.update {
                it.copy(
                    isRunningStereoGuidedTest = false,
                    stereoGuidedTestResult = resultText,
                    guidedStereoPassed = pass,
                    stereoProbeDone = true,
                    stereoSupported = pass,
                    stereoActualChannels = channelCount,
                    stereoModeRequested = if (pass) true else it.stereoModeRequested,
                    stereoChannelsSwapped = if (pass) autoSwap else it.stereoChannelsSwapped,
                    selectedMicId = if (pass) chosenMicId else it.selectedMicId,
                    isAutoMicMode = if (pass) chosenMicId == null else it.isAutoMicMode,
                    effectiveMicLabel = if (pass) chosenMic?.displayName else it.effectiveMicLabel,
                    effectiveMicWarning = if (pass) chosenMic?.warning else it.effectiveMicWarning,
                    effectiveMicRecommended = if (pass) chosenMic?.recommended ?: true else it.effectiveMicRecommended,
                    stereoProbeMessage = when {
                        pass && swappedOrientation -> "Guided test confirmed stereo separation (channels swapped)"
                        pass -> "Guided test confirmed stereo separation"
                        else -> "Guided test did not confirm stereo separation"
                    },
                    status = if (pass) "Guided stereo test passed" else "Guided stereo test failed",
                    inputSourceLabel = inputDiag?.sourceLabel ?: it.inputSourceLabel,
                    inputPreferredDeviceSet = inputDiag?.preferredDeviceSet ?: it.inputPreferredDeviceSet,
                    inputRoutedDevice = inputDiag?.let { d ->
                        val type = d.routedDeviceTypeLabel ?: "type?"
                        val id = d.routedDeviceId?.toString() ?: "unknown"
                        val addr = d.routedDeviceAddress ?: "n/a"
                        "$type#$id ($addr)"
                    } ?: it.inputRoutedDevice,
                    inputProcessingSummary = inputDiag?.let { d ->
                        "AGC=${d.agcEnabled ?: "n/a"} NS=${d.nsEnabled ?: "n/a"} AEC=${d.aecEnabled ?: "n/a"}"
                    } ?: it.inputProcessingSummary,
                    guidedStereoStep = GuidedStereoStep.DONE
                )
            }

            if (_uiState.value.diagnosticModeEnabled) {
                exportDiagnosticReport(
                    event = "guided_stereo_test",
                    entry = null,
                    recordingPath = _uiState.value.lastOutputPath,
                    extraLines = buildGuidedStereoDiagnosticLines(
                        left = left,
                        right = right,
                        center = center,
                        pass = pass,
                        orientation = orientationLabel,
                        inputDiag = inputDiag,
                        autoSwapApplied = autoSwap,
                        selectedMicIdAfterTest = chosenMicId
                    )
                )
            }
        }
    }

    private fun buildGuidedStereoDiagnosticLines(
        left: StereoWindowMeasurement,
        right: StereoWindowMeasurement,
        center: StereoWindowMeasurement,
        pass: Boolean,
        orientation: String,
        inputDiag: InputDiagnostics?,
        autoSwapApplied: Boolean,
        selectedMicIdAfterTest: Int?
    ): List<String> {
        val leftDominanceDb = left.leftRmsDb - left.rightRmsDb
        val rightDominanceDb = right.rightRmsDb - right.leftRmsDb
        val centerDiffDb = abs(center.leftRmsDb - center.rightRmsDb)
        val base = mutableListOf(
            "guided_stereo.pass=$pass",
            "guided_stereo.orientation=$orientation",
            "guided_stereo.auto_swap_applied=$autoSwapApplied",
            "guided_stereo.selected_mic_after_test=${selectedMicIdAfterTest ?: "auto"}",
            "guided_stereo.left.left_rms=${left.leftRmsDb}",
            "guided_stereo.left.right_rms=${left.rightRmsDb}",
            "guided_stereo.left.delta_l_minus_r=$leftDominanceDb",
            "guided_stereo.right.left_rms=${right.leftRmsDb}",
            "guided_stereo.right.right_rms=${right.rightRmsDb}",
            "guided_stereo.right.delta_r_minus_l=$rightDominanceDb",
            "guided_stereo.center.left_rms=${center.leftRmsDb}",
            "guided_stereo.center.right_rms=${center.rightRmsDb}",
            "guided_stereo.center.abs_delta=$centerDiffDb"
        )
        inputDiag?.let { d ->
            base += "input.source=${d.sourceLabel}(${d.source})"
            base += "input.preferred_device_set=${d.preferredDeviceSet}"
            base += "input.routed_device_id=${d.routedDeviceId ?: "unknown"}"
            base += "input.routed_device_type=${d.routedDeviceTypeLabel ?: d.routedDeviceType ?: "unknown"}"
            base += "input.routed_device_address=${d.routedDeviceAddress ?: ""}"
            base += "input.agc_available=${d.agcAvailable}"
            base += "input.agc_enabled=${d.agcEnabled ?: "n/a"}"
            base += "input.ns_available=${d.nsAvailable}"
            base += "input.ns_enabled=${d.nsEnabled ?: "n/a"}"
            base += "input.aec_available=${d.aecAvailable}"
            base += "input.aec_enabled=${d.aecEnabled ?: "n/a"}"
        }
        return base
    }

    private fun buildGlobalDspDiagnosticLines(configOverride: GlobalBalanceConfig? = null): List<String> {
        val state = _uiState.value
        val cfg = (configOverride ?: state.globalBalanceConfig).bounded()
        return listOf(
            "global_dsp.enabled=${cfg.autoBalanceEnabled || cfg.compressionEnabled || cfg.deEsserEnabled}",
            "global_dsp.auto_balance=${cfg.autoBalanceEnabled}",
            "global_dsp.compression=${cfg.compressionEnabled}",
            "global_dsp.de_esser=${cfg.deEsserEnabled}",
            "global_dsp.output_mode=${cfg.dspOutputMode}",
            "global_dsp.profile=${cfg.mixProfile}",
            "global_dsp.eq_intensity=${cfg.eqIntensity}",
            "global_dsp.comp_intensity=${cfg.compIntensity}",
            "global_dsp.deesser_intensity=${cfg.deEsserIntensity}",
            "global_dsp.comp_threshold_db=${cfg.compressorThresholdDb}",
            "global_dsp.comp_ratio=${cfg.compressorRatio}",
            "global_dsp.deesser_freq_hz=${cfg.deEsserFrequencyHz}",
            "global_dsp.deesser_width_hz=${cfg.deEsserWidthHz}",
            "global_dsp.limiter_ceiling_db=${cfg.limiterCeilingDb}",
            "global_dsp.output_trim_db=${cfg.outputTrimDb}",
            "global_dsp.running=${state.isGlobalDspRunning}",
            "global_dsp.comp_gr_db=${state.compGainReductionDb}",
            "global_dsp.deesser_gr_db=${state.deEsserGainReductionDb}",
            "global_dsp.limiter_hits=${state.globalDspLimiterHits}",
            "global_dsp.cpu_guard_state=${state.isGlobalDspCpuGuardActive}",
            "global_dsp.status_message=${state.dspStatusMessage ?: ""}"
        )
    }

    fun refreshMicrophones() {
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        val devices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }

        val microInfoById = loadMicrophoneInfoById(audioManager)

        val raw = devices.map { device ->
            val info = microInfoById[device.id]
            val baseName = device.productName.toString().takeIf { it.isNotBlank() } ?: "Microphone"
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
                rawAddress = device.address.takeIf { it.isNotBlank() },
                rawChannelCounts = device.channelCounts.takeIf { it.isNotEmpty() }?.joinToString(","),
                rawSampleRates = device.sampleRates.takeIf { it.isNotEmpty() }?.joinToString(","),
                rawEncodings = device.encodings.takeIf { it.isNotEmpty() }?.joinToString(",")
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
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) return

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

    fun runExternalABTest() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) return

        val builtIns = _uiState.value.microphones.filter { it.typeCode == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (builtIns.size < 2) {
            _uiState.update {
                it.copy(
                    status = "Test A/B indisponible: 2 micros internes minimum",
                    abTestResult = "A/B indisponible: moins de 2 micros internes détectés"
                )
            }
            return
        }

        val micA = builtIns[0]
        val micB = builtIns[1]

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunningABTest = true,
                    status = "A/B: ${micA.displayName} silence 2s, puis signal 6s",
                    abTestResult = null
                )
            }
            val measureA = runGuidedABMeasure(micA)

            _uiState.update {
                it.copy(status = "A/B: ${micB.displayName} silence 2s, puis signal 6s")
            }
            val measureB = runGuidedABMeasure(micB)

            _uiState.update { it.copy(isRunningABTest = false) }

            if (measureA == null || measureB == null) {
                _uiState.update {
                    it.copy(status = "Échec du test A/B", abTestResult = "A/B échoué: erreur de mesure")
                }
                return@launch
            }

            val scoreA = computeABScore(measureA.noise, measureA.signal)
            val scoreB = computeABScore(measureB.noise, measureB.signal)
            val winner = if (scoreA >= scoreB) micA else micB

            val result = buildString {
                appendLine("Test externe A/B terminé")
                appendLine("Micro A: ${micA.displayName} | score ${"%.1f".format(scoreA)} | bruit ${"%.1f".format(measureA.noise.rmsDb)} | signal ${"%.1f".format(measureA.signal.rmsDb)}")
                appendLine("Micro B: ${micB.displayName} | score ${"%.1f".format(scoreB)} | bruit ${"%.1f".format(measureB.noise.rmsDb)} | signal ${"%.1f".format(measureB.signal.rmsDb)}")
                appendLine("Micro recommandé: ${winner.displayName}")
            }

            _uiState.update {
                it.copy(
                    status = "Test A/B terminé",
                    abTestResult = result
                )
            }

            if (_uiState.value.diagnosticModeEnabled) {
                exportDiagnosticReport(
                    event = "ab_test",
                    entry = null,
                    recordingPath = _uiState.value.lastOutputPath,
                    extraLines = listOf(
                        "ab.micA=${micA.displayName}",
                        "ab.micA.score=$scoreA",
                        "ab.micA.noise_rms=${measureA.noise.rmsDb}",
                        "ab.micA.signal_rms=${measureA.signal.rmsDb}",
                        "ab.micB=${micB.displayName}",
                        "ab.micB.score=$scoreB",
                        "ab.micB.noise_rms=${measureB.noise.rmsDb}",
                        "ab.micB.signal_rms=${measureB.signal.rmsDb}",
                        "ab.winner=${winner.displayName}"
                    )
                )
            }
        }
    }

    private data class ABMeasure(
        val noise: CalibrationResult,
        val signal: CalibrationResult
    )

    private suspend fun runGuidedABMeasure(mic: MicrophoneOption): ABMeasure? {
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == mic.id }

        val noise = engine.runCalibration(
            durationSeconds = 2,
            preferredDevice = device,
            onProgress = { progress ->
                _uiState.update { s -> s.copy(rmsDb = progress.rmsDb, peakDb = progress.peakDb, headroomDb = -progress.peakDb) }
            }
        ) ?: return null

        val signal = engine.runCalibration(
            durationSeconds = 6,
            preferredDevice = device,
            onProgress = { progress ->
                _uiState.update { s -> s.copy(rmsDb = progress.rmsDb, peakDb = progress.peakDb, headroomDb = -progress.peakDb) }
            }
        ) ?: return null

        return ABMeasure(noise = noise, signal = signal)
    }

    private fun computeABScore(noise: CalibrationResult, signal: CalibrationResult): Float {
        val snr = signal.rmsDb - noise.rmsDb
        val peakPenalty = abs(signal.peakDb - (-6f))
        return (snr * 2f) - peakPenalty
    }

    fun runCalibration() {
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) return

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
        if (_uiState.value.isRecording || _uiState.value.isCalibrating || _uiState.value.isTestingMic || _uiState.value.isRunningABTest || _uiState.value.isRunningStereoGuidedTest) return

        val selectedMic = resolveEffectiveMicDevice()
        val wantsStereo = _uiState.value.stereoModeRequested
        val stereoOk = _uiState.value.stereoProbeDone && _uiState.value.stereoSupported
        val channels = if (wantsStereo && stereoOk) 2 else 1

        if (wantsStereo && !stereoOk) {
            _uiState.update { it.copy(status = "Stereo requested but not confirmed. Falling back to mono.") }
        }

        autoStopOnSilenceRequested = false
        silenceStartElapsedMs = null
        RecordingForegroundService.start(
            context = getApplication(),
            storageLocation = _uiState.value.storageLocation,
            preferredMicId = selectedMic?.id,
            requestedChannels = channels,
            swapStereoChannels = _uiState.value.stereoChannelsSwapped,
            inputGainDb = _uiState.value.recordingInputGainDb,
            ignoreSilenceEnabled = _uiState.value.ignoreSilenceEnabled,
            splitOnSilenceEnabled = _uiState.value.splitOnSilenceEnabled,
            silenceThresholdDb = _uiState.value.silenceThresholdDb,
            silenceDurationSec = _uiState.value.silenceDurationSec
        )
        _uiState.update {
            it.copy(
                status = if (channels == 2) "Recording (stereo)" else "Recording (mono)",
                elapsedMs = 0L,
                isSilenceDetected = false,
                currentSessionSegments = emptyList()
            )
        }
    }

    fun stopRecording() {
        autoStopOnSilenceRequested = false
        silenceStartElapsedMs = null
        _uiState.update { it.copy(status = "Stopping...", isSilenceDetected = false) }
        RecordingForegroundService.stop(getApplication())
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

    private fun normalizeBalanceDuration(seconds: Int): Int = when (seconds) {
        30, 60, 90 -> seconds
        else -> 30
    }

    private fun normalizeVu(db: Float): Float = ((db + 60f) / 60f).coerceIn(0f, 1f)

    private fun smoothVu(previous: Float, newValue: Float): Float = (0.8f * previous + 0.2f * newValue).coerceIn(0f, 1f)

    private fun queryDownloadsRecordings(favorites: Set<String>): List<RecordingListItem> {
        val resolver = getApplication<Application>().contentResolver
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        val selection = buildString {
            append("(")
            append("${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ")
            append("${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ")
            append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            append(") AND ")
            append("${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?")
        }
        val args = arrayOf("audio/wav", "audio/x-wav", "%.wav", "%Band Recorder%")
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val out = mutableListOf<RecordingListItem>()
        runCatching {
            resolver.query(filesUri, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "Recording"
                    val dateSec = c.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(filesUri, id)
                    val duration = readDurationMs(uri = uri, path = null)
                    val key = "ms:$id"
                    out += RecordingListItem(
                        key = key,
                        title = RecordingFileNaming.userVisibleTitle(name),
                        dateLabel = formatDateFromEpochSec(dateSec),
                        durationMs = duration,
                        sourceUri = uri,
                        filePath = null,
                        isFavorite = key in favorites,
                        sessionGroupKey = RecordingFileNaming.sessionBaseKey(name),
                        segmentIndex = RecordingFileNaming.segmentIndex(name)
                    )
                }
            }
        }
        return out
    }

    private fun queryAppPrivateRecordings(favorites: Set<String>): List<RecordingListItem> {
        val root = getApplication<Application>()
            .getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: getApplication<Application>().filesDir
        val folder = File(root, "band_recordings")
        if (!folder.exists()) return emptyList()
        return folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) == "wav" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val key = "fp:${f.absolutePath}"
                RecordingListItem(
                    key = key,
                    title = RecordingFileNaming.userVisibleTitle(f.name),
                    dateLabel = formatDateFromEpochMs(f.lastModified()),
                    durationMs = readDurationMs(uri = null, path = f.absolutePath),
                    sourceUri = null,
                    filePath = f.absolutePath,
                    isFavorite = key in favorites,
                    sessionGroupKey = RecordingFileNaming.sessionBaseKey(f.name),
                    segmentIndex = RecordingFileNaming.segmentIndex(f.name)
                )
            }
            ?: emptyList()
    }

    private fun readDurationMs(uri: Uri?, path: String?): Long {
        return runCatching {
            val mmr = MediaMetadataRetriever()
            if (uri != null) {
                mmr.setDataSource(getApplication(), uri)
            } else if (path != null) {
                mmr.setDataSource(path)
            }
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            duration
        }.getOrDefault(0L)
    }

    private fun formatDateFromEpochSec(epochSec: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochSec * 1000L))

    private fun formatDateFromEpochMs(epochMs: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun parseDateForSort(label: String): Long {
        val f = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return runCatching { f.parse(label)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun sortRecordings(items: List<RecordingListItem>): List<RecordingListItem> {
        return items.sortedWith(
            compareByDescending<RecordingListItem> {
                it.sessionGroupKey?.let(RecordingFileNaming::sessionTimestampMs) ?: parseDateForSort(it.dateLabel)
            }
                .thenBy { if (it.segmentIndex == null) 0 else 1 }
                .thenBy { it.segmentIndex ?: 0 }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
    }

    private fun decisionFromPeak(peakDb: Float): Pair<String, String> = when {
        peakDb > -3f -> "Peak > -3 dBFS" to "Niveau trop fort: éloigne le téléphone de 20 à 50 cm et vise moins les cymbales/amplis."
        peakDb > -6f -> "Peak entre -6 et -3 dBFS" to "Niveau limite: ajoute un peu de distance (10 à 20 cm), puis reteste."
        peakDb > -12f -> "Peak entre -12 et -6 dBFS (idéal)" to "Niveau recommandé: tu peux lancer REC."
        peakDb > -18f -> "Peak entre -18 et -12 dBFS" to "Niveau sûr mais un peu bas: rapproche légèrement si besoin."
        else -> "Peak < -18 dBFS" to "Niveau trop faible: rapproche le téléphone ou améliore l’orientation vers la source utile."
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

    private fun exportDiagnosticReport(
        event: String,
        entry: MicTestHistoryEntry?,
        recordingPath: String?,
        extraLines: List<String> = emptyList()
    ) {
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
            appendLine("stereo_requested=${_uiState.value.stereoModeRequested}")
            appendLine("stereo_channels_swapped=${_uiState.value.stereoChannelsSwapped}")
            appendLine("stereo_probe_done=${_uiState.value.stereoProbeDone}")
            appendLine("stereo_supported=${_uiState.value.stereoSupported}")
            appendLine("stereo_actual_channels=${_uiState.value.stereoActualChannels}")
            appendLine("stereo_probe_message=${_uiState.value.stereoProbeMessage}")
            appendLine("player_fx.preset=${_uiState.value.playerFxConfig.preset}")
            appendLine("player_fx.eq_enabled=${_uiState.value.playerFxConfig.eqEnabled}")
            appendLine("player_fx.comp_enabled=${_uiState.value.playerFxConfig.compressionEnabled}")
            appendLine("player_fx.deesser_enabled=${_uiState.value.playerFxConfig.deEsserEnabled}")
            appendLine("player_fx.boost_intensity=${_uiState.value.playerFxConfig.boostIntensity}")
            appendLine("input_source=${_uiState.value.inputSourceLabel}")
            appendLine("input_preferred_device_set=${_uiState.value.inputPreferredDeviceSet}")
            appendLine("input_routed_device=${_uiState.value.inputRoutedDevice}")
            appendLine("input_processing=${_uiState.value.inputProcessingSummary}")
            appendLine("selected_mic_id=${_uiState.value.selectedMicId ?: "auto"}")
            appendLine("effective_mic=${effective?.displayName ?: "none"}")
            appendLine("effective_mic_type=${effective?.typeLabel ?: "n/a"}")
            appendLine("effective_mic_recommended=${effective?.recommended ?: false}")
            appendLine("effective_mic_warning=${effective?.warning ?: ""}")
            appendLine("rms_db=${_uiState.value.rmsDb}")
            appendLine("peak_db=${_uiState.value.peakDb}")
            appendLine("headroom_db=${_uiState.value.headroomDb}")
            appendLine("balance.duration_sec=${_uiState.value.balanceDurationSec}")
            appendLine("balance.peak_zone=${_uiState.value.balanceDecisionLabel ?: ""}")
            appendLine("balance.recommendation=${_uiState.value.balanceRecommendationText ?: ""}")
            appendLine("elapsed_ms=${_uiState.value.elapsedMs}")
            appendLine("recording_path=${recordingPath ?: ""}")
            if (entry != null) {
                appendLine("test_duration_sec=${entry.durationSec}")
                appendLine("test_result_rms_db=${entry.rmsDb}")
                appendLine("test_result_peak_db=${entry.peakDb}")
            }
            extraLines.forEach { appendLine(it) }
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

    private fun exportSingleWavToDownloads(
        source: File,
        displayName: String,
        relativePath: String = "${Environment.DIRECTORY_DOWNLOADS}/Band Recorder"
    ): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
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
    }

    private fun importPostProcessSourceInternal(uri: Uri): ImportedPostProcessSource? {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val displayName = queryDisplayName(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null))
            ?: "imported_audio"
        val imported = importAudioSource(
            context = app,
            uri = uri,
            displayName = displayName,
            mimeType = resolver.getType(uri)
        ) ?: return null
        return ImportedPostProcessSource(
            file = imported.file,
            displayName = imported.displayName,
            kind = imported.kind,
            durationMs = imported.durationMs,
            sampleRateHz = imported.sampleRateHz,
            channels = imported.channels
        )
    }

    private fun queryDisplayName(cursor: Cursor?): String? {
        cursor ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return it.getString(index)
        }
    }

    private fun exportPostProcessedFiles(
        sourceFile: File,
        analysis: WavAnalysisResult,
        mode: PostProcessMode,
        sourceDisplayName: String
    ): String? {
        val baseName = sourceDisplayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "processed" }
        val editedRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/Band Recorder/Fichiers édités/$baseName"
        val tempDir = File(getApplication<Application>().cacheDir, "post_process_exports").apply {
            mkdirs()
        }
        val generated = mutableListOf<File>()
        val message = when (mode) {
            PostProcessMode.CLEAN_SINGLE_FILE -> {
                val outFile = File(tempDir, "${baseName}_cleaned.wav")
                com.bandrecorder.app.writeCleanedWav(sourceFile, analysis.info, analysis.segments, outFile)
                generated += outFile
                if (exportSingleWavToDownloads(outFile, outFile.name, editedRelativePath)) {
                    "Export traité: ${outFile.name}"
                } else {
                    null
                }
            }

            PostProcessMode.SPLIT_MULTIPLE_TRACKS -> {
                analysis.segments.forEachIndexed { index, segment ->
                    val outFile = File(tempDir, "${baseName}_track_${"%02d".format(Locale.US, index + 1)}.wav")
                    com.bandrecorder.app.writeWavSegment(sourceFile, analysis.info, segment, outFile)
                    generated += outFile
                }
                val exported = generated.count { exportSingleWavToDownloads(it, it.name, editedRelativePath) }
                if (exported == generated.size && exported > 0) {
                    "Export traité: $exported piste(s)"
                } else {
                    null
                }
            }
        }
        generated.forEach { runCatching { it.delete() } }
        return message
    }

    private fun framesToMs(sampleRate: Int, frame: Int): Long {
        if (sampleRate <= 0) return 0L
        return (frame.toLong() * 1000L) / sampleRate.toLong()
    }

    private fun buildPostProcessEnvelopePreview(
        windows: List<WavEnvelopeWindow>,
        durationMs: Long,
        windowMs: Long,
        maxPoints: Int = 1800
    ): List<PostProcessEnvelopePoint> {
        if (windows.isEmpty()) return emptyList()
        if (windows.size <= maxPoints) {
            return windows.mapIndexed { index, window ->
                val startMs = index * windowMs
                PostProcessEnvelopePoint(
                    startMs = startMs,
                    endMs = (startMs + windowMs).coerceAtMost(durationMs),
                    rmsDb = window.features.rmsDb,
                    peakNorm = window.features.peakNorm,
                    speechLikelihood = window.speechLikelihood
                )
            }
        }

        val chunkSize = ceil(windows.size.toDouble() / maxPoints.toDouble()).toInt().coerceAtLeast(1)
        return windows.chunked(chunkSize).mapIndexed { chunkIndex, chunk ->
            val startMs = chunkIndex * chunkSize.toLong() * windowMs
            val endMs = (startMs + (chunk.size.toLong() * windowMs)).coerceAtMost(durationMs)
            val avgRmsNorm = (chunk.sumOf { dbToNorm(it.features.rmsDb).toDouble() } / chunk.size.toDouble()).toFloat()
            PostProcessEnvelopePoint(
                startMs = startMs,
                endMs = endMs,
                rmsDb = normToDb(avgRmsNorm),
                peakNorm = chunk.maxOf { it.features.peakNorm },
                speechLikelihood = chunk.maxOf { it.speechLikelihood }
            )
        }
    }

    private fun dbToNorm(db: Float): Float = 10f.pow(db / 20f).coerceIn(0f, 1f)

    private fun normToDb(norm: Float): Float {
        val safeNorm = norm.coerceAtLeast(1e-6f)
        return (20.0 * log10(safeNorm.toDouble())).toFloat()
    }

    private fun schedulePostProcessReanalysis() {
        if (postProcessSourceFile == null) return
        postProcessReanalyzeJob?.cancel()
        postProcessReanalyzeJob = viewModelScope.launch {
            delay(250)
            analyzePostProcessSource()
        }
    }

    private fun markPostProcessPreviewDirty() {
        if (postProcessSourceFile == null) return
        _uiState.update {
            it.copy(
                postProcessIsAnalyzing = true,
                postProcessStatusMessage = "Recalcul de l'aperçu..."
            )
        }
    }

    private fun pushLiveFeatures(features: SignalFeatures) {
        liveDetectionHistory.addLast(features)
        while (liveDetectionHistory.size > 320) {
            liveDetectionHistory.removeFirst()
        }
    }

    private fun resetLiveSilenceState() {
        liveDetectionHistory.clear()
        liveDetectorInSilence = false
    }

    private fun readWindowRmsDb(
        raf: RandomAccessFile,
        info: WavInfo,
        frameBytes: Int,
        startFrame: Int,
        endFrame: Int
    ): Float {
        val frameCount = (endFrame - startFrame).coerceAtLeast(0)
        if (frameCount <= 0) return -90f
        val bytesToRead = frameCount * frameBytes
        val buffer = ByteArray(bytesToRead)
        raf.seek(info.dataOffset + (startFrame.toLong() * frameBytes.toLong()))
        val read = raf.read(buffer, 0, buffer.size).coerceAtLeast(0)
        if (read <= 1) return -90f

        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < read) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val norm = sample / 32768.0
            sumSquares += norm * norm
            samples++
            i += 2
        }
        if (samples <= 0) return -90f
        val rms = kotlin.math.sqrt((sumSquares / samples).coerceAtLeast(1e-12))
        return (20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-6))).toFloat()
    }

    private fun writeSegmentWav(
        sourceFile: File,
        info: WavInfo,
        startFrame: Int,
        endFrame: Int,
        outFile: File
    ) {
        val frameBytes = info.channels * 2
        val dataSize = ((endFrame - startFrame).coerceAtLeast(0) * frameBytes).coerceAtLeast(0)
        if (dataSize <= 0) return
        outFile.parentFile?.mkdirs()

        RandomAccessFile(sourceFile, "r").use { src ->
            FileOutputStream(outFile).use { out ->
                out.write(buildWavHeader(dataSize, info.sampleRate, info.channels))
                val start = info.dataOffset + (startFrame.toLong() * frameBytes.toLong())
                src.seek(start)
                val buffer = ByteArray(8192)
                var remaining = dataSize
                while (remaining > 0) {
                    val toRead = minOf(buffer.size, remaining)
                    val read = src.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun readWavInfo(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44L) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            val read = raf.read(header)
            if (read < 44) return null
            val sampleRate = readIntLE(header, 24)
            val channels = readShortLE(header, 22)
            val bitsPerSample = readShortLE(header, 34)
            val headerDataSize = readIntLE(header, 40)
            if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
            val fileDataSize = (file.length() - 44L).coerceAtLeast(0L).toInt()
            val dataSize = headerDataSize.coerceAtMost(fileDataSize).coerceAtLeast(0)
            return WavInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                dataOffset = 44L,
                dataSize = dataSize
            )
        }
    }

    private fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 16 / 8
        val totalDataLen = 36 + dataSize
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, channels)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, channels * 2)
        writeShortLE(header, 34, 16)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, dataSize)
        return header
    }

    private fun readIntLE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Int
    )

    private fun refreshCurrentSessionSegments() {
        val sessionBase = activeSessionBaseName ?: _uiState.value.currentSessionName
        if (sessionBase.isNullOrBlank()) {
            _uiState.update { it.copy(currentSessionSegments = emptyList()) }
            return
        }
        val merged = (queryDownloadsSessionSegments(sessionBase) + queryAppPrivateSessionSegments(sessionBase))
            .distinct()
            .sortedBy { RecordingFileNaming.segmentSortKey(it) }
            .map { RecordingFileNaming.userVisibleTitle(it) }
        _uiState.update {
            it.copy(
                currentSessionName = sessionBase,
                currentSessionSegments = merged
            )
        }
    }

    private fun queryDownloadsSessionSegments(sessionBase: String): List<String> {
        val resolver = getApplication<Application>().contentResolver
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${sessionBase}_morceau_%.wav", "%Band Recorder%")
        val out = mutableListOf<String>()
        runCatching {
            resolver.query(filesUri, projection, selection, args, null)?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val fileName = c.getString(nameCol) ?: continue
                    out += fileName
                }
            }
        }
        return out
    }

    private fun queryAppPrivateSessionSegments(sessionBase: String): List<String> {
        val root = getApplication<Application>()
            .getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: getApplication<Application>().filesDir
        val folder = File(root, "band_recordings")
        if (!folder.exists()) return emptyList()
        return folder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) == "wav" }
            ?.map { it.name }
            ?.filter { it.startsWith("${sessionBase}_morceau_") }
            ?.toList()
            ?: emptyList()
    }

    private data class ImportedPostProcessSource(
        val file: File,
        val displayName: String,
        val kind: ImportedAudioKind,
        val durationMs: Long,
        val sampleRateHz: Int?,
        val channels: Int?
    )

    override fun onCleared() {
        runCatching { engine.stopRecording() }
        AnalysisForegroundService.stop(getApplication())
        postProcessSourceFile?.let { runCatching { it.delete() } }
        postProcessWorkingFile?.takeIf { it != postProcessSourceFile }?.let { runCatching { it.delete() } }
        postProcessReanalyzeJob?.cancel()
        RecordingForegroundService.stop(getApplication())
        super.onCleared()
    }

    private fun observeAnalysisService() {
        viewModelScope.launch {
            AnalysisCoordinator.state.collectLatest { state ->
                when (state) {
                    AnalysisServiceState.Idle -> Unit
                    is AnalysisServiceState.Running -> {
                        if (postProcessSourceFile?.absolutePath != state.sourcePath) return@collectLatest
                        _uiState.update {
                            it.copy(
                                postProcessIsAnalyzing = true,
                                postProcessStatusMessage = state.message
                            )
                        }
                    }
                    is AnalysisServiceState.Failed -> {
                        if (postProcessSourceFile?.absolutePath != state.sourcePath) return@collectLatest
                        postProcessAnalysis = null
                        _uiState.update {
                            it.copy(
                                postProcessIsAnalyzing = false,
                                postProcessSegments = emptyList(),
                                postProcessCuts = emptyList(),
                                postProcessEnvelope = emptyList(),
                                postProcessStatusMessage = state.message
                            )
                        }
                    }
                    is AnalysisServiceState.Completed -> {
                        if (postProcessSourceFile?.absolutePath != state.sourcePath) return@collectLatest
                        postProcessWorkingFile = File(state.workingFilePath)
                        applyCompletedAnalysis(state.analysis)
                    }
                }
            }
        }
    }

    private fun applyCompletedAnalysis(analysis: WavAnalysisResult) {
        postProcessAnalysis = analysis
        val durationMs = framesToMs(analysis.info.sampleRate, analysis.info.dataSize / (analysis.info.channels * 2))
        val segments = analysis.segments.mapIndexed { index, segment ->
            val startMs = framesToMs(analysis.info.sampleRate, segment.startFrame)
            val endMs = framesToMs(analysis.info.sampleRate, segment.endFrame)
            PostProcessSegmentPreview(
                index = index + 1,
                startMs = startMs,
                endMs = endMs,
                durationMs = (endMs - startMs).coerceAtLeast(0L)
            )
        }
        val cuts = analysis.segments.dropLast(1).mapIndexed { index, segment ->
            val cutMs = framesToMs(analysis.info.sampleRate, segment.endFrame)
            PostProcessCutPreview(
                index = index + 1,
                cutMs = cutMs,
                beforeStartMs = (cutMs - 3_000L).coerceAtLeast(0L),
                beforeEndMs = cutMs,
                afterStartMs = cutMs,
                afterEndMs = (cutMs + 3_000L).coerceAtMost(durationMs)
            )
        }
        val windowMs = ((1_000L * (analysis.info.sampleRate / 20).coerceAtLeast(1)) / analysis.info.sampleRate).coerceAtLeast(1L)
        val envelope = buildPostProcessEnvelopePreview(
            windows = analysis.envelope,
            durationMs = durationMs,
            windowMs = windowMs
        )
        _uiState.update {
            it.copy(
                postProcessIsAnalyzing = false,
                postProcessSourceSampleRateHz = analysis.info.sampleRate,
                postProcessSourceChannels = analysis.info.channels,
                postProcessSegments = segments,
                postProcessCuts = cuts,
                postProcessEnvelope = envelope,
                silenceAutoThresholdDb = analysis.thresholds.autoThresholdDb,
                silenceEffectiveThresholdDb = analysis.thresholds.enterThresholdDb,
                silenceDetectionSummary = "Auto ${"%.1f".format(analysis.thresholds.autoThresholdDb)} dBFS, réglé ${"%.1f".format(analysis.thresholds.enterThresholdDb)} dBFS",
                postProcessStatusMessage = if (segments.isEmpty()) {
                    "Aucun segment utile trouvé avec ces réglages."
                } else {
                    "${segments.size} segment(s) trouvé(s)."
                }
            )
        }
    }

    private fun observeRecordingService() {
        viewModelScope.launch {
            RecordingCoordinator.state.collectLatest { state ->
                when (state) {
                    RecordingServiceState.Idle -> Unit
                    is RecordingServiceState.Failed -> {
                        resetLiveSilenceState()
                        _uiState.update {
                            it.copy(
                                isRecording = false,
                                isSilenceDetected = false,
                                leftVu = 0f,
                                rightVu = 0f,
                                status = state.message
                            )
                        }
                    }
                    is RecordingServiceState.Stopping -> {
                        activeSessionBaseName = state.sessionBaseName ?: activeSessionBaseName
                        _uiState.update {
                            it.copy(
                                isRecording = false,
                                isSilenceDetected = false,
                                currentSessionName = state.sessionBaseName ?: it.currentSessionName,
                                lastOutputPath = state.uiOutputPath ?: it.lastOutputPath,
                                status = state.message
                            )
                        }
                    }
                    is RecordingServiceState.Running -> {
                        activeSessionBaseName = state.sessionBaseName
                        applyRecordingStatus(state.status, state.sessionBaseName, state.uiOutputPath)
                    }
                    is RecordingServiceState.Completed -> {
                        activeSessionBaseName = state.sessionBaseName ?: activeSessionBaseName
                        resetLiveSilenceState()
                        _uiState.update {
                            it.copy(
                                isRecording = false,
                                isSilenceDetected = false,
                                leftVu = 0f,
                                rightVu = 0f,
                                currentSessionName = state.sessionBaseName ?: it.currentSessionName,
                                lastOutputPath = state.lastOutputPath ?: it.lastOutputPath,
                                status = state.message
                            )
                        }
                        refreshPlayerRecordings()
                        refreshCurrentSessionSegments()
                        if (_uiState.value.diagnosticModeEnabled) {
                            exportDiagnosticReport(
                                event = "recording_stop",
                                entry = null,
                                recordingPath = state.lastOutputPath ?: _uiState.value.lastOutputPath
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyRecordingStatus(
        status: com.bandrecorder.core.audio.RecordingStatus,
        sessionBaseName: String,
        uiOutputPath: String
    ) {
        val liveFeatures = SignalFeatures(
            rmsDb = status.rmsDb,
            peakNorm = dbToNorm(status.peakDb),
            lowBandRatio = status.lowBandRatio,
            midBandRatio = status.midBandRatio,
            highBandRatio = status.highBandRatio,
            zeroCrossingRate = status.zeroCrossingRate,
            transientDensity = status.transientDensity,
            crestDb = status.crestDb
        )
        pushLiveFeatures(liveFeatures)
        val liveThresholds = computeAdaptiveThresholds(liveDetectionHistory.toList(), _uiState.value.silenceThresholdDb)
        val silenceDecision = evaluateSilence(liveFeatures, liveThresholds, currentlyInSilence = liveDetectorInSilence)
        val thresholdCrossed = silenceDecision.isSilence
        if (!thresholdCrossed) {
            silenceStartElapsedMs = null
        } else if (silenceStartElapsedMs == null) {
            silenceStartElapsedMs = status.elapsedMs
        }
        val silenceElapsedMs = if (thresholdCrossed) {
            status.elapsedMs - (silenceStartElapsedMs ?: status.elapsedMs)
        } else {
            0L
        }
        val silenceDetected = thresholdCrossed &&
            (status.elapsedMs - (silenceStartElapsedMs ?: status.elapsedMs) >= silenceVisualHoldMs)

        _uiState.update {
            val newLeftVu = normalizeVu(status.leftPeakDb)
            val newRightVu = normalizeVu(status.rightPeakDb)
            val smoothedLeftVu = smoothVu(previous = it.leftVu, newValue = newLeftVu)
            val smoothedRightVu = smoothVu(previous = it.rightVu, newValue = newRightVu)
            it.copy(
                isRecording = true,
                rmsDb = status.rmsDb,
                peakDb = status.peakDb,
                leftVu = smoothedLeftVu,
                rightVu = smoothedRightVu,
                headroomDb = status.headroomDb,
                elapsedMs = status.elapsedMs,
                isSilenceDetected = silenceDetected,
                currentSessionName = sessionBaseName,
                currentSessionSegments = emptyList(),
                lastOutputPath = uiOutputPath,
                status = "Recording",
                silenceAutoThresholdDb = liveThresholds.autoThresholdDb,
                silenceEffectiveThresholdDb = liveThresholds.enterThresholdDb,
                silenceDetectionSummary = "Auto ${"%.1f".format(liveThresholds.autoThresholdDb)} dBFS, réglé ${"%.1f".format(liveThresholds.enterThresholdDb)} dBFS",
                inputSourceLabel = status.inputDiagnostics?.sourceLabel ?: it.inputSourceLabel,
                inputPreferredDeviceSet = status.inputDiagnostics?.preferredDeviceSet ?: it.inputPreferredDeviceSet,
                inputRoutedDevice = status.inputDiagnostics?.let { diag ->
                    val type = diag.routedDeviceTypeLabel ?: "type?"
                    val id = diag.routedDeviceId?.toString() ?: "unknown"
                    val addr = diag.routedDeviceAddress ?: "n/a"
                    "$type#$id ($addr)"
                } ?: it.inputRoutedDevice,
                inputProcessingSummary = status.inputDiagnostics?.let { diag ->
                    "AGC=${diag.agcEnabled ?: "n/a"} NS=${diag.nsEnabled ?: "n/a"} AEC=${diag.aecEnabled ?: "n/a"}"
                } ?: it.inputProcessingSummary,
                isGlobalDspAvailable = status.dspAvailable,
                isGlobalDspRunning = status.dspRunning,
                isGlobalDspCpuGuardActive = status.dspCpuGuardActive,
                isNearClipping = status.peakDb > -3f,
                compGainReductionDb = status.dspCompGainReductionDb,
                deEsserGainReductionDb = status.dspDeEsserGainReductionDb,
                globalDspLimiterHits = status.dspLimiterHits,
                dspStatusMessage = status.dspStatusMessage,
                globalBalanceConfig = it.globalBalanceConfig.copy(dspOutputMode = status.dspOutputMode).bounded()
            )
        }
        liveDetectorInSilence = silenceDecision.isSilence
        val shouldAutoStopOnSilence = _uiState.value.ignoreSilenceEnabled &&
            !_uiState.value.splitOnSilenceEnabled &&
            silenceElapsedMs >= (_uiState.value.silenceDurationSec * 1000L) &&
            !autoStopOnSilenceRequested
        if (shouldAutoStopOnSilence) {
            autoStopOnSilenceRequested = true
            _uiState.update { it.copy(status = "Blanc prolongé détecté, arrêt...") }
            stopRecording()
        }
        if (status.dspCpuGuardActive && !lastCpuGuardState && _uiState.value.diagnosticModeEnabled) {
            exportDiagnosticReport(
                event = "global_balance_runtime_guard_triggered",
                entry = null,
                recordingPath = _uiState.value.lastOutputPath,
                extraLines = buildGlobalDspDiagnosticLines()
            )
        }
        lastCpuGuardState = status.dspCpuGuardActive
    }
}
