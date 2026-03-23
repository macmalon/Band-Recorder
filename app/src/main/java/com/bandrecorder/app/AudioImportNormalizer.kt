package com.bandrecorder.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.bandrecorder.core.audio.SignalFeatures
import com.bandrecorder.core.audio.extractSignalFeatures
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ImportedAudioKind {
    WAV,
    M4A,
    UNSUPPORTED
}

internal data class NormalizedImportedAudio(
    val file: File,
    val displayName: String,
    val durationMs: Long,
    val info: WavInfo
)

internal data class ImportedAudioSource(
    val file: File,
    val displayName: String,
    val kind: ImportedAudioKind,
    val durationMs: Long,
    val sampleRateHz: Int?,
    val channels: Int?
)

internal fun detectImportedAudioKind(displayName: String?, mimeType: String?): ImportedAudioKind {
    val normalizedName = displayName?.lowercase(Locale.ROOT).orEmpty()
    val normalizedMime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    return when {
        normalizedName.endsWith(".wav") ||
            normalizedMime == "audio/wav" ||
            normalizedMime == "audio/x-wav" -> ImportedAudioKind.WAV
        normalizedName.endsWith(".m4a") ||
            normalizedMime == "audio/mp4" ||
            normalizedMime == "audio/m4a" ||
            normalizedMime == "audio/aac" ||
            normalizedMime == "audio/x-m4a" -> ImportedAudioKind.M4A
        else -> ImportedAudioKind.UNSUPPORTED
    }
}

internal fun normalizeImportedAudio(
    context: Context,
    sourceFile: File,
    displayName: String,
    kind: ImportedAudioKind,
    onProgress: ((Int) -> Unit)? = null
): NormalizedImportedAudio? {
    val tempDir = File(context.cacheDir, "post_process_imports").apply { mkdirs() }
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "imported_audio" }
    val normalizedFile = when (kind) {
        ImportedAudioKind.WAV -> {
            onProgress?.invoke(100)
            sourceFile
        }
        ImportedAudioKind.M4A -> {
            val baseName = safeName.substringBeforeLast('.', safeName)
            val target = File(tempDir, "${System.currentTimeMillis()}_${baseName}.wav")
            decodeAudioFileToWav(sourceFile, target, onProgress) ?: return null
        }
        ImportedAudioKind.UNSUPPORTED -> return null
    }

    val info = readWavInfo(normalizedFile) ?: run {
        runCatching { normalizedFile.delete() }
        return null
    }
    if (info.bitsPerSample != 16) {
        runCatching { normalizedFile.delete() }
        return null
    }

    val frameBytes = info.channels * 2
    val totalFrames = (info.dataSize / frameBytes).coerceAtLeast(0)
    val durationMs = if (info.sampleRate <= 0) 0L else (totalFrames.toLong() * 1_000L) / info.sampleRate.toLong()
    return NormalizedImportedAudio(
        file = normalizedFile,
        displayName = displayName,
        durationMs = durationMs,
        info = info
    )
}

internal fun importAudioSource(
    context: Context,
    uri: Uri,
    displayName: String,
    mimeType: String?
): ImportedAudioSource? {
    val kind = detectImportedAudioKind(displayName, mimeType)
    if (kind == ImportedAudioKind.UNSUPPORTED) return null
    val tempDir = File(context.cacheDir, "post_process_imports").apply { mkdirs() }
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "imported_audio" }
    val defaultExtension = when (kind) {
        ImportedAudioKind.WAV -> ".wav"
        ImportedAudioKind.M4A -> ".m4a"
        ImportedAudioKind.UNSUPPORTED -> ""
    }
    val fileName = if (safeName.contains('.')) safeName else "$safeName$defaultExtension"
    val target = File(tempDir, "${System.currentTimeMillis()}_$fileName")
    val importedFile = copyUriToFile(context, uri, target) ?: return null
    val metadata = probeImportedAudioMetadata(importedFile, kind)
    return ImportedAudioSource(
        file = importedFile,
        displayName = displayName,
        kind = kind,
        durationMs = metadata.durationMs,
        sampleRateHz = metadata.sampleRateHz,
        channels = metadata.channels
    )
}

private data class ImportedAudioMetadata(
    val durationMs: Long,
    val sampleRateHz: Int?,
    val channels: Int?
)

private fun probeImportedAudioMetadata(file: File, kind: ImportedAudioKind): ImportedAudioMetadata {
    return when (kind) {
        ImportedAudioKind.WAV -> {
            val info = readWavInfo(file)
            if (info == null) {
                ImportedAudioMetadata(0L, null, null)
            } else {
                val frameBytes = info.channels * 2
                val totalFrames = (info.dataSize / frameBytes).coerceAtLeast(0)
                val durationMs = if (info.sampleRate <= 0) 0L else (totalFrames.toLong() * 1_000L) / info.sampleRate.toLong()
                ImportedAudioMetadata(durationMs, info.sampleRate, info.channels)
            }
        }
        ImportedAudioKind.M4A -> {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return ImportedAudioMetadata(0L, null, null)
                val format = extractor.getTrackFormat(trackIndex)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                ImportedAudioMetadata(
                    durationMs = durationUs / 1_000L,
                    sampleRateHz = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                )
            } catch (_: Throwable) {
                ImportedAudioMetadata(0L, null, null)
            } finally {
                runCatching { extractor.release() }
            }
        }
        ImportedAudioKind.UNSUPPORTED -> ImportedAudioMetadata(0L, null, null)
    }
}

private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private fun copyUriToFile(context: Context, uri: Uri, target: File): File? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("Cannot open input stream")
        target
    }.getOrElse {
        runCatching { target.delete() }
        null
    }
}

private fun decodeAudioFileToWav(
    sourceFile: File,
    target: File,
    onProgress: ((Int) -> Unit)? = null
): File? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var outputSampleRate = 0
    var outputChannels = 0
    var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    var dataBytesWritten = 0
    var durationUs = 0L
    var lastProgress = -1
    try {
        extractor.setDataSource(sourceFile.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
        } else {
            0L
        }
        val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
        codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        FileOutputStream(target).use { it.write(ByteArray(44)) }
        FileOutputStream(target, true).use { output ->
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return null
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                if (outputSampleRate <= 0 || outputChannels <= 0) {
                                    val format = codec.outputFormat
                                    outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                                val chunk = extractPcm16Chunk(outputBuffer, bufferInfo, pcmEncoding) ?: return null
                                output.write(chunk)
                                dataBytesWritten += chunk.size
                                if (durationUs > 0L && bufferInfo.presentationTimeUs >= 0L) {
                                    val progress = ((bufferInfo.presentationTimeUs.coerceAtMost(durationUs) * 100L) / durationUs)
                                        .toInt()
                                        .coerceIn(0, 100)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress?.invoke(progress)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        }

        if (outputSampleRate <= 0) {
            outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }
        if (outputChannels <= 0) {
            outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
        if (outputSampleRate <= 0 || outputChannels <= 0 || dataBytesWritten <= 0) {
            runCatching { target.delete() }
            return null
        }
        finalizeTemporaryWavHeader(target, dataBytesWritten, outputSampleRate, outputChannels)
        onProgress?.invoke(100)
        return target
    } catch (_: Throwable) {
        runCatching { target.delete() }
        return null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

internal fun decodeAudioFileToAnalysisCache(
    sourceFile: File,
    onProgress: ((Int) -> Unit)? = null
): DecodedAudioAnalysisCacheEntry? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var outputSampleRate = 0
    var outputChannels = 0
    var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    var durationUs = 0L
    var lastProgress = -1
    val windows = mutableListOf<SignalFeatures>()
    var totalFrames = 0
    var windowFrames = 0
    var windowSampleCapacity = 0
    var windowSamples = ShortArray(0)
    var samplesInWindow = 0

    fun ensureWindowBuffer(sampleRate: Int, channels: Int) {
        if (windowFrames > 0 && windowSampleCapacity > 0) return
        windowFrames = (sampleRate / 20).coerceAtLeast(1)
        windowSampleCapacity = windowFrames * channels
        windowSamples = ShortArray(windowSampleCapacity)
        samplesInWindow = 0
    }

    fun flushWindow() {
        if (samplesInWindow <= 0 || outputChannels <= 0 || outputSampleRate <= 0) return
        windows += extractSignalFeatures(windowSamples, samplesInWindow, outputChannels, outputSampleRate)
        samplesInWindow = 0
    }

    fun processChunk(chunk: ByteArray) {
        if (outputSampleRate <= 0 || outputChannels <= 0) return
        ensureWindowBuffer(outputSampleRate, outputChannels)
        var byteIndex = 0
        while (byteIndex + 1 < chunk.size) {
            val lo = chunk[byteIndex].toInt() and 0xFF
            val hi = chunk[byteIndex + 1].toInt()
            windowSamples[samplesInWindow++] = ((hi shl 8) or lo).toShort()
            byteIndex += 2
            if (samplesInWindow == windowSampleCapacity) {
                flushWindow()
            }
        }
    }

    try {
        extractor.setDataSource(sourceFile.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
        } else {
            0L
        }
        val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
        codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return null
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime.coerceAtLeast(0L),
                            extractor.sampleFlags
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    ensureWindowBuffer(outputSampleRate, outputChannels)
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if (outputSampleRate <= 0 || outputChannels <= 0) {
                                val format = codec.outputFormat
                                outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                ensureWindowBuffer(outputSampleRate, outputChannels)
                            }
                            val chunk = extractPcm16Chunk(outputBuffer, bufferInfo, pcmEncoding) ?: return null
                            processChunk(chunk)
                            totalFrames += chunk.size / (2 * outputChannels)
                            if (durationUs > 0L && bufferInfo.presentationTimeUs >= 0L) {
                                val progress = ((bufferInfo.presentationTimeUs.coerceAtMost(durationUs) * 100L) / durationUs)
                                    .toInt()
                                    .coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress?.invoke(progress)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        flushWindow()
        if (outputSampleRate <= 0 || outputChannels <= 0 || totalFrames <= 0 || windows.isEmpty()) {
            return null
        }
        onProgress?.invoke(100)
        return DecodedAudioAnalysisCacheEntry(
            info = WavInfo(
                sampleRate = outputSampleRate,
                channels = outputChannels,
                bitsPerSample = 16,
                dataOffset = 44L,
                dataSize = totalFrames * outputChannels * 2
            ),
            windows = windows
        )
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

private fun extractPcm16Chunk(
    outputBuffer: ByteBuffer,
    bufferInfo: MediaCodec.BufferInfo,
    pcmEncoding: Int
): ByteArray? {
    val duplicate = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    duplicate.position(bufferInfo.offset)
    duplicate.limit(bufferInfo.offset + bufferInfo.size)
    return when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_16BIT -> {
            ByteArray(bufferInfo.size).also { duplicate.get(it) }
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val floatBuffer = duplicate.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            ByteArray(floatBuffer.remaining() * 2).also { bytes ->
                var byteIndex = 0
                while (floatBuffer.hasRemaining()) {
                    val sample = (floatBuffer.get().coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat()).roundToInt().toShort()
                    bytes[byteIndex++] = (sample.toInt() and 0xFF).toByte()
                    bytes[byteIndex++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
            }
        }
        else -> null
    }
}

private fun finalizeTemporaryWavHeader(file: File, dataSize: Int, sampleRate: Int, channels: Int) {
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(0L)
        raf.write(buildWavHeader(dataSize, sampleRate, channels))
    }
}
