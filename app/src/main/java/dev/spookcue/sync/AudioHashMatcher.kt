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
        const val MIN_CONSENSUS_VOTES = 5 // Minimum peak pair alignments required for full search
        const val CONFIDENCE_RATIO_THRESHOLD = 1.5f // Primary bucket votes vs runner-up
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
        var sumOffset: Long = 0L

        fun add(offset: Long) {
            votes++
            sumOffset += offset
        }

        fun averageOffset(): Long = if (votes > 0) sumOffset / votes else 0L
    }

    private val indexTable = mutableMapOf<Int, MutableList<Long>>()
    private var totalIndexedEntries = 0

    var lastDiagnostics: HashMatchDiagnostics = HashMatchDiagnostics()
        private set

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
        lastDiagnostics = HashMatchDiagnostics()
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
            for (movieT in movieTimestamps) {
                val offset = movieT - sample.t
                val bucketKey = ((offset.toDouble() / BUCKET_SIZE_MS).roundToLong()) * BUCKET_SIZE_MS
                val stats = histogram.getOrPut(bucketKey) { BucketStats() }
                stats.add(offset)
            }
        }

        if (histogram.isEmpty()) {
            lastDiagnostics = HashMatchDiagnostics(totalHashes = sampleHashes.size)
            return null
        }

        val sortedBuckets = histogram.entries.sortedByDescending { it.value.votes }
        val best = sortedBuckets.first()
        val runnerUpVotes = sortedBuckets.getOrNull(1)?.value?.votes ?: 0

        val votes = best.value.votes
        val ratio = if (runnerUpVotes > 0) votes.toFloat() / runnerUpVotes else votes.toFloat()
        val actualOffset = best.value.averageOffset()
        val estimatedMovieTimeMs = (actualOffset + bufferDurationMs).coerceAtLeast(0L)

        val isLocked = votes >= MIN_CONSENSUS_VOTES && (runnerUpVotes == 0 || ratio >= CONFIDENCE_RATIO_THRESHOLD)
        val confidence = (votes.toFloat() / (votes + 8)).coerceIn(0.5f, 1.0f)

        lastDiagnostics = HashMatchDiagnostics(
            totalHashes = sampleHashes.size,
            bestVotes = votes,
            runnerUpVotes = runnerUpVotes,
            bestEstimatedMovieTimeMs = estimatedMovieTimeMs,
            isMatch = isLocked
        )

        if (isLocked) {
            AppLogger.i(TAG, "Acoustic hash match locked! Votes=$votes, runnerUp=$runnerUpVotes, estimatedMovieTime=${estimatedMovieTimeMs / 1000}s")
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
            for (movieT in movieTimestamps) {
                val offset = movieT - sample.t
                if (offset in minOffset..maxOffset) {
                    val bucketKey = ((offset.toDouble() / BUCKET_SIZE_MS).roundToLong()) * BUCKET_SIZE_MS
                    val stats = histogram.getOrPut(bucketKey) { BucketStats() }
                    stats.add(offset)
                }
            }
        }

        if (histogram.isEmpty()) {
            lastDiagnostics = HashMatchDiagnostics(totalHashes = sampleHashes.size)
            return null
        }

        val best = histogram.maxByOrNull { it.value.votes } ?: return null
        val votes = best.value.votes
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
                confidence = (votes.toFloat() / (votes + 4)).coerceIn(0.6f, 1.0f),
                votes = votes
            )
        }

        return null
    }
}
