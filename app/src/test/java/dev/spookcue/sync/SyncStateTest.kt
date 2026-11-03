package dev.spookcue.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateTest {

    @Test
    fun testIdleState() {
        val state: SyncState = SyncState.Idle
        assertTrue(state is SyncState.Idle)
    }

    @Test
    fun testFindingProgressState() {
        val state = SyncState.FindingProgress(startedAtMs = 1000L, candidatesFound = 1)
        assertEquals(1000L, state.startedAtMs)
        assertEquals(1, state.candidatesFound)

        val defaultMatches = SyncState.FindingProgress(startedAtMs = 2000L)
        assertEquals(0, defaultMatches.candidatesFound)
    }

    @Test
    fun testMoviePlayingState() {
        val state = SyncState.MoviePlaying(
            currentMovieTimeMs = 50000L,
            confidence = 0.95f,
            nextScareCountdownMs = 12000L,
            nextCheckpointInMs = 3000L
        )
        assertEquals(50000L, state.currentMovieTimeMs)
        assertEquals(0.95f, state.confidence, 0.001f)
        assertEquals(12000L, state.nextScareCountdownMs)
        assertEquals(3000L, state.nextCheckpointInMs)

        val noNextScare = SyncState.MoviePlaying(
            currentMovieTimeMs = 60000L,
            confidence = 0.88f
        )
        assertNull(noNextScare.nextScareCountdownMs)
        assertNull(noNextScare.nextCheckpointInMs)
    }

    @Test
    fun testMoviePausedState() {
        val state = SyncState.MoviePaused(
            pausedAtMovieTimeMs = 120000L,
            isManual = true,
            listeningDutyActive = true
        )
        assertEquals(120000L, state.pausedAtMovieTimeMs)
        assertTrue(state.isManual)
        assertTrue(state.listeningDutyActive)

        val autoDetected = SyncState.MoviePaused(pausedAtMovieTimeMs = 90000L)
        assertEquals(false, autoDetected.isManual)
    }

    @Test
    fun testErrorState() {
        val state = SyncState.Error(message = "Microphone permission denied")
        assertEquals("Microphone permission denied", state.message)
    }
}
