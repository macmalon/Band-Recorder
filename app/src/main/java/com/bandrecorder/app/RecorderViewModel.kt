package com.bandrecorder.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bandrecorder.core.audio.AndroidAudioRecordEngine
import com.bandrecorder.core.audio.AudioLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecorderUiState(
    val isRecording: Boolean = false,
    val rmsDb: Float = -90f,
    val peakDb: Float = -90f,
    val headroomDb: Float = 90f,
    val status: String = "Ready"
)

class RecorderViewModel : ViewModel() {
    private val engine = AndroidAudioRecordEngine()

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engine.levelFlow().collect { level: AudioLevel ->
                _uiState.update {
                    it.copy(
                        rmsDb = level.rmsDb,
                        peakDb = level.peakDb,
                        headroomDb = level.headroomDb
                    )
                }
            }
        }
    }

    fun toggleRecording() {
        val nowRecording = !_uiState.value.isRecording
        if (nowRecording) {
            engine.start()
        } else {
            engine.stop()
        }
        _uiState.update {
            it.copy(
                isRecording = nowRecording,
                status = if (nowRecording) "Recording" else "Stopped"
            )
        }
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }
}
