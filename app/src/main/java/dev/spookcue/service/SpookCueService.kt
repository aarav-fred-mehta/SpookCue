package dev.spookcue.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.spookcue.alert.NotificationManager
import dev.spookcue.alert.ScareScheduler
import dev.spookcue.audio.AcousticFingerprinter
import dev.spookcue.audio.AudioCaptureManager
import dev.spookcue.audio.SttEngine
import dev.spookcue.audio.VadEngine
import dev.spookcue.data.model.MovieSyncData
import dev.spookcue.data.repository.MovieRepository
import dev.spookcue.sync.SyncEngine
import dev.spookcue.sync.SyncState
import dev.spookcue.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SpookCueService : Service() {

    @Inject
    lateinit var syncEngine: SyncEngine

    @Inject
    lateinit var audioCaptureManager: AudioCaptureManager

    @Inject
    lateinit var vadEngine: VadEngine

    @Inject
    lateinit var sttEngine: SttEngine

    @Inject
    lateinit var acousticFingerprinter: AcousticFingerprinter

    @Inject
    lateinit var scareScheduler: ScareScheduler

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    lateinit var movieRepository: MovieRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var syncJob: Job? = null
    private var activeMovieTitle = "Audio Sync"
    private var currentSyncMode = "subtitles"

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
                val title = intent.getStringExtra(EXTRA_MOVIE_TITLE) ?: "Movie"
                val runtimeMs = intent.getLongExtra(EXTRA_RUNTIME_MS, 3 * 60 * 60 * 1000L)
                activeMovieTitle = title
                startForegroundService(title, runtimeMs)
                startAcousticSync(tmdbId)
            }
            NotificationManager.ACTION_OFFSET_MINUS_5S -> {
                syncEngine.adjustClock(-5000L)
            }
            NotificationManager.ACTION_OFFSET_PLUS_5S -> {
                syncEngine.adjustClock(5000L)
            }
            NotificationManager.ACTION_TOGGLE_PAUSE -> {
                syncEngine.togglePause()
            }
            NotificationManager.ACTION_FORCE_RESYNC -> {
                syncEngine.forceResync()
            }
            NotificationManager.ACTION_STOP_SERVICE, ACTION_STOP_SYNC -> {
                stopSync()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService(title: String, runtimeMs: Long) {
        val notification = notificationManager.buildNotification(title, SyncState.Idle)

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NotificationManager.NOTIFICATION_ID,
            notification,
            foregroundType
        )

        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        val wakeTimeout = runtimeMs + 30 * 60 * 1000L
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpookCue::ListeningWakeLock")?.apply {
            acquire(wakeTimeout)
        }
    }

    private fun startAcousticSync(tmdbId: Int) {
        audioCaptureManager.startCapture()
        vadEngine.reset()
        sttEngine.reset()

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            // 1. Load Movie Details & Decoupled Sync Data
            if (tmdbId > 0) {
                val detailsResult = movieRepository.getMovieDetails(tmdbId)
                val syncDataResult = movieRepository.getMovieSyncData(tmdbId)

                detailsResult.onSuccess { movie ->
                    activeMovieTitle = movie.title
                    val syncData = syncDataResult.getOrElse {
                        MovieSyncData(tmdbId = tmdbId, type = movie.syncType)
                    }
                    currentSyncMode = syncData.type
                    syncEngine.start(movie, syncData)
                    scareScheduler.startScheduling(movie.scares)
                    movieRepository.recordMovieSynced(tmdbId)
                }
            }

            // 2. Audio Processing Loop
            launch {
                val audioBufferForHashes = mutableListOf<Short>()
                val checkpointAudioBuffer = mutableListOf<Short>()

                audioCaptureManager.audioFrames.collect { frame ->
                    val currentState = syncEngine.state.value

                    if (currentSyncMode == "audio_hash") {
                        // Acoustic Hash Pipeline (sliding 3.5s window evaluated every 0.5s in FindingProgress, 1.0s in Playing)
                        audioBufferForHashes.addAll(frame.toList())
                        if (audioBufferForHashes.size >= 56000) {
                            val samples = audioBufferForHashes.subList(0, 56000).toShortArray()
                            val hopSamples = if (currentState is SyncState.FindingProgress) 8000 else 16000
                            val remaining = audioBufferForHashes.subList(hopSamples, audioBufferForHashes.size).toList()
                            audioBufferForHashes.clear()
                            audioBufferForHashes.addAll(remaining)

                            val hashes = acousticFingerprinter.extractFingerprintsFromShorts(samples)
                            syncEngine.onAudioHashesExtracted(hashes, bufferDurationMs = 3500L)
                        }
                    } else {
                        // Subtitles / Speech Pipeline
                        when (currentState) {
                            is SyncState.FindingProgress -> {
                                // Stage 1: Continuous VAD + STT to acquire initial progress
                                val segments = vadEngine.processFrame(frame)
                                for (segment in segments) {
                                    val result = sttEngine.transcribe(segment)
                                    if (result.text.isNotBlank()) {
                                        syncEngine.onSpeechTranscribed(result.text)
                                    }
                                }
                            }

                            is SyncState.MoviePlaying -> {
                                // Stage 2: Low-cost scheduled checkpoint listening (VAD is OFF)
                                val nextCheckpoint = currentState.nextCheckpointInMs
                                val isNearCheckpoint = nextCheckpoint != null && nextCheckpoint in -1500L..2500L

                                if (isNearCheckpoint) {
                                    checkpointAudioBuffer.addAll(frame.toList())
                                    // Collect ~2.5 seconds of audio around the checkpoint
                                    if (checkpointAudioBuffer.size >= 40000) {
                                        val floats = FloatArray(checkpointAudioBuffer.size) { checkpointAudioBuffer[it] / 32768.0f }
                                        checkpointAudioBuffer.clear()
                                        val result = sttEngine.transcribe(floats)
                                        if (result.text.isNotBlank()) {
                                            syncEngine.onSpeechTranscribed(result.text)
                                        }
                                    }
                                } else {
                                    checkpointAudioBuffer.clear()
                                }
                            }

                            is SyncState.MoviePaused -> {
                                // Stage 3: Low-cost duty cycle (sample speech periodically)
                                checkpointAudioBuffer.addAll(frame.toList())
                                if (checkpointAudioBuffer.size >= 48000) { // ~3 seconds
                                    val floats = FloatArray(checkpointAudioBuffer.size) { checkpointAudioBuffer[it] / 32768.0f }
                                    checkpointAudioBuffer.clear()
                                    val result = sttEngine.transcribe(floats)
                                    if (result.text.isNotBlank()) {
                                        syncEngine.onSpeechTranscribed(result.text)
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            }

            // 3. Notification updates
            syncEngine.state.collect { state ->
                notificationManager.updateNotification(activeMovieTitle, state)
            }
        }
    }

    private fun stopSync() {
        audioCaptureManager.stopCapture()
        vadEngine.reset()
        sttEngine.reset()
        syncEngine.stop()
        scareScheduler.stopScheduling()
        syncJob?.cancel()
        syncJob = null

        wakeLock?.apply {
            if (isHeld) release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopSync()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SYNC = "dev.spookcue.action.START_SYNC"
        const val ACTION_STOP_SYNC = "dev.spookcue.action.STOP_SYNC"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
        const val EXTRA_MOVIE_TITLE = "extra_movie_title"
        const val EXTRA_RUNTIME_MS = "extra_runtime_ms"
    }
}
