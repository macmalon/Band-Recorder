package com.bandrecorder.app

import android.app.Application
import android.content.Context

enum class StorageLocation {
    DOWNLOADS,
    APP_PRIVATE
}

data class AppSettings(
    val storageLocation: StorageLocation = StorageLocation.DOWNLOADS,
    val selectedMicId: Int? = null,
    val diagnosticMode: Boolean = true,
    val showAdvancedInternals: Boolean = false,
    val testDurationSec: Int = 5,
    val stereoModeRequested: Boolean = false,
    val stereoChannelsSwapped: Boolean = false
)

class AppSettingsStore(app: Application) {
    private val prefs = app.getSharedPreferences("band_recorder_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val storageRaw = prefs.getString(KEY_STORAGE_LOCATION, StorageLocation.DOWNLOADS.name)
        val storage = runCatching { StorageLocation.valueOf(storageRaw ?: StorageLocation.DOWNLOADS.name) }
            .getOrDefault(StorageLocation.DOWNLOADS)
        val micId = if (prefs.contains(KEY_MIC_ID)) prefs.getInt(KEY_MIC_ID, -1).takeIf { it >= 0 } else null
        val diagnosticMode = prefs.getBoolean(KEY_DIAGNOSTIC_MODE, true)
        val showAdvanced = prefs.getBoolean(KEY_SHOW_ADVANCED_INTERNALS, false)
        val testDurationSec = prefs.getInt(KEY_TEST_DURATION_SEC, 5).coerceIn(5, 30)
        val stereoRequested = prefs.getBoolean(KEY_STEREO_MODE_REQUESTED, false)
        val stereoChannelsSwapped = prefs.getBoolean(KEY_STEREO_CHANNELS_SWAPPED, false)
        return AppSettings(
            storageLocation = storage,
            selectedMicId = micId,
            diagnosticMode = diagnosticMode,
            showAdvancedInternals = showAdvanced,
            testDurationSec = testDurationSec,
            stereoModeRequested = stereoRequested,
            stereoChannelsSwapped = stereoChannelsSwapped
        )
    }

    fun setStorageLocation(location: StorageLocation) {
        prefs.edit().putString(KEY_STORAGE_LOCATION, location.name).apply()
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

    fun setStereoModeRequested(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEREO_MODE_REQUESTED, enabled).apply()
    }

    fun setStereoChannelsSwapped(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEREO_CHANNELS_SWAPPED, enabled).apply()
    }

    private companion object {
        const val KEY_STORAGE_LOCATION = "storage_location"
        const val KEY_MIC_ID = "selected_mic_id"
        const val KEY_DIAGNOSTIC_MODE = "diagnostic_mode"
        const val KEY_SHOW_ADVANCED_INTERNALS = "show_advanced_internals"
        const val KEY_TEST_DURATION_SEC = "test_duration_sec"
        const val KEY_STEREO_MODE_REQUESTED = "stereo_mode_requested"
        const val KEY_STEREO_CHANNELS_SWAPPED = "stereo_channels_swapped"
    }
}
