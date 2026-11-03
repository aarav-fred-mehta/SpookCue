package dev.spookcue.sync

import dev.spookcue.data.model.PhoneticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneticMatcherTest {

    private val matcher = PhoneticMatcher()

    @Test
    fun testTokenizeAndEncode() {
        val tokens = matcher.tokenizeAndEncode("clap clap in the cellar")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.contains("SLR"))
        assertTrue(tokens.contains("AN"))
        assertTrue(tokens.contains("0"))
    }

    @Test
    fun testFindBestMatchExact() {
        val index = listOf(
            PhoneticEntry(startMs = 10000L, phonetics = listOf("KL", "P", "SLR")),
            PhoneticEntry(startMs = 25000L, phonetics = listOf("SLR", "DR", "FST"))
        )

        val match = matcher.findBestMatch(listOf("KL", "P", "SLR"), index, threshold = 0.8f)
        assertNotNull(match)
        assertEquals(10000L, match?.matchedTimeMs)
    }

    @Test
    fun testFindBestMatchContainment() {
        val index = listOf(
            PhoneticEntry(startMs = 77000L, phonetics = listOf("KML", "KT", "AN", "TX"))
        )
        val speechTokens = listOf("A", "HRT", "KML", "KT", "AN", "TX", "A0", "A", "MTM")

        val match = matcher.findBestMatch(speechTokens, index, threshold = 0.70f)
        assertNotNull(match)
        assertEquals(77000L, match?.matchedTimeMs)
        assertEquals(1.0f, match?.score ?: 0f, 0.001f)
    }

    @Test
    fun testNoMatchBelowThreshold() {
        val index = listOf(
            PhoneticEntry(startMs = 10000L, phonetics = listOf("KL", "P", "SLR"))
        )

        val match = matcher.findBestMatch(listOf("XX", "YY", "ZZ"), index, threshold = 0.8f)
        assertNull(match)
    }

    @Test
    fun testEvaluateTopK() {
        val index = listOf(
            PhoneticEntry(startMs = 10000L, phonetics = listOf("KL", "P", "SLR")),      // exact 3 match
            PhoneticEntry(startMs = 20000L, phonetics = listOf("KL", "P", "OTHER")),    // 2 match
            PhoneticEntry(startMs = 30000L, phonetics = listOf("KL", "DIFF", "OTHER")), // 1 match
            PhoneticEntry(startMs = 40000L, phonetics = listOf("NO", "MATCH", "HERE"))  // 0 match
        )

        val input = listOf("KL", "P", "SLR")
        val topK = matcher.evaluateTopK(input, index, k = 2, threshold = 0.5f)

        assertEquals(2, topK.size)
        assertEquals(10000L, topK[0].entry.startMs)
        assertEquals(20000L, topK[1].entry.startMs)
        assertTrue(topK[0].score >= topK[1].score)
    }

    @Test
    fun testEvaluateTopKBounded() {
        val index = listOf(
            PhoneticEntry(startMs = 10_000L, phonetics = listOf("KL", "P", "SLR")),
            PhoneticEntry(startMs = 60_000L, phonetics = listOf("KL", "P", "SLR")),
            PhoneticEntry(startMs = 120_000L, phonetics = listOf("KL", "P", "SLR")),
            PhoneticEntry(startMs = 200_000L, phonetics = listOf("KL", "P", "SLR"))
        )

        val input = listOf("KL", "P", "SLR")
        // Center at 65,000L with 15,000L window => only 60,000L is in range [50,000 .. 80,000]
        val bounded = matcher.evaluateTopKBounded(
            inputTokens = input,
            index = index,
            centerTimeMs = 65_000L,
            windowMs = 15_000L,
            k = 3,
            threshold = 0.7f
        )

        assertEquals(1, bounded.size)
        assertEquals(60_000L, bounded[0].entry.startMs)
    }

    @Test
    fun testTokenizeAndEncodeStripsSoundDescriptions() {
        // Audio descriptions in parentheses or brackets should be stripped completely
        val textWithDescriptions = "(sighs) clap clap [groans] in the cellar (speaks foreign language) *music*"
        val tokens = matcher.tokenizeAndEncode(textWithDescriptions)
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.contains("SLR"))
        assertTrue(tokens.contains("KLP"))
        // None of the description words (sighs, groans, speaks, foreign, language, music) should be present
        assertFalse(tokens.contains("SS")) // sighs
        assertFalse(tokens.contains("KRNS")) // groans
        assertFalse(tokens.contains("SPKS")) // speaks
        assertFalse(tokens.contains("FRN")) // foreign
        assertFalse(tokens.contains("LNKJ")) // language
        assertFalse(tokens.contains("MSK")) // music
    }

    @Test
    fun testOnlyDescriptionReturnsEmpty() {
        val onlyDesc = "(speaks foreign language) [sighs] *clicking*"
        val tokens = matcher.tokenizeAndEncode(onlyDesc)
        assertTrue(tokens.isEmpty())
    }
}
