package com.bandrecorder.app

import android.app.Application
import android.content.Context

enum class StorageLocation {
    DOWNLOADS,
    APP_PRIVATE
}

data class AppSettings(
    val storageLocation: StorageLocation = StorageLocation.DOWNLOADS,
    val selectedMicId: Int? = null
)

class AppSettingsStore(app: Application) {
    private val prefs = app.getSharedPreferences("band_recorder_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val storageRaw = prefs.getString(KEY_STORAGE_LOCATION, StorageLocation.DOWNLOADS.name)
        val storage = runCatching { StorageLocation.valueOf(storageRaw ?: StorageLocation.DOWNLOADS.name) }
            .getOrDefault(StorageLocation.DOWNLOADS)
        val micId = if (prefs.contains(KEY_MIC_ID)) prefs.getInt(KEY_MIC_ID, -1).takeIf { it >= 0 } else null
        return AppSettings(storageLocation = storage, selectedMicId = micId)
    }

    fun setStorageLocation(location: StorageLocation) {
        prefs.edit().putString(KEY_STORAGE_LOCATION, location.name).apply()
    }

    fun setSelectedMicId(micId: Int?) {
        prefs.edit().apply {
            if (micId == null) remove(KEY_MIC_ID) else putInt(KEY_MIC_ID, micId)
        }.apply()
    }

    private companion object {
        const val KEY_STORAGE_LOCATION = "storage_location"
        const val KEY_MIC_ID = "selected_mic_id"
    }
}

