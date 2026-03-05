package com.bandrecorder.app

enum class PlayerFxPreset {
    FLAT,
    ROCK,
    POP
}

data class PlayerFxConfig(
    val preset: PlayerFxPreset = PlayerFxPreset.ROCK,
    val eqEnabled: Boolean = true,
    val compressionEnabled: Boolean = true,
    val deEsserEnabled: Boolean = true,
    val eqIntensity: Float = 0.45f,
    val compressionIntensity: Float = 0.35f,
    val deEsserIntensity: Float = 0.35f,
    val boostIntensity: Float = 0f
) {
    fun bounded(): PlayerFxConfig = copy(
        eqIntensity = eqIntensity.coerceIn(0f, 1f),
        compressionIntensity = compressionIntensity.coerceIn(0f, 1f),
        deEsserIntensity = deEsserIntensity.coerceIn(0f, 1f),
        boostIntensity = boostIntensity.coerceIn(0f, 1f)
    )
}
