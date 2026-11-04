package dev.spookcue.sync

import android.os.SystemClock
import dev.spookcue.audio.AcousticFingerprinter
import dev.spookcue.audio.AudioEnergyMeter
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.MovieSyncData
import dev.spookcue.data.model.PhoneticEntry
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SyncDebugInfo(
    val lastText: String = "",
    val windowText: String = "",
    val tokens: List<String> = emptyList(),
    val matchScore: Float = 0f,
    val matchedTimeMs: Long? = null,
    val candidateCount: Int = 0,
    val consecutiveMisses: Int = 0,
    val staleMissCount: Int = 0,
    val windowSegments: Int = 0,
    val topKScores: List<Float> = emptyList(),
    val instantDb: Float = 0f,
    val stageName: String = "Idle",
    val syncMode: String = "subtitles",
    val hashCount: Int = 0,
    val hashVotes: Int = 0,
    val hashRunnerUpVotes: Int = 0,
    val isEnergyDropped: Boolean = false
)

@Singleton
class SyncEngine @Inject constructor(
    private val phoneticMatcher: PhoneticMatcher,
    val movieClock: MovieClock,
    private val transcriptWindow: TranscriptWindow,
    val audioHashMatcher: AudioHashMatcher,
    val acousticFingerprinter: AcousticFingerprinter,
    val audioEnergyMeter: AudioEnergyMeter
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val CANDIDATE_WINDOW_MS = 60_000L
        private const val TEMPORAL_TOLERANCE_MS = 2_500L

        // Fast-lock thresholds for unique high-information utterances
        private const val FAST_LOCK_MIN_TOKENS = 6
        private const val FAST_LOCK_MIN_SCORE = 0.85f
        private const val FAST_LOCK_MAX_RUNNER_UP_SCORE = 0.70f
        private const val FAST_LOCK_PROXIMITY_MARGIN_MS = 15_000L

        // Stage 2 Checkpoint & Drift Thresholds
        private const val DRIFT_CORRECTION_MIN_MS = 500L
        private const val DRIFT_CORRECTION_MAX_MS = 6_000L
        private const val DRIFT_DAMPING_FACTOR = 0.25f
        private const val MAX_CONSECUTIVE_MISSES_FOR_PAUSE = 3

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

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private var currentMovie: MovieDetails? = null
    private var currentSyncData: MovieSyncData? = null
    private var sortedPhoneticIndex: List<PhoneticEntry> = emptyList()

    data class CandidateGroup(
        val systemTimeMs: Long,
        val matches: List<PhoneticMatcher.RankedMatch>
    )

    private val candidateGroups = mutableListOf<CandidateGroup>()
    private val pausedResumeCandidates = mutableListOf<CandidateGroup>()
    private var consecutiveMissedCheckpoints = 0
    private var activeSoundscapeSustainedMs = 0L

    fun start(movie: MovieDetails, syncData: MovieSyncData) {
        AppLogger.i(TAG, "Starting 3-Stage Sync for '${movie.title}' (type: ${syncData.type}, scares: ${movie.scares.size})")
        currentMovie = movie
        currentSyncData = syncData
        candidateGroups.clear()
        pausedResumeCandidates.clear()
        transcriptWindow.clear()
        movieClock.reset()
        consecutiveMissedCheckpoints = 0
        activeSoundscapeSustainedMs = 0L

        if (syncData.type == "audio_hash") {
            audioHashMatcher.initIndex(syncData.audioHashes)
        } else {
            sortedPhoneticIndex = syncData.phoneticIndex.sortedBy { it.startMs }
        }

        _state.value = SyncState.FindingProgress(startedAtMs = timeProvider(), candidatesFound = 0)
        startTicker()
    }

    fun stop() {
        AppLogger.i(TAG, "Stopping SyncEngine")
        tickerJob?.cancel()
        tickerJob = null
        currentMovie = null
        currentSyncData = null
        sortedPhoneticIndex = emptyList()
        candidateGroups.clear()
        pausedResumeCandidates.clear()
        transcriptWindow.clear()
        audioHashMatcher.clear()
        movieClock.reset()
        consecutiveMissedCheckpoints = 0
        activeSoundscapeSustainedMs = 0L
        _state.value = SyncState.Idle
    }

    fun forceResync() {
        AppLogger.i(TAG, "Force resync requested")
        candidateGroups.clear()
        pausedResumeCandidates.clear()
        transcriptWindow.clear()
        movieClock.reset()
        consecutiveMissedCheckpoints = 0
        activeSoundscapeSustainedMs = 0L
        _state.value = SyncState.FindingProgress(startedAtMs = timeProvider(), candidatesFound = 0)
    }

    fun togglePause() {
        when (val s = _state.value) {
            is SyncState.MoviePlaying -> {
                AppLogger.i(TAG, "Manual pause requested")
                movieClock.freeze()
                transcriptWindow.clear()
                pausedResumeCandidates.clear()
                _state.value = SyncState.MoviePaused(
                    pausedAtMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                    isManual = true
                )
            }
            is SyncState.MoviePaused -> {
                AppLogger.i(TAG, "Manual resume requested")
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
     * Process speech transcribed from audio (Subtitles mode).
     */
    fun onSpeechTranscribed(text: String) {
        val movie = currentMovie ?: return
        if (text.isBlank()) return

        _lastTranscript.value = text
        val segmentTokens = phoneticMatcher.tokenizeAndEncode(text)
        val windowText = transcriptWindow.push(text)
        val windowTokens = phoneticMatcher.tokenizeAndEncode(windowText)

        AppLogger.i(TAG, "onSpeechTranscribed: \"$text\" -> tokens: $segmentTokens (State: ${_state.value})")

        when (val currentState = _state.value) {
            is SyncState.FindingProgress -> {
                val now = timeProvider()

                // 1. Check Fast-Lock on high-information distinctive sentence
                if (segmentTokens.size >= FAST_LOCK_MIN_TOKENS) {
                    val segTopK = phoneticMatcher.evaluateTopK(segmentTokens, sortedPhoneticIndex, k = 3)
                    if (segTopK.isNotEmpty()) {
                        val best = segTopK.first()
                        val runnerUp = segTopK.getOrNull(1)
                        val isUnique = best.score >= FAST_LOCK_MIN_SCORE &&
                            (runnerUp == null || runnerUp.score < FAST_LOCK_MAX_RUNNER_UP_SCORE ||
                             abs(best.entry.startMs - runnerUp.entry.startMs) <= FAST_LOCK_PROXIMITY_MARGIN_MS)

                        if (isUnique) {
                            movieClock.snap(best.entry.startMs)
                            transcriptWindow.clear()
                            candidateGroups.clear()
                            consecutiveMissedCheckpoints = 0
                            val movieTime = movieClock.getCurrentMovieTimeMs()
                            AppLogger.i(TAG, ">>> FAST-LOCKED! Snapped to movie time: ${movieTime / 1000}s (score: ${best.score}) <<<")
                            _state.value = SyncState.MoviePlaying(
                                currentMovieTimeMs = movieTime,
                                confidence = best.score,
                                nextScareCountdownMs = calculateNextScareCountdown()
                            )
                            return
                        }
                    }
                }

                // 2. Standard 2-Candidate Temporal Consensus
                val searchTokens = if (segmentTokens.size >= 3) segmentTokens else windowTokens
                val topK = phoneticMatcher.evaluateTopK(searchTokens, sortedPhoneticIndex, k = 3)
                updateDebugInfo(text, windowText, searchTokens, topK.firstOrNull()?.score ?: 0f, topK.firstOrNull()?.entry?.startMs)

                if (topK.isNotEmpty()) {
                    candidateGroups.removeAll { now - it.systemTimeMs > CANDIDATE_WINDOW_MS }
                    val agreement = findAgreement(topK, now, candidateGroups)
                    if (agreement != null) {
                        movieClock.snap(agreement.entry.startMs)
                        transcriptWindow.clear()
                        candidateGroups.clear()
                        consecutiveMissedCheckpoints = 0
                        val movieTime = movieClock.getCurrentMovieTimeMs()
                        AppLogger.i(TAG, ">>> CONSENSUS LOCKED! Snapped to movie time: ${movieTime / 1000}s (confidence: ${agreement.score}) <<<")
                        _state.value = SyncState.MoviePlaying(
                            currentMovieTimeMs = movieTime,
                            confidence = agreement.score,
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    } else {
                        candidateGroups.add(CandidateGroup(now, topK))
                        _state.value = currentState.copy(candidatesFound = candidateGroups.size)
                    }
                }
            }

            is SyncState.MoviePlaying -> {
                val currentMovieTime = movieClock.getCurrentMovieTimeMs()
                val topK = phoneticMatcher.evaluateTopKBounded(
                    windowTokens,
                    sortedPhoneticIndex,
                    centerTimeMs = currentMovieTime,
                    windowMs = 15_000L,
                    k = 3,
                    threshold = 0.65f
                )

                if (topK.isNotEmpty()) {
                    val best = topK.first()
                    consecutiveMissedCheckpoints = 0
                    val drift = best.entry.startMs - currentMovieTime
                    AppLogger.d(TAG, "MoviePlaying checkpoint matched: drift=${drift}ms, score=${best.score}")

                    // Simple direct drift fix logic
                    if (abs(drift) in DRIFT_CORRECTION_MIN_MS..DRIFT_CORRECTION_MAX_MS) {
                        val correction = (DRIFT_DAMPING_FACTOR * drift).toLong()
                        movieClock.adjustOffset(correction)
                        AppLogger.i(TAG, "Applied micro-drift correction: ${correction}ms")
                    }
                    updateDebugInfo(text, windowText, windowTokens, best.score, best.entry.startMs)
                } else {
                    consecutiveMissedCheckpoints++
                    AppLogger.w(TAG, "Checkpoint missed ($consecutiveMissedCheckpoints in a row). Energy dropped: ${audioEnergyMeter.isEnergyDropped()}")
                    // Check pause condition: misses >= 3 AND audio energy dropped
                    if (consecutiveMissedCheckpoints >= MAX_CONSECUTIVE_MISSES_FOR_PAUSE && audioEnergyMeter.isEnergyDropped()) {
                        AppLogger.w(TAG, ">>> MOVIE PAUSE DETECTED! Freezing clock at ${currentMovieTime / 1000}s <<<")
                        movieClock.freeze()
                        transcriptWindow.clear()
                        pausedResumeCandidates.clear()
                        _state.value = SyncState.MoviePaused(
                            pausedAtMovieTimeMs = currentMovieTime,
                            isManual = false
                        )
                    }
                }
            }

            is SyncState.MoviePaused -> {
                val now = timeProvider()
                // Tier 1: Check against paused position window (±30s)
                val topK = phoneticMatcher.evaluateTopKBounded(
                    windowTokens,
                    sortedPhoneticIndex,
                    centerTimeMs = currentState.pausedAtMovieTimeMs,
                    windowMs = PAUSE_LOCAL_WINDOW_MS,
                    k = 3,
                    threshold = 0.70f
                )

                if (topK.isNotEmpty()) {
                    val best = topK.first()
                    pausedResumeCandidates.removeAll { now - it.systemTimeMs > CANDIDATE_WINDOW_MS }
                    val agreement = findAgreement(topK, now, pausedResumeCandidates)
                    // Either 2 agreeing utterances or high-confidence score >= 0.85
                    if (agreement != null || best.score >= 0.85f) {
                        val resumeTime = agreement?.entry?.startMs ?: best.entry.startMs
                        movieClock.snap(resumeTime)
                        transcriptWindow.clear()
                        pausedResumeCandidates.clear()
                        consecutiveMissedCheckpoints = 0
                        AppLogger.i(TAG, ">>> MOVIE RESUMED FROM PAUSE! Snapped to ${resumeTime / 1000}s <<<")
                        _state.value = SyncState.MoviePlaying(
                            currentMovieTimeMs = resumeTime,
                            confidence = maxOf(best.score, agreement?.score ?: 0f),
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    } else {
                        pausedResumeCandidates.add(CandidateGroup(now, topK))
                    }
                }
            }

            else -> Unit
        }
    }

    /**
     * Process acoustic hashes extracted from audio (Acoustic Hash mode).
     */
    fun onAudioHashesExtracted(hashes: List<dev.spookcue.data.model.AudioHashEntry>, bufferDurationMs: Long = 3500L) {
        if (currentSyncData?.type != "audio_hash") return

        when (val currentState = _state.value) {
            is SyncState.FindingProgress -> {
                val result = audioHashMatcher.match(hashes, bufferDurationMs)
                if (result != null) {
                    movieClock.snap(result.movieTimeMs)
                    consecutiveMissedCheckpoints = 0
                    AppLogger.i(TAG, ">>> ACOUSTIC HASH LOCKED! Snapped to movie time: ${result.movieTimeMs / 1000}s (votes: ${result.votes}) <<<")
                    _state.value = SyncState.MoviePlaying(
                        currentMovieTimeMs = result.movieTimeMs,
                        confidence = result.confidence,
                        nextScareCountdownMs = calculateNextScareCountdown()
                    )
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
                    }
                } else {
                    consecutiveMissedCheckpoints++
                    if (consecutiveMissedCheckpoints >= MAX_CONSECUTIVE_MISSES_FOR_PAUSE && audioEnergyMeter.isEnergyDropped()) {
                        AppLogger.w(TAG, ">>> ACOUSTIC HASH PAUSE DETECTED! Freezing clock <<<")
                        movieClock.freeze()
                        _state.value = SyncState.MoviePaused(
                            pausedAtMovieTimeMs = currentMovieTime,
                            isManual = false
                        )
                    }
                }
            }

            is SyncState.MoviePaused -> {
                val bounded = audioHashMatcher.matchBounded(hashes, currentState.pausedAtMovieTimeMs, bufferDurationMs, windowMs = PAUSE_LOCAL_WINDOW_MS)
                if (bounded != null) {
                    movieClock.snap(bounded.movieTimeMs)
                    consecutiveMissedCheckpoints = 0
                    AppLogger.i(TAG, ">>> ACOUSTIC HASH RESUMED! Snapped to ${bounded.movieTimeMs / 1000}s <<<")
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
        movieClock.snap(movieTimeMs)
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
        _debugInfo.value = _debugInfo.value.copy(
            lastText = "Simulated Drift: ${driftMs}ms applied"
        )
    }

    private fun findAgreement(
        currentMatches: List<PhoneticMatcher.RankedMatch>,
        now: Long,
        candidates: List<CandidateGroup>
    ): PhoneticMatcher.RankedMatch? {
        for (group in candidates) {
            val expectedMovieDiff = now - group.systemTimeMs
            for (curr in currentMatches) {
                for (prev in group.matches) {
                    val actualMovieDiff = curr.entry.startMs - prev.entry.startMs
                    if (actualMovieDiff < 1000L) continue

                    val delta = abs(expectedMovieDiff - actualMovieDiff)
                    if (delta <= TEMPORAL_TOLERANCE_MS) {
                        return curr
                    }
                }
            }
        }
        return null
    }

    private fun calculateNextScareCountdown(): Long? {
        val movie = currentMovie ?: return null
        val currentTime = movieClock.getCurrentMovieTimeMs()
        val nextScare = movie.scares
            .filter { it.resolvedTimeMs > currentTime }
            .minByOrNull { it.resolvedTimeMs }

        return nextScare?.let { it.resolvedTimeMs - currentTime }
    }

    private fun calculateNextCheckpoint(): Long? {
        if (sortedPhoneticIndex.isEmpty()) return null
        val currentMovieTime = movieClock.getCurrentMovieTimeMs()
        val nextCue = sortedPhoneticIndex.firstOrNull { it.startMs > currentMovieTime }
        return nextCue?.let { it.startMs - currentMovieTime }
    }

    private fun updateDebugInfo(lastText: String, windowText: String, tokens: List<String>, score: Float, matchedMs: Long?) {
        val stageStr = when (_state.value) {
            is SyncState.FindingProgress -> "Stage 1: Finding Progress"
            is SyncState.MoviePlaying -> "Stage 2: Movie Playing"
            is SyncState.MoviePaused -> "Stage 3: Movie Paused"
            is SyncState.Idle -> "Idle"
            is SyncState.Error -> "Error"
        }
        _debugInfo.value = SyncDebugInfo(
            lastText = lastText,
            windowText = windowText,
            tokens = tokens,
            matchScore = score,
            matchedTimeMs = matchedMs,
            candidateCount = candidateGroups.size,
            consecutiveMisses = consecutiveMissedCheckpoints,
            staleMissCount = consecutiveMissedCheckpoints,
            windowSegments = transcriptWindow.segmentCount(),
            instantDb = audioEnergyMeter.getInstantEnergyDb(),
            stageName = stageStr
        )
    }

    fun tickOnce(now: Long = timeProvider()) {
        when (val s = _state.value) {
            is SyncState.FindingProgress -> {
                candidateGroups.removeAll { now - it.systemTimeMs > CANDIDATE_WINDOW_MS }
                _state.value = s.copy(candidatesFound = candidateGroups.size)
            }
            is SyncState.MoviePlaying -> {
                val currentMovieTime = movieClock.getCurrentMovieTimeMs()
                val nextScare = calculateNextScareCountdown()
                val nextCheckpoint = calculateNextCheckpoint()
                _state.value = s.copy(
                    currentMovieTimeMs = currentMovieTime,
                    nextScareCountdownMs = nextScare,
                    nextCheckpointInMs = nextCheckpoint
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
