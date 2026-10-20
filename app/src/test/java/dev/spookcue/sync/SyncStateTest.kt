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
    fun testAcquiringState() {
        val state = SyncState.Acquiring(startedAtMs = 1000L, candidateMatches = 1)
        assertEquals(1000L, state.startedAtMs)
        assertEquals(1, state.candidateMatches)

        val defaultMatches = SyncState.Acquiring(startedAtMs = 2000L)
        assertEquals(0, defaultMatches.candidateMatches)
    }

    @Test
    fun testLockedState() {
        val state = SyncState.Locked(
            currentMovieTimeMs = 50000L,
            confidence = 0.95f,
            nextScareCountdownMs = 12000L
        )
        assertEquals(50000L, state.currentMovieTimeMs)
        assertEquals(0.95f, state.confidence, 0.001f)
        assertEquals(12000L, state.nextScareCountdownMs)

        val noNextScare = SyncState.Locked(
            currentMovieTimeMs = 60000L,
            confidence = 0.88f
        )
        assertNull(noNextScare.nextScareCountdownMs)
    }

    @Test
    fun testVerifyingState() {
        val state = SyncState.Verifying(
            currentMovieTimeMs = 120000L,
            driftMs = 350L
        )
        assertEquals(120000L, state.currentMovieTimeMs)
        assertEquals(350L, state.driftMs)
    }

    @Test
    fun testErrorState() {
        val state = SyncState.Error(message = "Microphone permission denied")
        assertEquals("Microphone permission denied", state.message)
    }
}
