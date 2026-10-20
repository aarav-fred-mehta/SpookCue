package dev.spookcue.ui.screens.home

import dev.spookcue.data.model.JumpScare
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.repository.MovieRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepository: FakeMovieRepository
    private lateinit var viewModel: HomeViewModel

    private val testItem = MovieCatalogItem(
        tmdbId = 138843,
        title = "The Conjuring",
        releaseYear = 2013,
        runtimeMinutes = 112,
        scareCount = 12,
        posterPath = "/conjuring.jpg",
        hasSubtitles = true
    )

    private val testDetails = MovieDetails(
        tmdbId = 138843,
        title = "The Conjuring",
        releaseYear = 2013,
        runtimeMinutes = 112,
        scares = listOf(
            JumpScare(
                id = "scare_01",
                resolvedTimeMs = 1245000,
                type = "major",
                description = "Clap clap in cellar"
            )
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeMovieRepository(
            initialMovies = listOf(testItem),
            detailsMap = mapOf(138843 to testDetails)
        )
        viewModel = HomeViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialCatalogInitialization() {
        assertTrue(fakeRepository.initializeCalled)
        assertTrue(fakeRepository.syncCatalogCalled)
    }

    @Test
    fun testSearchQueryChanged() {
        assertEquals("", viewModel.uiState.value.searchQuery)
        viewModel.onSearchQueryChanged("conjuring")
        assertEquals("conjuring", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun testMovieClickedAndBottomSheetLoaded() = runBlocking {
        viewModel.onMovieClicked(testItem)

        val state = viewModel.uiState.value
        assertEquals(testItem, state.selectedMovie)
        assertNotNull(state.selectedMovieDetails)
        assertEquals("The Conjuring", state.selectedMovieDetails?.title)
        assertFalse(state.isLoadingDetails)
    }

    @Test
    fun testDismissBottomSheet() = runBlocking {
        viewModel.onMovieClicked(testItem)
        assertEquals(testItem, viewModel.uiState.value.selectedMovie)

        viewModel.onDismissBottomSheet()
        assertNull(viewModel.uiState.value.selectedMovie)
        assertNull(viewModel.uiState.value.selectedMovieDetails)
        assertFalse(viewModel.uiState.value.isLoadingDetails)
    }

    @Test
    fun testStartSyncRecordsTimestampAndInvokesNavigation() = runBlocking {
        var navigatedTmdbId: Int? = null
        viewModel.onStartSync(testItem) { tmdbId ->
            navigatedTmdbId = tmdbId
        }

        assertEquals(138843, navigatedTmdbId)
        assertTrue(fakeRepository.syncedTimestamps.containsKey(138843))
        assertNull(viewModel.uiState.value.selectedMovie)
    }

    private class FakeMovieRepository(
        initialMovies: List<MovieCatalogItem>,
        private val detailsMap: Map<Int, MovieDetails>
    ) : MovieRepository {
        var initializeCalled = false
        var syncCatalogCalled = false
        val syncedTimestamps = mutableMapOf<Int, Long>()
        private val moviesFlow = MutableStateFlow(initialMovies)

        override fun getCatalogStream(): Flow<List<MovieCatalogItem>> = moviesFlow

        override fun searchCatalog(query: String): Flow<List<MovieCatalogItem>> {
            return moviesFlow.map { list ->
                if (query.isBlank()) list
                else list.filter { it.title.contains(query, ignoreCase = true) }
            }
        }

        override suspend fun getMovieDetails(tmdbId: Int): Result<MovieDetails> {
            val d = detailsMap[tmdbId]
            return if (d != null) Result.success(d) else Result.failure(Exception("Not found"))
        }

        override suspend fun syncCatalog(force: Boolean): Result<Boolean> {
            syncCatalogCalled = true
            return Result.success(true)
        }

        override suspend fun initializeCatalogIfNeeded() {
            initializeCalled = true
        }

        override suspend fun recordMovieSynced(tmdbId: Int, timestamp: Long) {
            syncedTimestamps[tmdbId] = timestamp
        }
    }
}
