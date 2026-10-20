package dev.spookcue.sync

import dev.spookcue.data.model.PhoneticEntry
import dev.spookcue.util.DoubleMetaphone
import dev.spookcue.util.TokenSetRatio
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneticMatcher @Inject constructor() {

    private val metaphone = DoubleMetaphone()

    private val stopwords = setOf(
        "OH", "AH", "UH", "UM", "WHOA", "HEY", "YEAH", "HMM", "HUH", "OKAY",
        "A", "AN", "THE", "AND", "OR", "BUT", "OF", "TO", "IN", "ON"
    )

    data class MatchResult(
        val matchedTimeMs: Long,
        val score: Float,
        val candidateTokens: List<String>
    )

    fun tokenizeAndEncode(text: String): List<String> {
        val words = text.uppercase()
            .replace(Regex("[^A-Z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopwords }

        return words.mapNotNull { word ->
            val enc = metaphone.encode(word)
            enc.primary.ifEmpty { null }
        }
    }

    fun findBestMatch(
        inputTokens: List<String>,
        index: List<PhoneticEntry>,
        threshold: Float = 0.8f
    ): MatchResult? {
        if (inputTokens.isEmpty() || index.isEmpty()) return null

        var bestScore = 0f
        var bestEntry: PhoneticEntry? = null

        for (entry in index) {
            val score = TokenSetRatio.calculate(inputTokens, entry.phonetics)
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        return if (bestScore >= threshold && bestEntry != null) {
            MatchResult(
                matchedTimeMs = bestEntry.startMs,
                score = bestScore,
                candidateTokens = bestEntry.phonetics
            )
        } else {
            null
        }
    }

    fun findBoundedMatch(
        inputTokens: List<String>,
        index: List<PhoneticEntry>,
        centerTimeMs: Long,
        windowMs: Long = 90_000L,
        threshold: Float = 0.8f
    ): MatchResult? {
        val minTime = centerTimeMs - windowMs
        val maxTime = centerTimeMs + windowMs

        val boundedIndex = index.filter { it.startMs in minTime..maxTime }
        return findBestMatch(inputTokens, boundedIndex, threshold)
    }
}
