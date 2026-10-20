package dev.spookcue.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionsTest {

    @Test
    fun testFormatTimeMs() {
        // Negative / Edge cases
        assertEquals("00:00", (-1000L).formatTimeMs())
        assertEquals("00:00", 0L.formatTimeMs())

        // Seconds
        assertEquals("00:05", 5_000L.formatTimeMs())
        assertEquals("00:45", 45_000L.formatTimeMs())

        // Minutes & Seconds
        assertEquals("01:00", 60_000L.formatTimeMs())
        assertEquals("02:05", 125_000L.formatTimeMs())
        assertEquals("20:45", 1_245_000L.formatTimeMs())

        // Hours, Minutes & Seconds
        assertEquals("01:00:00", 3_600_000L.formatTimeMs())
        assertEquals("01:01:05", 3_665_000L.formatTimeMs())
        assertEquals("02:26:40", 8_800_000L.formatTimeMs())
    }

    @Test
    fun testFormatCountdownMs() {
        // Non-positive
        assertEquals("0s", 0L.formatCountdownMs())
        assertEquals("0s", (-500L).formatCountdownMs())

        // Sub-second rounds up
        assertEquals("1s", 1L.formatCountdownMs())
        assertEquals("1s", 500L.formatCountdownMs())
        assertEquals("1s", 1000L.formatCountdownMs())

        // Greater than 1s rounds up correctly
        assertEquals("2s", 1001L.formatCountdownMs())
        assertEquals("2s", 1500L.formatCountdownMs())
        assertEquals("2s", 2000L.formatCountdownMs())
        assertEquals("3s", 2001L.formatCountdownMs())
        assertEquals("10s", 10_000L.formatCountdownMs())
    }
}
