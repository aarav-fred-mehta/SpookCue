package dev.spookcue.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Software Automatic Gain Control (AGC) and RMS normalizer.
 * Provides smooth, noise-gated gain adjustment to avoid amplifying background noise floors.
 */
@Singleton
class GainController @Inject constructor() {

    private var currentGain = 1.0f
    private val targetRms = 2500.0f
    private val maxGain = 5.0f
    private val minGain = 0.5f
    private val noiseGateRms = 25.0f

    fun processFrame(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples

        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()

        if (rms > noiseGateRms) {
            val desiredGain = (targetRms / rms).coerceIn(minGain, maxGain)
            currentGain += (desiredGain - currentGain) * 0.05f
        } else {
            // Decay toward unity gain when signal is below noise gate
            currentGain += (1.0f - currentGain) * 0.05f
        }

        val output = ShortArray(samples.size)
        for (i in samples.indices) {
            val amplified = (samples[i] * currentGain).toInt()
            output[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }
}
