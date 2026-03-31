package com.bandrecorder.app

import com.bandrecorder.core.audio.RecordingProcessingConfig

data class RecordingProcessingSettings(
    val limiterEnabled: Boolean = true,
    val hpfEnabled: Boolean = true
) {
    fun toCoreConfig(): RecordingProcessingConfig = RecordingProcessingConfig(
        limiterEnabled = limiterEnabled,
        hpfEnabled = hpfEnabled
    )
}
