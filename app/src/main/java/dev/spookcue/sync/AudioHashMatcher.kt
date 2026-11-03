package dev.spookcue.sync

import dev.spookcue.data.model.AudioHashEntry
import dev.spookcue.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Matches live acoustic landmark hashes against a movie's precomputed hash index
 * using offset histogram voting (Shazam / Dejavu algorithm).
 */
@Singleton
class AudioHashMatcher @Inject constructor() {

    companion object {
        private const val TAG = "AudioHashMatcher"
        const val BUCKET_SIZE_MS = 300L // 300ms histogram resolution
        const val MIN_CONSENSUS_VOTES = 6 // Minimum peak pair alignments required
        const val CONFIDENCE_RATIO_THRESHOLD = 1.8f // Primary bucket votes vs runner-up
    }

    data class HashMatchResult(
        val movieTimeMs: Long,
        val confidence: Float,
        val votes: Int
    )

    private class BucketStats {
        var votes: Int = 0
        var sumOffset: Long = 0L

        fun add(offset: Long) {
            votes++
            sumOffset += offset
        }

        fun averageOffset(): Long = if (votes > 0) sumOffset / votes else 0L
    }

    private val indexTable = mutableMapOf<Int, MutableList<Long>>()
    private var totalIndexedEntries = 0

    /**
     * Initializes the lookup table for the movie's acoustic hashes.
     */
    @Synchronized
    fun initIndex(entries: List<AudioHashEntry>) {
        indexTable.clear()
        totalIndexedEntries = entries.size
        for (entry in entries) {
            val list = indexTable.getOrPut(entry.h) { mutableListOf() }
            list.add(entry.t)
        }
        AppLogger.i(TAG, "Initialized acoustic hash index: $totalIndexedEntries hashes (${indexTable.size} unique keys)")
    }

    @Synchronized
    fun clear() {
        indexTable.clear()
        totalIndexedEntries = 0
    }

    /**
     * Matches sample hashes against the entire movie index.
     * [sampleHashes]: hashes extracted from captured audio, with sample timestamps.
     * [currentCaptureEndMs]: the local system or capture timestamp corresponding to the end of the sample buffer.
     */
    @Synchronized
    fun match(sampleHashes: List<AudioHashEntry>, currentCaptureEndMs: Long): HashMatchResult? {
        if (indexTable.isEmpty() || sampleHashes.isEmpty()) return null

        val histogram = mutableMapOf<Long, BucketStats>()

        for (sample in sampleHashes) {
            val movieTimestamps = indexTable[sample.h] ?: continue
            for (movieT in movieTimestamps) {
                val offset = movieT - sample.t
                val bucketKey = ((offset.toDouble() / BUCKET_SIZE_MS).roundToLong()) * BUCKET_SIZE_MS
                val stats = histogram.getOrPut(bucketKey) { BucketStats() }
                stats.add(offset)
            }
        }

        if (histogram.isEmpty()) return null

        val sortedBuckets = histogram.entries.sortedByDescending { it.value.votes }
        val best = sortedBuckets.first()
        val runnerUpVotes = sortedBuckets.getOrNull(1)?.value?.votes ?: 0

        val votes = best.value.votes
        val ratio = if (runnerUpVotes > 0) votes.toFloat() / runnerUpVotes else votes.toFloat()

        if (votes >= MIN_CONSENSUS_VOTES && (runnerUpVotes == 0 || ratio >= CONFIDENCE_RATIO_THRESHOLD)) {
            val actualOffset = best.value.averageOffset()
            val estimatedMovieTimeMs = currentCaptureEndMs + actualOffset
            val confidence = (votes.toFloat() / (votes + 10)).coerceIn(0.6f, 1.0f)
            AppLogger.i(TAG, "Acoustic hash match locked! Votes=$votes, runnerUp=$runnerUpVotes, estimatedMovieTime=${estimatedMovieTimeMs / 1000}s")
            return HashMatchResult(
                movieTimeMs = estimatedMovieTimeMs,
                confidence = confidence,
                votes = votes
            )
        }

        return null
    }

    /**
     * Bounded matching: verifies whether sample hashes match near an expected movie timestamp.
     */
    @Synchronized
    fun matchBounded(
        sampleHashes: List<AudioHashEntry>,
        currentCaptureEndMs: Long,
        expectedMovieTimeMs: Long,
        windowMs: Long = 15_000L
    ): HashMatchResult? {
        if (indexTable.isEmpty() || sampleHashes.isEmpty()) return null

        val minOffset = (expectedMovieTimeMs - currentCaptureEndMs) - windowMs
        val maxOffset = (expectedMovieTimeMs - currentCaptureEndMs) + windowMs

        val histogram = mutableMapOf<Long, BucketStats>()

        for (sample in sampleHashes) {
            val movieTimestamps = indexTable[sample.h] ?: continue
            for (movieT in movieTimestamps) {
                val offset = movieT - sample.t
                if (offset in minOffset..maxOffset) {
                    val bucketKey = ((offset.toDouble() / BUCKET_SIZE_MS).roundToLong()) * BUCKET_SIZE_MS
                    val stats = histogram.getOrPut(bucketKey) { BucketStats() }
                    stats.add(offset)
                }
            }
        }

        if (histogram.isEmpty()) return null

        val best = histogram.maxByOrNull { it.value.votes } ?: return null
        val votes = best.value.votes
        if (votes >= 3) {
            val actualOffset = best.value.averageOffset()
            val estimatedMovieTimeMs = currentCaptureEndMs + actualOffset
            return HashMatchResult(
                movieTimeMs = estimatedMovieTimeMs,
                confidence = (votes.toFloat() / (votes + 4)).coerceIn(0.5f, 1.0f),
                votes = votes
            )
        }

        return null
    }
}
