package dev.spookcue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenSetRatioTest {

    @Test
    fun testExactMatch() {
        val tokensA = listOf("KL", "P", "KL", "P")
        val tokensB = listOf("KL", "P")
        val score = TokenSetRatio.calculate(tokensA, tokensB)
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun testPartialMatch() {
        val tokensA = listOf("KL", "P", "SLR")
        val tokensB = listOf("KL", "P", "DR")
        val score = TokenSetRatio.calculate(tokensA, tokensB)
        assertEquals(0.5f, score, 0.001f)
    }

    @Test
    fun testDisjointSets() {
        val tokensA = listOf("NHT", "SLR")
        val tokensB = listOf("PL", "FST")
        val score = TokenSetRatio.calculate(tokensA, tokensB)
        assertEquals(0.0f, score, 0.001f)
    }

    @Test
    fun testHighSimilarityThreshold() {
        val tokensA = listOf("A", "B", "C", "D", "E")
        val tokensB = listOf("A", "B", "C", "D")
        val score = TokenSetRatio.calculate(tokensA, tokensB)
        assertTrue(score >= 0.8f)
    }
}
