package dev.spookcue.sync

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MovieClock @Inject constructor() {

    internal var timeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    private var anchorMovieMs: Long = 0L
    private var anchorSystemMs: Long = 0L
    private var isClockRunning: Boolean = false
    private var frozenAtMovieMs: Long? = null

    fun snap(movieTimeMs: Long) {
        anchorMovieMs = movieTimeMs
        anchorSystemMs = timeProvider()
        isClockRunning = true
        frozenAtMovieMs = null
    }

    fun getCurrentMovieTimeMs(): Long {
        frozenAtMovieMs?.let { return it }
        if (!isClockRunning) return anchorMovieMs
        val elapsedSinceAnchor = timeProvider() - anchorSystemMs
        return anchorMovieMs + elapsedSinceAnchor
    }

    /** Freeze the clock at the current movie time. */
    fun freeze() {
        if (frozenAtMovieMs == null && isClockRunning) {
            frozenAtMovieMs = getCurrentMovieTimeMs()
        }
    }

    /** Resume ticking from the frozen position. */
    fun resume() {
        val frozen = frozenAtMovieMs ?: return
        anchorMovieMs = frozen
        anchorSystemMs = timeProvider()
        frozenAtMovieMs = null
    }

    val isFrozen: Boolean
        get() = frozenAtMovieMs != null

    fun adjustOffset(deltaMs: Long) {
        if (frozenAtMovieMs != null) {
            frozenAtMovieMs = frozenAtMovieMs!! + deltaMs
        } else {
            anchorMovieMs += deltaMs
        }
    }

    fun reset() {
        anchorMovieMs = 0L
        anchorSystemMs = 0L
        isClockRunning = false
        frozenAtMovieMs = null
    }

    val isRunning: Boolean
        get() = isClockRunning && frozenAtMovieMs == null
}
