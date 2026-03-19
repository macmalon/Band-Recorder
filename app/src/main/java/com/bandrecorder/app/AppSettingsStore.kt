package com.bandrecorder.app

import android.app.Application
import android.content.Context
import com.bandrecorder.core.audio.DspOutputMode
import com.bandrecorder.core.audio.GlobalBalanceConfig
import com.bandrecorder.core.audio.MixProfile

enum class StorageLocation {
    DOWNLOADS,
    APP_PRIVATE
}

data class AppSettings(
    val storageLocation: StorageLocation = StorageLocation.DOWNLOADS,
    val ignoreSilenceEnabled: Boolean = false,
    val splitOnSilenceEnabled: Boolean = false,
    val silenceThresholdDb: Float = 0f,
    val silenceDurationSec: Int = 8,
    val recordingInputGainDb: Float = 0f,
    val selectedMicId: Int? = null,
    val diagnosticMode: Boolean = true,
    val showAdvancedInternals: Boolean = false,
    val testDurationSec: Int = 5,
    val balanceDurationSec: Int = 30,
    val stereoModeRequested: Boolean = false,
    val stereoChannelsSwapped: Boolean = false,
    val uiVintageV2Enabled: Boolean = false,
    val globalBalanceConfig: GlobalBalanceConfig = GlobalBalanceConfig(),
    val playerFxConfig: PlayerFxConfig = PlayerFxConfig(),
    val favoriteRecordingKeys: Set<String> = emptySet()
)

class AppSettingsStore(app: Application) {
    private val prefs = app.getSharedPreferences("band_recorder_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val storageRaw = prefs.getString(KEY_STORAGE_LOCATION, StorageLocation.DOWNLOADS.name)
        val storage = runCatching { StorageLocation.valueOf(storageRaw ?: StorageLocation.DOWNLOADS.name) }
            .getOrDefault(StorageLocation.DOWNLOADS)
        val ignoreSilenceEnabled = prefs.getBoolean(KEY_IGNORE_SILENCE_ENABLED, false)
        val splitOnSilenceEnabled = prefs.getBoolean(KEY_SPLIT_ON_SILENCE_ENABLED, false) && ignoreSilenceEnabled
        val silenceThresholdDb = if (prefs.contains(KEY_SILENCE_THRESHOLD_OFFSET_DB)) {
            prefs.getFloat(KEY_SILENCE_THRESHOLD_OFFSET_DB, 0f).coerceIn(-18f, 18f)
        } else {
            0f
        }
        val silenceDurationSec = prefs.getInt(KEY_SILENCE_DURATION_SEC, 8).coerceIn(2, 20)
        val recordingInputGainDb = prefs.getFloat(KEY_RECORDING_INPUT_GAIN_DB, 0f).coerceIn(-24f, 24f)
        val micId = if (prefs.contains(KEY_MIC_ID)) prefs.getInt(KEY_MIC_ID, -1).takeIf { it >= 0 } else null
        val diagnosticMode = prefs.getBoolean(KEY_DIAGNOSTIC_MODE, true)
        val showAdvanced = prefs.getBoolean(KEY_SHOW_ADVANCED_INTERNALS, false)
        val testDurationSec = prefs.getInt(KEY_TEST_DURATION_SEC, 5).coerceIn(5, 30)
        val balanceDurationSec = normalizeBalanceDuration(prefs.getInt(KEY_BALANCE_DURATION_SEC, 30))
        val stereoRequested = prefs.getBoolean(KEY_STEREO_MODE_REQUESTED, false)
        val stereoChannelsSwapped = prefs.getBoolean(KEY_STEREO_CHANNELS_SWAPPED, false)
        val uiVintageV2Enabled = prefs.getBoolean(KEY_UI_VINTAGE_V2_ENABLED, false)
        val dspOutput = runCatching {
            DspOutputMode.valueOf(
                prefs.getString(KEY_DSP_OUTPUT_MODE, DspOutputMode.MONITORING_ONLY.name)
                    ?: DspOutputMode.MONITORING_ONLY.name
            )
        }.getOrDefault(DspOutputMode.MONITORING_ONLY)
        val profile = runCatching {
            MixProfile.valueOf(
                prefs.getString(KEY_MIX_PROFILE, MixProfile.ROCK_POP_BALANCED.name)
                    ?: MixProfile.ROCK_POP_BALANCED.name
            )
        }.getOrDefault(MixProfile.ROCK_POP_BALANCED)
        val globalBalanceConfig = GlobalBalanceConfig(
            autoBalanceEnabled = prefs.getBoolean(KEY_AUTO_BALANCE, true),
            compressionEnabled = prefs.getBoolean(KEY_COMPRESSOR, true),
            deEsserEnabled = prefs.getBoolean(KEY_DE_ESSER, true),
            dspOutputMode = dspOutput,
            mixProfile = profile,
            eqIntensity = prefs.getFloat(KEY_EQ_INTENSITY, 0.55f),
            compIntensity = prefs.getFloat(KEY_COMP_INTENSITY, 0.4f),
            deEsserIntensity = prefs.getFloat(KEY_DE_ESSER_INTENSITY, 0.45f),
            compressorThresholdDb = prefs.getFloat(KEY_COMP_THRESHOLD_DB, -18f),
            compressorRatio = prefs.getFloat(KEY_COMP_RATIO, 2.1f),
            deEsserFrequencyHz = prefs.getFloat(KEY_DE_ESSER_FREQ_HZ, 6500f),
            deEsserWidthHz = prefs.getFloat(KEY_DE_ESSER_WIDTH_HZ, 2800f),
            limiterCeilingDb = prefs.getFloat(KEY_LIMITER_CEILING_DB, -1.5f),
            outputTrimDb = prefs.getFloat(KEY_OUTPUT_TRIM_DB, 0f)
        ).bounded()
        val playerPreset = runCatching {
            PlayerFxPreset.valueOf(
                prefs.getString(KEY_PLAYER_FX_PRESET, PlayerFxPreset.ROCK.name) ?: PlayerFxPreset.ROCK.name
            )
        }.getOrDefault(PlayerFxPreset.ROCK)
        val playerFxConfig = PlayerFxConfig(
            preset = playerPreset,
            eqEnabled = prefs.getBoolean(KEY_PLAYER_FX_EQ_ENABLED, true),
            compressionEnabled = prefs.getBoolean(KEY_PLAYER_FX_COMP_ENABLED, true),
            deEsserEnabled = prefs.getBoolean(KEY_PLAYER_FX_DEESSER_ENABLED, true),
            eqIntensity = prefs.getFloat(KEY_PLAYER_FX_EQ_INTENSITY, 0.45f),
            compressionIntensity = prefs.getFloat(KEY_PLAYER_FX_COMP_INTENSITY, 0.35f),
            deEsserIntensity = prefs.getFloat(KEY_PLAYER_FX_DEESSER_INTENSITY, 0.35f),
            boostIntensity = prefs.getFloat(KEY_PLAYER_FX_BOOST_INTENSITY, 0f)
        ).bounded()
        val favorites = prefs.getStringSet(KEY_PLAYER_FAVORITES, emptySet())?.toSet() ?: emptySet()
        return AppSettings(
            storageLocation = storage,
            ignoreSilenceEnabled = ignoreSilenceEnabled,
            splitOnSilenceEnabled = splitOnSilenceEnabled,
            silenceThresholdDb = silenceThresholdDb,
            silenceDurationSec = silenceDurationSec,
            recordingInputGainDb = recordingInputGainDb,
            selectedMicId = micId,
            diagnosticMode = diagnosticMode,
            showAdvancedInternals = showAdvanced,
            testDurationSec = testDurationSec,
            balanceDurationSec = balanceDurationSec,
            stereoModeRequested = stereoRequested,
            stereoChannelsSwapped = stereoChannelsSwapped,
            uiVintageV2Enabled = uiVintageV2Enabled,
            globalBalanceConfig = globalBalanceConfig,
            playerFxConfig = playerFxConfig,
            favoriteRecordingKeys = favorites
        )
    }

    fun setStorageLocation(location: StorageLocation) {
        prefs.edit().putString(KEY_STORAGE_LOCATION, location.name).apply()
    }

    fun setIgnoreSilenceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IGNORE_SILENCE_ENABLED, enabled).apply()
    }

    fun setSplitOnSilenceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPLIT_ON_SILENCE_ENABLED, enabled).apply()
    }

    fun setSilenceThresholdDb(value: Float) {
        prefs.edit().putFloat(KEY_SILENCE_THRESHOLD_OFFSET_DB, value.coerceIn(-18f, 18f)).apply()
    }

    fun setSilenceDurationSec(value: Int) {
        prefs.edit().putInt(KEY_SILENCE_DURATION_SEC, value.coerceIn(2, 20)).apply()
    }

    fun setRecordingInputGainDb(value: Float) {
        prefs.edit().putFloat(KEY_RECORDING_INPUT_GAIN_DB, value.coerceIn(-24f, 24f)).apply()
    }

    fun setSelectedMicId(micId: Int?) {
        prefs.edit().apply {
            if (micId == null) remove(KEY_MIC_ID) else putInt(KEY_MIC_ID, micId)
        }.apply()
    }

    fun setDiagnosticMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIAGNOSTIC_MODE, enabled).apply()
    }

    fun setShowAdvancedInternals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ADVANCED_INTERNALS, enabled).apply()
    }

    fun setTestDurationSec(seconds: Int) {
        prefs.edit().putInt(KEY_TEST_DURATION_SEC, seconds.coerceIn(5, 30)).apply()
    }

    fun setBalanceDurationSec(seconds: Int) {
        prefs.edit().putInt(KEY_BALANCE_DURATION_SEC, normalizeBalanceDuration(seconds)).apply()
    }

    fun setStereoModeRequested(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEREO_MODE_REQUESTED, enabled).apply()
    }

    fun setStereoChannelsSwapped(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEREO_CHANNELS_SWAPPED, enabled).apply()
    }

    fun setUiVintageV2Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UI_VINTAGE_V2_ENABLED, enabled).apply()
    }

    fun setGlobalBalanceConfig(config: GlobalBalanceConfig) {
        val bounded = config.bounded()
        prefs.edit()
            .putBoolean(KEY_AUTO_BALANCE, bounded.autoBalanceEnabled)
            .putBoolean(KEY_COMPRESSOR, bounded.compressionEnabled)
            .putBoolean(KEY_DE_ESSER, bounded.deEsserEnabled)
            .putString(KEY_DSP_OUTPUT_MODE, bounded.dspOutputMode.name)
            .putString(KEY_MIX_PROFILE, bounded.mixProfile.name)
            .putFloat(KEY_EQ_INTENSITY, bounded.eqIntensity)
            .putFloat(KEY_COMP_INTENSITY, bounded.compIntensity)
            .putFloat(KEY_DE_ESSER_INTENSITY, bounded.deEsserIntensity)
            .putFloat(KEY_COMP_THRESHOLD_DB, bounded.compressorThresholdDb)
            .putFloat(KEY_COMP_RATIO, bounded.compressorRatio)
            .putFloat(KEY_DE_ESSER_FREQ_HZ, bounded.deEsserFrequencyHz)
            .putFloat(KEY_DE_ESSER_WIDTH_HZ, bounded.deEsserWidthHz)
            .putFloat(KEY_LIMITER_CEILING_DB, bounded.limiterCeilingDb)
            .putFloat(KEY_OUTPUT_TRIM_DB, bounded.outputTrimDb)
            .apply()
    }

    fun setPlayerFxConfig(config: PlayerFxConfig) {
        val bounded = config.bounded()
        prefs.edit()
            .putString(KEY_PLAYER_FX_PRESET, bounded.preset.name)
            .putBoolean(KEY_PLAYER_FX_EQ_ENABLED, bounded.eqEnabled)
            .putBoolean(KEY_PLAYER_FX_COMP_ENABLED, bounded.compressionEnabled)
            .putBoolean(KEY_PLAYER_FX_DEESSER_ENABLED, bounded.deEsserEnabled)
            .putFloat(KEY_PLAYER_FX_EQ_INTENSITY, bounded.eqIntensity)
            .putFloat(KEY_PLAYER_FX_COMP_INTENSITY, bounded.compressionIntensity)
            .putFloat(KEY_PLAYER_FX_DEESSER_INTENSITY, bounded.deEsserIntensity)
            .putFloat(KEY_PLAYER_FX_BOOST_INTENSITY, bounded.boostIntensity)
            .apply()
    }

    fun setFavoriteRecordingKeys(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_PLAYER_FAVORITES, keys).apply()
    }

    private fun normalizeBalanceDuration(seconds: Int): Int = when (seconds) {
        30, 60, 90 -> seconds
        else -> 30
    }

    private companion object {
        const val KEY_STORAGE_LOCATION = "storage_location"
        const val KEY_IGNORE_SILENCE_ENABLED = "ignore_silence_enabled"
        const val KEY_SPLIT_ON_SILENCE_ENABLED = "split_on_silence_enabled"
        const val KEY_SILENCE_THRESHOLD_OFFSET_DB = "silence_threshold_offset_db"
        const val KEY_SILENCE_DURATION_SEC = "silence_duration_sec"
        const val KEY_RECORDING_INPUT_GAIN_DB = "recording_input_gain_db"
        const val KEY_MIC_ID = "selected_mic_id"
        const val KEY_DIAGNOSTIC_MODE = "diagnostic_mode"
        const val KEY_SHOW_ADVANCED_INTERNALS = "show_advanced_internals"
        const val KEY_TEST_DURATION_SEC = "test_duration_sec"
        const val KEY_BALANCE_DURATION_SEC = "balance_duration_sec"
        const val KEY_STEREO_MODE_REQUESTED = "stereo_mode_requested"
        const val KEY_STEREO_CHANNELS_SWAPPED = "stereo_channels_swapped"
        const val KEY_UI_VINTAGE_V2_ENABLED = "ui_vintage_v2_enabled"
        const val KEY_AUTO_BALANCE = "global_auto_balance"
        const val KEY_COMPRESSOR = "global_compression"
        const val KEY_DE_ESSER = "global_de_esser"
        const val KEY_DSP_OUTPUT_MODE = "global_dsp_output_mode"
        const val KEY_MIX_PROFILE = "global_mix_profile"
        const val KEY_EQ_INTENSITY = "global_eq_intensity"
        const val KEY_COMP_INTENSITY = "global_comp_intensity"
        const val KEY_DE_ESSER_INTENSITY = "global_de_esser_intensity"
        const val KEY_COMP_THRESHOLD_DB = "global_comp_threshold_db"
        const val KEY_COMP_RATIO = "global_comp_ratio"
        const val KEY_DE_ESSER_FREQ_HZ = "global_de_esser_freq_hz"
        const val KEY_DE_ESSER_WIDTH_HZ = "global_de_esser_width_hz"
        const val KEY_LIMITER_CEILING_DB = "global_limiter_ceiling_db"
        const val KEY_OUTPUT_TRIM_DB = "global_output_trim_db"
        const val KEY_PLAYER_FX_PRESET = "player_fx_preset"
        const val KEY_PLAYER_FX_EQ_ENABLED = "player_fx_eq_enabled"
        const val KEY_PLAYER_FX_COMP_ENABLED = "player_fx_comp_enabled"
        const val KEY_PLAYER_FX_DEESSER_ENABLED = "player_fx_deesser_enabled"
        const val KEY_PLAYER_FX_EQ_INTENSITY = "player_fx_eq_intensity"
        const val KEY_PLAYER_FX_COMP_INTENSITY = "player_fx_comp_intensity"
        const val KEY_PLAYER_FX_DEESSER_INTENSITY = "player_fx_deesser_intensity"
        const val KEY_PLAYER_FX_BOOST_INTENSITY = "player_fx_boost_intensity"
        const val KEY_PLAYER_FAVORITES = "player_favorites"
    }
}
