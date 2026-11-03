package dev.spookcue.sync

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling buffer of recent speech transcriptions.
 * Accumulates text segments with timestamps, evicts entries older than [windowMs].
 * Returns the concatenated text of all live entries for phonetic matching.
 */
@Singleton
class TranscriptWindow @Inject constructor() {

    companion object {
        const val DEFAULT_WINDOW_MS = 20_000L // 20 seconds
    }

    internal var timeProvider: () -> Long = { SystemClock.elapsedRealtime() }
    internal var windowMs: Long = DEFAULT_WINDOW_MS

    private data class Entry(val text: String, val timestampMs: Long)

    private val entries = mutableListOf<Entry>()

    /** Add a new transcription segment. Returns the full concatenated window text. */
    fun push(text: String): String {
        val now = timeProvider()
        entries.add(Entry(text.trim(), now))
        evict(now)
        return getWindowText()
    }

    /** Get concatenated text of all live entries. */
    fun getWindowText(): String {
        evict(timeProvider())
        return entries.joinToString(" ") { it.text }
    }

    /** Number of segments currently in the window. */
    fun segmentCount(): Int {
        evict(timeProvider())
        return entries.size
    }

    fun clear() {
        entries.clear()
    }

    private fun evict(now: Long) {
        entries.removeAll { now - it.timestampMs > windowMs }
    }
}
