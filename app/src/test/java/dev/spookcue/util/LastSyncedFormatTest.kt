package dev.spookcue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastSyncedFormatTest {

    @Test
    fun testNullOrZeroTimestamp() {
        assertEquals("Never synced", formatLastSynced(null))
        assertEquals("Never synced", formatLastSynced(0L))
        assertEquals("Never synced", formatLastSynced(-1000L))
    }

    @Test
    fun testRecentTimestamp() {
        val now = System.currentTimeMillis()
        assertEquals("Just now", formatLastSynced(now - 15_000L))
        assertEquals("5 min ago", formatLastSynced(now - 5 * 60_000L))
        assertEquals("3 hr ago", formatLastSynced(now - 3 * 3600_000L))
        assertEquals("2 days ago", formatLastSynced(now - 2 * 24 * 3600_000L))
    }

    @Test
    fun testOlderTimestampFormatsAsDate() {
        val oldTimestamp = 1600000000000L // Sep 2020
        val formatted = formatLastSynced(oldTimestamp)
        assertTrue(formatted.contains("2020"))
    }
}
