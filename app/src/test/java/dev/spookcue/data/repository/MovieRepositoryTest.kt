package dev.spookcue.data.repository

import dev.spookcue.data.local.AssetLoader
import dev.spookcue.data.local.MovieDao
import dev.spookcue.data.local.MovieEntity
import dev.spookcue.data.model.JumpScare
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.PhoneticEntry
import dev.spookcue.data.remote.CdnClient
import dev.spookcue.data.remote.DeltaSyncManager
import dev.spookcue.data.remote.VersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MovieRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var fakeMovieDao: FakeMovieDao
    private lateinit var fakeAssetLoader: FakeAssetLoader
    private lateinit var fakeCdnClient: FakeCdnClient
    private lateinit var repository: MovieRepositoryImpl

    private val testCatalog = listOf(
        MovieCatalogItem(
            tmdbId = 138843,
            title = "The Conjuring",
            releaseYear = 2013,
            runtimeMinutes = 112,
            scareCount = 12,
            posterPath = "/conjuring.jpg",
            hasSubtitles = true
        ),
        MovieCatalogItem(
            tmdbId = 58138,
            title = "Insidious",
            releaseYear = 2010,
            runtimeMinutes = 103,
            scareCount = 14,
            posterPath = "/insidious.jpg",
            hasSubtitles = true
        )
    )

    private val testConjuringDetails = MovieDetails(
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
        ),
        phoneticIndex = listOf(
            PhoneticEntry(startMs = 1240800L, phonetics = listOf("KL", "P"))
        )
    )

    @Before
    fun setup() {
        fakeMovieDao = FakeMovieDao()
        fakeAssetLoader = FakeAssetLoader(
            catalog = testCatalog,
            detailsMap = mapOf(138843 to testConjuringDetails)
        )
        fakeCdnClient = FakeCdnClient()

        repository = MovieRepositoryImpl(
            context = null,
            movieDao = fakeMovieDao,
            assetLoader = fakeAssetLoader,
            cdnClient = fakeCdnClient,
            deltaSyncManager = null,
            json = json,
            ioDispatcher = Dispatchers.Unconfined
        )
        repository.storageDir = tempFolder.root
    }

    @Test
    fun testInitializeCatalogWhenEmpty() = runBlocking {
        assertEquals(0, fakeMovieDao.getMovieCount())

        repository.initializeCatalogIfNeeded()

        assertEquals(2, fakeMovieDao.getMovieCount())
        val stream = repository.getCatalogStream().first()
        assertEquals(2, stream.size)
        assertEquals("The Conjuring", stream[0].title)
    }

    @Test
    fun testInitializeCatalogDoesNotOverwriteWhenNotEmpty() = runBlocking {
        fakeMovieDao.insertMovies(
            listOf(
                MovieEntity(
                    tmdbId = 999,
                    title = "Existing Movie",
                    releaseYear = 2020,
                    runtimeMinutes = 90,
                    scareCount = 1
                )
            )
        )

        repository.initializeCatalogIfNeeded()

        assertEquals(1, fakeMovieDao.getMovieCount())
        assertEquals("Existing Movie", fakeMovieDao.getAllMovies().first()[0].title)
    }

    @Test
    fun testSearchCatalog() = runBlocking {
        fakeMovieDao.insertMovies(testCatalog.map { MovieEntity.fromDomain(it) })

        val all = repository.searchCatalog("").first()
        assertEquals(2, all.size)

        val searched = repository.searchCatalog("conjuring").first()
        assertEquals(1, searched.size)
        assertEquals("The Conjuring", searched[0].title)

        val none = repository.searchCatalog("NonExistent").first()
        assertEquals(0, none.size)
    }

    @Test
    fun testGetMovieDetailsFromAssetLoader() = runBlocking {
        val result = repository.getMovieDetails(138843)
        assertTrue(result.isSuccess)
        val details = result.getOrNull()
        assertNotNull(details)
        assertEquals(138843, details?.tmdbId)
        assertEquals(1, details?.scares?.size)
    }

    @Test
    fun testGetMovieDetailsFromDiskCache() = runBlocking {
        val moviesDir = File(tempFolder.root, "movies").apply { mkdirs() }
        val cachedDetails = MovieDetails(
            tmdbId = 777,
            title = "Cached Movie",
            releaseYear = 2024,
            runtimeMinutes = 100,
            scares = emptyList()
        )
        File(moviesDir, "777.json").writeText(json.encodeToString(MovieDetails.serializer(), cachedDetails))

        val result = repository.getMovieDetails(777)
        assertTrue(result.isSuccess)
        assertEquals("Cached Movie", result.getOrNull()?.title)
    }

    @Test
    fun testGetMovieDetailsFallbackToCdnAndSavesToDisk() = runBlocking {
        val remoteDetails = MovieDetails(
            tmdbId = 888,
            title = "Remote Movie",
            releaseYear = 2025,
            runtimeMinutes = 95,
            scares = emptyList()
        )
        fakeCdnClient.movieDetailsMap[888] = remoteDetails

        val result = repository.getMovieDetails(888)
        assertTrue(result.isSuccess)
        assertEquals("Remote Movie", result.getOrNull()?.title)

        // Verify it was written to disk cache
        val diskFile = File(tempFolder.root, "movies/888.json")
        assertTrue(diskFile.exists())
        val decoded = json.decodeFromString<MovieDetails>(diskFile.readText())
        assertEquals(888, decoded.tmdbId)
    }

    @Test
    fun testRecordMovieSynced() = runBlocking {
        fakeMovieDao.insertMovies(testCatalog.map { MovieEntity.fromDomain(it) })

        val syncTime = 1700000000000L
        repository.recordMovieSynced(138843, syncTime)

        val updated = fakeMovieDao.getMovieById(138843)
        assertEquals(syncTime, updated?.lastSyncedAt)
    }

    // Fakes for testing
    private class FakeMovieDao : MovieDao {
        private val moviesFlow = MutableStateFlow<List<MovieEntity>>(emptyList())

        override fun getAllMovies(): Flow<List<MovieEntity>> = moviesFlow

        override fun searchMoviesFts(matchQuery: String): Flow<List<MovieEntity>> {
            val term = matchQuery.replace("*", "").lowercase()
            return moviesFlow.map { list ->
                list.filter { it.title.lowercase().contains(term) }
            }
        }

        override fun searchMoviesLike(query: String): Flow<List<MovieEntity>> {
            val term = query.lowercase()
            return moviesFlow.map { list ->
                list.filter { it.title.lowercase().contains(term) }
            }
        }

        override suspend fun getMovieById(tmdbId: Int): MovieEntity? {
            return moviesFlow.value.find { it.tmdbId == tmdbId }
        }

        override suspend fun getMovieCount(): Int = moviesFlow.value.size

        override suspend fun insertMovies(movies: List<MovieEntity>) {
            val current = moviesFlow.value.toMutableList()
            for (movie in movies) {
                current.removeAll { it.tmdbId == movie.tmdbId }
                current.add(movie)
            }
            moviesFlow.value = current
        }

        override suspend fun updateLastSynced(tmdbId: Int, timestamp: Long) {
            val current = moviesFlow.value.toMutableList()
            val index = current.indexOfFirst { it.tmdbId == tmdbId }
            if (index != -1) {
                current[index] = current[index].copy(lastSyncedAt = timestamp)
                moviesFlow.value = current
            }
        }

        override suspend fun deleteAllMovies() {
            moviesFlow.value = emptyList()
        }
    }

    private class FakeAssetLoader(
        private val catalog: List<MovieCatalogItem>,
        private val detailsMap: Map<Int, MovieDetails>
    ) : AssetLoader {
        override fun loadCatalog(): List<MovieCatalogItem> = catalog
        override fun loadMovieDetails(tmdbId: Int): MovieDetails? = detailsMap[tmdbId]
    }

    private class FakeCdnClient : CdnClient {
        val movieDetailsMap = mutableMapOf<Int, MovieDetails>()

        override suspend fun fetchVersion(): Result<VersionInfo> = Result.success(VersionInfo(1L))
        override suspend fun fetchCatalog(): Result<List<MovieCatalogItem>> = Result.success(emptyList())
        override suspend fun fetchMovieDetails(tmdbId: Int): Result<MovieDetails> {
            val details = movieDetailsMap[tmdbId]
            return if (details != null) {
                Result.success(details)
            } else {
                Result.failure(Exception("Movie not found on CDN"))
            }
        }
    }
}
