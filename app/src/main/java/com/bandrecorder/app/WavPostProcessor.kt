package com.bandrecorder.app

import com.bandrecorder.core.audio.AdaptiveSilenceThresholds
import com.bandrecorder.core.audio.SignalFeatures
import com.bandrecorder.core.audio.applyThresholdOffset
import com.bandrecorder.core.audio.computeAdaptiveThresholds
import com.bandrecorder.core.audio.evaluateSilence
import com.bandrecorder.core.audio.extractSignalFeatures
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

internal data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long
)

internal data class WavFrameSegment(
    val startFrame: Long,
    val endFrame: Long
)

internal data class WavAnalysisResult(
    val info: WavInfo,
    val segments: List<WavFrameSegment>,
    val envelope: List<WavEnvelopeWindow>,
    val thresholds: AdaptiveSilenceThresholds
)

internal data class WavEnvelopeWindow(
    val features: SignalFeatures,
    val speechLikelihood: Float,
    val isSilenceCandidate: Boolean
)

private const val MIN_MUSICAL_SEGMENT_SEC = 50
private const val WAV_COPY_BUFFER_BYTES = 256 * 1024
private const val WAV_ANALYSIS_BUFFER_BYTES = 256 * 1024

internal fun analyzeWavBySilence(
    sourceFile: File,
    silenceThresholdDb: Float,
    silenceDurationSec: Int,
    onProgress: ((Int) -> Unit)? = null
): WavAnalysisResult? {
    val info = readWavInfo(sourceFile) ?: return null
    if (info.bitsPerSample != 16) return null
    val frameBytes = info.channels * 2
    val totalFrames = (info.dataSize / frameBytes).coerceAtLeast(0)
    if (totalFrames <= 0) return null

    val windows = readSignalWindowsSequentially(sourceFile, info, onProgress) ?: return null

    return analyzeSignalWindows(
        info = info,
        windows = windows,
        silenceThresholdDb = silenceThresholdDb,
        silenceDurationSec = silenceDurationSec
    ).also { onProgress?.invoke(100) }
}

internal fun analyzeSignalWindows(
    info: WavInfo,
    windows: List<SignalFeatures>,
    silenceThresholdDb: Float,
    silenceDurationSec: Int
): WavAnalysisResult? {
    if (info.bitsPerSample != 16) return null
    val frameBytes = info.channels * 2
    val totalFrames = (info.dataSize / frameBytes).coerceAtLeast(0)
    if (totalFrames <= 0 || windows.isEmpty()) return null

    val windowFrames = (info.sampleRate / 20).coerceAtLeast(1).toLong()
    val cutFrames = (info.sampleRate.toLong() * silenceDurationSec).coerceAtLeast(windowFrames)
    val minSegmentFrames = (info.sampleRate.toLong() * MIN_MUSICAL_SEGMENT_SEC).coerceAtLeast(windowFrames)
    val resumeConfirmFrames = (info.sampleRate.toLong() * 4L).coerceAtLeast(windowFrames)
    val resumePrerollFrames = (info.sampleRate.toLong() * 6L).coerceAtLeast(resumeConfirmFrames)
    val autoThresholds = computeAdaptiveThresholds(windows)
    val thresholds = applyThresholdOffset(autoThresholds, silenceThresholdDb)
    val envelope = mutableListOf<WavEnvelopeWindow>()

    val segments = mutableListOf<WavFrameSegment>()
    var inSignal = false
    var segmentStart = 0L
    var silenceRunStart: Long? = null
    var signalCandidateStart: Long? = null
    var requireConfirmedResume = false
    var resumeStartFloor = 0L

    windows.forEachIndexed { index, features ->
        val windowStart = index * windowFrames
        val windowEnd = (windowStart + windowFrames).coerceAtMost(totalFrames)
        val decision = evaluateSilence(features, thresholds, currentlyInSilence = !inSignal)
        envelope += WavEnvelopeWindow(
            features = features,
            speechLikelihood = decision.speechLikelihood,
            isSilenceCandidate = decision.isSilence
        )

        if (decision.isSilence) {
            if (inSignal && silenceRunStart == null) {
                silenceRunStart = windowStart
            }
            val silenceStart = silenceRunStart
            if (inSignal && silenceStart != null && (windowEnd - silenceStart) >= cutFrames) {
                val end = (silenceStart + cutFrames).coerceAtMost(totalFrames)
                if (end - segmentStart >= minSegmentFrames) {
                    segments += WavFrameSegment(startFrame = segmentStart, endFrame = end)
                }
                inSignal = false
                silenceRunStart = null
                signalCandidateStart = null
                requireConfirmedResume = true
                resumeStartFloor = end
            }
        } else {
            if (!inSignal) {
                if (!requireConfirmedResume) {
                    segmentStart = windowStart
                    inSignal = true
                } else {
                    val candidateStart = signalCandidateStart ?: windowStart.also {
                        signalCandidateStart = it
                    }
                    if ((windowEnd - candidateStart) >= resumeConfirmFrames) {
                        val confirmedPoint = candidateStart + resumeConfirmFrames
                        segmentStart = (confirmedPoint - resumePrerollFrames).coerceAtLeast(resumeStartFloor)
                        inSignal = true
                        signalCandidateStart = null
                    }
                }
            }
            silenceRunStart = null
        }

        if (decision.isSilence && !inSignal) {
            signalCandidateStart = null
        }
    }

    if (inSignal) {
        val end = totalFrames
        if (end - segmentStart >= minSegmentFrames) {
            segments += WavFrameSegment(startFrame = segmentStart, endFrame = end)
        }
    }

    return WavAnalysisResult(info = info, segments = segments, envelope = envelope, thresholds = thresholds)
}

internal fun writeWavSegment(
    sourceFile: File,
    info: WavInfo,
    segment: WavFrameSegment,
    outFile: File,
    onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
) {
    val frameBytes = info.channels * 2
    val dataSize = ((segment.endFrame - segment.startFrame).coerceAtLeast(0) * frameBytes).coerceAtLeast(0)
    if (dataSize <= 0 || dataSize > Int.MAX_VALUE.toLong()) return
    outFile.parentFile?.mkdirs()

    RandomAccessFile(sourceFile, "r").use { src ->
        FileOutputStream(outFile).use { out ->
            out.write(buildWavHeader(dataSize.toInt(), info.sampleRate, info.channels))
            val start = info.dataOffset + (segment.startFrame.toLong() * frameBytes.toLong())
            src.seek(start)
            val buffer = ByteArray(WAV_COPY_BUFFER_BYTES)
            var remaining = dataSize
            var written = 0L
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = src.read(buffer, 0, toRead)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
                written += read
                onProgress?.invoke(written, dataSize)
            }
        }
    }
}

internal fun writeCleanedWav(
    sourceFile: File,
    info: WavInfo,
    segments: List<WavFrameSegment>,
    outFile: File,
    onProgress: ((writtenBytes: Long, totalBytes: Long) -> Unit)? = null
) {
    val frameBytes = info.channels * 2
    val dataSize = segments.sumOf { (it.endFrame - it.startFrame).coerceAtLeast(0) * frameBytes }
    if (dataSize <= 0 || dataSize > Int.MAX_VALUE.toLong()) return
    outFile.parentFile?.mkdirs()

    RandomAccessFile(sourceFile, "r").use { src ->
        FileOutputStream(outFile).use { out ->
            out.write(buildWavHeader(dataSize.toInt(), info.sampleRate, info.channels))
            val buffer = ByteArray(WAV_COPY_BUFFER_BYTES)
            var written = 0L
            segments.forEach { segment ->
                var remaining = ((segment.endFrame - segment.startFrame).coerceAtLeast(0) * frameBytes).coerceAtLeast(0)
                val start = info.dataOffset + (segment.startFrame.toLong() * frameBytes.toLong())
                src.seek(start)
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = src.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                    written += read
                    onProgress?.invoke(written, dataSize)
                }
            }
        }
    }
}

internal fun readWavInfo(file: File): WavInfo? {
    if (!file.exists() || file.length() < 44L) return null
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(44)
        val read = raf.read(header)
        if (read < 44) return null
        val sampleRate = readIntLE(header, 24)
        val channels = readShortLE(header, 22)
        val bitsPerSample = readShortLE(header, 34)
        val headerDataSize = readIntLE(header, 40).toLong() and 0xFFFF_FFFFL
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        val fileDataSize = (file.length() - 44L).coerceAtLeast(0L)
        val dataSize = headerDataSize.coerceAtMost(fileDataSize).coerceAtLeast(0)
        return WavInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataOffset = 44L,
            dataSize = dataSize
        )
    }
}

internal fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
    val byteRate = sampleRate * channels * 16 / 8
    val totalDataLen = 36 + dataSize
    return ByteArray(44).apply {
        this[0] = 'R'.code.toByte()
        this[1] = 'I'.code.toByte()
        this[2] = 'F'.code.toByte()
        this[3] = 'F'.code.toByte()
        writeIntLE(this, 4, totalDataLen)
        this[8] = 'W'.code.toByte()
        this[9] = 'A'.code.toByte()
        this[10] = 'V'.code.toByte()
        this[11] = 'E'.code.toByte()
        this[12] = 'f'.code.toByte()
        this[13] = 'm'.code.toByte()
        this[14] = 't'.code.toByte()
        this[15] = ' '.code.toByte()
        writeIntLE(this, 16, 16)
        writeShortLE(this, 20, 1)
        writeShortLE(this, 22, channels)
        writeIntLE(this, 24, sampleRate)
        writeIntLE(this, 28, byteRate)
        writeShortLE(this, 32, channels * 2)
        writeShortLE(this, 34, 16)
        this[36] = 'd'.code.toByte()
        this[37] = 'a'.code.toByte()
        this[38] = 't'.code.toByte()
        this[39] = 'a'.code.toByte()
        writeIntLE(this, 40, dataSize)
    }
}

private fun readSignalWindowsSequentially(
    sourceFile: File,
    info: WavInfo,
    onProgress: ((Int) -> Unit)? = null
): List<SignalFeatures>? {
    val frameBytes = info.channels * 2
    val totalFrames = (info.dataSize / frameBytes).coerceAtLeast(0L)
    if (totalFrames <= 0L) return null

    val windowFrames = (info.sampleRate / 20).coerceAtLeast(1)
    val windowSampleCapacity = windowFrames * info.channels
    val readBufferFrames = (WAV_ANALYSIS_BUFFER_BYTES / frameBytes).coerceAtLeast(windowFrames)
    val readBuffer = ByteArray(readBufferFrames * frameBytes)
    val windowSamples = ShortArray(windowSampleCapacity)
    val totalWindows = ((totalFrames + windowFrames - 1L) / windowFrames.toLong()).coerceAtLeast(1L)
    val windows = ArrayList<SignalFeatures>(totalWindows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    var samplesInWindow = 0
    var processedFrames = 0L
    var lastProgress = -1

    fun publishProgress() {
        val progress = ((processedFrames.coerceIn(0L, totalFrames) * 100L) / totalFrames).toInt().coerceIn(0, 100)
        if (progress != lastProgress) {
            lastProgress = progress
            onProgress?.invoke(progress)
        }
    }

    fun flushWindow() {
        if (samplesInWindow <= 0) return
        windows += extractSignalFeatures(windowSamples, samplesInWindow, info.channels, info.sampleRate)
        processedFrames += samplesInWindow / info.channels
        samplesInWindow = 0
        publishProgress()
    }

    RandomAccessFile(sourceFile, "r").use { raf ->
        raf.seek(info.dataOffset)
        var remainingFrames = totalFrames
        while (remainingFrames > 0L) {
            val framesToRead = minOf(readBufferFrames.toLong(), remainingFrames).toInt()
            val bytesToRead = framesToRead * frameBytes
            val read = raf.read(readBuffer, 0, bytesToRead)
            if (read <= 0) break
            val safeRead = read - (read % frameBytes)
            if (safeRead <= 0) break

            var byteIndex = 0
            while (byteIndex + 1 < safeRead) {
                val lo = readBuffer[byteIndex].toInt() and 0xFF
                val hi = readBuffer[byteIndex + 1].toInt()
                windowSamples[samplesInWindow++] = ((hi shl 8) or lo).toShort()
                byteIndex += 2
                if (samplesInWindow == windowSampleCapacity) {
                    flushWindow()
                }
            }

            remainingFrames -= safeRead / frameBytes
        }
    }

    flushWindow()
    if (windows.isEmpty()) return null
    onProgress?.invoke(100)
    return windows
}

private fun readIntLE(buffer: ByteArray, offset: Int): Int {
    return (buffer[offset].toInt() and 0xFF) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
        ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
        ((buffer[offset + 3].toInt() and 0xFF) shl 24)
}

private fun readShortLE(buffer: ByteArray, offset: Int): Int {
    return (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
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
