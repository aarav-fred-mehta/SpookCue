package dev.spookcue.audio

import dev.spookcue.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Tracks short-term and long-term acoustic energy to detect movie playback soundscapes
 * versus quiet room murmur, pauses, or silence.
 */
@Singleton
class AudioEnergyMeter @Inject constructor() {

    companion object {
        private const val TAG = "AudioEnergyMeter"

        // Typical cinema/TV movie audio produces sustained RMS above 42 dB even during quiet scenes
        // Ambient room noise / whispered silence in a theater typically rests between 20-35 dB
        const val MOVIE_SOUNDSCAPE_MIN_DB = 42.0f
        const val PAUSE_SILENCE_MAX_DB = 36.0f

        private const val SHORT_TERM_ALPHA = 0.30f
        private const val LONG_TERM_ALPHA = 0.05f
    }

    private var shortTermEnergyDb: Float = 0f
    private var longTermEnergyDb: Float = 0f
    private var sampleCount: Long = 0

    @Synchronized
    fun processFrame(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f

        var sumSquare = 0.0
        for (sample in samples) {
            sumSquare += (sample.toDouble() * sample)
        }
        val rms = sqrt(sumSquare / samples.size)
        val instantDb = if (rms > 0) {
            (20.0 * log10(rms / 32767.0) + 90.0).toFloat().coerceIn(0f, 100f)
        } else {
            0f
        }

        if (sampleCount == 0L) {
            shortTermEnergyDb = instantDb
            longTermEnergyDb = instantDb
        } else {
            shortTermEnergyDb += SHORT_TERM_ALPHA * (instantDb - shortTermEnergyDb)
            longTermEnergyDb += LONG_TERM_ALPHA * (instantDb - longTermEnergyDb)
        }
        sampleCount++

        return instantDb
    }

    @Synchronized
    fun getInstantEnergyDb(): Float = shortTermEnergyDb

    @Synchronized
    fun getAverageEnergyDb(): Float = longTermEnergyDb

    /**
     * Returns true if audio energy dropped substantially below active movie levels
     * indicating playback was paused or cut to quiet theater ambience.
     */
    @Synchronized
    fun isEnergyDropped(): Boolean {
        return shortTermEnergyDb < PAUSE_SILENCE_MAX_DB ||
               (longTermEnergyDb > MOVIE_SOUNDSCAPE_MIN_DB && (longTermEnergyDb - shortTermEnergyDb) > 8.0f)
    }

    /**
     * Returns true if audio energy has restored to active movie soundscape levels.
     */
    @Synchronized
    fun isMovieSoundscapeActive(): Boolean {
        return shortTermEnergyDb >= MOVIE_SOUNDSCAPE_MIN_DB
    }

    @Synchronized
    fun reset() {
        shortTermEnergyDb = 0f
        longTermEnergyDb = 0f
        sampleCount = 0
    }
}
