package com.bandrecorder.core.audio

import kotlinx.coroutines.flow.Flow

interface AudioRecordEngine {
    fun start(sampleRate: Int = 48_000)
    fun stop()
    fun levelFlow(): Flow<AudioLevel>
}

data class AudioLevel(
    val rmsDb: Float,
    val peakDb: Float,
    val headroomDb: Float
)
