package com.bandrecorder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFileNamingTest {
    @Test
    fun `session naming follows expected convention`() {
        val base = RecordingFileNaming.sessionBaseName()
        assertTrue(base.matches(Regex("""^session_\d{6}_\d{6}$""")))
        assertEquals("${base}_raw.wav", RecordingFileNaming.rawFileName(base))
        assertEquals("${base}_clean.wav", RecordingFileNaming.cleanFileName(base))
        assertEquals("${base}_morceau_01.wav", RecordingFileNaming.morceauFileName(base, 1))
        assertEquals("${base}_morceau_12.wav", RecordingFileNaming.morceauFileName(base, 12))
        assertEquals("${base}_clean.meta.json", RecordingFileNaming.cleanMetadataFileName(base))
    }

    @Test
    fun `user title maps morceau filenames to French friendly labels`() {
        assertEquals(
            "Morceau 1",
            RecordingFileNaming.userVisibleTitle("session_050326_102030_morceau_01.wav")
        )
        assertEquals(
            "Morceau 15",
            RecordingFileNaming.userVisibleTitle("session_050326_102030_morceau_15.wav")
        )
        assertEquals(
            "session_050326_102030_raw.wav",
            RecordingFileNaming.userVisibleTitle("session_050326_102030_raw.wav")
        )
    }

    @Test
    fun `segment sort key is numeric for morceau files`() {
        val key02 = RecordingFileNaming.segmentSortKey("session_050326_102030_morceau_02.wav")
        val key10 = RecordingFileNaming.segmentSortKey("session_050326_102030_morceau_10.wav")
        val keyRaw = RecordingFileNaming.segmentSortKey("session_050326_102030_raw.wav")

        assertTrue(key02 < key10)
        assertEquals(Int.MAX_VALUE, keyRaw)
    }
}
