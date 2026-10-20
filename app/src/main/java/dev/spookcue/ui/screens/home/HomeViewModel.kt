package dev.spookcue.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.repository.MovieRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val searchQuery: String = "",
    val isSyncing: Boolean = false,
    val error: String? = null,
    val selectedMovie: MovieCatalogItem? = null,
    val selectedMovieDetails: MovieDetails? = null,
    val isLoadingDetails: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val movieRepository: MovieRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    val movies: StateFlow<List<MovieCatalogItem>> = _searchQuery
        .flatMapLatest { query ->
            movieRepository.searchCatalog(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            movieRepository.initializeCatalogIfNeeded()
            syncCatalog(silent = true)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onMovieClicked(movie: MovieCatalogItem) {
        _uiState.update {
            it.copy(
                selectedMovie = movie,
                selectedMovieDetails = null,
                isLoadingDetails = true
            )
        }
        viewModelScope.launch {
            val result = movieRepository.getMovieDetails(movie.tmdbId)
            _uiState.update { current ->
                if (current.selectedMovie?.tmdbId == movie.tmdbId) {
                    current.copy(
                        selectedMovieDetails = result.getOrNull(),
                        isLoadingDetails = false
                    )
                } else {
                    current
                }
            }
        }
    }

    fun onDismissBottomSheet() {
        _uiState.update {
            it.copy(
                selectedMovie = null,
                selectedMovieDetails = null,
                isLoadingDetails = false
            )
        }
    }

    fun onStartSync(movie: MovieCatalogItem, onNavigate: (Int) -> Unit) {
        viewModelScope.launch {
            movieRepository.recordMovieSynced(movie.tmdbId)
            onDismissBottomSheet()
            onNavigate(movie.tmdbId)
        }
    }

    fun syncCatalog(silent: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = if (silent) null else it.error) }
            val result = movieRepository.syncCatalog(force = !silent)
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    error = if (silent) null else result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }
}
