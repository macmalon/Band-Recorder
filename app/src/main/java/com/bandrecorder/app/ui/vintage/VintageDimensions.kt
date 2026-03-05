package com.bandrecorder.app.ui.vintage

import androidx.compose.ui.unit.dp

object VintageDimensions {
    val screenPadding = 16.dp
    val sectionSpacing = 12.dp
    val smallSpacing = 8.dp

    val knobSize = 110.dp
    val recordButtonSize = 160.dp
    val vuWidth = 220.dp
    val vuHeight = 132.dp

    val topPanelHeight = 56.dp
    val navButtonHeight = 46.dp
    val switchWidth = 116.dp
    val switchHeight = 44.dp

    val timelineHeight = 42.dp
    val recLedSize = 16.dp
    val vuCornerRadius = 12.dp
    val hardwareButtonCorner = 14.dp

    val tolexScale = 1.6f
    val tolexMinScale = 1.4f
    val tolexMaxScale = 1.8f
    const val tolexTranslationXPx = -40f
    const val tolexTranslationYPx = -20f
    const val tolexNoiseAlpha = 0.025f

    const val vuNeedlePivotX = 0.5f
    const val vuNeedlePivotY = 0.78f
    const val vuNeedleBaseAngleOffset = 0f
    const val vuMinAngle = -45f
    const val vuMaxAngle = 45f
    const val vuAnimationDurationMs = 80

    const val knobBaseAngle = 12f
    const val knobTapAmplitudeDeg = 6f
    const val knobFeedbackDurationMs = 120
    const val knobTapOutRatio = 0.7f

    const val ledPulseMinAlpha = 0.6f
    const val ledPulseMaxAlpha = 1f
    const val ledGlowVariation = 0.1f
    const val ledPulseDurationMs = 900

    val topToVuSpacing = 24.dp
    val vuToRecordSpacing = 32.dp
    val recordToKnobSpacing = 28.dp
    val knobToHardwareSpacing = 36.dp
    val facadeMaxWidth = 520.dp
}
