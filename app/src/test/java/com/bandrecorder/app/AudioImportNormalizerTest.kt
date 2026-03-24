package com.bandrecorder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioImportNormalizerTest {

    @Test
    fun `detect import kind recognizes wav from extension and mime`() {
        assertEquals(ImportedAudioKind.WAV, detectImportedAudioKind("take.wav", null))
        assertEquals(ImportedAudioKind.WAV, detectImportedAudioKind("take.bin", "audio/wav"))
        assertEquals(ImportedAudioKind.WAV, detectImportedAudioKind("take.bin", "audio/x-wav"))
    }

    @Test
    fun `detect import kind recognizes m4a variants`() {
        assertEquals(ImportedAudioKind.M4A, detectImportedAudioKind("take.m4a", null))
        assertEquals(ImportedAudioKind.M4A, detectImportedAudioKind("take.bin", "audio/mp4"))
        assertEquals(ImportedAudioKind.M4A, detectImportedAudioKind("take.bin", "audio/aac"))
        assertEquals(ImportedAudioKind.M4A, detectImportedAudioKind("take.bin", "audio/x-m4a"))
        assertEquals(ImportedAudioKind.M4A, detectImportedAudioKind("take.flac", "audio/flac"))
        assertEquals(ImportedAudioKind.M4A, detectImportedAudioKind("take.mp3", "audio/mpeg"))
    }

    @Test
    fun `detect import kind rejects unsupported format`() {
        assertEquals(ImportedAudioKind.UNSUPPORTED, detectImportedAudioKind("take.txt", "text/plain"))
        assertEquals(ImportedAudioKind.UNSUPPORTED, detectImportedAudioKind(null, null))
    }

    @Test
    fun `build decoded segment export specs preserves track naming and expected bytes`() {
        val specs = buildDecodedSegmentExportSpecs(
            baseName = "set_live",
            segments = listOf(
                WavFrameSegment(startFrame = 0L, endFrame = 100L),
                WavFrameSegment(startFrame = 150L, endFrame = 250L)
            ),
            bytesPerFrame = 4L
        )

        assertEquals(2, specs.size)
        assertEquals(listOf("set_live_track_01.wav", "set_live_track_02.wav"), specs.map { it.displayName })
        assertEquals(listOf(400L, 400L), specs.map { it.expectedDataBytes })
    }

    @Test
    fun `compute decoded chunk dispatch routes overlapping bytes to matching segments`() {
        val dispatch = computeDecodedChunkDispatch(
            segments = listOf(
                WavFrameSegment(startFrame = 2L, endFrame = 5L),
                WavFrameSegment(startFrame = 5L, endFrame = 9L)
            ),
            chunkStartFrame = 3L,
            chunkEndFrame = 7L,
            bytesPerFrame = 2L,
            firstMatchingSegmentIndex = 0
        )

        assertEquals(2, dispatch.writes.size)
        assertEquals(0, dispatch.writes[0].segmentIndex)
        assertEquals(0, dispatch.writes[0].startByte)
        assertEquals(4, dispatch.writes[0].byteCount)
        assertEquals(1, dispatch.writes[1].segmentIndex)
        assertEquals(4, dispatch.writes[1].startByte)
        assertEquals(4, dispatch.writes[1].byteCount)
        assertEquals(1, dispatch.nextSegmentIndex)
    }

    @Test
    fun `compute decoded chunk dispatch supports single pass accumulation across chunks`() {
        val segments = listOf(
            WavFrameSegment(startFrame = 1L, endFrame = 3L),
            WavFrameSegment(startFrame = 4L, endFrame = 7L)
        )
        val chunkSizeFrames = 2L
        val bytesPerFrame = 2L
        val chunks = listOf(
            byteArrayOf(10, 11, 12, 13),
            byteArrayOf(14, 15, 16, 17),
            byteArrayOf(18, 19, 20, 21),
            byteArrayOf(22, 23, 24, 25)
        )

        val outputs = Array(segments.size) { mutableListOf<Byte>() }
        var chunkStartFrame = 0L
        var nextSegmentIndex = 0
        chunks.forEach { chunk ->
            val chunkEndFrame = chunkStartFrame + chunkSizeFrames
            val dispatch = computeDecodedChunkDispatch(
                segments = segments,
                chunkStartFrame = chunkStartFrame,
                chunkEndFrame = chunkEndFrame,
                bytesPerFrame = bytesPerFrame,
                firstMatchingSegmentIndex = nextSegmentIndex
            )
            dispatch.writes.forEach { write ->
                repeat(write.byteCount) { offset ->
                    outputs[write.segmentIndex] += chunk[write.startByte + offset]
                }
            }
            nextSegmentIndex = dispatch.nextSegmentIndex
            chunkStartFrame = chunkEndFrame
        }

        assertEquals(listOf<Byte>(12, 13, 14, 15), outputs[0])
        assertEquals(listOf<Byte>(18, 19, 20, 21, 22, 23), outputs[1])
        assertTrue(nextSegmentIndex >= segments.size)
    }
}
