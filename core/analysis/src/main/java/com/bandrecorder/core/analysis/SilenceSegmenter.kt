package com.bandrecorder.core.analysis

class SilenceSegmenter(
    private val silenceThresholdDb: Float = -45f,
    private val silenceSecondsToCut: Float = 8f
) {
    fun shouldCut(currentRmsDb: Float, continuousSilenceSeconds: Float): Boolean {
        return currentRmsDb <= silenceThresholdDb && continuousSilenceSeconds >= silenceSecondsToCut
    }
}
