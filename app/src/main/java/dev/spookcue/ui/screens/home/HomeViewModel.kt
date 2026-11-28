package dev.spookcue.ui.screens.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.repository.MovieRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import javax.inject.Inject

enum class FeaturedCategory {
    THEATER_NEW,
    POPULAR_HOME
}

data class HomeUiState(
    val searchQuery: String = "",
    val isSyncing: Boolean = false,
    val isInitialLoading: Boolean = true,
    val error: String? = null,
    val selectedMovie: MovieCatalogItem? = null,
    val selectedMovieDetails: MovieDetails? = null,
    val isLoadingDetails: Boolean = false,
    val selectedCategory: FeaturedCategory = FeaturedCategory.THEATER_NEW,
    val theaterMovies: List<MovieCatalogItem> = emptyList(),
    val popularHomeMovies: List<MovieCatalogItem> = emptyList(),
    val totalMovieCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val movieRepository: MovieRepository,
    @ApplicationContext private val context: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private var prefetchJob: Job? = null

    val movies: StateFlow<List<MovieCatalogItem>> = _searchQuery
        .flatMapLatest { query ->
            movieRepository.searchCatalog(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun categorizeCatalog(catalog: List<MovieCatalogItem>): Pair<List<MovieCatalogItem>, List<MovieCatalogItem>> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        // 1. Theater / New Releases (dynamically tagged "NEW" or released in current/previous calendar year, guarding invalid future years)
        val theater = catalog
            .filter {
                val yr = it.releaseYear ?: 0
                val isRecentYear = yr in (currentYear - 1)..(currentYear + 1)
                ("NEW" in it.tags && (yr == 0 || yr <= currentYear + 1)) || isRecentYear
            }
            .sortedWith(
                compareByDescending<MovieCatalogItem> { if ("NEW" in it.tags) 1 else 0 }
                    .thenByDescending { (it.releaseYear ?: 0).coerceAtMost(currentYear + 1) }
                    .thenByDescending { if (it.hasAcousticSync) 1 else 0 }
                    .thenByDescending { it.scareCount }
            )
            .take(16)

        // 2. Popular Home Watch (TMDB popular/trending "HOT", iconic classics, acoustic-sync ready, high scare counts)
        val popularTitlesLower = setOf(
            "the conjuring", "the conjuring 2", "insidious", "sinister", "hereditary",
            "it follows", "[rec]", "a quiet place", "a quiet place part ii", "the ring",
            "lights out", "annabelle: creation", "don't breathe", "the babadook", "oculus",
            "the autopsy of jane doe", "28 days later", "the descent", "paranormal activity",
            "alien", "the shining", "midsommar", "barbarian", "terrifier 2", "smile",
            "scream vi", "get out", "talk to me", "longlegs"
        )
        val theaterIds = theater.map { it.tmdbId }.toSet()
        val homeCandidates = catalog
            .filter { "HOT" in it.tags || it.title.lowercase() in popularTitlesLower || it.scareCount >= 14 }
            .distinctBy { it.tmdbId }
        val nonTheaterCandidates = homeCandidates.filter { it.tmdbId !in theaterIds }
        val homePool = if (nonTheaterCandidates.size >= 16) nonTheaterCandidates else homeCandidates

        val home = homePool
            .sortedByDescending {
                var score = it.scareCount.coerceAtMost(25)
                if (it.title.lowercase() in popularTitlesLower) score += 400
                if ("HOT" in it.tags) score += 200
                if (it.hasAcousticSync) score += 150
                score
            }
            .take(16)

        return Pair(theater, home)
    }

    init {
        // Continuous observation of catalog stream so UI state reactively reflects Room DB updates
        viewModelScope.launch {
            movieRepository.getCatalogStream().collect { catalog ->
                if (catalog.isNotEmpty()) {
                    val (theater, home) = categorizeCatalog(catalog)
                    _uiState.update { current ->
                        current.copy(
                            theaterMovies = theater,
                            popularHomeMovies = home,
                            totalMovieCount = catalog.size
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                movieRepository.initializeCatalogIfNeeded()

                // Await movies from repository flow
                val catalogSnapshot = withTimeoutOrNull(3000L) {
                    movieRepository.getCatalogStream().first { it.isNotEmpty() }
                } ?: emptyList()

                if (catalogSnapshot.isNotEmpty()) {
                    val (theater, home) = categorizeCatalog(catalogSnapshot)
                    _uiState.update {
                        it.copy(
                            theaterMovies = theater,
                            popularHomeMovies = home,
                            totalMovieCount = catalogSnapshot.size
                        )
                    }

                    // Barrier: Await complete image loading into Coil cache for top carousel movies
                    val featuredBatch = (theater + home).distinctBy { it.tmdbId }
                    awaitPrefetchFeatured(featuredBatch)

                    // Background prefetch first 16 catalog movies
                    val nextBatch = catalogSnapshot.take(16).distinctBy { it.tmdbId }
                    prefetchMovies(nextBatch)
                }
            } catch (e: Exception) {
                Log.e("SpookCue", "Catalog initialization notice: ${e.message}", e)
            } finally {
                // Minimum loading display limit: 150ms (0.15s) on device, 0ms in tests
                val minDisplayDuration = if (context != null) 150L else 0L
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = (minDisplayDuration - elapsed).coerceAtLeast(0L)
                if (remaining > 0L) {
                    delay(remaining)
                }

                _uiState.update { it.copy(isInitialLoading = false) }
            }

            syncCatalog(silent = true)
        }
    }

    private suspend fun awaitPrefetchFeatured(items: List<MovieCatalogItem>) {
        val appContext = context ?: return
        withTimeoutOrNull(2000L) {
            coroutineScope {
                items.map { movie ->
                    async(Dispatchers.IO) {
                        val posterPath = movie.posterPath?.takeIf { it.isNotBlank() } ?: return@async
                        val url = "https://image.tmdb.org/t/p/w185$posterPath"
                        val request = ImageRequest.Builder(appContext)
                            .data(url)
                            .crossfade(false)
                            .allowRgb565(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        runCatching {
                            appContext.imageLoader.execute(request)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun onCategorySelected(category: FeaturedCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun prefetchMovies(items: List<MovieCatalogItem>) {
        val appContext = context ?: return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            items.take(8).forEach { movie ->
                val posterPath = movie.posterPath?.takeIf { it.isNotBlank() } ?: return@forEach
                val url = "https://image.tmdb.org/t/p/w185$posterPath"
                val request = ImageRequest.Builder(appContext)
                    .data(url)
                    .crossfade(false)
                    .allowRgb565(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
                appContext.imageLoader.enqueue(request)
            }
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
            val updatedCount = movieRepository.getMovieCount()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    totalMovieCount = if (updatedCount > 0) updatedCount else it.totalMovieCount,
                    error = if (silent) null else result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }
}
