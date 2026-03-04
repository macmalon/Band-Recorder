package com.bandrecorder.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class AndroidAudioRecordEngine : AudioRecordEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val levels = MutableStateFlow(AudioLevel(rmsDb = -90f, peakDb = -90f, headroomDb = 90f))

    private var audioRecord: AudioRecord? = null
    private var readerJob: Job? = null

    override fun start(sampleRate: Int) {
        if (audioRecord != null) return

        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)

        val source = MediaRecorder.AudioSource.UNPROCESSED
        val record = AudioRecord(source, sampleRate, channelMask, encoding, bufferSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()

        readerJob = scope.launch {
            val buffer = ShortArray(bufferSize / 2)
            while (isActive && audioRecord != null) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    levels.value = analyze(buffer, read)
                }
            }
        }
    }

    override fun stop() {
        readerJob?.cancel()
        readerJob = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
    }

    override fun levelFlow(): Flow<AudioLevel> = levels.asStateFlow()

    private fun analyze(samples: ShortArray, size: Int): AudioLevel {
        var sum = 0.0
        var peak = 0.0

        for (i in 0 until size) {
            val norm = samples[i] / Short.MAX_VALUE.toDouble()
            sum += norm * norm
            val a = abs(norm)
            if (a > peak) peak = a
        }

        val rms = sqrt((sum / size).coerceAtLeast(1e-12))
        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
        val peakDb = (20.0 * log10(peak.coerceAtLeast(1e-6))).toFloat()
        val headroom = -peakDb

        return AudioLevel(rmsDb = rmsDb, peakDb = peakDb, headroomDb = headroom)
    }
}
