package dev.spookcue.sync

import android.os.SystemClock
import dev.spookcue.audio.AcousticFingerprinter
import dev.spookcue.audio.AudioEnergyMeter
import dev.spookcue.data.model.AudioHashEntry
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.MovieSyncData
import dev.spookcue.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SyncDebugInfo(
    val lastText: String = "",
    val matchedTimeMs: Long? = null,
    val consecutiveMisses: Int = 0,
    val instantDb: Float = 0f,
    val stageName: String = "Idle",
    val syncMode: String = "audio_hash",
    val hashCount: Int = 0,
    val hashVotes: Int = 0,
    val hashRunnerUpVotes: Int = 0,
    val isEnergyDropped: Boolean = false
)

@Singleton
class SyncEngine @Inject constructor(
    val movieClock: MovieClock,
    val audioHashMatcher: AudioHashMatcher,
    val acousticFingerprinter: AcousticFingerprinter,
    val audioEnergyMeter: AudioEnergyMeter
) {
    companion object {
        private const val TAG = "SyncEngine"

        // Stage 2 Checkpoint & Drift Thresholds
        private const val DRIFT_CORRECTION_MIN_MS = 500L
        private const val DRIFT_CORRECTION_MAX_MS = 6_000L
        private const val DRIFT_DAMPING_FACTOR = 0.25f

        // Stage 3 Re-acquisition
        private const val PAUSE_LOCAL_WINDOW_MS = 30_000L
    }

    internal var timeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _debugInfo = MutableStateFlow(SyncDebugInfo())
    val debugInfo: StateFlow<SyncDebugInfo> = _debugInfo.asStateFlow()

    private val _syncHistory = MutableStateFlow<List<SyncHistoryRecord>>(emptyList())
    val syncHistory: StateFlow<List<SyncHistoryRecord>> = _syncHistory.asStateFlow()

    private val jsonSerializer = Json { prettyPrint = true }

    fun recordSyncEvent(eventType: String, movieTimeMs: Long, confidence: Float = 1.0f, details: String = "") {
        val record = SyncHistoryRecord(
            timestampMs = System.currentTimeMillis(),
            eventType = eventType,
            syncMode = currentSyncData?.type ?: "audio_hash",
            movieTimeMs = movieTimeMs,
            confidence = confidence,
            details = details
        )
        _syncHistory.value = (_syncHistory.value + record).takeLast(100)
        val confStr = "%.2f".format(confidence)
        AppLogger.i(TAG, "[SYNC_HISTORY][$eventType] @ ${movieTimeMs / 1000}s (${record.syncMode}, conf: $confStr) - $details")
    }

    fun getSyncHistoryJson(): String {
        return jsonSerializer.encodeToString(_syncHistory.value)
    }

    fun clearHistory() {
        _syncHistory.value = emptyList()
    }

    private var currentMovie: MovieDetails? = null
    private var currentSyncData: MovieSyncData? = null
    private var consecutiveMissedCheckpoints = 0
    private var activeSoundscapeSustainedMs = 0L

    data class AcousticCandidate(
        val movieTimeMs: Long,
        val systemTimeMs: Long,
        val votes: Int,
        val consensusHits: Int = 1
    )
    private var pendingAcousticCandidate: AcousticCandidate? = null

    fun start(movie: MovieDetails, syncData: MovieSyncData) {
        AppLogger.i(TAG, "Starting Acoustic Landmark Sync for '${movie.title}' (hashes: ${syncData.audioHashes.size}, scares: ${movie.scares.size})")
        recordSyncEvent("START", 0L, 1.0f, "Starting acoustic sync for '${movie.title}'")
        currentMovie = movie
        currentSyncData = syncData
        movieClock.reset()
        consecutiveMissedCheckpoints = 0
        activeSoundscapeSustainedMs = 0L
        pendingAcousticCandidate = null

        audioHashMatcher.initIndex(syncData.audioHashes)

        _debugInfo.value = SyncDebugInfo(
            stageName = "Stage 1: Finding Progress",
            syncMode = "audio_hash"
        )

        _state.value = SyncState.FindingProgress(startedAtMs = timeProvider(), candidatesFound = 0)
        startTicker()
    }

    fun stop() {
        AppLogger.i(TAG, "Stopping SyncEngine")
        tickerJob?.cancel()
        tickerJob = null
        currentMovie = null
        currentSyncData = null
        audioHashMatcher.clear()
        pendingAcousticCandidate = null
        movieClock.reset()
        consecutiveMissedCheckpoints = 0
        activeSoundscapeSustainedMs = 0L
        _state.value = SyncState.Idle
    }

    fun forceResync() {
        AppLogger.i(TAG, "Force resync requested")
        recordSyncEvent("RESYNC", 0L, 0f, "Force resync requested")
        pendingAcousticCandidate = null
        movieClock.reset()
        consecutiveMissedCheckpoints = 0
        activeSoundscapeSustainedMs = 0L
        _state.value = SyncState.FindingProgress(startedAtMs = timeProvider(), candidatesFound = 0)
    }

    fun togglePause() {
        when (val s = _state.value) {
            is SyncState.MoviePlaying -> {
                AppLogger.i(TAG, "Manual pause requested")
                recordSyncEvent("PAUSE", movieClock.getCurrentMovieTimeMs(), 1.0f, "Manual pause requested")
                movieClock.freeze()
                _state.value = SyncState.MoviePaused(
                    pausedAtMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                    isManual = true
                )
            }
            is SyncState.MoviePaused -> {
                AppLogger.i(TAG, "Manual resume requested")
                recordSyncEvent("RESUME", movieClock.getCurrentMovieTimeMs(), 1.0f, "Manual resume requested")
                movieClock.resume()
                consecutiveMissedCheckpoints = 0
                _state.value = SyncState.MoviePlaying(
                    currentMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                    confidence = 1.0f,
                    nextScareCountdownMs = calculateNextScareCountdown()
                )
            }
            else -> Unit
        }
    }

    fun adjustClock(deltaMs: Long) {
        AppLogger.i(TAG, "Adjusting clock by ${deltaMs}ms")
        movieClock.adjustOffset(deltaMs)
        when (val s = _state.value) {
            is SyncState.MoviePlaying -> {
                _state.value = s.copy(
                    currentMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                    nextScareCountdownMs = calculateNextScareCountdown()
                )
            }
            is SyncState.MoviePaused -> {
                _state.value = s.copy(
                    pausedAtMovieTimeMs = movieClock.getCurrentMovieTimeMs()
                )
            }
            else -> Unit
        }
    }

    /**
     * Process acoustic hashes extracted from audio.
     */
    fun onAudioHashesExtracted(hashes: List<AudioHashEntry>, bufferDurationMs: Long = 3500L) {
        when (val currentState = _state.value) {
            is SyncState.FindingProgress -> {
                val result = audioHashMatcher.match(hashes, bufferDurationMs)
                if (result != null) {
                    val now = timeProvider()
                    val prev = pendingAcousticCandidate
                    val ratio = if (result.runnerUpVotes > 0) result.votes.toFloat() / result.runnerUpVotes else 999f

                    // Fast lock strictly for isolated clean match with zero runner-up votes (e.g. tests or pristine quiet audio).
                    val isFastLock = (result.runnerUpVotes == 0 && result.votes >= 6)

                    var consensusConfirmed = isFastLock
                    var newHits = 1

                    if (!consensusConfirmed && prev != null && (now - prev.systemTimeMs) <= 2500L) {
                        val deltaMovie = result.movieTimeMs - prev.movieTimeMs
                        val deltaSystem = now - prev.systemTimeMs
                        // Forward trajectory agreement: movie must advance in sync with wall clock
                        if (deltaMovie >= 0L && abs(deltaMovie - deltaSystem) <= 1200L) {
                            newHits = prev.consensusHits + 1
                            val requiredHits = when {
                                ratio >= 2.0f && result.votes >= 6 -> 2
                                result.votes >= 12 && ratio >= 1.6f -> 2
                                else -> 3
                            }
                            if (newHits >= requiredHits) {
                                consensusConfirmed = true
                            }
                        }
                    }

                    if (consensusConfirmed) {
                        pendingAcousticCandidate = null
                        movieClock.snap(result.movieTimeMs)
                        consecutiveMissedCheckpoints = 0
                        AppLogger.i(TAG, ">>> ACOUSTIC HASH LOCKED! Snapped to movie time: ${result.movieTimeMs / 1000}s (votes: ${result.votes}, ratio=%.1f, hits=$newHits) <<<".format(ratio))
                        recordSyncEvent("LOCK", result.movieTimeMs, result.confidence, "Acoustic hash locked (votes: ${result.votes}, hits: $newHits)")
                        _state.value = SyncState.MoviePlaying(
                            currentMovieTimeMs = result.movieTimeMs,
                            confidence = result.confidence,
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    } else {
                        if (newHits > 1) {
                            pendingAcousticCandidate = AcousticCandidate(result.movieTimeMs, now, result.votes, consensusHits = newHits)
                            AppLogger.d(TAG, "Acoustic candidate @ ${result.movieTimeMs / 1000}s (votes: ${result.votes}, hits: $newHits) confirmed trajectory — awaiting lock")
                            recordSyncEvent("CANDIDATE", result.movieTimeMs, result.confidence, "Acoustic candidate (votes: ${result.votes}, hits: $newHits)")
                        } else if (prev == null || (now - prev.systemTimeMs) > 1500L || prev.consensusHits == 1 || result.votes >= prev.votes) {
                            pendingAcousticCandidate = AcousticCandidate(result.movieTimeMs, now, result.votes, consensusHits = 1)
                            AppLogger.d(TAG, "Acoustic candidate @ ${result.movieTimeMs / 1000}s (votes: ${result.votes}) registered — awaiting trajectory confirmation")
                        } else {
                            AppLogger.d(TAG, "Ignoring candidate @ ${result.movieTimeMs / 1000}s (${result.votes} vs ${prev.votes} votes)")
                        }
                        _state.value = SyncState.FindingProgress(
                            candidatesFound = newHits,
                            startedAtMs = currentState.startedAtMs
                        )
                    }
                } else {
                    if (pendingAcousticCandidate != null && (timeProvider() - pendingAcousticCandidate!!.systemTimeMs) > 8000L) {
                        pendingAcousticCandidate = null
                    }
                }
            }

            is SyncState.MoviePlaying -> {
                val currentMovieTime = movieClock.getCurrentMovieTimeMs()
                val bounded = audioHashMatcher.matchBounded(hashes, currentMovieTime, bufferDurationMs, windowMs = 15_000L)
                if (bounded != null) {
                    consecutiveMissedCheckpoints = 0
                    val drift = bounded.movieTimeMs - currentMovieTime
                    if (abs(drift) in DRIFT_CORRECTION_MIN_MS..DRIFT_CORRECTION_MAX_MS) {
                        val correction = (DRIFT_DAMPING_FACTOR * drift).toLong()
                        movieClock.adjustOffset(correction)
                        AppLogger.d(TAG, "Applied acoustic hash drift correction: ${correction}ms")
                        recordSyncEvent("DRIFT", currentMovieTime, 1.0f, "Acoustic drift correction applied: ${correction}ms")
                    }
                } else {
                    consecutiveMissedCheckpoints++
                    AppLogger.d(TAG, "Acoustic bounded checkpoint missed ($consecutiveMissedCheckpoints in a row)")
                }
            }

            is SyncState.MoviePaused -> {
                val bounded = audioHashMatcher.matchBounded(hashes, currentState.pausedAtMovieTimeMs, bufferDurationMs, windowMs = PAUSE_LOCAL_WINDOW_MS)
                if (bounded != null) {
                    movieClock.snap(bounded.movieTimeMs)
                    consecutiveMissedCheckpoints = 0
                    AppLogger.i(TAG, ">>> ACOUSTIC HASH RESUMED! Snapped to ${bounded.movieTimeMs / 1000}s <<<")
                    recordSyncEvent("RESUME", bounded.movieTimeMs, bounded.confidence, "Acoustic hash resumed")
                    _state.value = SyncState.MoviePlaying(
                        currentMovieTimeMs = bounded.movieTimeMs,
                        confidence = bounded.confidence,
                        nextScareCountdownMs = calculateNextScareCountdown()
                    )
                }
            }

            else -> Unit
        }

        // Update debug state with live acoustic statistics
        val diag = audioHashMatcher.lastDiagnostics
        val stageStr = when (_state.value) {
            is SyncState.FindingProgress -> "Stage 1: Finding Progress"
            is SyncState.MoviePlaying -> "Stage 2: Movie Playing"
            is SyncState.MoviePaused -> "Stage 3: Movie Paused"
            else -> "Idle"
        }
        val instantDb = audioEnergyMeter.getInstantEnergyDb()
        val energyDropped = audioEnergyMeter.isEnergyDropped()
        _debugInfo.value = _debugInfo.value.copy(
            syncMode = "audio_hash",
            stageName = stageStr,
            hashCount = hashes.size,
            hashVotes = diag.bestVotes,
            hashRunnerUpVotes = diag.runnerUpVotes,
            matchedTimeMs = if (diag.bestEstimatedMovieTimeMs > 0) diag.bestEstimatedMovieTimeMs else null,
            instantDb = instantDb,
            isEnergyDropped = energyDropped,
            consecutiveMisses = consecutiveMissedCheckpoints,
            lastText = "Acoustic hashes: ${hashes.size} | Best votes: ${diag.bestVotes} (runner-up: ${diag.runnerUpVotes})"
        )
    }

    fun simulateAcousticLock(movieTimeMs: Long = 600_000L) {
        recordSyncEvent("LOCK", movieTimeMs, 0.95f, "Simulated acoustic lock")
        movieClock.snap(movieTimeMs)
        pendingAcousticCandidate = null
        consecutiveMissedCheckpoints = 0
        _state.value = SyncState.MoviePlaying(
            currentMovieTimeMs = movieTimeMs,
            confidence = 0.95f,
            nextScareCountdownMs = calculateNextScareCountdown()
        )
        _debugInfo.value = _debugInfo.value.copy(
            syncMode = "audio_hash",
            stageName = "Stage 2: Movie Playing (Simulated)",
            hashVotes = 12,
            hashRunnerUpVotes = 2,
            matchedTimeMs = movieTimeMs,
            lastText = "Simulated Acoustic Lock @ ${movieTimeMs / 1000}s"
        )
    }

    fun simulateDrift(driftMs: Long) {
        movieClock.adjustOffset(driftMs)
        recordSyncEvent("DRIFT", movieClock.getCurrentMovieTimeMs(), 1.0f, "Simulated drift: ${driftMs}ms")
        _debugInfo.value = _debugInfo.value.copy(
            lastText = "Simulated Drift: ${driftMs}ms applied"
        )
    }

    private fun calculateNextScareCountdown(): Long? {
        val movie = currentMovie ?: return null
        val currentTime = movieClock.getCurrentMovieTimeMs()
        val nextScare = movie.scares
            .filter { it.resolvedTimeMs > currentTime }
            .minByOrNull { it.resolvedTimeMs }

        return nextScare?.let { it.resolvedTimeMs - currentTime }
    }

    fun tickOnce(now: Long = timeProvider()) {
        when (val s = _state.value) {
            is SyncState.FindingProgress -> {
                // Keep candidate state up to date
            }
            is SyncState.MoviePlaying -> {
                val currentMovieTime = movieClock.getCurrentMovieTimeMs()
                val nextScare = calculateNextScareCountdown()
                _state.value = s.copy(
                    currentMovieTimeMs = currentMovieTime,
                    nextScareCountdownMs = nextScare,
                    nextCheckpointInMs = null
                )
            }
            is SyncState.MoviePaused -> {
                // Tier 2 Fallback: If sustained loud movie soundscape has returned for >15s, but Tier 1 didn't match
                if (audioEnergyMeter.isMovieSoundscapeActive()) {
                    activeSoundscapeSustainedMs += 500L
                    if (activeSoundscapeSustainedMs >= 15_000L) {
                        AppLogger.w(TAG, "Sustained movie soundscape detected without Tier 1 match. Falling back to Stage 1 full search.")
                        activeSoundscapeSustainedMs = 0L
                        forceResync()
                    }
                } else {
                    activeSoundscapeSustainedMs = 0L
                }
            }
            else -> Unit
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(500)
                tickOnce(timeProvider())
            }
        }
    }
}
