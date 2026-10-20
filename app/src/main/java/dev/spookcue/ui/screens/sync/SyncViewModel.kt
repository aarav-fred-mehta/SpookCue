package dev.spookcue.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spookcue.alert.ScareScheduler
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.repository.MovieRepository
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
    private val scareScheduler: ScareScheduler
) : ViewModel() {

    private val _movie = MutableStateFlow<MovieDetails?>(null)
    val movie: StateFlow<MovieDetails?> = _movie.asStateFlow()

    val syncState: StateFlow<SyncState> = syncEngine.state

    private val _isPulsing = MutableStateFlow(false)
    val isPulsing: StateFlow<Boolean> = _isPulsing.asStateFlow()

    fun loadMovieAndStartSync(tmdbId: Int) {
        viewModelScope.launch {
            val result = movieRepository.getMovieDetails(tmdbId)
            result.onSuccess { details ->
                _movie.value = details
                syncEngine.start(details)
                scareScheduler.startScheduling(details.scares)
                movieRepository.recordMovieSynced(tmdbId)
            }
        }

        viewModelScope.launch {
            scareScheduler.alertEvents.collect { event ->
                when (event) {
                    is ScareScheduler.AlertEvent.Warning10s -> {
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
        syncEngine.stop()
        scareScheduler.stopScheduling()
        _isPulsing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
