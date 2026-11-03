package dev.spookcue.sync

import android.util.Log
import dev.spookcue.data.model.PhoneticEntry
import dev.spookcue.util.DoubleMetaphone
import dev.spookcue.util.TokenSetRatio
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneticMatcher @Inject constructor() {

    companion object {
        private const val TAG = "PhoneticMatcher"
        const val DEFAULT_THRESHOLD = 0.70f // Tolerant threshold for distant audio
    }

    private val metaphone = DoubleMetaphone()

    // Must match Python scraper's sanitizer.py STOPWORDS exactly!
    // Python only filters low-information interjections:
    // {"oh", "ah", "uh", "um", "whoa", "hey", "yeah", "hmm", "huh", "okay", "ok"}
    // Pre-indexed JSON files DO contain words like "a", "the", "in", "to", "and",
    // so we must NOT filter those out here!
    private val stopwords = setOf(
        "OH", "AH", "UH", "UM", "WHOA", "HEY", "YEAH", "HMM", "HUH", "OKAY", "OK"
    )

    data class MatchResult(
        val matchedTimeMs: Long,
        val score: Float,
        val candidateTokens: List<String>
    )

    data class MatchEvaluation(
        val bestEntry: PhoneticEntry?,
        val bestScore: Float,
        val isMatch: Boolean
    )

    data class RankedMatch(
        val entry: PhoneticEntry,
        val score: Float
    )

    fun tokenizeAndEncode(text: String): List<String> {
        val cleanText = text
            .replace(Regex("""\([^\)]*\)|\[[^\]]*\]|\*[^\*]*\*"""), " ")
            .uppercase()
            .replace(Regex("[^A-Z0-9 ]"), " ")
        val words = cleanText
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopwords }

        return words.mapNotNull { word ->
            val enc = metaphone.encode(word)
            enc.primary.ifEmpty { null }
        }
    }

    fun evaluate(
        inputTokens: List<String>,
        index: List<PhoneticEntry>,
        threshold: Float = DEFAULT_THRESHOLD
    ): MatchEvaluation {
        if (inputTokens.isEmpty() || index.isEmpty()) {
            return MatchEvaluation(bestEntry = null, bestScore = 0f, isMatch = false)
        }

        var bestScore = 0f
        var bestEntry: PhoneticEntry? = null

        for (entry in index) {
            val score = TokenSetRatio.calculate(inputTokens, entry.phonetics)
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        if (bestEntry != null && bestScore >= 0.40f) {
            Log.d(TAG, "Best phonetic candidate: score=${"%.2f".format(bestScore)} at ${bestEntry.startMs / 1000}s (threshold=$threshold)")
        }

        val isMatch = bestScore >= threshold && bestEntry != null
        if (isMatch) {
            Log.i(TAG, "Positive match found! Score=${"%.2f".format(bestScore)} at ${bestEntry!!.startMs / 1000}s")
        }

        return MatchEvaluation(
            bestEntry = bestEntry,
            bestScore = bestScore,
            isMatch = isMatch
        )
    }

    /**
     * Returns the top-K matches above threshold, sorted by score descending.
     * Used by SyncEngine for multi-candidate consensus.
     */
    fun evaluateTopK(
        inputTokens: List<String>,
        index: List<PhoneticEntry>,
        k: Int = 3,
        threshold: Float = DEFAULT_THRESHOLD
    ): List<RankedMatch> {
        if (inputTokens.isEmpty() || index.isEmpty() || k <= 0) return emptyList()

        val heap = PriorityQueue<RankedMatch>(k + 1, compareBy { it.score })

        for (entry in index) {
            val score = TokenSetRatio.calculate(inputTokens, entry.phonetics)
            if (score >= threshold) {
                heap.add(RankedMatch(entry, score))
                if (heap.size > k) heap.poll()
            }
        }

        return heap.sortedByDescending { it.score }
    }

    /**
     * Bounded top-K search using binary search on startMs.
     */
    fun evaluateTopKBounded(
        inputTokens: List<String>,
        index: List<PhoneticEntry>,
        centerTimeMs: Long,
        windowMs: Long,
        k: Int = 3,
        threshold: Float = DEFAULT_THRESHOLD
    ): List<RankedMatch> {
        if (inputTokens.isEmpty() || index.isEmpty() || k <= 0) return emptyList()
        val minTime = centerTimeMs - windowMs
        val maxTime = centerTimeMs + windowMs

        val rawStart = index.binarySearchBy(minTime) { it.startMs }
        var startIdx = if (rawStart < 0) -(rawStart + 1) else rawStart
        while (startIdx > 0 && index[startIdx - 1].startMs >= minTime) {
            startIdx--
        }

        val rawEnd = index.binarySearchBy(maxTime) { it.startMs }
        var endIdx = if (rawEnd < 0) -(rawEnd + 1) else rawEnd + 1
        while (endIdx < index.size && index[endIdx].startMs <= maxTime) {
            endIdx++
        }

        val safeStart = startIdx.coerceIn(0, index.size)
        val safeEnd = endIdx.coerceIn(safeStart, index.size)
        val bounded = index.subList(safeStart, safeEnd)
        return evaluateTopK(inputTokens, bounded, k, threshold)
    }

    fun findBestMatch(
        inputTokens: List<String>,
        index: List<PhoneticEntry>,
        threshold: Float = DEFAULT_THRESHOLD
    ): MatchResult? {
        val eval = evaluate(inputTokens, index, threshold)
        return if (eval.isMatch && eval.bestEntry != null) {
            MatchResult(
                matchedTimeMs = eval.bestEntry.startMs,
                score = eval.bestScore,
                candidateTokens = eval.bestEntry.phonetics
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
        threshold: Float = DEFAULT_THRESHOLD
    ): MatchResult? {
        val minTime = centerTimeMs - windowMs
        val maxTime = centerTimeMs + windowMs

        val boundedIndex = index.filter { it.startMs in minTime..maxTime }
        return findBestMatch(inputTokens, boundedIndex, threshold)
    }
}
