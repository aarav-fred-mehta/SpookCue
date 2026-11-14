package dev.spookcue.ui.screens.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.alert.ScareScheduler
import dev.spookcue.audio.AudioCaptureManager
import dev.spookcue.audio.VadEngine
import dev.spookcue.data.local.UserPreferencesManager
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.repository.MovieRepository
import dev.spookcue.service.SpookCueService
import dev.spookcue.sync.SyncDebugInfo
import dev.spookcue.sync.SyncHistoryRecord
import dev.spookcue.sync.SyncEngine
import dev.spookcue.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val movieRepository: MovieRepository,
    private val syncEngine: SyncEngine,
    private val scareScheduler: ScareScheduler,
    private val audioCaptureManager: AudioCaptureManager,
    private val vadEngine: VadEngine,
    private val preferencesManager: UserPreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "SyncViewModel"
    }

    private val _movie = MutableStateFlow<MovieDetails?>(null)
    val movie: StateFlow<MovieDetails?> = _movie.asStateFlow()

    val syncState: StateFlow<SyncState> = syncEngine.state

    private val _isPulsing = MutableStateFlow(false)
    val isPulsing: StateFlow<Boolean> = _isPulsing.asStateFlow()

    private val _revealedScareIds = MutableStateFlow<Set<String>>(emptySet())
    val revealedScareIds: StateFlow<Set<String>> = _revealedScareIds.asStateFlow()

    private val _isScreenOff = MutableStateFlow(false)
    val isScreenOff: StateFlow<Boolean> = _isScreenOff.asStateFlow()

    // Real-Time Audio Debug State (observed from audio singletons)
    val rmsDb: StateFlow<Float> = audioCaptureManager.rmsDb
    val speechDetected: StateFlow<Boolean> = vadEngine.speechDetected

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    val debugInfo: StateFlow<SyncDebugInfo> = syncEngine.debugInfo
    val syncHistory: StateFlow<List<SyncHistoryRecord>> = syncEngine.syncHistory

    private val _isDebugVisible = MutableStateFlow(preferencesManager.isAudioDebug())
    val isDebugVisible: StateFlow<Boolean> = _isDebugVisible.asStateFlow()

    init {
        viewModelScope.launch {
            syncEngine.lastTranscript.collect { text ->
                if (text.isNotBlank()) {
                    _liveTranscript.value = text
                }
            }
        }

        viewModelScope.launch {
            scareScheduler.alertEvents.collect { event ->
                when (event) {
                    is ScareScheduler.AlertEvent.WarningLead -> {
                        if (event.scare.type.equals("major", ignoreCase = true)) {
                            _isPulsing.value = true
                        }
                    }
                    is ScareScheduler.AlertEvent.ScareNow -> {
                        _isPulsing.value = false
                    }
                    else -> Unit
                }
            }
        }
    }

    fun toggleDebugVisible() {
        _isDebugVisible.value = !_isDebugVisible.value
    }

    fun simulateSpeech(text: String) {
        syncEngine.onSpeechTranscribed(text)
    }
    fun simulateAcousticLock(targetMovieTimeMs: Long = 600_000L) {
        syncEngine.simulateAcousticLock(targetMovieTimeMs)
    }

    fun simulateDrift(driftMs: Long) {
        syncEngine.simulateDrift(driftMs)
    }

    fun clearSyncHistory() {
        syncEngine.clearHistory()
    }

    fun exportSyncHistory(context: Context) {
        val json = syncEngine.getSyncHistoryJson()
        val movieTitle = _movie.value?.title ?: "Movie"

        // 1. Log to logcat with clear delimiter for automated ADB capture
        Log.i("SyncExport", "=== SPOOKCUE_SYNC_EXPORT_START ===")
        Log.i("SyncExport", json)
        Log.i("SyncExport", "=== SPOOKCUE_SYNC_EXPORT_END ===")

        // 2. Save to external files dir (accessible via adb pull)
        try {
            val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
            val exportFile = java.io.File(exportDir, "spookcue_sync_history.json")
            exportFile.writeText(json)
            Log.i("SyncExport", "Saved sync history to: ${exportFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("SyncExport", "Failed to write sync history to file", e)
        }

        // 3. Launch Android system share sheet
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SpookCue Sync Log: $movieTitle")
                putExtra(Intent.EXTRA_TEXT, json)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooserIntent = Intent.createChooser(shareIntent, "Export SpookCue Sync Log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            Log.e("SyncExport", "Failed to launch share chooser", e)
        }
    }


    fun toggleScareRevealed(scareId: String) {
        val current = _revealedScareIds.value
        if (current.contains(scareId)) {
            _revealedScareIds.value = current - scareId
        } else {
            _revealedScareIds.value = current + scareId
        }
    }

    fun setScreenOff(enabled: Boolean) {
        _isScreenOff.value = enabled
    }

    fun toggleScreenOff() {
        _isScreenOff.value = !_isScreenOff.value
    }

    fun loadMovieAndStartSync(tmdbId: Int, syncModeOverride: String? = null) {
        Log.i(TAG, "loadMovieAndStartSync called with tmdbId: $tmdbId")
        viewModelScope.launch {
            val result = movieRepository.getMovieDetails(tmdbId)
            result.onSuccess { details ->
                Log.i(TAG, "Successfully loaded '${details.title}' (runtime: ${details.runtimeMinutes}m)")
                _movie.value = details
                _isListening.value = true

                // Start SpookCueService as foreground service — it owns audio capture, VAD, STT and sync
                val intent = Intent(context, SpookCueService::class.java).apply {
                    action = SpookCueService.ACTION_START_SYNC
                    putExtra(SpookCueService.EXTRA_TMDB_ID, tmdbId)
                    putExtra(SpookCueService.EXTRA_MOVIE_TITLE, details.title)
                    putExtra(SpookCueService.EXTRA_RUNTIME_MS, details.runtimeMinutes * 60_000L)
                    if (syncModeOverride != null) {
                        putExtra(SpookCueService.EXTRA_SYNC_MODE, syncModeOverride)
                    }
                }
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { e ->
                Log.e(TAG, "Failed to load movie details for tmdbId $tmdbId", e)
            }
        }
    }

    fun togglePause() {
        Log.i(TAG, "togglePause requested")
        syncEngine.togglePause()
    }

    fun adjustOffset(deltaMs: Long) {
        syncEngine.adjustClock(deltaMs)
    }

    fun forceResync() {
        Log.i(TAG, "Force resync requested")
        syncEngine.forceResync()
    }

    fun stop() {
        Log.i(TAG, "Stopping SyncViewModel and stopping SpookCueService")
        val intent = Intent(context, SpookCueService::class.java).apply {
            action = SpookCueService.ACTION_STOP_SYNC
        }
        context.startService(intent)
        _isPulsing.value = false
        _isListening.value = false
        _liveTranscript.value = ""
    }

    override fun onCleared() {
        super.onCleared()
    }
}
