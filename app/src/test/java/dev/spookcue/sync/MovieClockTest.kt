package dev.spookcue.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MovieClockTest {

    private lateinit var clock: MovieClock
    private var simulatedTime: Long = 0L

    @Before
    fun setUp() {
        clock = MovieClock()
        simulatedTime = 5000L
        clock.timeProvider = { simulatedTime }
    }

    @Test
    fun testSnapAndTicking() {
        assertFalse(clock.isRunning)
        clock.snap(60_000L) // Snap to 1 min movie time
        assertTrue(clock.isRunning)
        assertEquals(60_000L, clock.getCurrentMovieTimeMs())

        simulatedTime += 3000L
        assertEquals(63_000L, clock.getCurrentMovieTimeMs())
    }

    @Test
    fun testFreezeAndResume() {
        clock.snap(100_000L)
        simulatedTime += 5000L // movie time is 105,000L
        assertEquals(105_000L, clock.getCurrentMovieTimeMs())

        // Freeze
        clock.freeze()
        assertTrue(clock.isFrozen)
        assertFalse(clock.isRunning)
        assertEquals(105_000L, clock.getCurrentMovieTimeMs())

        // Advance system time while frozen — movie time should NOT advance
        simulatedTime += 30_000L
        assertEquals(105_000L, clock.getCurrentMovieTimeMs())

        // Resume
        clock.resume()
        assertFalse(clock.isFrozen)
        assertTrue(clock.isRunning)
        assertEquals(105_000L, clock.getCurrentMovieTimeMs())

        // Now ticking resumes
        simulatedTime += 2000L
        assertEquals(107_000L, clock.getCurrentMovieTimeMs())
    }

    @Test
    fun testAdjustOffsetDuringFreeze() {
        clock.snap(50_000L)
        clock.freeze()
        assertEquals(50_000L, clock.getCurrentMovieTimeMs())

        clock.adjustOffset(5000L)
        assertEquals(55_000L, clock.getCurrentMovieTimeMs())

        clock.resume()
        simulatedTime += 1000L
        assertEquals(56_000L, clock.getCurrentMovieTimeMs())
    }

    @Test
    fun testReset() {
        clock.snap(50_000L)
        clock.freeze()
        clock.reset()

        assertFalse(clock.isRunning)
        assertFalse(clock.isFrozen)
        assertEquals(0L, clock.getCurrentMovieTimeMs())
    }
}
