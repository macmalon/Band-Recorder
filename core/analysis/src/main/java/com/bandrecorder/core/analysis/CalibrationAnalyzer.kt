package com.bandrecorder.core.analysis

import kotlin.math.log10
import kotlin.math.sqrt

class CalibrationAnalyzer {
    fun computeRmsDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -90f
        var sum = 0.0
        for (s in samples) {
            val n = s / Short.MAX_VALUE.toDouble()
            sum += n * n
        }
        val rms = sqrt(sum / samples.size)
        return (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
    }

    fun computePeakDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -90f
        var peak = 0.0
        for (s in samples) {
            val n = kotlin.math.abs(s / Short.MAX_VALUE.toDouble())
            if (n > peak) peak = n
        }
        return (20.0 * log10(peak.coerceAtLeast(1e-6))).toFloat()
    }
}
