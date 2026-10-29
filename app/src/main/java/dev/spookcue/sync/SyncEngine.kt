package dev.spookcue.sync

import android.os.SystemClock
import dev.spookcue.data.model.MovieDetails
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
    val tokens: List<String> = emptyList(),
    val matchScore: Float = 0f,
    val matchedTimeMs: Long? = null,
    val candidateCount: Int = 0
)

@Singleton
class SyncEngine @Inject constructor(
    private val phoneticMatcher: PhoneticMatcher,
    val movieClock: MovieClock
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _debugInfo = MutableStateFlow(SyncDebugInfo())
    val debugInfo: StateFlow<SyncDebugInfo> = _debugInfo.asStateFlow()

    private var currentMovie: MovieDetails? = null

    private data class CandidateMatch(val systemTimeMs: Long, val movieTimeMs: Long)
    private val candidateMatches = mutableListOf<CandidateMatch>()

    fun start(movie: MovieDetails) {
        currentMovie = movie
        candidateMatches.clear()
        movieClock.reset()
        _state.value = SyncState.Acquiring(startedAtMs = SystemClock.elapsedRealtime(), candidateMatches = 0)
        startTicker()
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        currentMovie = null
        candidateMatches.clear()
        movieClock.reset()
        _state.value = SyncState.Idle
    }

    fun forceResync() {
        val movie = currentMovie ?: return
        candidateMatches.clear()
        movieClock.reset()
        _state.value = SyncState.Acquiring(startedAtMs = SystemClock.elapsedRealtime(), candidateMatches = 0)
    }

    fun adjustClock(deltaMs: Long) {
        movieClock.adjustOffset(deltaMs)
    }

    fun onSpeechTranscribed(text: String) {
        val movie = currentMovie ?: return
        val tokens = phoneticMatcher.tokenizeAndEncode(text)
        if (tokens.isEmpty()) {
            _debugInfo.value = SyncDebugInfo(lastText = text, tokens = emptyList())
            return
        }

        when (val currentState = _state.value) {
            is SyncState.Acquiring -> {
                val match = phoneticMatcher.findBestMatch(tokens, movie.phoneticIndex)
                _debugInfo.value = SyncDebugInfo(
                    lastText = text,
                    tokens = tokens,
                    matchScore = match?.score ?: 0f,
                    matchedTimeMs = match?.matchedTimeMs,
                    candidateCount = candidateMatches.size
                )
                if (match != null) {
                    val now = SystemClock.elapsedRealtime()
                    candidateMatches.removeAll { now - it.systemTimeMs > 45_000L }

                    val agreement = candidateMatches.firstOrNull { prev ->
                        val expectedMovieDiff = now - prev.systemTimeMs
                        val actualMovieDiff = match.matchedTimeMs - prev.movieTimeMs
                        abs(expectedMovieDiff - actualMovieDiff) <= 2000L
                    }

                    if (agreement != null) {
                        movieClock.snap(match.matchedTimeMs)
                        _state.value = SyncState.Locked(
                            currentMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                            confidence = match.score,
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    } else {
                        candidateMatches.add(CandidateMatch(now, match.matchedTimeMs))
                        _state.value = currentState.copy(candidateMatches = candidateMatches.size)
                    }
                }
            }

            is SyncState.Verifying -> {
                val currentMovieTime = movieClock.getCurrentMovieTimeMs()
                val match = phoneticMatcher.findBoundedMatch(
                    tokens,
                    movie.phoneticIndex,
                    centerTimeMs = currentMovieTime,
                    windowMs = 90_000L
                )
                if (match != null) {
                    val drift = match.matchedTimeMs - currentMovieTime
                    if (abs(drift) <= 2000L) {
                        _state.value = SyncState.Locked(
                            currentMovieTimeMs = currentMovieTime,
                            confidence = match.score,
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    } else {
                        movieClock.adjustOffset(drift)
                        _state.value = SyncState.Locked(
                            currentMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                            confidence = match.score,
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    private fun calculateNextScareCountdown(): Long? {
        val movie = currentMovie ?: return null
        val currentTime = movieClock.getCurrentMovieTimeMs()
        val nextScare = movie.scares
            .filter { it.resolvedTimeMs > currentTime }
            .minByOrNull { it.resolvedTimeMs }

        return nextScare?.let { it.resolvedTimeMs - currentTime }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(500)
                when (val currentState = _state.value) {
                    is SyncState.Locked -> {
                        _state.value = currentState.copy(
                            currentMovieTimeMs = movieClock.getCurrentMovieTimeMs(),
                            nextScareCountdownMs = calculateNextScareCountdown()
                        )
                    }
                    is SyncState.Verifying -> {
                        _state.value = currentState.copy(
                            currentMovieTimeMs = movieClock.getCurrentMovieTimeMs()
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}
