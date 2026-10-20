package dev.spookcue.sync

import dev.spookcue.data.model.PhoneticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneticMatcherTest {

    private val matcher = PhoneticMatcher()

    @Test
    fun testTokenizeAndEncode() {
        val tokens = matcher.tokenizeAndEncode("clap clap in the cellar")
        assertEquals(true, tokens.isNotEmpty())
    }

    @Test
    fun testFindBestMatch() {
        val index = listOf(
            PhoneticEntry(startMs = 10000L, phonetics = listOf("KL", "P")),
            PhoneticEntry(startMs = 25000L, phonetics = listOf("SLR", "DR"))
        )

        val match = matcher.findBestMatch(listOf("KL", "P"), index, threshold = 0.8f)
        assertNotNull(match)
        assertEquals(10000L, match?.matchedTimeMs)
    }

    @Test
    fun testNoMatchBelowThreshold() {
        val index = listOf(
            PhoneticEntry(startMs = 10000L, phonetics = listOf("KL", "P"))
        )

        val match = matcher.findBestMatch(listOf("XX", "YY"), index, threshold = 0.8f)
        assertNull(match)
    }
}
