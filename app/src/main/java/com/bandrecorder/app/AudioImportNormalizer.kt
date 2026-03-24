package com.bandrecorder.app

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.bandrecorder.core.audio.SignalFeatures
import com.bandrecorder.core.audio.extractSignalFeatures
import java.io.BufferedOutputStream
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
    val file: File?,
    val uri: Uri?,
    val displayName: String,
    val kind: ImportedAudioKind,
    val durationMs: Long,
    val sampleRateHz: Int?,
    val channels: Int?
)

internal data class DirectDecodedAudioExportResult(
    val displayNames: List<String>
)

private const val DIRECT_WAV_EXPORT_BUFFER_BYTES = 256 * 1024

internal fun detectImportedAudioKind(displayName: String?, mimeType: String?): ImportedAudioKind {
    val normalizedName = displayName?.lowercase(Locale.ROOT).orEmpty()
    val normalizedMime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    return when {
        normalizedName.endsWith(".wav") ||
            normalizedMime == "audio/wav" ||
            normalizedMime == "audio/x-wav" -> ImportedAudioKind.WAV
        normalizedMime.startsWith("audio/") ||
        normalizedName.endsWith(".m4a") ||
            normalizedName.endsWith(".mp3") ||
            normalizedName.endsWith(".aac") ||
            normalizedName.endsWith(".flac") ||
            normalizedName.endsWith(".ogg") ||
            normalizedName.endsWith(".oga") ||
            normalizedName.endsWith(".opus") ||
            normalizedName.endsWith(".mp4") ||
            normalizedName.endsWith(".3gp") ||
            normalizedName.endsWith(".amr") ||
            normalizedName.endsWith(".webm") ||
            normalizedName.endsWith(".weba") ||
            normalizedMime == "audio/mp4" ||
            normalizedMime == "audio/m4a" ||
            normalizedMime == "audio/aac" ||
            normalizedMime == "audio/x-m4a" -> ImportedAudioKind.M4A
        else -> ImportedAudioKind.UNSUPPORTED
    }
}

internal fun normalizeImportedAudio(
    context: Context,
    sourceFile: File? = null,
    sourceUri: Uri? = null,
    displayName: String,
    kind: ImportedAudioKind,
    onProgress: ((Int) -> Unit)? = null
): NormalizedImportedAudio? {
    val tempDir = File(context.cacheDir, "post_process_imports").apply { mkdirs() }
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "imported_audio" }
    val localSourceFile = sourceFile ?: sourceUri?.takeIf { it.scheme.equals("file", ignoreCase = true) }
        ?.path
        ?.let(::File)
        ?.takeIf { it.exists() }
    val normalizedFile = when (kind) {
        ImportedAudioKind.WAV -> {
            if (localSourceFile != null) {
                onProgress?.invoke(100)
                localSourceFile
            } else {
                val target = File(tempDir, "${System.currentTimeMillis()}_${safeName.substringBeforeLast('.', safeName)}.wav")
                copyUriToFile(context, sourceUri ?: return null, target) ?: return null
            }
        }
        ImportedAudioKind.M4A -> {
            val baseName = safeName.substringBeforeLast('.', safeName)
            val target = File(tempDir, "${System.currentTimeMillis()}_${baseName}.wav")
            decodeAudioFileToWav(context, localSourceFile, sourceUri, target, onProgress) ?: return null
        }
        ImportedAudioKind.UNSUPPORTED -> return null
    }

    val info = readImportedWavInfo(normalizedFile) ?: run {
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
    val directFile = uri.takeIf { it.scheme.equals("file", ignoreCase = true) }
        ?.path
        ?.let(::File)
        ?.takeIf { it.exists() }
    val metadata = probeImportedAudioMetadata(context, directFile, if (directFile == null) uri else null, kind)
    return ImportedAudioSource(
        file = directFile,
        uri = if (directFile == null) uri else null,
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

private fun probeImportedAudioMetadata(
    context: Context,
    file: File?,
    uri: Uri?,
    kind: ImportedAudioKind
): ImportedAudioMetadata {
    return when (kind) {
        ImportedAudioKind.WAV -> {
            val info = readImportedWavInfo(context, file, uri)
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
                configureExtractorDataSource(extractor, context, file, uri) ?: return ImportedAudioMetadata(0L, null, null)
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

internal fun buildImportPreflightMessage(
    context: Context,
    sourceFile: File? = null,
    sourceUri: Uri? = null,
    displayName: String,
    kind: ImportedAudioKind,
    durationMsHint: Long?,
    sampleRateHzHint: Int?,
    channelsHint: Int?
): String? {
    val tempDir = File(context.cacheDir, "post_process_imports").apply { mkdirs() }
    val requiredBytes = when (kind) {
        ImportedAudioKind.WAV -> if (sourceFile != null) {
            sourceFile.length().coerceAtLeast(0L)
        } else {
            querySourceLength(context, sourceUri).coerceAtLeast(0L)
        }
        ImportedAudioKind.M4A -> estimateDecodedWavBytes(
            durationMs = durationMsHint,
            sampleRateHz = sampleRateHzHint,
            channels = channelsHint
        )
        ImportedAudioKind.UNSUPPORTED -> return "Import impossible: format audio non supporté."
    }
    if (requiredBytes <= 0L) return null
    val safetyMargin = 64L * 1024L * 1024L
    val available = tempDir.usableSpace.coerceAtLeast(0L)
    if (available >= requiredBytes + safetyMargin) return null
    val requiredLabel = formatBytes(requiredBytes + safetyMargin)
    val availableLabel = formatBytes(available)
    return "Espace insuffisant pour préparer \"$displayName\". Requis: $requiredLabel, dispo: $availableLabel."
}

private fun publishDecodeProgress(
    durationUs: Long,
    presentationTimeUs: Long,
    decodedFrames: Long,
    sampleRate: Int,
    lastProgress: Int,
    onProgress: ((Int) -> Unit)?
): Int {
    var bestProgress = lastProgress
    if (durationUs > 0L) {
        val timeProgress = if (presentationTimeUs >= 0L) {
            ((presentationTimeUs.coerceAtMost(durationUs) * 100L) / durationUs).toInt().coerceIn(0, 100)
        } else {
            0
        }
        bestProgress = maxOf(bestProgress, timeProgress)
        if (decodedFrames > 0L && sampleRate > 0) {
            val expectedFrames = ((durationUs * sampleRate.toLong()) / 1_000_000L).coerceAtLeast(1L)
            val frameProgress = ((decodedFrames.coerceAtMost(expectedFrames) * 100L) / expectedFrames)
                .toInt()
                .coerceIn(0, 100)
            bestProgress = maxOf(bestProgress, frameProgress)
        }
    }
    if (bestProgress != lastProgress) {
        onProgress?.invoke(bestProgress)
    }
    return bestProgress
}

private class ReusablePcmChunkBuffer {
    private var bytes = ByteArray(0)

    fun decode(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        pcmEncoding: Int
    ): ByteArray? {
        val duplicate = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(bufferInfo.offset)
        duplicate.limit(bufferInfo.offset + bufferInfo.size)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                ensureCapacity(bufferInfo.size)
                duplicate.get(bytes, 0, bufferInfo.size)
                bytes
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuffer = duplicate.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val outputSize = floatBuffer.remaining() * 2
                ensureCapacity(outputSize)
                var byteIndex = 0
                while (floatBuffer.hasRemaining()) {
                    val sample = (floatBuffer.get().coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat()).roundToInt().toShort()
                    bytes[byteIndex++] = (sample.toInt() and 0xFF).toByte()
                    bytes[byteIndex++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }
                bytes
            }
            else -> null
        }
    }

    private fun ensureCapacity(required: Int) {
        if (bytes.size >= required) return
        bytes = ByteArray(required)
    }
}

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
    context: Context,
    sourceFile: File?,
    sourceUri: Uri?,
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
    var decodedFrames = 0L
    val pcmChunkBuffer = ReusablePcmChunkBuffer()
    try {
        configureExtractorDataSource(extractor, context, sourceFile, sourceUri) ?: return null
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
                                val chunk = pcmChunkBuffer.decode(outputBuffer, bufferInfo, pcmEncoding) ?: return null
                                val chunkSize = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> bufferInfo.size
                                    AudioFormat.ENCODING_PCM_FLOAT -> (bufferInfo.size / 4) * 2
                                    else -> return null
                                }
                                output.write(chunk, 0, chunkSize)
                                dataBytesWritten += chunkSize
                                decodedFrames += chunkSize / (2L * outputChannels.coerceAtLeast(1))
                                lastProgress = publishDecodeProgress(
                                    durationUs = durationUs,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    decodedFrames = decodedFrames,
                                    sampleRate = outputSampleRate,
                                    lastProgress = lastProgress,
                                    onProgress = onProgress
                                )
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
    var totalFrames = 0L
    var windowFrames = 0
    var windowSampleCapacity = 0
    var windowSamples = ShortArray(0)
    var samplesInWindow = 0
    val pcmChunkBuffer = ReusablePcmChunkBuffer()

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

    fun processChunk(chunk: ByteArray, chunkSize: Int) {
        if (outputSampleRate <= 0 || outputChannels <= 0) return
        ensureWindowBuffer(outputSampleRate, outputChannels)
        var byteIndex = 0
        while (byteIndex + 1 < chunkSize) {
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
                            val chunk = pcmChunkBuffer.decode(outputBuffer, bufferInfo, pcmEncoding) ?: return null
                            val chunkSize = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> bufferInfo.size
                                AudioFormat.ENCODING_PCM_FLOAT -> (bufferInfo.size / 4) * 2
                                else -> return null
                            }
                            processChunk(chunk, chunkSize)
                            totalFrames += chunkSize / (2 * outputChannels)
                            lastProgress = publishDecodeProgress(
                                durationUs = durationUs,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                decodedFrames = totalFrames,
                                sampleRate = outputSampleRate,
                                lastProgress = lastProgress,
                                onProgress = onProgress
                            )
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
                dataSize = totalFrames * outputChannels.toLong() * 2L
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

private fun finalizeTemporaryWavHeader(file: File, dataSize: Int, sampleRate: Int, channels: Int) {
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(0L)
        raf.write(buildImportedWavHeader(dataSize, sampleRate, channels))
    }
}

private fun readImportedWavInfo(file: File): WavInfo? {
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
        val dataSize = headerDataSize.coerceAtMost(fileDataSize).coerceAtLeast(0L)
        return WavInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataOffset = 44L,
            dataSize = dataSize
        )
    }
}

private fun readImportedWavInfo(context: Context, file: File?, uri: Uri?): WavInfo? {
    return when {
        file != null -> readImportedWavInfo(file)
        uri != null -> {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(44)
                val read = input.read(header)
                if (read < 44) return null
                val sampleRate = readIntLE(header, 24)
                val channels = readShortLE(header, 22)
                val bitsPerSample = readShortLE(header, 34)
                val headerDataSize = readIntLE(header, 40).toLong() and 0xFFFF_FFFFL
                if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
                val sourceLength = querySourceLength(context, uri).coerceAtLeast(44L)
                val fileDataSize = (sourceLength - 44L).coerceAtLeast(0L)
                val dataSize = headerDataSize.coerceAtMost(fileDataSize).coerceAtLeast(0L)
                WavInfo(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    dataOffset = 44L,
                    dataSize = dataSize
                )
            }
        }
        else -> null
    }
}

private fun configureExtractorDataSource(
    extractor: MediaExtractor,
    context: Context,
    sourceFile: File?,
    sourceUri: Uri?
): AssetFileDescriptor? {
    return when {
        sourceFile != null -> {
            extractor.setDataSource(sourceFile.absolutePath)
            null
        }
        sourceUri != null -> {
            val afd = context.contentResolver.openAssetFileDescriptor(sourceUri, "r") ?: return null
            if (afd.declaredLength >= 0L) {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            } else {
                extractor.setDataSource(afd.fileDescriptor)
            }
            afd
        }
        else -> null
    }
}

private fun querySourceLength(context: Context, uri: Uri?): Long {
    uri ?: return -1L
    val afdLength = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length >= 0L) afd.length else -1L
        } ?: -1L
    }.getOrDefault(-1L)
    if (afdLength >= 0L) return afdLength
    val projection = arrayOf(android.provider.OpenableColumns.SIZE)
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use -1L
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index < 0) return@use -1L
            cursor.getLong(index)
        } ?: -1L
    }.getOrDefault(-1L)
}

private fun estimateDecodedWavBytes(
    durationMs: Long?,
    sampleRateHz: Int?,
    channels: Int?
): Long {
    val safeDurationMs = durationMs?.coerceAtLeast(0L) ?: return 0L
    val safeSampleRate = sampleRateHz?.takeIf { it > 0 } ?: 48_000
    val safeChannels = channels?.takeIf { it > 0 } ?: 2
    return (safeDurationMs * safeSampleRate.toLong() * safeChannels.toLong() * 2L) / 1000L
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024L
    val mb = kb * 1024L
    val gb = mb * 1024L
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / gb.toDouble())
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / mb.toDouble())
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / kb.toDouble())
        else -> "$bytes B"
    }
}

private fun buildImportedWavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
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

private data class PendingMediaStoreWav(
    val displayName: String,
    val uri: Uri,
    val stream: BufferedOutputStream,
    val expectedDataBytes: Int,
    var writtenDataBytes: Int = 0
)

private fun createPendingMediaStoreWav(
    context: Context,
    displayName: String,
    relativePath: String,
    expectedDataBytes: Long,
    sampleRate: Int,
    channels: Int
): PendingMediaStoreWav? {
    if (expectedDataBytes <= 0L || expectedDataBytes > Int.MAX_VALUE.toLong()) return null
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    return runCatching {
        val rawStream = resolver.openOutputStream(uri) ?: error("Cannot open output stream")
        val stream = BufferedOutputStream(rawStream, DIRECT_WAV_EXPORT_BUFFER_BYTES)
        stream.write(buildImportedWavHeader(expectedDataBytes.toInt(), sampleRate, channels))
        PendingMediaStoreWav(
            displayName = displayName,
            uri = uri,
            stream = stream,
            expectedDataBytes = expectedDataBytes.toInt()
        )
    }.getOrElse {
        resolver.delete(uri, null, null)
        null
    }
}

private fun publishPendingMediaStoreWav(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    return resolver.update(uri, values, null, null) > 0
}

internal fun exportDecodedAudioSelectionToTempWavs(
    sourceFile: File,
    analysis: WavAnalysisResult,
    mode: PostProcessMode,
    outputDir: File,
    baseName: String,
    onProgress: ((Int) -> Unit)? = null
): List<File>? {
    if (analysis.segments.isEmpty()) return emptyList()
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var outputSampleRate = 0
    var outputChannels = 0
    var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    var durationUs = 0L
    var lastProgress = -1
    val pcmChunkBuffer = ReusablePcmChunkBuffer()
    val bytesPerFrame = analysis.info.channels * 2L
    val sortedSegments = analysis.segments.sortedBy { it.startFrame }
    if (sortedSegments.any { it.endFrame <= it.startFrame }) return null
    outputDir.mkdirs()

    data class OutputHandle(
        val file: File,
        val expectedDataBytes: Int,
        val stream: FileOutputStream,
        var writtenDataBytes: Int = 0
    )

    val handles = mutableListOf<OutputHandle>()
    val outputFiles = mutableListOf<File>()
    val totalOutputBytes = sortedSegments.sumOf { ((it.endFrame - it.startFrame) * bytesPerFrame).coerceAtLeast(0L) }
    if (totalOutputBytes <= 0L) return null
    if (mode == PostProcessMode.CLEAN_SINGLE_FILE && totalOutputBytes > Int.MAX_VALUE.toLong()) return null
    var completedSuccessfully = false

    try {
        fun createHandle(file: File, expectedDataBytes: Long): OutputHandle? {
            if (expectedDataBytes <= 0L || expectedDataBytes > Int.MAX_VALUE.toLong()) return null
            FileOutputStream(file).use { it.write(ByteArray(44)) }
            val stream = FileOutputStream(file, true)
            return OutputHandle(file, expectedDataBytes.toInt(), stream).also {
                handles += it
                outputFiles += file
            }
        }

        when (mode) {
            PostProcessMode.CLEAN_SINGLE_FILE -> {
                createHandle(
                    File(outputDir, "${baseName}_cleaned.wav"),
                    totalOutputBytes
                ) ?: return null
            }
            PostProcessMode.SPLIT_MULTIPLE_TRACKS -> {
                sortedSegments.forEachIndexed { index, segment ->
                    createHandle(
                        File(outputDir, "${baseName}_track_${"%02d".format(Locale.US, index + 1)}.wav"),
                        ((segment.endFrame - segment.startFrame) * bytesPerFrame).coerceAtLeast(0L)
                    ) ?: return null
                }
            }
        }

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
        var chunkStartFrame = 0L
        var firstMatchingSegmentIndex = 0
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return null
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                    if (outputSampleRate != analysis.info.sampleRate || outputChannels != analysis.info.channels) {
                        return null
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
                                if (outputSampleRate != analysis.info.sampleRate || outputChannels != analysis.info.channels) {
                                    return null
                                }
                            }
                            val chunk = pcmChunkBuffer.decode(outputBuffer, bufferInfo, pcmEncoding) ?: return null
                            val chunkSize = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> bufferInfo.size
                                AudioFormat.ENCODING_PCM_FLOAT -> (bufferInfo.size / 4) * 2
                                else -> return null
                            }
                            val framesInChunk = chunkSize / (outputChannels * 2)
                            val chunkEndFrame = chunkStartFrame + framesInChunk

                            while (
                                firstMatchingSegmentIndex < sortedSegments.size &&
                                sortedSegments[firstMatchingSegmentIndex].endFrame <= chunkStartFrame
                            ) {
                                firstMatchingSegmentIndex += 1
                            }

                            var segmentIndex = firstMatchingSegmentIndex
                            while (segmentIndex < sortedSegments.size) {
                                val segment = sortedSegments[segmentIndex]
                                if (segment.startFrame >= chunkEndFrame) break
                                val overlapStart = maxOf(chunkStartFrame, segment.startFrame)
                                val overlapEnd = minOf(chunkEndFrame, segment.endFrame)
                                if (overlapEnd > overlapStart) {
                                    val startByte = ((overlapStart - chunkStartFrame) * bytesPerFrame).toInt()
                                    val endByte = ((overlapEnd - chunkStartFrame) * bytesPerFrame).toInt()
                                    val handle = when (mode) {
                                        PostProcessMode.CLEAN_SINGLE_FILE -> handles.first()
                                        PostProcessMode.SPLIT_MULTIPLE_TRACKS -> handles[segmentIndex]
                                    }
                                    handle.stream.write(chunk, startByte, endByte - startByte)
                                    handle.writtenDataBytes += (endByte - startByte)
                                }
                                if (segment.endFrame <= chunkEndFrame) {
                                    segmentIndex += 1
                                } else {
                                    break
                                }
                            }

                            chunkStartFrame = chunkEndFrame
                            lastProgress = publishDecodeProgress(
                                durationUs = durationUs,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                decodedFrames = chunkEndFrame,
                                sampleRate = outputSampleRate,
                                lastProgress = lastProgress,
                                onProgress = onProgress
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        handles.forEach { handle ->
            handle.stream.flush()
            handle.stream.close()
            if (handle.writtenDataBytes <= 0 || handle.writtenDataBytes != handle.expectedDataBytes) {
                return null
            }
            finalizeTemporaryWavHeader(handle.file, handle.writtenDataBytes, analysis.info.sampleRate, analysis.info.channels)
        }
        onProgress?.invoke(100)
        completedSuccessfully = true
        return outputFiles
    } catch (_: Throwable) {
        return null
    } finally {
        handles.forEach { handle -> runCatching { handle.stream.close() } }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        if (!completedSuccessfully) {
            outputFiles.forEach { file -> runCatching { file.delete() } }
        }
    }
}

internal fun exportDecodedAudioSelectionToDownloads(
    context: Context,
    sourceFile: File,
    analysis: WavAnalysisResult,
    mode: PostProcessMode,
    relativePath: String,
    baseName: String,
    onProgress: ((Int) -> Unit)? = null
): DirectDecodedAudioExportResult? {
    if (analysis.segments.isEmpty()) return DirectDecodedAudioExportResult(emptyList())
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var outputSampleRate = 0
    var outputChannels = 0
    var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    var durationUs = 0L
    var lastProgress = -1
    val pcmChunkBuffer = ReusablePcmChunkBuffer()
    val bytesPerFrame = analysis.info.channels * 2L
    val sortedSegments = analysis.segments.sortedBy { it.startFrame }
    if (sortedSegments.any { it.endFrame <= it.startFrame }) return null

    data class OutputHandle(
        val pending: PendingMediaStoreWav
    )

    val handles = mutableListOf<OutputHandle>()
    var completedSuccessfully = false

    try {
        fun createHandle(displayName: String, expectedDataBytes: Long): OutputHandle? {
            val pending = createPendingMediaStoreWav(
                context = context,
                displayName = displayName,
                relativePath = relativePath,
                expectedDataBytes = expectedDataBytes,
                sampleRate = analysis.info.sampleRate,
                channels = analysis.info.channels
            ) ?: return null
            return OutputHandle(pending).also { handles += it }
        }

        when (mode) {
            PostProcessMode.CLEAN_SINGLE_FILE -> {
                val totalOutputBytes = sortedSegments.sumOf {
                    ((it.endFrame - it.startFrame) * bytesPerFrame).coerceAtLeast(0L)
                }
                createHandle("${baseName}_cleaned.wav", totalOutputBytes) ?: return null
            }
            PostProcessMode.SPLIT_MULTIPLE_TRACKS -> {
                sortedSegments.forEachIndexed { index, segment ->
                    val displayName = "${baseName}_track_${"%02d".format(Locale.US, index + 1)}.wav"
                    val expectedDataBytes = ((segment.endFrame - segment.startFrame) * bytesPerFrame).coerceAtLeast(0L)
                    createHandle(displayName, expectedDataBytes) ?: return null
                }
            }
        }

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
        var chunkStartFrame = 0L
        var firstMatchingSegmentIndex = 0
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return null
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                    if (outputSampleRate != analysis.info.sampleRate || outputChannels != analysis.info.channels) {
                        return null
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
                                if (outputSampleRate != analysis.info.sampleRate || outputChannels != analysis.info.channels) {
                                    return null
                                }
                            }
                            val chunk = pcmChunkBuffer.decode(outputBuffer, bufferInfo, pcmEncoding) ?: return null
                            val chunkSize = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> bufferInfo.size
                                AudioFormat.ENCODING_PCM_FLOAT -> (bufferInfo.size / 4) * 2
                                else -> return null
                            }
                            val framesInChunk = chunkSize / (outputChannels * 2)
                            val chunkEndFrame = chunkStartFrame + framesInChunk

                            while (
                                firstMatchingSegmentIndex < sortedSegments.size &&
                                sortedSegments[firstMatchingSegmentIndex].endFrame <= chunkStartFrame
                            ) {
                                firstMatchingSegmentIndex += 1
                            }

                            var segmentIndex = firstMatchingSegmentIndex
                            while (segmentIndex < sortedSegments.size) {
                                val segment = sortedSegments[segmentIndex]
                                if (segment.startFrame >= chunkEndFrame) break
                                val overlapStart = maxOf(chunkStartFrame, segment.startFrame)
                                val overlapEnd = minOf(chunkEndFrame, segment.endFrame)
                                if (overlapEnd > overlapStart) {
                                    val startByte = ((overlapStart - chunkStartFrame) * bytesPerFrame).toInt()
                                    val endByte = ((overlapEnd - chunkStartFrame) * bytesPerFrame).toInt()
                                    val handle = when (mode) {
                                        PostProcessMode.CLEAN_SINGLE_FILE -> handles.first()
                                        PostProcessMode.SPLIT_MULTIPLE_TRACKS -> handles[segmentIndex]
                                    }
                                    handle.pending.stream.write(chunk, startByte, endByte - startByte)
                                    handle.pending.writtenDataBytes += (endByte - startByte)
                                }
                                if (segment.endFrame <= chunkEndFrame) {
                                    segmentIndex += 1
                                } else {
                                    break
                                }
                            }

                            chunkStartFrame = chunkEndFrame
                            lastProgress = publishDecodeProgress(
                                durationUs = durationUs,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                decodedFrames = chunkEndFrame,
                                sampleRate = outputSampleRate,
                                lastProgress = lastProgress,
                                onProgress = onProgress
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        for (handle in handles) {
            handle.pending.stream.flush()
            handle.pending.stream.close()
            if (handle.pending.writtenDataBytes <= 0 || handle.pending.writtenDataBytes != handle.pending.expectedDataBytes) {
                return null
            }
            if (!publishPendingMediaStoreWav(context, handle.pending.uri)) {
                return null
            }
        }
        onProgress?.invoke(100)
        completedSuccessfully = true
        return DirectDecodedAudioExportResult(handles.map { it.pending.displayName })
    } catch (_: Throwable) {
        return null
    } finally {
        handles.forEach { handle -> runCatching { handle.pending.stream.close() } }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        if (!completedSuccessfully) {
            val resolver = context.contentResolver
            handles.forEach { handle -> runCatching { resolver.delete(handle.pending.uri, null, null) } }
        }
    }
}
