package dev.spookcue.sync

import dev.spookcue.audio.AcousticFingerprinter
import dev.spookcue.audio.AudioEnergyMeter
import dev.spookcue.data.model.AudioHashEntry
import dev.spookcue.data.model.JumpScare
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.MovieSyncData
import dev.spookcue.data.model.PhoneticEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest {

    private lateinit var syncEngine: SyncEngine
    private lateinit var phoneticMatcher: PhoneticMatcher
    private lateinit var movieClock: MovieClock
    private lateinit var transcriptWindow: TranscriptWindow
    private lateinit var audioHashMatcher: AudioHashMatcher
    private lateinit var acousticFingerprinter: AcousticFingerprinter
    private lateinit var audioEnergyMeter: AudioEnergyMeter
    private var simulatedTime: Long = 0L

    private val sampleMovie = MovieDetails(
        tmdbId = 101,
        title = "Haunted House",
        releaseYear = 2024,
        runtimeMinutes = 90,
        syncType = "subtitles",
        scares = listOf(
            JumpScare(
                id = "scare1",
                resolvedTimeMs = 600_000L,
                type = "major",
                description = "Ghost appears in mirror"
            )
        )
    )

    private val samplePhoneticSyncData by lazy {
        MovieSyncData(
            tmdbId = 101,
            type = "subtitles",
            phoneticIndex = listOf(
                PhoneticEntry(startMs = 30_000L, phonetics = phoneticMatcher.tokenizeAndEncode("do you see that ghost")),
                PhoneticEntry(startMs = 40_000L, phonetics = phoneticMatcher.tokenizeAndEncode("it is coming this way")),
                PhoneticEntry(startMs = 50_000L, phonetics = phoneticMatcher.tokenizeAndEncode("run out of the house")),
                PhoneticEntry(startMs = 60_000L, phonetics = phoneticMatcher.tokenizeAndEncode("hide in the dark cellar")),
                PhoneticEntry(startMs = 70_000L, phonetics = phoneticMatcher.tokenizeAndEncode("do not make any noise")),
                PhoneticEntry(startMs = 80_000L, phonetics = phoneticMatcher.tokenizeAndEncode("that scares us just thinking about everything"))
            )
        )
    }

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
                AudioHashEntry(h = 1007, t = 23_000L)
            )
        )
    }

    @Before
    fun setUp() {
        phoneticMatcher = PhoneticMatcher()
        movieClock = MovieClock()
        transcriptWindow = TranscriptWindow()
        audioHashMatcher = AudioHashMatcher()
        acousticFingerprinter = AcousticFingerprinter()
        audioEnergyMeter = AudioEnergyMeter()

        simulatedTime = 10_000L
        movieClock.timeProvider = { simulatedTime }
        transcriptWindow.timeProvider = { simulatedTime }

        syncEngine = SyncEngine(
            phoneticMatcher = phoneticMatcher,
            movieClock = movieClock,
            transcriptWindow = transcriptWindow,
            audioHashMatcher = audioHashMatcher,
            acousticFingerprinter = acousticFingerprinter,
            audioEnergyMeter = audioEnergyMeter
        )
        syncEngine.timeProvider = { simulatedTime }
    }

    @Test
    fun testFindingProgressToMoviePlayingConsensus() {
        syncEngine.start(sampleMovie, samplePhoneticSyncData)
        assertTrue(syncEngine.state.value is SyncState.FindingProgress)

        // Utterance 1 at system time 10s: "do you see that ghost"
        syncEngine.onSpeechTranscribed("do you see that ghost")
        val state1 = syncEngine.state.value as SyncState.FindingProgress
        assertEquals(1, state1.candidatesFound)

        // Advance 10s: system time 20s. Utterance 2: "it is coming this way"
        simulatedTime = 20_000L
        syncEngine.onSpeechTranscribed("it is coming this way")

        // Expected diff = 10s. Actual movie diff = 40s - 30s = 10s. Delta = 0ms <= 2500ms
        val state2 = syncEngine.state.value
        assertTrue("Expected MoviePlaying state but was $state2", state2 is SyncState.MoviePlaying)
        val playing = state2 as SyncState.MoviePlaying
        assertEquals(40_000L, playing.currentMovieTimeMs)
    }

    @Test
    fun testFastLockOnUniqueSentence() {
        syncEngine.start(sampleMovie, samplePhoneticSyncData)
        assertTrue(syncEngine.state.value is SyncState.FindingProgress)

        // 7 distinctive phonetic tokens, exact match on 80_000L
        syncEngine.onSpeechTranscribed("that scares us just thinking about everything")

        val state = syncEngine.state.value
        assertTrue("Expected MoviePlaying via Fast-Lock but was $state", state is SyncState.MoviePlaying)
        val playing = state as SyncState.MoviePlaying
        assertEquals(80_000L, playing.currentMovieTimeMs)
    }

    @Test
    fun testMoviePlayingMicroDriftAdjustment() {
        syncEngine.start(sampleMovie, samplePhoneticSyncData)

        // Lock at 40s at system time 20s
        syncEngine.onSpeechTranscribed("do you see that ghost")
        simulatedTime = 20_000L
        syncEngine.onSpeechTranscribed("it is coming this way")
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)

        // Advance system time by 2s -> clock is at 42_000L
        simulatedTime = 22_000L
        assertEquals(42_000L, movieClock.getCurrentMovieTimeMs())

        // Dialogue arrives that matches line at 40_000L ("it is coming this way")
        // drift = 40_000 - 42_000 = -2000ms. Damping 0.25 -> correction = -500ms
        syncEngine.onSpeechTranscribed("it is coming this way")

        assertEquals(41_500L, movieClock.getCurrentMovieTimeMs())
    }

    @Test
    fun testMoviePlayingToMoviePausedOnEnergyDrop() {
        syncEngine.start(sampleMovie, samplePhoneticSyncData)

        // Lock
        syncEngine.onSpeechTranscribed("that scares us just thinking about everything")
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)

        // Feed quiet ambient noise below PAUSE_SILENCE_MAX_DB (e.g. 20 dB) into AudioEnergyMeter
        val quietFrame = ShortArray(512) { 5 }
        for (i in 1..20) {
            audioEnergyMeter.processFrame(quietFrame)
        }
        assertTrue("Expected energy dropped", audioEnergyMeter.isEnergyDropped())

        // 3 consecutive missed checkpoints while energy is dropped
        for (i in 1..3) {
            syncEngine.onSpeechTranscribed("unrelated audience chatter $i")
        }

        val state = syncEngine.state.value
        assertTrue("Expected MoviePaused state but was $state", state is SyncState.MoviePaused)
        assertTrue(movieClock.isFrozen)
    }

    @Test
    fun testMoviePausedTier1LocalRecovery() {
        syncEngine.start(sampleMovie, samplePhoneticSyncData)

        // Fast lock at 40s
        syncEngine.onSpeechTranscribed("do you see that ghost")
        simulatedTime = 20_000L
        syncEngine.onSpeechTranscribed("it is coming this way")
        assertTrue(syncEngine.state.value is SyncState.MoviePlaying)

        // Pause manually
        syncEngine.togglePause()
        assertTrue(syncEngine.state.value is SyncState.MoviePaused)

        // Resume audio speech near paused position (40s -> 50s "run out of the house")
        simulatedTime = 60_000L
        syncEngine.onSpeechTranscribed("run out of the house")

        simulatedTime = 70_000L
        syncEngine.onSpeechTranscribed("hide in the dark cellar")

        val state = syncEngine.state.value
        assertTrue("Expected MoviePlaying after Tier 1 recovery but was $state", state is SyncState.MoviePlaying)
    }

    @Test
    fun testAcousticHashMatchStage1And2() {
        val quietPlace = MovieDetails(
            tmdbId = 202,
            title = "A Quiet Place",
            releaseYear = 2018,
            runtimeMinutes = 90,
            syncType = "audio_hash"
        )
        syncEngine.start(quietPlace, sampleHashSyncData)
        assertTrue(syncEngine.state.value is SyncState.FindingProgress)

        // Simulate 6 matching sample hashes captured at system time 5000L
        // with sample.t = 0..3000ms.
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
        assertTrue("Expected MoviePlaying after hash lock but was $state", state is SyncState.MoviePlaying)
    }

    @Test
    fun testManualPauseAndResumeToggle() {
        syncEngine.start(sampleMovie, samplePhoneticSyncData)

        // Lock
        syncEngine.onSpeechTranscribed("that scares us just thinking about everything")
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
}
