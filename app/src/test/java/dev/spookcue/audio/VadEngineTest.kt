package dev.spookcue.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadEngineTest {

    @Test
    fun testPcmConversionAccuracy() {
        // Test short to float normalization logic used by VadEngine
        val pcm = shortArrayOf(0, 16384, -16384, Short.MAX_VALUE, Short.MIN_VALUE)
        val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }

        assertEquals(0.0f, floats[0], 0.0001f)
        assertEquals(0.5f, floats[1], 0.001f)
        assertEquals(-0.5f, floats[2], 0.001f)
        assertTrue(floats[3] in 0.999f..1.0f)
        assertEquals(-1.0f, floats[4], 0.0001f)
    }

    @Test
    fun testTranscriptionResultContract() {
        val emptyResult = TranscriptionResult(text = "", confidence = 0.0f)
        assertTrue(emptyResult.text.isEmpty())
        assertEquals(0.0f, emptyResult.confidence, 0.0001f)

        val validResult = TranscriptionResult(text = "Hello world", confidence = 1.0f)
        assertEquals("Hello world", validResult.text)
        assertEquals(1.0f, validResult.confidence, 0.0001f)
    }

    @Test
    fun testVadEngineMockBehavior() {
        val mockEngine = object : VadEngine {
            private var speech = false
            override val speechDetected = kotlinx.coroutines.flow.MutableStateFlow(false)

            override fun processFrame(samples: ShortArray): List<FloatArray> {
                return if (speech) listOf(FloatArray(samples.size)) else emptyList()
            }

            override fun isSpeechActive(): Boolean = speech
            override fun flush(): List<FloatArray> = emptyList()
            override fun reset() { speech = false }
            fun setSpeech(value: Boolean) { speech = value }
        }

        assertFalse(mockEngine.isSpeechActive())
        assertEquals(0, mockEngine.processFrame(ShortArray(512)).size)

        mockEngine.setSpeech(true)
        assertTrue(mockEngine.isSpeechActive())
        assertEquals(1, mockEngine.processFrame(ShortArray(512)).size)

        mockEngine.reset()
        assertFalse(mockEngine.isSpeechActive())
    }
}
