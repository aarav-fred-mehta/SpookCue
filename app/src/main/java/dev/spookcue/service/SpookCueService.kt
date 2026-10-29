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
import dev.spookcue.audio.AudioCaptureManager
import dev.spookcue.audio.SpeechRecognizerManager
import dev.spookcue.data.repository.MovieRepository
import dev.spookcue.sync.SyncEngine
import dev.spookcue.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SpookCueService : Service() {

    @Inject
    lateinit var syncEngine: SyncEngine

    @Inject
    lateinit var audioCaptureManager: AudioCaptureManager

    @Inject
    lateinit var speechRecognizerManager: SpeechRecognizerManager

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

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
                val title = intent.getStringExtra(EXTRA_MOVIE_TITLE) ?: "Movie"
                activeMovieTitle = title
                startForegroundService(title)
                startAcousticSync(tmdbId)
            }
            NotificationManager.ACTION_OFFSET_MINUS_5S -> {
                syncEngine.adjustClock(-5000L)
            }
            NotificationManager.ACTION_OFFSET_PLUS_5S -> {
                syncEngine.adjustClock(5000L)
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

    private fun startForegroundService(title: String) {
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
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpookCue::ListeningWakeLock")?.apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun startAcousticSync(tmdbId: Int) {
        audioCaptureManager.startCapture()
        speechRecognizerManager.startListening()

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            launch {
                speechRecognizerManager.transcriptions.collect { text ->
                    syncEngine.onSpeechTranscribed(text)
                }
            }

            if (tmdbId > 0) {
                movieRepository.getMovieDetails(tmdbId).onSuccess { movie ->
                    activeMovieTitle = movie.title
                    syncEngine.start(movie)
                    scareScheduler.startScheduling(movie.scares)
                }
            }

            syncEngine.state.collect { state ->
                notificationManager.updateNotification(activeMovieTitle, state)
            }
        }
    }

    private fun stopSync() {
        speechRecognizerManager.stopListening()
        audioCaptureManager.stopCapture()
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
    }
}
