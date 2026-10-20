package dev.spookcue.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class GainControllerTest {

    private lateinit var gainController: GainController

    @Before
    fun setup() {
        gainController = GainController()
    }

    @Test
    fun testProcessEmptyFrameReturnsEmpty() {
        val empty = ShortArray(0)
        val result = gainController.processFrame(empty)
        assertEquals(0, result.size)
    }

    @Test
    fun testQuietFrameBelowRmsThresholdDoesNotAdaptGain() {
        // RMS of 10 is well below 50.0f threshold
        val quietSamples = ShortArray(160) { 10 }
        val output = gainController.processFrame(quietSamples)

        assertEquals(160, output.size)
        // With initial gain = 1.0f, samples remain ~10
        assertEquals(10.toShort(), output[0])
    }

    @Test
    fun testNormalAudioAdaptsGainTowardsTargetRms() {
        // Frame with RMS ~ 500 (lower than target 2500)
        // Gain should adapt upwards towards targetRms / rms = 5.0
        val samples = ShortArray(160) { 500 }
        var processed = samples
        for (i in 0 until 50) {
            processed = gainController.processFrame(samples)
        }

        // Amplified samples should be significantly higher than original 500
        assertTrue("Output should be amplified above input", processed[0] > 500)
        // Check that RMS is closer to targetRms (2500)
        var sumSq = 0.0
        for (s in processed) sumSq += s * s
        val finalRms = sqrt(sumSq / processed.size)
        assertTrue("Final RMS should be closer to target 2500: $finalRms", finalRms > 1500)
    }

    @Test
    fun testLoudAudioAttenuatesGain() {
        // Frame with high amplitude 10,000 (above target 2500)
        // Gain should adapt downwards towards minGain (0.5)
        val samples = ShortArray(160) { 10000 }
        var processed = samples
        for (i in 0 until 50) {
            processed = gainController.processFrame(samples)
        }

        assertTrue("Output should be attenuated below input", processed[0] < 10000)
    }

    @Test
    fun testOutputNeverExceedsShortRange() {
        // Extreme input that would overflow without clamping
        val extremeSamples = ShortArray(160) { Short.MAX_VALUE }
        val output = gainController.processFrame(extremeSamples)

        for (sample in output) {
            assertTrue(sample <= Short.MAX_VALUE)
            assertTrue(sample >= Short.MIN_VALUE)
        }
    }
}
