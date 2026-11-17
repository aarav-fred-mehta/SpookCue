package dev.spookcue.sync

import dev.spookcue.audio.AcousticFingerprinter
import dev.spookcue.audio.AudioEnergyMeter
import dev.spookcue.data.model.AudioHashEntry
import dev.spookcue.data.model.JumpScare
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.MovieSyncData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest {

    private lateinit var syncEngine: SyncEngine
    private lateinit var movieClock: MovieClock
    private lateinit var audioHashMatcher: AudioHashMatcher
    private lateinit var acousticFingerprinter: AcousticFingerprinter
    private lateinit var audioEnergyMeter: AudioEnergyMeter
    private var simulatedTime: Long = 0L

    private val sampleMovie = MovieDetails(
        tmdbId = 202,
        title = "A Quiet Place",
        releaseYear = 2018,
        runtimeMinutes = 90,
        syncType = "audio_hash",
        scares = listOf(
            JumpScare(
                id = "scare1",
                resolvedTimeMs = 600_000L,
                type = "major",
                description = "Monster attacks the silo"
            )
        )
    )

    private val sampleHashSyncData by lazy {
        MovieSyncData(
            tmdbId = 202,
            type = "audio_hash",
            audioHashes = listOf(
                AudioHashEntry(h = 1001, t = 20_000L),
                AudioHashEntry(h = 1002, t = 20_500L),
                AudioHashEntry(h = 1003, t = 21_000L),
                AudioHashEntry(h = 1004, t = 21_500L),
                AudioHashEntry(h = 1005, t = 22_000L),
                AudioHashEntry(h = 1006, t = 22_500L),
                AudioHashEntry(h = 1007, t = 23_000L),
                AudioHashEntry(h = 1008, t = 23_500L),
                AudioHashEntry(h = 1009, t = 24_000L),
                AudioHashEntry(h = 1010, t = 24_500L)
            )
        )
    }

    @Before
    fun setUp() {
        movieClock = MovieClock()
        audioHashMatcher = AudioHashMatcher()
        acousticFingerprinter = AcousticFingerprinter()
        audioEnergyMeter = AudioEnergyMeter()

        simulatedTime = 10_000L
        movieClock.timeProvider = { simulatedTime }

        syncEngine = SyncEngine(
            movieClock = movieClock,
            audioHashMatcher = audioHashMatcher,
            acousticFingerprinter = acousticFingerprinter,
            audioEnergyMeter = audioEnergyMeter
        )
        syncEngine.timeProvider = { simulatedTime }
    }

    @Test
    fun testAcousticHashMatchStage1FastLock() {
        syncEngine.start(sampleMovie, sampleHashSyncData)
        assertTrue(syncEngine.state.value is SyncState.FindingProgress)

        // 6 matching sample hashes captured at system time 5000L
        // Offsets match 20_000L - 0L = 20_000L
        val sampleHashes = listOf(
            AudioHashEntry(h = 1001, t = 0L),
            AudioHashEntry(h = 1002, t = 500L),
            AudioHashEntry(h = 1003, t = 1000L),
            AudioHashEntry(h = 1004, t = 1500L),
            AudioHashEntry(h = 1005, t = 2000L),
            AudioHashEntry(h = 1006, t = 2500L)
        )

        simulatedTime = 5000L
        syncEngine.onAudioHashesExtracted(sampleHashes, bufferDurationMs = 3000L)

        val state = syncEngine.state.value
        assertTrue("Expected MoviePlaying after clean hash lock but was $state", state is SyncState.MoviePlaying)
        val playing = state as SyncState.MoviePlaying
        assertEquals(23_000L, playing.currentMovieTimeMs)
    }

    @Test
    fun testAcousticTemporalConsensusVerification() {
        // Reference data with primary sequence at 20s and runner-up sequence at 50s
        val syncData = MovieSyncData(
            tmdbId = 202,
            type = "audio_hash",
            audioHashes = listOf(
                AudioHashEntry(h = 2001, t = 20_000L),
                AudioHashEntry(h = 2002, t = 20_500L),
                AudioHashEntry(h = 2003, t = 21_000L),
                AudioHashEntry(h = 2004, t = 21_500L),
                AudioHashEntry(h = 2005, t = 22_000L),
                AudioHashEntry(h = 2006, t = 22_500L),
                AudioHashEntry(h = 2007, t = 23_000L),
                AudioHashEntry(h = 2008, t = 23_500L),
                // Secondary runner-up bucket (exactly 2 aligned matches at offset 50000)
                AudioHashEntry(h = 2001, t = 50_000L),
                AudioHashEntry(h = 2002, t = 50_500L)
            )
        )
        syncEngine.start(sampleMovie, syncData)
        assertTrue(syncEngine.state.value is SyncState.FindingProgress)

        // Window 1 at system time 1000L
        val window1 = listOf(
            AudioHashEntry(h = 2001, t = 0L),
            AudioHashEntry(h = 2002, t = 500L),
            AudioHashEntry(h = 2003, t = 1000L),
            AudioHashEntry(h = 2004, t = 1500L),
            AudioHashEntry(h = 2005, t = 2000L),
            AudioHashEntry(h = 2006, t = 2500L)
        )

        simulatedTime = 1000L
        syncEngine.onAudioHashesExtracted(window1, bufferDurationMs = 2500L)

        // Candidate 1 registered — should still be in FindingProgress awaiting confirmation
        val stateAfterWindow1 = syncEngine.state.value
        assertTrue("Expected FindingProgress pending consensus confirmation but was $stateAfterWindow1", stateAfterWindow1 is SyncState.FindingProgress)

        // Window 2 at system time 2000L (+1000ms), movie advanced by +1000ms (offset 21_000L)
        val window2 = listOf(
            AudioHashEntry(h = 2003, t = 0L),
            AudioHashEntry(h = 2004, t = 500L),
            AudioHashEntry(h = 2005, t = 1000L),
            AudioHashEntry(h = 2006, t = 1500L),
            AudioHashEntry(h = 2007, t = 2000L),
            AudioHashEntry(h = 2008, t = 2500L)
        )

        simulatedTime = 2000L
        syncEngine.onAudioHashesExtracted(window2, bufferDurationMs = 2500L)

        // Consensus confirmed! Movie progress and system time both advanced in sync
        val stateAfterWindow2 = syncEngine.state.value
        assertTrue("Expected MoviePlaying after consensus confirmation but was $stateAfterWindow2", stateAfterWindow2 is SyncState.MoviePlaying)
    }

    @Test
    fun testAcousticHashBoundedDriftCorrection() {
        syncEngine.start(sampleMovie, sampleHashSyncData)

        // Lock at 20s
        val sampleHashes = listOf(
            AudioHashEntry(h = 1001, t = 0L),
            AudioHashEntry(h = 1002, t = 500L),
            AudioHashEntry(h = 1003, t = 1000L),
            AudioHashEntry(h = 1004, t = 1500L),
            AudioHashEntry(h = 1005, t = 2000L),
            AudioHashEntry(h = 1006, t = 2500L)
        )
        simulatedTime = 5000L
        syncEngine.onAudioHashesExtracted(sampleHashes, bufferDurationMs = 3000L)
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)

        // Advance system time by 2s -> clock is at 25_000L
        simulatedTime = 7000L
        assertEquals(25_000L, movieClock.getCurrentMovieTimeMs())

        // Live audio arrives corresponding to 20_000L offset + 3000ms = 23_000L (drift is -2000ms)
        // DRIFT_DAMPING_FACTOR = 0.25 -> correction = -500ms -> clock becomes 24_500L
        syncEngine.onAudioHashesExtracted(sampleHashes, bufferDurationMs = 3000L)
        assertEquals(24_500L, movieClock.getCurrentMovieTimeMs())
    }

    @Test
    fun testManualPauseAndResumeToggle() {
        syncEngine.start(sampleMovie, sampleHashSyncData)
        syncEngine.simulateAcousticLock(30_000L)
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)

        // Toggle pause -> paused
        syncEngine.togglePause()
        assertTrue(syncEngine.state.value is SyncState.MoviePaused)
        assertTrue(movieClock.isFrozen)

        // Toggle pause -> playing
        syncEngine.togglePause()
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)
        assertTrue(movieClock.isRunning)
    }

    @Test
    fun testAcousticHashPausedResume() {
        syncEngine.start(sampleMovie, sampleHashSyncData)
        syncEngine.simulateAcousticLock(20_000L)
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)

        syncEngine.togglePause()
        assertTrue(syncEngine.state.value is SyncState.MoviePaused)

        // Provide hashes near paused position (22_000L)
        val resumeHashes = listOf(
            AudioHashEntry(h = 1005, t = 0L),
            AudioHashEntry(h = 1006, t = 500L),
            AudioHashEntry(h = 1007, t = 1000L),
            AudioHashEntry(h = 1008, t = 1500L),
            AudioHashEntry(h = 1009, t = 2000L),
            AudioHashEntry(h = 1010, t = 2500L)
        )

        simulatedTime = 60_000L
        syncEngine.onAudioHashesExtracted(resumeHashes, bufferDurationMs = 3000L)

        val state = syncEngine.state.value
        assertTrue("Expected MoviePlaying after resume but was $state", state is SyncState.MoviePlaying)
        assertEquals(25_000L, (state as SyncState.MoviePlaying).currentMovieTimeMs)
    }
}
