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
    fun testSubsetContainment() {
        // Subtitle entry is 3 words inside a 10-word transcribed speech segment
        val speech = listOf("H", "ANTS", "KT", "FRM", "M", "MM", "FR", "M", "TS", "KT")
        val subtitle = listOf("ANTS", "KT", "FRM")
        val score = TokenSetRatio.calculate(speech, subtitle)
        // Since subtitle is 100% contained in speech, score must be 1.0
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun testPartialMatch() {
        val tokensA = listOf("KL", "P", "SLR")
        val tokensB = listOf("KL", "P", "DR")
        val score = TokenSetRatio.calculate(tokensA, tokensB)
        // 2 common tokens out of 3 each (with functional token weighting): ~0.73f
        assertTrue("Expected partial score between 0.70 and 0.85, got $score", score in 0.70f..0.85f)
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

    @Test
    fun testFuzzyTokenMatchingNearMatch() {
        // "Annabelle Higgins" [ANPL, HKNS] vs misheard "Annabelle Higgin" [ANPL, HKN]
        val subtitle = listOf("ANPL", "HKNS")
        val speech = listOf("ANPL", "HKN")
        val score = TokenSetRatio.calculate(subtitle, speech)
        assertTrue("Expected score >= 0.85 for near phonetic match, got $score", score >= 0.85f)
    }

    @Test
    fun testFuzzyTokenMatchingAcousticConfusion() {
        // "God's heart" [KT, HRT] vs misheard "guard side" [KRT, ST]
        // "KT" and "KRT" match fuzzily (edit dist = 1)
        val subtitle = listOf("AR", "SKRFS", "TX", "KT")
        val speech = listOf("TLR", "SKRFS", "TX", "KRT")
        val score = TokenSetRatio.calculate(subtitle, speech)
        assertTrue("Expected score >= 0.70 for acoustically confused sentence, got $score", score >= 0.70f)
    }

    @Test
    fun testShortTwoWordSubtitleContainmentInLongSpeech() {
        // Short 2-word subtitle "Annabelle Higgins" inside an 8-word transcribed sentence
        val subtitle = listOf("ANPL", "HKNS")
        val speech = listOf("H", "X", "FR", "M", "ANPL", "HKNS", "TR", "ST")
        val score = TokenSetRatio.calculate(speech, subtitle)
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun testAnnabelle10mDialogueMatching() {
        // Transcribed from Whisper at 10m35s:
        // "I was waiting on this until baby. You were feeling me? I'm not guilty, yes."
        val speech = listOf("A", "AS", "ATNK", "AN", "0S", "ANTL", "PP", "A", "AR", "FLNK", "M", "A", "M", "NT", "KLT", "AS")
        // Stored in 250546.json at 630880ms:
        val subtitle = listOf("AL", "RT", "HLT", "AN", "A", "SKNT", "A", "AS", "ATNK", "AN", "0S", "ANTL", "0", "PP", "PT", "A", "AR", "FLNK", "A", "LTL", "KLT", "AS")
        val score = TokenSetRatio.calculate(speech, subtitle)
        println("Annabelle 10m dialogue match score: $score")
        assertTrue("Expected score >= 0.70, got $score", score >= 0.70f)
    }
}
