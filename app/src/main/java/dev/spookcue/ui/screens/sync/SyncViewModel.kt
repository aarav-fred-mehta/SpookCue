package dev.spookcue.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spookcue.alert.ScareScheduler
import dev.spookcue.audio.AudioCaptureManager
import dev.spookcue.audio.SpeechRecognizerManager
import dev.spookcue.data.local.UserPreferencesManager
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.repository.MovieRepository
import dev.spookcue.sync.SyncDebugInfo
import dev.spookcue.sync.SyncEngine
import dev.spookcue.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val movieRepository: MovieRepository,
    private val syncEngine: SyncEngine,
    private val scareScheduler: ScareScheduler,
    private val audioCaptureManager: AudioCaptureManager,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val preferencesManager: UserPreferencesManager
) : ViewModel() {

    private val _movie = MutableStateFlow<MovieDetails?>(null)
    val movie: StateFlow<MovieDetails?> = _movie.asStateFlow()

    val syncState: StateFlow<SyncState> = syncEngine.state

    private val _isPulsing = MutableStateFlow(false)
    val isPulsing: StateFlow<Boolean> = _isPulsing.asStateFlow()

    private val _revealedScareIds = MutableStateFlow<Set<String>>(emptySet())
    val revealedScareIds: StateFlow<Set<String>> = _revealedScareIds.asStateFlow()

    private val _isScreenOff = MutableStateFlow(false)
    val isScreenOff: StateFlow<Boolean> = _isScreenOff.asStateFlow()

    // Real-Time Audio Debug State
    val rmsDb: StateFlow<Float> = audioCaptureManager.rmsDb
    val speechDetected: StateFlow<Boolean> = audioCaptureManager.speechDetected
    val liveTranscript: StateFlow<String> = speechRecognizerManager.livePartialText
    val isListening: StateFlow<Boolean> = speechRecognizerManager.isListening
    val debugInfo: StateFlow<SyncDebugInfo> = syncEngine.debugInfo

    private val _isDebugVisible = MutableStateFlow(preferencesManager.isAudioDebug())
    val isDebugVisible: StateFlow<Boolean> = _isDebugVisible.asStateFlow()

    fun toggleDebugVisible() {
        _isDebugVisible.value = !_isDebugVisible.value
    }

    fun simulateSpeech(text: String) {
        syncEngine.onSpeechTranscribed(text)
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

    fun loadMovieAndStartSync(tmdbId: Int) {
        viewModelScope.launch {
            val result = movieRepository.getMovieDetails(tmdbId)
            result.onSuccess { details ->
                _movie.value = details
                syncEngine.start(details)
                scareScheduler.startScheduling(details.scares)
                audioCaptureManager.startCapture()
                speechRecognizerManager.startListening()
                movieRepository.recordMovieSynced(tmdbId)
            }
        }

        viewModelScope.launch {
            speechRecognizerManager.transcriptions.collect { text ->
                syncEngine.onSpeechTranscribed(text)
            }
        }

        viewModelScope.launch {
            scareScheduler.alertEvents.collect { event ->
                when (event) {
                    is ScareScheduler.AlertEvent.WarningLead -> {
                        if (event.scare.type == "major") {
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

    fun adjustOffset(deltaMs: Long) {
        syncEngine.adjustClock(deltaMs)
    }

    fun forceResync() {
        syncEngine.forceResync()
    }

    fun stop() {
        speechRecognizerManager.stopListening()
        audioCaptureManager.stopCapture()
        syncEngine.stop()
        scareScheduler.stopScheduling()
        _isPulsing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
