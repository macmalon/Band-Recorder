package com.bandrecorder.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed class AnalysisServiceState {
    data object Idle : AnalysisServiceState()

    data class Running(
        val sourcePath: String,
        val message: String,
        val progressPercent: Int
    ) : AnalysisServiceState()

    data class Completed(
        val sourcePath: String,
        val workingFilePath: String?,
        val analysis: WavAnalysisResult
    ) : AnalysisServiceState()

    data class Failed(
        val sourcePath: String,
        val message: String
    ) : AnalysisServiceState()
}

internal object AnalysisCoordinator {
    private val _state = MutableStateFlow<AnalysisServiceState>(AnalysisServiceState.Idle)
    val state: StateFlow<AnalysisServiceState> = _state.asStateFlow()

    fun publish(state: AnalysisServiceState) {
        _state.value = state
    }
}
