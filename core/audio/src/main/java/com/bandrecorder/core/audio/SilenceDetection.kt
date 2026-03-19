package com.bandrecorder.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10

data class SignalFeatures(
    val rmsDb: Float,
    val peakNorm: Float,
    val lowBandRatio: Float,
    val midBandRatio: Float,
    val highBandRatio: Float,
    val zeroCrossingRate: Float,
    val transientDensity: Float,
    val crestDb: Float
)

data class AdaptiveSilenceThresholds(
    val autoThresholdDb: Float,
    val enterThresholdDb: Float,
    val exitThresholdDb: Float
)

data class SilenceDecision(
    val isSilence: Boolean,
    val speechLikelihood: Float
)

fun extractSignalFeatures(
    samples: ShortArray,
    size: Int,
    channels: Int,
    sampleRate: Int
): SignalFeatures {
    val usable = size.coerceAtLeast(0)
    if (usable <= 0 || sampleRate <= 0) {
        return SignalFeatures(-90f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    val monoCount = if (channels >= 2) usable / 2 else usable
    if (monoCount <= 0) {
        return SignalFeatures(-90f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    val lowAlpha = lowPassAlpha(sampleRate, 280f)
    val midAlpha = lowPassAlpha(sampleRate, 2_200f)

    var sumSquares = 0.0
    var peakAbs = 0.0
    var lowState = 0.0
    var midState = 0.0
    var lowEnergy = 0.0
    var midEnergy = 0.0
    var highEnergy = 0.0
    var zeroCrossings = 0
    var transientCount = 0
    var previous = 0.0

    var frameIndex = 0
    while (frameIndex < monoCount) {
        val sample = if (channels >= 2) {
            val base = frameIndex * 2
            (((samples[base].toInt() + samples[base + 1].toInt()) / 2).toShort()).toInt()
        } else {
            samples[frameIndex].toInt()
        }
        val norm = sample / Short.MAX_VALUE.toDouble()
        val absNorm = abs(norm)
        sumSquares += norm * norm
        if (absNorm > peakAbs) peakAbs = absNorm

        lowState += lowAlpha * (norm - lowState)
        midState += midAlpha * (norm - midState)
        val low = lowState
        val mid = midState - lowState
        val high = norm - midState
        lowEnergy += low * low
        midEnergy += mid * mid
        highEnergy += high * high

        if (frameIndex > 0 && ((previous >= 0.0) != (norm >= 0.0))) {
            zeroCrossings++
        }
        if (frameIndex > 0 && abs(norm - previous) >= 0.08) {
            transientCount++
        }
        previous = norm
        frameIndex++
    }

    val rms = kotlin.math.sqrt((sumSquares / monoCount.toDouble()).coerceAtLeast(1e-12))
    val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
    val totalBandEnergy = (lowEnergy + midEnergy + highEnergy).coerceAtLeast(1e-12)
    val peakDb = (20.0 * log10(peakAbs.coerceAtLeast(1e-6))).toFloat()
    return SignalFeatures(
        rmsDb = rmsDb,
        peakNorm = peakAbs.coerceIn(0.0, 1.0).toFloat(),
        lowBandRatio = (lowEnergy / totalBandEnergy).toFloat(),
        midBandRatio = (midEnergy / totalBandEnergy).toFloat(),
        highBandRatio = (highEnergy / totalBandEnergy).toFloat(),
        zeroCrossingRate = (zeroCrossings.toDouble() / monoCount.toDouble()).toFloat(),
        transientDensity = (transientCount.toDouble() / monoCount.toDouble()).toFloat(),
        crestDb = (peakDb - rmsDb).coerceAtLeast(0f)
    )
}

fun computeAdaptiveThresholds(
    windows: List<SignalFeatures>,
    thresholdOffsetDb: Float
): AdaptiveSilenceThresholds {
    if (windows.isEmpty()) {
        val auto = -42f
        val enter = (auto + thresholdOffsetDb).coerceIn(-80f, -18f)
        return AdaptiveSilenceThresholds(auto, enter, (enter + 4f).coerceAtMost(-10f))
    }

    val rmsValues = windows.map { it.rmsDb }.sorted()
    val noiseFloor = percentile(rmsValues, 0.18f)
    val activityFloor = percentile(rmsValues, 0.72f)
    val spread = (activityFloor - noiseFloor).coerceAtLeast(0f)
    val baseLift = when {
        spread >= 24f -> 12f
        spread >= 16f -> 10f
        spread >= 10f -> 8f
        else -> 6f
    }
    val auto = (noiseFloor + baseLift).coerceIn(-72f, -24f)
    val enter = (auto + thresholdOffsetDb).coerceIn(-80f, -18f)
    val exit = (enter + 4f).coerceAtMost(-10f)
    return AdaptiveSilenceThresholds(autoThresholdDb = auto, enterThresholdDb = enter, exitThresholdDb = exit)
}

fun evaluateSilence(
    features: SignalFeatures,
    thresholds: AdaptiveSilenceThresholds,
    currentlyInSilence: Boolean
): SilenceDecision {
    val speechLikelihood = computeSpeechLikelihood(features, thresholds.enterThresholdDb, thresholds.exitThresholdDb)
    val belowEnter = features.rmsDb <= thresholds.enterThresholdDb
    val belowExit = features.rmsDb <= thresholds.exitThresholdDb
    val nearExit = features.rmsDb <= thresholds.exitThresholdDb + 1.5f

    val isSilence = if (currentlyInSilence) {
        belowExit || (nearExit && speechLikelihood >= 0.55f)
    } else {
        belowEnter || (nearExit && speechLikelihood >= 0.72f)
    }
    return SilenceDecision(isSilence = isSilence, speechLikelihood = speechLikelihood)
}

private fun computeSpeechLikelihood(
    features: SignalFeatures,
    enterThresholdDb: Float,
    exitThresholdDb: Float
): Float {
    val lowLevelScore = normalize((exitThresholdDb + 8f) - features.rmsDb, 0f, 18f)
    val midFocusScore = normalize(features.midBandRatio, 0.28f, 0.7f)
    val edgePenalty = 1f - normalize(features.lowBandRatio + features.highBandRatio, 0.35f, 0.9f)
    val transientPenalty = 1f - normalize(features.transientDensity, 0.01f, 0.12f)
    val crestPenalty = 1f - normalize(features.crestDb, 5f, 20f)
    val zcrScore = 1f - abs(normalize(features.zeroCrossingRate, 0.03f, 0.18f) - 0.45f) * 1.8f
    val borderlineBonus = normalize((exitThresholdDb + 4f) - features.rmsDb, -6f, 10f)

    return (
        (lowLevelScore * 0.24f) +
            (midFocusScore * 0.24f) +
            (edgePenalty * 0.16f) +
            (transientPenalty * 0.18f) +
            (crestPenalty * 0.10f) +
            (zcrScore.coerceIn(0f, 1f) * 0.08f) +
            (borderlineBonus * 0.10f)
        ).coerceIn(0f, 1f)
}

private fun percentile(values: List<Float>, fraction: Float): Float {
    if (values.isEmpty()) return -42f
    val index = ((values.size - 1) * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, values.lastIndex)
    return values[index]
}

private fun normalize(value: Float, min: Float, max: Float): Float {
    if (max <= min) return 0f
    return ((value - min) / (max - min)).coerceIn(0f, 1f)
}

private fun lowPassAlpha(sampleRate: Int, cutoffHz: Float): Double {
    val dt = 1.0 / sampleRate.toDouble()
    val rc = 1.0 / (2.0 * PI * cutoffHz.toDouble().coerceAtLeast(1.0))
    return dt / (rc + dt)
}
