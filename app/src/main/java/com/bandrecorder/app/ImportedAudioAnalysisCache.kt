package com.bandrecorder.app

import com.bandrecorder.core.audio.SignalFeatures
import java.io.File

internal data class DecodedAudioAnalysisCacheEntry(
    val info: WavInfo,
    val windows: List<SignalFeatures>
)

private data class CachedImportedAudioAnalysis(
    val sourcePath: String,
    val fileLength: Long,
    val lastModified: Long,
    val decoded: DecodedAudioAnalysisCacheEntry
)

internal object ImportedAudioAnalysisCache {
    private var cached: CachedImportedAudioAnalysis? = null

    fun get(sourceFile: File): DecodedAudioAnalysisCacheEntry? {
        val entry = cached ?: return null
        return if (
            entry.sourcePath == sourceFile.absolutePath &&
            entry.fileLength == sourceFile.length() &&
            entry.lastModified == sourceFile.lastModified()
        ) {
            entry.decoded
        } else {
            null
        }
    }

    fun put(sourceFile: File, decoded: DecodedAudioAnalysisCacheEntry) {
        cached = CachedImportedAudioAnalysis(
            sourcePath = sourceFile.absolutePath,
            fileLength = sourceFile.length(),
            lastModified = sourceFile.lastModified(),
            decoded = decoded
        )
    }

    fun clear() {
        cached = null
    }
}
