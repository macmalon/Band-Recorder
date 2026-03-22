package com.bandrecorder.app

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `detect import kind rejects unsupported format`() {
        assertEquals(ImportedAudioKind.UNSUPPORTED, detectImportedAudioKind("take.flac", "audio/flac"))
        assertEquals(ImportedAudioKind.UNSUPPORTED, detectImportedAudioKind(null, null))
    }
}
