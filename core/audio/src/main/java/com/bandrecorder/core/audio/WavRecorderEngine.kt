package com.bandrecorder.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class CalibrationResult(
    val rmsDb: Float,
    val peakDb: Float,
    val recommendedGainDb: Float
)

data class CalibrationProgress(
    val progressPercent: Int,
    val rmsDb: Float,
    val peakDb: Float
)

data class RecordingStatus(
    val isRecording: Boolean = false,
    val rmsDb: Float = -90f,
    val peakDb: Float = -90f,
    val headroomDb: Float = 90f,
    val elapsedMs: Long = 0L,
    val outputPath: String? = null,
    val inputDiagnostics: InputDiagnostics? = null,
    val dspAvailable: Boolean = true,
    val dspRunning: Boolean = false,
    val dspCpuGuardActive: Boolean = false,
    val dspOutputMode: DspOutputMode = DspOutputMode.MONITORING_ONLY,
    val dspCompGainReductionDb: Float = 0f,
    val dspDeEsserGainReductionDb: Float = 0f,
    val dspLimiterHits: Int = 0,
    val dspStatusMessage: String = "Bypass"
)

data class InputDiagnostics(
    val source: Int,
    val sourceLabel: String,
    val preferredDeviceSet: Boolean,
    val routedDeviceId: Int?,
    val routedDeviceType: Int?,
    val routedDeviceTypeLabel: String?,
    val routedDeviceAddress: String?,
    val agcAvailable: Boolean,
    val agcEnabled: Boolean?,
    val nsAvailable: Boolean,
    val nsEnabled: Boolean?,
    val aecAvailable: Boolean,
    val aecEnabled: Boolean?
)

data class StereoProbeResult(
    val supported: Boolean,
    val actualChannelCount: Int,
    val message: String,
    val diagnostics: InputDiagnostics? = null
)

data class StereoWindowMeasurement(
    val leftRmsDb: Float,
    val rightRmsDb: Float,
    val actualChannelCount: Int,
    val framesRead: Int,
    val diagnostics: InputDiagnostics? = null
)

class WavRecorderEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val status = MutableStateFlow(RecordingStatus())

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var outputFile: File? = null
    private var dataBytesWritten: Int = 0
    private var sampleRateHz: Int = 48_000
    private var channelCount: Int = 1
    private var swapStereoChannels: Boolean = false
    private var inputDiagnostics: InputDiagnostics? = null
    private var activeBalanceConfig: GlobalBalanceConfig = GlobalBalanceConfig()
    private var liveDspEngine: LiveDspEngine? = null

    private data class AudioRecordInit(
        val record: AudioRecord,
        val source: Int
    )

    fun statusFlow(): StateFlow<RecordingStatus> = status.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun runCalibration(
        durationSeconds: Int = 30,
        sampleRate: Int = 48_000,
        preferredDevice: AudioDeviceInfo? = null,
        onProgress: (CalibrationProgress) -> Unit
    ): CalibrationResult? = withContext(Dispatchers.Default) {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return@withContext null

        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)
        val init = createAudioRecord(sampleRate, channelMask, encoding, bufferSize) ?: return@withContext null
        val record = init.record
        if (preferredDevice != null) {
            runCatching { record.setPreferredDevice(preferredDevice) }
        }

        var peak = 0.0
        var sumSquares = 0.0
        var totalSamples = 0
        val targetSamples = sampleRate * durationSeconds

        val buffer = ShortArray(bufferSize / 2)
        record.startRecording()
        try {
            while (totalSamples < targetSamples) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val norm = buffer[i] / Short.MAX_VALUE.toDouble()
                    sumSquares += norm * norm
                    val a = abs(norm)
                    if (a > peak) peak = a
                }
                totalSamples += read
                val progress = ((totalSamples.toFloat() / targetSamples.toFloat()) * 100f).toInt().coerceIn(0, 100)
                val currentRms = sqrt((sumSquares / totalSamples.coerceAtLeast(1)).coerceAtLeast(1e-12))
                val currentRmsDb = (20.0 * log10(currentRms.coerceAtLeast(1e-6))).toFloat()
                val currentPeakDb = (20.0 * log10(peak.coerceAtLeast(1e-6))).toFloat()
                onProgress(
                    CalibrationProgress(
                        progressPercent = progress,
                        rmsDb = currentRmsDb,
                        peakDb = currentPeakDb
                    )
                )
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        val rms = sqrt((sumSquares / totalSamples.coerceAtLeast(1)).coerceAtLeast(1e-12))
        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
        val peakDb = (20.0 * log10(peak.coerceAtLeast(1e-6))).toFloat()

        val targetPeakDb = -12f
        val recommended = (targetPeakDb - peakDb).coerceIn(-18f, 18f)

        CalibrationResult(rmsDb = rmsDb, peakDb = peakDb, recommendedGainDb = recommended)
    }

    @SuppressLint("MissingPermission")
    suspend fun probeStereoCapability(
        sampleRate: Int = 48_000,
        preferredDevice: AudioDeviceInfo? = null
    ): StereoProbeResult = withContext(Dispatchers.Default) {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            return@withContext StereoProbeResult(false, 1, "Stereo min buffer unsupported")
        }
        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)
        val init = createAudioRecord(sampleRate, channelMask, encoding, bufferSize)
            ?: return@withContext StereoProbeResult(false, 1, "Stereo AudioRecord init failed")
        val record = init.record

        val preferredSet = if (preferredDevice != null) {
            runCatching { record.setPreferredDevice(preferredDevice) }.getOrDefault(false)
        } else {
            false
        }

        val actual = record.channelCount
        val readBuffer = ShortArray(4096)

        val result = runCatching {
            record.startRecording()
            val routed = runCatching { record.routedDevice }.getOrNull()

            var totalPairs = 0
            var leftAbs = 0.0
            var rightAbs = 0.0
            var diffAbs = 0.0
            var equalCount = 0
            var successfulReads = 0

            repeat(6) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) return@repeat
                successfulReads++

                if (actual >= 2) {
                    val pairs = read / 2
                    for (i in 0 until pairs) {
                        val l = readBuffer[i * 2].toDouble()
                        val r = readBuffer[i * 2 + 1].toDouble()
                        leftAbs += abs(l)
                        rightAbs += abs(r)
                        diffAbs += abs(l - r)
                        if (l == r) equalCount++
                    }
                    totalPairs += pairs
                }
            }

            val readsOk = successfulReads > 0
            if (!readsOk || actual < 2 || totalPairs == 0) {
                val msg = buildString {
                    append("Stereo not confirmed (reads=")
                    append(successfulReads)
                    append(", channels=")
                    append(actual)
                    append(", preferred_set=")
                    append(preferredSet)
                    append(", routed_device=")
                    append(routed?.id ?: "unknown")
                    append(")")
                }
                return@runCatching StereoProbeResult(false, max(actual, 1), msg)
            }

            val avgL = leftAbs / totalPairs.toDouble()
            val avgR = rightAbs / totalPairs.toDouble()
            val avgDiff = diffAbs / totalPairs.toDouble()
            val equalRatio = equalCount.toDouble() / totalPairs.toDouble()
            val diffVsLevel = avgDiff / max(1.0, (avgL + avgR) * 0.5)

            val likelyDualMono = equalRatio > 0.985 || diffVsLevel < 0.015
            val supported = !likelyDualMono

            val msg = buildString {
                if (supported) append("Stereo confirmed")
                else append("2ch detected but likely dual-mono")
                append(" (reads=")
                append(successfulReads)
                append(", channels=")
                append(actual)
                append(", diff_ratio=")
                append("%.3f".format(diffVsLevel))
                append(", equal_ratio=")
                append("%.3f".format(equalRatio))
                append(", preferred_set=")
                append(preferredSet)
                append(", routed_device=")
                append(routed?.id ?: "unknown")
                append(")")
            }
            StereoProbeResult(supported, actual, msg)
        }.getOrElse {
            StereoProbeResult(false, max(actual, 1), "Stereo probe failed: ${it.message ?: "unknown error"}")
        }

        val diagnostics = buildInputDiagnostics(
            record = record,
            source = init.source,
            preferredSet = preferredSet
        )

        runCatching { record.stop() }
        record.release()
        result.copy(diagnostics = diagnostics)
    }

    @SuppressLint("MissingPermission")
    suspend fun measureStereoWindow(
        durationSeconds: Int = 3,
        sampleRate: Int = 48_000,
        preferredDevice: AudioDeviceInfo? = null
    ): StereoWindowMeasurement? = withContext(Dispatchers.Default) {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return@withContext null

        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)
        val init = createAudioRecord(sampleRate, channelMask, encoding, bufferSize) ?: return@withContext null
        val record = init.record
        if (preferredDevice != null) {
            runCatching { record.setPreferredDevice(preferredDevice) }
        }
        val actual = record.channelCount
        if (actual < 2) {
            record.release()
            return@withContext null
        }

        val targetFrames = sampleRate * durationSeconds
        val readBuffer = ShortArray(bufferSize / 2)
        var framesRead = 0
        var leftSumSquares = 0.0
        var rightSumSquares = 0.0
        var diagnostics: InputDiagnostics? = null

        record.startRecording()
        try {
            while (framesRead < targetFrames) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue
                val usable = read - (read % 2)
                var i = 0
                while (i < usable) {
                    val left = readBuffer[i] / Short.MAX_VALUE.toDouble()
                    val right = readBuffer[i + 1] / Short.MAX_VALUE.toDouble()
                    leftSumSquares += left * left
                    rightSumSquares += right * right
                    framesRead++
                    i += 2
                    if (framesRead >= targetFrames) break
                }
            }
        } finally {
            diagnostics = buildInputDiagnostics(
                record = record,
                source = init.source,
                preferredSet = preferredDevice != null
            )
            runCatching { record.stop() }
            record.release()
        }

        if (framesRead <= 0) return@withContext null
        val leftRmsDb = rmsDbFromSumSquares(leftSumSquares, framesRead)
        val rightRmsDb = rmsDbFromSumSquares(rightSumSquares, framesRead)
        StereoWindowMeasurement(
            leftRmsDb = leftRmsDb,
            rightRmsDb = rightRmsDb,
            actualChannelCount = actual,
            framesRead = framesRead,
            diagnostics = diagnostics
        )
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        output: File,
        sampleRate: Int = 48_000,
        preferredDevice: AudioDeviceInfo? = null,
        requestedChannelCount: Int = 1,
        swapStereoChannels: Boolean = false,
        balanceConfig: GlobalBalanceConfig = GlobalBalanceConfig()
    ) {
        if (recordJob != null) return

        val channels = if (requestedChannelCount >= 2) 2 else 1
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return

        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)
        val init = createAudioRecord(sampleRate, channelMask, encoding, bufferSize) ?: return
        val record = init.record
        if (preferredDevice != null) {
            runCatching { record.setPreferredDevice(preferredDevice) }
        }

        output.parentFile?.mkdirs()
        outputFile = output
        sampleRateHz = sampleRate
        channelCount = channels
        this.swapStereoChannels = swapStereoChannels
        activeBalanceConfig = balanceConfig.bounded()
        liveDspEngine = LiveDspEngine(sampleRateHz, channelCount, activeBalanceConfig)
        dataBytesWritten = 0
        inputDiagnostics = buildInputDiagnostics(
            record = record,
            source = init.source,
            preferredSet = preferredDevice != null
        )

        val stream = FileOutputStream(output)
        stream.write(ByteArray(44))

        audioRecord = record
        record.startRecording()
        val startMs = System.currentTimeMillis()

        status.value = RecordingStatus(
            isRecording = true,
            outputPath = output.absolutePath,
            inputDiagnostics = inputDiagnostics,
            dspAvailable = true,
            dspOutputMode = activeBalanceConfig.dspOutputMode
        )

        recordJob = scope.launch {
            val rawShorts = ShortArray(bufferSize / 2)
            val processedShorts = ShortArray(bufferSize / 2)
            val bytes = ByteArray(rawShorts.size * 2)

            try {
                while (isActive) {
                    val read = record.read(rawShorts, 0, rawShorts.size)
                    if (read <= 0) continue

                    val dspStats = liveDspEngine?.processInterleaved(rawShorts, read, processedShorts) ?: DspRuntimeStats()
                    val effectiveForMeters = if (dspStats.active) processedShorts else rawShorts
                    val level = analyze(effectiveForMeters, read)
                    val elapsed = System.currentTimeMillis() - startMs
                    status.value = RecordingStatus(
                        isRecording = true,
                        rmsDb = level.rmsDb,
                        peakDb = level.peakDb,
                        headroomDb = level.headroomDb,
                        elapsedMs = elapsed,
                        outputPath = output.absolutePath,
                        inputDiagnostics = inputDiagnostics,
                        dspAvailable = true,
                        dspRunning = dspStats.active && !dspStats.bypassed,
                        dspCpuGuardActive = dspStats.cpuGuardActive,
                        dspOutputMode = activeBalanceConfig.dspOutputMode,
                        dspCompGainReductionDb = dspStats.compGainReductionDb,
                        dspDeEsserGainReductionDb = dspStats.deEsserGainReductionDb,
                        dspLimiterHits = dspStats.limiterHits,
                        dspStatusMessage = dspStats.statusMessage
                    )

                    val writeSource = when {
                        dspStats.active && activeBalanceConfig.dspOutputMode == DspOutputMode.MONITORING_AND_RECORDING -> processedShorts
                        else -> rawShorts
                    }

                    var bi = 0
                    if (channelCount == 2 && this@WavRecorderEngine.swapStereoChannels) {
                        val usable = read - (read % 2)
                        var i = 0
                        while (i < usable) {
                            val left = writeSource[i].toInt()
                            val right = writeSource[i + 1].toInt()
                            // Write R then L to swap output channels.
                            bytes[bi++] = (right and 0xFF).toByte()
                            bytes[bi++] = ((right shr 8) and 0xFF).toByte()
                            bytes[bi++] = (left and 0xFF).toByte()
                            bytes[bi++] = ((left shr 8) and 0xFF).toByte()
                            i += 2
                        }
                    } else {
                        for (i in 0 until read) {
                            val s = writeSource[i].toInt()
                            bytes[bi++] = (s and 0xFF).toByte()
                            bytes[bi++] = ((s shr 8) and 0xFF).toByte()
                        }
                    }
                    stream.write(bytes, 0, bi)
                    dataBytesWritten += bi
                }
            } finally {
                runCatching { stream.flush() }
                runCatching { stream.close() }
                finalizeWavHeader(output, dataBytesWritten, sampleRateHz, channelCount)
                runCatching { record.stop() }
                record.release()
                audioRecord = null
                recordJob = null
                liveDspEngine = null
                status.value = RecordingStatus(
                    isRecording = false,
                    rmsDb = levelOrDefault(status.value.rmsDb),
                    peakDb = levelOrDefault(status.value.peakDb),
                    headroomDb = levelOrDefault(status.value.headroomDb),
                    elapsedMs = status.value.elapsedMs,
                    outputPath = output.absolutePath,
                    inputDiagnostics = inputDiagnostics,
                    dspAvailable = true,
                    dspRunning = false,
                    dspCpuGuardActive = status.value.dspCpuGuardActive,
                    dspOutputMode = activeBalanceConfig.dspOutputMode,
                    dspCompGainReductionDb = status.value.dspCompGainReductionDb,
                    dspDeEsserGainReductionDb = status.value.dspDeEsserGainReductionDb,
                    dspLimiterHits = status.value.dspLimiterHits,
                    dspStatusMessage = "Bypass"
                )
            }
        }
    }

    fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
    }

    private fun levelOrDefault(v: Float): Float = if (v.isFinite()) v else -90f

    private fun createAudioRecord(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSize: Int
    ): AudioRecordInit? {
        val sources = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            val record = AudioRecord(source, sampleRate, channelMask, encoding, bufferSize)
            if (record.state == AudioRecord.STATE_INITIALIZED) return AudioRecordInit(record, source)
            record.release()
        }
        return null
    }

    private fun analyze(samples: ShortArray, size: Int): AudioLevel {
        var sum = 0.0
        var peak = 0.0

        for (i in 0 until size) {
            val norm = samples[i] / Short.MAX_VALUE.toDouble()
            sum += norm * norm
            val a = abs(norm)
            if (a > peak) peak = a
        }

        val rms = sqrt((sum / size.coerceAtLeast(1)).coerceAtLeast(1e-12))
        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
        val peakDb = (20.0 * log10(peak.coerceAtLeast(1e-6))).toFloat()

        return AudioLevel(rmsDb = rmsDb, peakDb = peakDb, headroomDb = -peakDb)
    }

    private fun rmsDbFromSumSquares(sumSquares: Double, sampleCount: Int): Float {
        val rms = sqrt((sumSquares / sampleCount.coerceAtLeast(1)).coerceAtLeast(1e-12))
        return (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
    }

    private fun buildInputDiagnostics(
        record: AudioRecord,
        source: Int,
        preferredSet: Boolean
    ): InputDiagnostics {
        val routed = runCatching { record.routedDevice }.getOrNull()
        val sessionId = runCatching { record.audioSessionId }.getOrDefault(0)

        val agcAvailable = AutomaticGainControl.isAvailable()
        val nsAvailable = NoiseSuppressor.isAvailable()
        val aecAvailable = AcousticEchoCanceler.isAvailable()

        val agcEnabled = effectEnabled(sessionId, agcAvailable) { AutomaticGainControl.create(sessionId) }
        val nsEnabled = effectEnabled(sessionId, nsAvailable) { NoiseSuppressor.create(sessionId) }
        val aecEnabled = effectEnabled(sessionId, aecAvailable) { AcousticEchoCanceler.create(sessionId) }

        return InputDiagnostics(
            source = source,
            sourceLabel = sourceLabel(source),
            preferredDeviceSet = preferredSet,
            routedDeviceId = routed?.id,
            routedDeviceType = routed?.type,
            routedDeviceTypeLabel = routed?.type?.let { deviceTypeLabel(it) },
            routedDeviceAddress = routed?.address?.takeIf { it.isNotBlank() },
            agcAvailable = agcAvailable,
            agcEnabled = agcEnabled,
            nsAvailable = nsAvailable,
            nsEnabled = nsEnabled,
            aecAvailable = aecAvailable,
            aecEnabled = aecEnabled
        )
    }

    private fun <T : android.media.audiofx.AudioEffect> effectEnabled(
        sessionId: Int,
        available: Boolean,
        creator: () -> T?
    ): Boolean? {
        if (!available || sessionId <= 0) return null
        return runCatching {
            creator()?.let { effect ->
                val enabled = effect.enabled
                effect.release()
                enabled
            }
        }.getOrNull()
    }

    private fun sourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source_$source"
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        else -> "TYPE_$type"
    }

    private fun finalizeWavHeader(file: File, dataSize: Int, sampleRate: Int, channels: Int) {
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

        val raf = RandomAccessFile(file, "rw")
        raf.seek(0)
        raf.write(header)
        raf.close()
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
}
