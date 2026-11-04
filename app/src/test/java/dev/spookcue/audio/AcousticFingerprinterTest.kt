package dev.spookcue.audio

import dev.spookcue.data.model.AudioHashEntry
import dev.spookcue.sync.AudioHashMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AcousticFingerprinterTest {

    private lateinit var fingerprinter: AcousticFingerprinter
    private lateinit var matcher: AudioHashMatcher

    @Before
    fun setUp() {
        fingerprinter = AcousticFingerprinter()
        matcher = AudioHashMatcher()
    }

    @Test
    fun testEncodeHashIntegrity() {
        val f1 = 45
        val f2 = 120
        val deltaFrames = 15

        val hash = fingerprinter.encodeHash(f1, f2, deltaFrames)
        val decodedF1 = hash and 0x1FF
        val decodedF2 = (hash shr 9) and 0x1FF
        val decodedDt = (hash shr 18) and 0x3F

        assertEquals(f1, decodedF1)
        assertEquals(f2, decodedF2)
        assertEquals(deltaFrames, decodedDt)
    }

    @Test
    fun testFingerprintExtractionFromTone() {
        // Generate 2 seconds of 16kHz audio with mixed 440 Hz + 1200 Hz tones
        val sampleRate = 16000
        val durationSec = 2
        val samples = FloatArray(sampleRate * durationSec) { i ->
            val t = i.toFloat() / sampleRate
            (0.6f * sin(2.0 * PI * 440.0 * t) + 0.5f * sin(2.0 * PI * 1200.0 * t)).toFloat()
        }

        val hashes = fingerprinter.extractFingerprints(samples, startTimestampOffsetMs = 1000L)
        assertTrue("Expected extracted hashes from multi-frequency audio", hashes.isNotEmpty())
        assertTrue("Expected hashes to have valid timestamps", hashes.all { it.t >= 1000L })
    }

    @Test
    fun testMatcherConsensusVoting() {
        val referenceHashes = listOf(
            AudioHashEntry(h = 501, t = 10_000L),
            AudioHashEntry(h = 502, t = 10_300L),
            AudioHashEntry(h = 503, t = 10_600L),
            AudioHashEntry(h = 504, t = 10_900L),
            AudioHashEntry(h = 505, t = 11_200L),
            AudioHashEntry(h = 506, t = 11_500L),
            AudioHashEntry(h = 507, t = 11_800L)
        )
        matcher.initIndex(referenceHashes)

        // Capture buffer with 7 matching sample hashes at offset 10_000ms
        val sampleHashes = listOf(
            AudioHashEntry(h = 501, t = 0L),
            AudioHashEntry(h = 502, t = 300L),
            AudioHashEntry(h = 503, t = 600L),
            AudioHashEntry(h = 504, t = 900L),
            AudioHashEntry(h = 505, t = 1200L),
            AudioHashEntry(h = 506, t = 1500L),
            AudioHashEntry(h = 507, t = 1800L)
        )

        val result = matcher.match(sampleHashes, bufferDurationMs = 1800L)
        assertNotNull("Expected positive hash match", result)
        assertEquals(11_800L, result!!.movieTimeMs)
        assertEquals(7, result.votes)
    }

    @Test
    fun testRejectsStationarySameBinHum() {
        val sampleRate = 16000
        val durationSec = 2
        val samples = FloatArray(sampleRate * durationSec) { i ->
            val t = i.toFloat() / sampleRate
            (0.8f * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * t)).toFloat()
        }
        val hashes = fingerprinter.extractFingerprints(samples)
        assertTrue(hashes.isEmpty())
    }
}