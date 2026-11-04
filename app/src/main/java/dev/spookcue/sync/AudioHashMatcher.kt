package dev.spookcue.sync

import dev.spookcue.data.model.AudioHashEntry
import dev.spookcue.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Matches live acoustic landmark hashes against a movie's precomputed hash index
 * using offset histogram voting (Shazam / Dejavu algorithm) with stop-word frequency
 * pruning and TF-IDF uniqueness weighting to prevent false locks on drone/ambient noise.
 */
@Singleton
class AudioHashMatcher @Inject constructor() {

    companion object {
        private const val TAG = "AudioHashMatcher"
        const val BUCKET_SIZE_MS = 300L // 300ms histogram resolution
        const val MIN_CONSENSUS_VOTES = 6 // Minimum peak pair alignments required for full search
        const val MIN_WEIGHTED_SCORE = 12.0f // Minimum uniqueness score required (noise ceiling is 10.0)
        const val CONFIDENCE_RATIO_THRESHOLD = 1.7f // Primary bucket score vs runner-up score
        const val MAX_KEY_OCCURRENCES = 25 // Stop-word threshold to prune repetitive drone/noise frequencies
    }

    data class HashMatchResult(
        val movieTimeMs: Long,
        val confidence: Float,
        val votes: Int,
        val runnerUpVotes: Int = 0
    )

    data class HashMatchDiagnostics(
        val totalHashes: Int = 0,
        val bestVotes: Int = 0,
        val runnerUpVotes: Int = 0,
        val bestEstimatedMovieTimeMs: Long = 0L,
        val isMatch: Boolean = false
    )

    private class BucketStats {
        var votes: Int = 0
        var score: Float = 0f
        var sumOffset: Long = 0L

        fun add(offset: Long, weight: Float) {
            votes++
            score += weight
            sumOffset += offset
        }

        fun averageOffset(): Long = if (votes > 0) sumOffset / votes else 0L
    }

    private val indexTable = mutableMapOf<Int, MutableList<Long>>()
    private val keyFrequencyTable = mutableMapOf<Int, Int>()
    private var totalIndexedEntries = 0

    var lastDiagnostics: HashMatchDiagnostics = HashMatchDiagnostics()
        private set

    /**
     * Initializes the lookup table for the movie's acoustic hashes with stop-word pruning.
     */
    @Synchronized
    fun initIndex(entries: List<AudioHashEntry>) {
        indexTable.clear()
        keyFrequencyTable.clear()
        totalIndexedEntries = entries.size

        // Count raw occurrences across movie to detect repetitive drone/hum frequencies
        for (entry in entries) {
            keyFrequencyTable[entry.h] = (keyFrequencyTable[entry.h] ?: 0) + 1
        }

        var prunedKeys = 0
        var prunedHashes = 0
        for (entry in entries) {
            val freq = keyFrequencyTable[entry.h] ?: 1
            if (freq > MAX_KEY_OCCURRENCES) {
                prunedHashes++
                continue
            }
            val list = indexTable.getOrPut(entry.h) { mutableListOf() }
            list.add(entry.t)
        }

        for ((_, freq) in keyFrequencyTable) {
            if (freq > MAX_KEY_OCCURRENCES) prunedKeys++
        }

        AppLogger.i(TAG, "Initialized acoustic hash index: ${totalIndexedEntries - prunedHashes} hashes retained (${indexTable.size} unique keys). Pruned $prunedKeys stop-word keys ($prunedHashes repetitive hashes).")
    }

    @Synchronized
    fun clear() {
        indexTable.clear()
        keyFrequencyTable.clear()
        totalIndexedEntries = 0
        lastDiagnostics = HashMatchDiagnostics()
    }

    private fun computeKeyWeight(freq: Int): Float = when {
        freq <= 2 -> 3.0f
        freq <= 8 -> 2.0f
        freq <= 20 -> 1.0f
        else -> 0.5f
    }

    /**
     * Matches sample hashes against the entire movie index (Stage 1 / Global Search).
     * [sampleHashes]: hashes extracted from captured audio, with sample timestamps relative to buffer start (0..bufferDurationMs).
     * [bufferDurationMs]: the duration of the audio buffer (default 3500ms).
     */
    @Synchronized
    fun match(sampleHashes: List<AudioHashEntry>, bufferDurationMs: Long = 3500L): HashMatchResult? {
        if (indexTable.isEmpty() || sampleHashes.isEmpty()) {
            lastDiagnostics = HashMatchDiagnostics(totalHashes = sampleHashes.size)
            return null
        }

        val histogram = mutableMapOf<Long, BucketStats>()

        for (sample in sampleHashes) {
            val movieTimestamps = indexTable[sample.h] ?: continue
            val freq = keyFrequencyTable[sample.h] ?: 1
            val weight = computeKeyWeight(freq)
            for (movieT in movieTimestamps) {
                val offset = movieT - sample.t
                val bucketKey = ((offset.toDouble() / BUCKET_SIZE_MS).roundToLong()) * BUCKET_SIZE_MS
                val stats = histogram.getOrPut(bucketKey) { BucketStats() }
                stats.add(offset, weight)
            }
        }

        if (histogram.isEmpty()) {
            lastDiagnostics = HashMatchDiagnostics(totalHashes = sampleHashes.size)
            return null
        }

        val sortedBuckets = histogram.entries.sortedByDescending { it.value.score }
        val best = sortedBuckets.first()
        val runnerUpScore = sortedBuckets.getOrNull(1)?.value?.score ?: 0f
        val runnerUpVotes = sortedBuckets.getOrNull(1)?.value?.votes ?: 0

        val score = best.value.score
        val votes = best.value.votes
        val ratio = if (runnerUpScore > 0f) score / runnerUpScore else score
        val actualOffset = best.value.averageOffset()
        val estimatedMovieTimeMs = (actualOffset + bufferDurationMs).coerceAtLeast(0L)

        val isLocked = votes >= MIN_CONSENSUS_VOTES && score >= MIN_WEIGHTED_SCORE &&
            (runnerUpScore == 0f || ratio >= CONFIDENCE_RATIO_THRESHOLD)
        val confidence = (score / (score + 10f)).coerceIn(0.5f, 1.0f)

        lastDiagnostics = HashMatchDiagnostics(
            totalHashes = sampleHashes.size,
            bestVotes = votes,
            runnerUpVotes = runnerUpVotes,
            bestEstimatedMovieTimeMs = estimatedMovieTimeMs,
            isMatch = isLocked
        )

        if (isLocked) {
            AppLogger.i(TAG, "Acoustic hash match locked! Votes=$votes (score=%.1f), runnerUpVotes=$runnerUpVotes (runnerScore=%.1f, ratio=%.1f), estimatedMovieTime=${estimatedMovieTimeMs / 1000}s".format(score, runnerUpScore, ratio))
            return HashMatchResult(
                movieTimeMs = estimatedMovieTimeMs,
                confidence = confidence,
                votes = votes,
                runnerUpVotes = runnerUpVotes
            )
        }

        return null
    }

    /**
     * Bounded matching: verifies whether sample hashes match near an expected movie timestamp (Stage 2/3).
     */
    @Synchronized
    fun matchBounded(
        sampleHashes: List<AudioHashEntry>,
        expectedMovieTimeMs: Long,
        bufferDurationMs: Long = 3500L,
        windowMs: Long = 15_000L
    ): HashMatchResult? {
        if (indexTable.isEmpty() || sampleHashes.isEmpty()) {
            lastDiagnostics = HashMatchDiagnostics(totalHashes = sampleHashes.size)
            return null
        }

        val expectedOffset = expectedMovieTimeMs - bufferDurationMs
        val minOffset = expectedOffset - windowMs
        val maxOffset = expectedOffset + windowMs

        val histogram = mutableMapOf<Long, BucketStats>()

        for (sample in sampleHashes) {
            val movieTimestamps = indexTable[sample.h] ?: continue
            val freq = keyFrequencyTable[sample.h] ?: 1
            val weight = computeKeyWeight(freq)
            for (movieT in movieTimestamps) {
                val offset = movieT - sample.t
                if (offset in minOffset..maxOffset) {
                    val bucketKey = ((offset.toDouble() / BUCKET_SIZE_MS).roundToLong()) * BUCKET_SIZE_MS
                    val stats = histogram.getOrPut(bucketKey) { BucketStats() }
                    stats.add(offset, weight)
                }
            }
        }

        if (histogram.isEmpty()) {
            lastDiagnostics = HashMatchDiagnostics(totalHashes = sampleHashes.size)
            return null
        }

        val best = histogram.maxByOrNull { it.value.score } ?: return null
        val votes = best.value.votes
        val score = best.value.score
        val actualOffset = best.value.averageOffset()
        val estimatedMovieTimeMs = (actualOffset + bufferDurationMs).coerceAtLeast(0L)

        val isLocked = votes >= 3
        lastDiagnostics = HashMatchDiagnostics(
            totalHashes = sampleHashes.size,
            bestVotes = votes,
            runnerUpVotes = 0,
            bestEstimatedMovieTimeMs = estimatedMovieTimeMs,
            isMatch = isLocked
        )

        if (isLocked) {
            return HashMatchResult(
                movieTimeMs = estimatedMovieTimeMs,
                confidence = (score / (score + 4f)).coerceIn(0.6f, 1.0f),
                votes = votes,
                runnerUpVotes = 0
            )
        }

        return null
    }
}
