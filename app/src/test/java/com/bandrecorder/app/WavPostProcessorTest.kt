package com.bandrecorder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.math.abs

class WavPostProcessorTest {

    @Test
    fun `analysis keeps single segment when no silence exceeds threshold`() {
        val file = createMonoWav(listOf(section(3, 12_000)))

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = -20f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(1, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments.first().startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments.first().endFrame), 3_000L)
    }

    @Test
    fun `analysis splits long silence and keeps n seconds before cut`() {
        val file = createMonoWav(
            listOf(
                section(2, 12_000),
                section(2, 0),
                section(2, 12_000)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = -35f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(2, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].startFrame), 0L)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 3_000L)
        assertNear(ms(analysis.info, analysis.segments[1].startFrame), 4_000L)
        assertNear(ms(analysis.info, analysis.segments[1].endFrame), 6_000L)
    }

    @Test
    fun `analysis ignores silence at file edges and trims after n seconds`() {
        val file = createMonoWav(
            listOf(
                section(2, 0),
                section(2, 12_000),
                section(2, 0)
            )
        )

        val analysis = analyzeWavBySilence(file, silenceThresholdDb = -35f, silenceDurationSec = 1)

        requireNotNull(analysis)
        assertEquals(1, analysis.segments.size)
        assertNear(ms(analysis.info, analysis.segments[0].startFrame), 2_000L)
        assertNear(ms(analysis.info, analysis.segments[0].endFrame), 5_000L)
    }

    @Test
    fun `cleaned wav concatenates kept segments in order`() {
        val source = createMonoWav(
            listOf(
                section(2, 12_000),
                section(2, 0),
                section(2, 9_000)
            )
        )
        val analysis = requireNotNull(analyzeWavBySilence(source, silenceThresholdDb = -35f, silenceDurationSec = 1))
        val out = File.createTempFile("cleaned_", ".wav")

        writeCleanedWav(source, analysis.info, analysis.segments, out)

        val outInfo = requireNotNull(readWavInfo(out))
        val totalFrames = outInfo.dataSize / (outInfo.channels * 2)
        assertNear(ms(outInfo, totalFrames), 5_000L)
    }

    @Test
    fun `segment export writes one wav per detected segment`() {
        val source = createMonoWav(
            listOf(
                section(2, 12_000),
                section(2, 0),
                section(2, 12_000)
            )
        )
        val analysis = requireNotNull(analyzeWavBySilence(source, silenceThresholdDb = -35f, silenceDurationSec = 1))
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

    private fun section(durationSec: Int, amplitude: Int): Section = Section(durationSec, amplitude)

    private fun ms(info: WavInfo, frame: Int): Long = (frame.toLong() * 1_000L) / info.sampleRate.toLong()

    private fun assertNear(actual: Long, expected: Long, toleranceMs: Long = 60L) {
        assertTrue("expected=$expected actual=$actual", abs(actual - expected) <= toleranceMs)
    }

    private data class Section(
        val durationSec: Int,
        val amplitude: Int
    )
}
