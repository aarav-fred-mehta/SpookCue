package dev.spookcue.sync

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TranscriptWindowTest {

    private lateinit var window: TranscriptWindow
    private var simulatedTime: Long = 0L

    @Before
    fun setUp() {
        window = TranscriptWindow()
        simulatedTime = 1000L
        window.timeProvider = { simulatedTime }
    }

    @Test
    fun testPushAndConcatenation() {
        val r1 = window.push("hello")
        assertEquals("hello", r1)
        assertEquals(1, window.segmentCount())

        simulatedTime += 2000L
        val r2 = window.push("world")
        assertEquals("hello world", r2)
        assertEquals(2, window.segmentCount())
        assertEquals("hello world", window.getWindowText())
    }

    @Test
    fun testEvictionAfterWindowDuration() {
        window.push("segment one") // at t=1000
        simulatedTime += 10_000L  // t=11000
        window.push("segment two")

        assertEquals(2, window.segmentCount())
        assertEquals("segment one segment two", window.getWindowText())

        // Advance simulated time past 20s from first segment (t = 22000 -> diff 21000 > 20000)
        simulatedTime = 22_000L
        assertEquals("segment two", window.getWindowText())
        assertEquals(1, window.segmentCount())

        // Advance past 20s from second segment (t = 32000 -> diff 21000 > 20000)
        simulatedTime = 32_000L
        assertEquals("", window.getWindowText())
        assertEquals(0, window.segmentCount())
    }

    @Test
    fun testClear() {
        window.push("test line")
        assertEquals(1, window.segmentCount())
        window.clear()
        assertEquals(0, window.segmentCount())
        assertEquals("", window.getWindowText())
    }
}
