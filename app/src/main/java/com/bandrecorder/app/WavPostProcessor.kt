package com.bandrecorder.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt

internal data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Int
)

internal data class WavFrameSegment(
    val startFrame: Int,
    val endFrame: Int
)

internal data class WavAnalysisResult(
    val info: WavInfo,
    val segments: List<WavFrameSegment>,
    val envelope: List<WavEnvelopeWindow>
)

internal data class WavEnvelopeWindow(
    val rmsDb: Float,
    val peakNorm: Float
)

internal fun analyzeWavBySilence(
    sourceFile: File,
    silenceThresholdDb: Float,
    silenceDurationSec: Int
): WavAnalysisResult? {
    val info = readWavInfo(sourceFile) ?: return null
    if (info.bitsPerSample != 16) return null
    val frameBytes = info.channels * 2
    val totalFrames = (info.dataSize / frameBytes).coerceAtLeast(0)
    if (totalFrames <= 0) return null

    val windowFrames = (info.sampleRate / 20).coerceAtLeast(1)
    val cutFrames = (info.sampleRate * silenceDurationSec).coerceAtLeast(windowFrames)
    val minSegmentFrames = (info.sampleRate / 2).coerceAtLeast(1)
    val envelope = mutableListOf<WavEnvelopeWindow>()

    val segments = mutableListOf<WavFrameSegment>()
    RandomAccessFile(sourceFile, "r").use { raf ->
        var inSignal = false
        var segmentStart = 0
        var silenceRunStart: Int? = null
        var windowStart = 0

        while (windowStart < totalFrames) {
            val windowEnd = (windowStart + windowFrames).coerceAtMost(totalFrames)
            val stats = readWindowStats(raf, info, frameBytes, windowStart, windowEnd)
            envelope += WavEnvelopeWindow(rmsDb = stats.rmsDb, peakNorm = stats.peakNorm)
            val isSilent = stats.rmsDb <= silenceThresholdDb

            if (isSilent) {
                if (inSignal && silenceRunStart == null) {
                    silenceRunStart = windowStart
                }
                if (inSignal && silenceRunStart != null && (windowEnd - silenceRunStart) >= cutFrames) {
                    val end = (silenceRunStart + cutFrames).coerceAtMost(totalFrames)
                    if (end - segmentStart >= minSegmentFrames) {
                        segments += WavFrameSegment(startFrame = segmentStart, endFrame = end)
                    }
                    inSignal = false
                    silenceRunStart = null
                }
            } else {
                if (!inSignal) {
                    segmentStart = windowStart
                    inSignal = true
                }
                silenceRunStart = null
            }

            windowStart = windowEnd
        }

        if (inSignal) {
            val end = totalFrames
            if (end - segmentStart >= minSegmentFrames) {
                segments += WavFrameSegment(startFrame = segmentStart, endFrame = end)
            }
        }
    }

    return WavAnalysisResult(info = info, segments = segments, envelope = envelope)
}

internal fun writeWavSegment(
    sourceFile: File,
    info: WavInfo,
    segment: WavFrameSegment,
    outFile: File
) {
    val frameBytes = info.channels * 2
    val dataSize = ((segment.endFrame - segment.startFrame).coerceAtLeast(0) * frameBytes).coerceAtLeast(0)
    if (dataSize <= 0) return
    outFile.parentFile?.mkdirs()

    RandomAccessFile(sourceFile, "r").use { src ->
        FileOutputStream(outFile).use { out ->
            out.write(buildWavHeader(dataSize, info.sampleRate, info.channels))
            val start = info.dataOffset + (segment.startFrame.toLong() * frameBytes.toLong())
            src.seek(start)
            val buffer = ByteArray(8192)
            var remaining = dataSize
            while (remaining > 0) {
                val toRead = minOf(buffer.size, remaining)
                val read = src.read(buffer, 0, toRead)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

internal fun writeCleanedWav(
    sourceFile: File,
    info: WavInfo,
    segments: List<WavFrameSegment>,
    outFile: File
) {
    val frameBytes = info.channels * 2
    val dataSize = segments.sumOf { (it.endFrame - it.startFrame).coerceAtLeast(0) * frameBytes }
    if (dataSize <= 0) return
    outFile.parentFile?.mkdirs()

    RandomAccessFile(sourceFile, "r").use { src ->
        FileOutputStream(outFile).use { out ->
            out.write(buildWavHeader(dataSize, info.sampleRate, info.channels))
            val buffer = ByteArray(8192)
            segments.forEach { segment ->
                var remaining = ((segment.endFrame - segment.startFrame).coerceAtLeast(0) * frameBytes).coerceAtLeast(0)
                val start = info.dataOffset + (segment.startFrame.toLong() * frameBytes.toLong())
                src.seek(start)
                while (remaining > 0) {
                    val toRead = minOf(buffer.size, remaining)
                    val read = src.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
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
        val headerDataSize = readIntLE(header, 40)
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        val fileDataSize = (file.length() - 44L).coerceAtLeast(0L).toInt()
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

private data class WindowStats(
    val rmsDb: Float,
    val peakNorm: Float
)

private fun readWindowStats(
    raf: RandomAccessFile,
    info: WavInfo,
    frameBytes: Int,
    startFrame: Int,
    endFrame: Int
): WindowStats {
    val frameCount = (endFrame - startFrame).coerceAtLeast(0)
    if (frameCount <= 0) return WindowStats(rmsDb = -90f, peakNorm = 0f)
    val bytesToRead = frameCount * frameBytes
    val buffer = ByteArray(bytesToRead)
    raf.seek(info.dataOffset + (startFrame.toLong() * frameBytes.toLong()))
    val read = raf.read(buffer, 0, buffer.size).coerceAtLeast(0)
    if (read <= 1) return WindowStats(rmsDb = -90f, peakNorm = 0f)

    var sumSquares = 0.0
    var peakAbs = 0.0
    var samples = 0
    var i = 0
    while (i + 1 < read) {
        val lo = buffer[i].toInt() and 0xFF
        val hi = buffer[i + 1].toInt()
        val sample = ((hi shl 8) or lo).toShort().toInt()
        val norm = sample / 32768.0
        peakAbs = maxOf(peakAbs, kotlin.math.abs(norm))
        sumSquares += norm * norm
        samples++
        i += 2
    }
    if (samples <= 0) return WindowStats(rmsDb = -90f, peakNorm = 0f)
    val rms = sqrt((sumSquares / samples).coerceAtLeast(1e-12))
    return WindowStats(
        rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat(),
        peakNorm = peakAbs.coerceIn(0.0, 1.0).toFloat()
    )
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
