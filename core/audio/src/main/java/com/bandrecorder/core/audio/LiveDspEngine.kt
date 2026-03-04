package com.bandrecorder.core.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class DspOutputMode {
    MONITORING_ONLY,
    MONITORING_AND_RECORDING
}

enum class MixProfile {
    ROCK_POP_BALANCED
}

data class GlobalBalanceConfig(
    val autoBalanceEnabled: Boolean = true,
    val compressionEnabled: Boolean = true,
    val deEsserEnabled: Boolean = true,
    val dspOutputMode: DspOutputMode = DspOutputMode.MONITORING_ONLY,
    val mixProfile: MixProfile = MixProfile.ROCK_POP_BALANCED,
    val eqIntensity: Float = 0.55f,
    val compIntensity: Float = 0.4f,
    val deEsserIntensity: Float = 0.45f,
    val compressorThresholdDb: Float = -18f,
    val compressorRatio: Float = 2.1f,
    val deEsserFrequencyHz: Float = 6500f,
    val deEsserWidthHz: Float = 2800f,
    val limiterCeilingDb: Float = -1.5f,
    val outputTrimDb: Float = 0f
) {
    fun bounded(): GlobalBalanceConfig = copy(
        eqIntensity = eqIntensity.coerceIn(0f, 1f),
        compIntensity = compIntensity.coerceIn(0f, 1f),
        deEsserIntensity = deEsserIntensity.coerceIn(0f, 1f),
        compressorThresholdDb = compressorThresholdDb.coerceIn(-40f, -6f),
        compressorRatio = compressorRatio.coerceIn(1.1f, 6f),
        deEsserFrequencyHz = deEsserFrequencyHz.coerceIn(3500f, 11000f),
        deEsserWidthHz = deEsserWidthHz.coerceIn(800f, 6000f),
        limiterCeilingDb = limiterCeilingDb.coerceIn(-6f, -0.2f),
        outputTrimDb = outputTrimDb.coerceIn(-12f, 6f)
    )
}

data class DspRuntimeStats(
    val active: Boolean = false,
    val bypassed: Boolean = true,
    val cpuGuardActive: Boolean = false,
    val compGainReductionDb: Float = 0f,
    val deEsserGainReductionDb: Float = 0f,
    val limiterHits: Int = 0,
    val statusMessage: String = "Bypass"
)

class LiveDspEngine(
    sampleRateHz: Int,
    channelCount: Int,
    config: GlobalBalanceConfig
) {
    private val sr = sampleRateHz.coerceAtLeast(8_000)
    private val channels = channelCount.coerceIn(1, 2)
    private var cfg = config.bounded()

    private val hpfPrevX = FloatArray(channels)
    private val hpfPrevY = FloatArray(channels)
    private val lowPrev = FloatArray(channels)
    private val midPrev = FloatArray(channels)
    private val hiPrev = FloatArray(channels)

    private var compEnv = 0f
    private var deEsserEnv = 0f

    private var cpuGuardCounter = 0
    private var overloadCounter = 0
    private var limiterHitsAccum = 0

    private val hpfAlpha = highPassAlpha(35f, sr.toFloat())
    private val lowAlpha = lowPassAlpha(150f, sr.toFloat())
    private val midAlpha = lowPassAlpha(2200f, sr.toFloat())
    private val hiAlpha = lowPassAlpha(6500f, sr.toFloat())
    private val compAttack = envelopeCoeff(4f, sr.toFloat())
    private val compRelease = envelopeCoeff(85f, sr.toFloat())
    private val deEsserAttack = envelopeCoeff(2f, sr.toFloat())
    private val deEsserRelease = envelopeCoeff(70f, sr.toFloat())

    fun updateConfig(config: GlobalBalanceConfig) {
        cfg = config.bounded()
    }

    fun processInterleaved(input: ShortArray, sampleCount: Int, out: ShortArray): DspRuntimeStats {
        if (sampleCount <= 0) {
            return DspRuntimeStats()
        }
        var cfgLocal = cfg
        val active = cfgLocal.autoBalanceEnabled || cfgLocal.compressionEnabled || cfgLocal.deEsserEnabled
        if (!active) {
            for (i in 0 until sampleCount) out[i] = input[i]
            return DspRuntimeStats(active = false, bypassed = true, statusMessage = "Bypass")
        }

        val blockFrames = sampleCount / channels
        val blockNs = (blockFrames.toDouble() * 1_000_000_000.0 / sr.toDouble()).toLong().coerceAtLeast(1L)
        val startNs = System.nanoTime()

        val cpuGuard = cpuGuardCounter > 0
        val enableAutoEq = cfgLocal.autoBalanceEnabled && !cpuGuard
        val enableDeEsser = cfgLocal.deEsserEnabled && !cpuGuard
        val enableComp = cfgLocal.compressionEnabled

        val profile = cfgLocal.mixProfile
        val eqAmount = cfgLocal.eqIntensity
        val compAmount = cfgLocal.compIntensity
        val deEssAmount = cfgLocal.deEsserIntensity

        val thresholdLin = dbToLin(cfgLocal.compressorThresholdDb)
        val ratio = cfgLocal.compressorRatio
        val limiterLin = dbToLin(cfgLocal.limiterCeilingDb)
        val trimLin = dbToLin(cfgLocal.outputTrimDb)
        val deEssThreshold = dbToLin(-30f + (1f - deEssAmount) * 10f)

        var compGrDbPeak = 0f
        var deEsserGrDbPeak = 0f
        var limiterHitsBlock = 0

        var i = 0
        while (i < sampleCount) {
            val ch = i % channels
            var x = (input[i] / 32768f).coerceIn(-1f, 1f)

            // HPF anti-rumble
            val yHp = hpfAlpha * (hpfPrevY[ch] + x - hpfPrevX[ch])
            hpfPrevX[ch] = x
            hpfPrevY[ch] = yHp
            x = yHp

            if (enableAutoEq) {
                val low = onePoleLowPass(x, lowAlpha, lowPrev, ch)
                val mid = onePoleLowPass(x, midAlpha, midPrev, ch)
                val hiBand = x - onePoleLowPass(x, hiAlpha, hiPrev, ch)
                val lowBand = low
                val presenceBand = mid - low

                val shaped = when (profile) {
                    MixProfile.ROCK_POP_BALANCED -> {
                        val lowBoost = 0.22f * eqAmount
                        val presenceBoost = 0.16f * eqAmount
                        val harshCut = 0.10f * eqAmount
                        x + (lowBand * lowBoost) + (presenceBand * presenceBoost) - (hiBand * harshCut)
                    }
                }
                x = shaped
            }

            if (enableComp) {
                val detector = abs(x)
                compEnv = followEnvelope(compEnv, detector, compAttack, compRelease)
                val over = if (compEnv > thresholdLin) compEnv / thresholdLin else 1f
                val gain = if (over > 1f) over.pow((1f / ratio) - 1f) else 1f
                x *= 1f - ((1f - gain) * compAmount)
                val grDb = linToDb(max(gain, 1e-6f)) * -1f
                if (grDb > compGrDbPeak) compGrDbPeak = grDb
            }

            if (enableDeEsser) {
                val hi = x - onePoleLowPass(x, hiAlpha, hiPrev, ch)
                val detector = abs(hi)
                deEsserEnv = followEnvelope(deEsserEnv, detector, deEsserAttack, deEsserRelease)
                if (deEsserEnv > deEssThreshold) {
                    val over = ((deEsserEnv / deEssThreshold) - 1f).coerceAtLeast(0f)
                    val reduction = (over * 0.35f * deEssAmount).coerceIn(0f, 0.85f)
                    x -= hi * reduction
                    val grDb = linToDb((1f - reduction).coerceAtLeast(1e-5f)) * -1f
                    if (grDb > deEsserGrDbPeak) deEsserGrDbPeak = grDb
                }
            }

            x *= trimLin

            if (abs(x) > limiterLin) {
                limiterHitsBlock++
                val sign = if (x >= 0f) 1f else -1f
                x = sign * limiterLin + (x - sign * limiterLin) * 0.18f
                x = x.coerceIn(-1f, 1f)
            }

            out[i] = (x * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }

        limiterHitsAccum += limiterHitsBlock

        val elapsedNs = System.nanoTime() - startNs
        if (elapsedNs > (blockNs * 6L / 10L)) {
            overloadCounter++
        } else {
            overloadCounter = max(0, overloadCounter - 1)
        }

        if (overloadCounter >= 8) {
            cpuGuardCounter = 80
        } else if (cpuGuardCounter > 0) {
            cpuGuardCounter--
        }

        val guardActive = cpuGuardCounter > 0
        val status = when {
            guardActive -> "CPU élevé"
            limiterHitsBlock > 0 -> "Risque saturation"
            else -> "Actif"
        }
        return DspRuntimeStats(
            active = true,
            bypassed = false,
            cpuGuardActive = guardActive,
            compGainReductionDb = compGrDbPeak,
            deEsserGainReductionDb = deEsserGrDbPeak,
            limiterHits = limiterHitsAccum,
            statusMessage = status
        )
    }

    private fun onePoleLowPass(x: Float, alpha: Float, state: FloatArray, ch: Int): Float {
        val y = alpha * x + (1f - alpha) * state[ch]
        state[ch] = y
        return y
    }

    private fun followEnvelope(current: Float, input: Float, attack: Float, release: Float): Float {
        return if (input > current) attack * current + (1f - attack) * input
        else release * current + (1f - release) * input
    }

    private fun dbToLin(db: Float): Float = 10f.pow(db / 20f)
    private fun linToDb(lin: Float): Float = (20f * log10(max(lin, 1e-9f)))
    private fun envelopeCoeff(ms: Float, sr: Float): Float = exp(-1f / (max(1f, ms) * 0.001f * sr))
    private fun lowPassAlpha(cutoffHz: Float, sr: Float): Float {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz.coerceAtLeast(10f))
        val dt = 1f / sr
        return (dt / (rc + dt)).coerceIn(0.0001f, 0.9999f)
    }

    private fun highPassAlpha(cutoffHz: Float, sr: Float): Float {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz.coerceAtLeast(10f))
        val dt = 1f / sr
        return (rc / (rc + dt)).coerceIn(0.0001f, 0.9999f)
    }
}

