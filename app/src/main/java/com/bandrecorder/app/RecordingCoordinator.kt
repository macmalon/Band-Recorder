package com.bandrecorder.app

import com.bandrecorder.core.audio.RecordingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed class RecordingServiceState {
    data object Idle : RecordingServiceState()

    data class Running(
        val status: RecordingStatus,
        val sessionBaseName: String,
        val uiOutputPath: String
    ) : RecordingServiceState()

    data class Stopping(
        val sessionBaseName: String?,
        val uiOutputPath: String?,
        val message: String
    ) : RecordingServiceState()

    data class Completed(
        val sessionBaseName: String?,
        val lastOutputPath: String?,
        val message: String,
        val segmentCount: Int
    ) : RecordingServiceState()

    data class Failed(
        val message: String
    ) : RecordingServiceState()
}

internal object RecordingCoordinator {
    private val _state = MutableStateFlow<RecordingServiceState>(RecordingServiceState.Idle)
    val state: StateFlow<RecordingServiceState> = _state.asStateFlow()

    fun publish(state: RecordingServiceState) {
        _state.value = state
    }
}
