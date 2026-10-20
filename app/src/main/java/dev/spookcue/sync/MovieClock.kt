package dev.spookcue.sync

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MovieClock @Inject constructor() {

    private var anchorMovieMs: Long = 0L
    private var anchorSystemMs: Long = 0L
    private var isClockRunning: Boolean = false

    fun snap(movieTimeMs: Long) {
        anchorMovieMs = movieTimeMs
        anchorSystemMs = SystemClock.elapsedRealtime()
        isClockRunning = true
    }

    fun getCurrentMovieTimeMs(): Long {
        if (!isClockRunning) return anchorMovieMs
        val elapsedSinceAnchor = SystemClock.elapsedRealtime() - anchorSystemMs
        return anchorMovieMs + elapsedSinceAnchor
    }

    fun adjustOffset(deltaMs: Long) {
        anchorMovieMs += deltaMs
    }

    fun reset() {
        anchorMovieMs = 0L
        anchorSystemMs = 0L
        isClockRunning = false
    }

    val isRunning: Boolean
        get() = isClockRunning
}
