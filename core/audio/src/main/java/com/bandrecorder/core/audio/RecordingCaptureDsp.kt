package com.bandrecorder.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

data class RecordingProcessingConfig(
    val limiterEnabled: Boolean = true,
    val hpfEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,
    val hpfCutoffHz: Float = 35f
) {
    fun bounded(): RecordingProcessingConfig = copy(
        limiterCeilingDb = limiterCeilingDb.coerceIn(-6f, -0.2f),
        hpfCutoffHz = hpfCutoffHz.coerceIn(20f, 120f)
    )
}

data class RecordingCaptureDspStats(
    val active: Boolean = false,
    val limiterHits: Int = 0,
    val statusMessage: String = "Bypass"
)

class RecordingCaptureDsp(
    sampleRateHz: Int,
    channelCount: Int,
    inputGainDb: Float,
    config: RecordingProcessingConfig
) {
    private val sr = sampleRateHz.coerceAtLeast(8_000).toFloat()
    private val channels = channelCount.coerceIn(1, 2)
    private var inputGainLinear = dbToLin(inputGainDb.coerceIn(-24f, 24f))
    private var cfg = config.bounded()
    private val prevX = FloatArray(channels)
    private val prevY = FloatArray(channels)
    private var limiterHitsAccum = 0

    fun processInterleaved(input: ShortArray, sampleCount: Int, out: ShortArray): RecordingCaptureDspStats {
        if (sampleCount <= 0) return RecordingCaptureDspStats()

        val cfgLocal = cfg
        val useHpf = cfgLocal.hpfEnabled
        val useLimiter = cfgLocal.limiterEnabled
        val active = useHpf || useLimiter
        val alpha = highPassAlpha(cfgLocal.hpfCutoffHz, sr)
        val limiterLin = dbToLin(cfgLocal.limiterCeilingDb)
        var limiterHitsBlock = 0

        for (i in 0 until sampleCount) {
            val ch = i % channels
            var x = (input[i] / 32768f) * inputGainLinear

            if (useHpf) {
                val y = alpha * (prevY[ch] + x - prevX[ch])
                prevX[ch] = x
                prevY[ch] = y
                x = y
            }

            if (useLimiter && abs(x) > limiterLin) {
                limiterHitsBlock++
                val sign = if (x >= 0f) 1f else -1f
                x = sign * limiterLin + (x - sign * limiterLin) * 0.18f
            }

            out[i] = (x.coerceIn(-1f, 1f) * 32767f)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        limiterHitsAccum += limiterHitsBlock
        val status = when {
            limiterHitsBlock > 0 && useHpf -> "HPF + limiteur"
            limiterHitsBlock > 0 -> "Limiteur actif"
            useHpf && useLimiter -> "HPF + limiteur"
            useHpf -> "HPF actif"
            useLimiter -> "Limiteur actif"
            else -> "Bypass"
        }
        return RecordingCaptureDspStats(
            active = active,
            limiterHits = limiterHitsAccum,
            statusMessage = status
        )
    }

    private fun dbToLin(db: Float): Float = 10f.pow(db / 20f)

    private fun highPassAlpha(cutoffHz: Float, sr: Float): Float {
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val dt = 1f / sr
        return (rc / (rc + dt)).coerceIn(0.0001f, 0.9999f)
    }
}
