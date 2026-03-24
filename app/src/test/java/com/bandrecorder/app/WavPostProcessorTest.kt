package com.bandrecorder.app

import com.bandrecorder.core.audio.SignalFeatures
import com.bandrecorder.core.audio.applyThresholdOffset
import com.bandrecorder.core.audio.computeAdaptiveThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.math.abs

class WavPostProcessorTest {

    @Test
    fun `auto threshold stays independent from user offset`() {
        val windows = listOf(
            feature(-66f),
            feature(-63f),
            feature(-61f),
            feature(-60f),
            feature(-58f),
            feature(-52f),
            feature(-48f),
            feature(-42f)
        )

        val auto = computeAdaptiveThresholds(windows)
        val adjusted = applyThresholdOffset(auto, 6f)

        assertEquals(-55f, auto.autoThresholdDb, 0.01f)
        assertEquals(-55f, auto.enterThresholdDb, 0.01f)
        assertEquals(-51f, auto.exitThresholdDb, 0.01f)
        assertEquals(auto.autoThresholdDb, adjusted.autoThresholdDb, 0.0f)
        assertEquals(-49f, adjusted.enterThresholdDb, 0.01f)
        assertEquals(-45f, adjusted.exitThresholdDb, 0.01f)
    }

    @Test
    fun `cached window rebuild keeps auto threshold and reapplies offset`() {
        val windows = listOf(
            feature(-66f),
            feature(-63f),
            feature(-61f),
            feature(-60f),
            feature(-58f),
            feature(-52f),
            feature(-48f),
            feature(-42f)
        )
        val cached = DecodedAudioAnalysisCacheEntry(
            info = WavInfo(
                sampleRate = 1_000,
                channels = 1,
                bitsPerSample = 16,
                dataOffset = 44L,
                dataSize = windows.size * 50L * 2L
            ),
            windows = windows
        )

        val analysis = rebuildAnalysisFromCachedWindows(
            cached = cached,
            silenceThresholdDb = 6f,
            silenceDurationSec = 1
        )

        assertNotNull(analysis)
        analysis!!
        assertEquals(-55f, analysis.thresholds.autoThresholdDb, 0.01f)
        assertEquals(-49f, analysis.thresholds.enterThresholdDb, 0.01f)
        assertEquals(-45f, analysis.thresholds.exitThresholdDb, 0.01f)
    }

    @Test
    fun `analysis keeps single segment when no silence exceeds threshold`() {
        val file = createMonoWav(listOf(section(60, 12_000)))

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(1, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments.first().startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments.first().endFrame), 60_000L)
    }

    @Test
    fun `analysis splits long silence and keeps n seconds before cut`() {
        val file = createMonoWav(
            listOf(
                section(60, 12_000),
                section(2, 0),
                section(60, 12_000)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(2, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 61_000L)
        assertNear(ms(analysis.info, analysis.segments[1].startFrame), 61_000L, toleranceMs = 1_200L)
        assertNear(ms(analysis.info, analysis.segments[1].endFrame), 122_000L, toleranceMs = 1_200L)
    }

    @Test
    fun `analysis ignores short volume bumps inside a silence`() {
        val file = createMonoWav(
            listOf(
                section(60, 12_000),
                section(2, 0),
                section(2, 7_000),
                section(2, 0),
                section(60, 12_000)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(2, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 61_000L)
        assertNear(ms(analysis.info, analysis.segments[1].startFrame), 64_000L, toleranceMs = 1_500L)
        assertNear(ms(analysis.info, analysis.segments[1].endFrame), 126_000L, toleranceMs = 1_500L)
    }

    @Test
    fun `analysis keeps low but audible music instead of classifying it as silence`() {
        val file = createAlternatingMonoWav(
            sections = listOf(
                section(60, 12_000),
                section(20, 1_600),
                section(60, 12_000)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 2)

        requireNotNull(analysis)
        assertEquals(1, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments.first().startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments.first().endFrame), 140_000L, toleranceMs = 1_500L)
    }

    @Test
    fun `analysis still removes prolonged very low passages`() {
        val file = createAlternatingMonoWav(
            sections = listOf(
                section(60, 12_000),
                section(4, 120),
                section(60, 12_000)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 2)

        requireNotNull(analysis)
        assertEquals(2, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 62_000L, toleranceMs = 1_500L)
        assertTrue(ms(analysis.info, analysis.segments[1].startFrame) >= 62_000L)
    }

    @Test
    fun `analysis ignores silence at file edges and trims after n seconds`() {
        val file = createMonoWav(
            listOf(
                section(2, 0),
                section(60, 12_000),
                section(2, 0)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(1, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].startFrame), 2_000L)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 63_000L)
    }

    @Test
    fun `analysis ignores segments shorter than fifty seconds`() {
        val file = createMonoWav(
            listOf(
                section(60, 12_000),
                section(3, 0),
                section(20, 12_000),
                section(3, 0),
                section(60, 12_000)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = 0f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(2, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 61_000L)
        assertTrue(ms(analysis.info, analysis.segments[1].startFrame) >= 80_000L)
        assertTrue(ms(analysis.info, analysis.segments[1].endFrame) >= 140_000L)
    }

    @Test
    fun `cleaned wav concatenates kept segments in order`() {
        val source = createMonoWav(
            listOf(
                section(60, 12_000),
                section(10, 0),
                section(60, 9_000)
            )
        )
        val analysis = requireNotNull(analyzeWavBySilence(source, silenceThresholdDb = 0f, silenceDurationSec = 1))
        val out = File.createTempFile("cleaned_", ".wav")

        writeCleanedWav(source, analysis.info, analysis.segments, out)

        val outInfo = requireNotNull(readWavInfo(out))
        val totalFrames = outInfo.dataSize / (outInfo.channels * 2)
        assertNear(ms(outInfo, totalFrames), 123_000L, toleranceMs = 1_500L)
    }

    @Test
    fun `segment export writes one wav per detected segment`() {
        val source = createMonoWav(
            listOf(
                section(60, 12_000),
                section(2, 0),
                section(60, 12_000)
            )
        )
        val analysis = requireNotNull(analyzeWavBySilence(source, silenceThresholdDb = 0f, silenceDurationSec = 1))
        val dir = Files.createTempDirectory("segment_export_").toFile()

        val files = analysis.segments.mapIndexed { index, segment ->
            File(dir, "track_${"%02d".format(index + 1)}.wav").also {
                writeWavSegment(source, analysis.info, segment, it)
            }
        }

        assertEquals(2, files.size)
        assertEquals(listOf("track_01.wav", "track_02.wav"), files.map(File::getName))
        files.forEach { exported ->
            assertTrue(exported.exists())
            assertTrue(exported.length() > 44L)
        }
    }

    private fun createMonoWav(sections: List<Section>, sampleRate: Int = 1_000): File {
        val samples = ShortArray(sections.sumOf { it.durationSec * sampleRate })
        var cursor = 0
        sections.forEach { section ->
            repeat(section.durationSec * sampleRate) {
                samples[cursor++] = section.amplitude.toShort()
            }
        }

        return File.createTempFile("wav_post_", ".wav").apply {
            FileOutputStream(this).use { out ->
                out.write(buildWavHeader(samples.size * 2, sampleRate, 1))
                samples.forEach { sample ->
                    out.write(sample.toInt() and 0xFF)
                    out.write((sample.toInt() shr 8) and 0xFF)
                }
            }
        }
    }

    private fun createAlternatingMonoWav(sections: List<Section>, sampleRate: Int = 1_000): File {
        val samples = ShortArray(sections.sumOf { it.durationSec * sampleRate })
        var cursor = 0
        sections.forEach { section ->
            repeat(section.durationSec * sampleRate) { index ->
                val sign = if (index % 2 == 0) 1 else -1
                samples[cursor++] = (section.amplitude * sign).toShort()
            }
        }

        return File.createTempFile("wav_post_alt_", ".wav").apply {
            FileOutputStream(this).use { out ->
                out.write(buildWavHeader(samples.size * 2, sampleRate, 1))
                samples.forEach { sample ->
                    out.write(sample.toInt() and 0xFF)
                    out.write((sample.toInt() shr 8) and 0xFF)
                }
            }
        }
    }

    private fun section(durationSec: Int, amplitude: Int): Section = Section(durationSec, amplitude)

    private fun feature(rmsDb: Float): SignalFeatures = SignalFeatures(
        rmsDb = rmsDb,
        peakNorm = 0.18f,
        lowBandRatio = 0.32f,
        midBandRatio = 0.44f,
        highBandRatio = 0.24f,
        zeroCrossingRate = 0.08f,
        transientDensity = 0.03f,
        crestDb = 9f
    )

    private fun ms(info: WavInfo, frame: Long): Long = (frame * 1_000L) / info.sampleRate.toLong()

    private fun assertNear(actual: Long, expected: Long, toleranceMs: Long = 60L) {
        assertTrue("expected=$expected actual=$actual", abs(actual - expected) <= toleranceMs)
    }

    private data class Section(
        val durationSec: Int,
        val amplitude: Int
    )
}
