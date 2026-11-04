package dev.spookcue.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.data.local.AssetLoader
import dev.spookcue.data.local.MovieDao
import dev.spookcue.data.local.MovieEntity
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.MovieSyncData
import dev.spookcue.data.remote.CdnClient
import dev.spookcue.data.remote.DeltaSyncManager
import dev.spookcue.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface MovieRepository {
    fun getCatalogStream(): Flow<List<MovieCatalogItem>>
    fun searchCatalog(query: String): Flow<List<MovieCatalogItem>>
    suspend fun getMovieDetails(tmdbId: Int): Result<MovieDetails>
    suspend fun getMovieSyncData(tmdbId: Int): Result<MovieSyncData>
    suspend fun syncCatalog(force: Boolean = false): Result<Boolean>
    suspend fun initializeCatalogIfNeeded()
    suspend fun recordMovieSynced(tmdbId: Int, timestamp: Long = System.currentTimeMillis())
    suspend fun getMovieCount(): Int
}

@Singleton
class MovieRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val movieDao: MovieDao,
    private val assetLoader: AssetLoader,
    private val cdnClient: CdnClient,
    private val deltaSyncManager: DeltaSyncManager?,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : MovieRepository {

    internal var storageDir: File? = null
    private val baseFilesDir: File get() = storageDir ?: context?.filesDir ?: File(".")

    override fun getCatalogStream(): Flow<List<MovieCatalogItem>> {
        return movieDao.getAllMovies().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun searchCatalog(query: String): Flow<List<MovieCatalogItem>> {
        val trimmed = query.trim()
        val flow = if (trimmed.isBlank()) {
            movieDao.getAllMovies()
        } else {
            val clean = trimmed.replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) {
                movieDao.getAllMovies()
            } else {
                val matchQuery = words.joinToString(" ") { "$it*" }
                runCatching {
                    movieDao.searchMoviesFts(matchQuery)
                }.getOrElse {
                    movieDao.searchMoviesLike(clean)
                }
            }
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getMovieDetails(tmdbId: Int): Result<MovieDetails> = withContext(ioDispatcher) {
        // 1. Check bundled assets first (assets/movies/{tmdbId}.json)
        val bundled = assetLoader.loadMovieDetails(tmdbId)
        if (bundled != null) {
            return@withContext Result.success(bundled)
        }

        // 2. Check local disk cache (downloaded from CDN for unbundled movies)
        val diskFile = File(baseFilesDir, "movies/$tmdbId.json")
        if (diskFile.exists()) {
            runCatching {
                val text = diskFile.readText()
                json.decodeFromString<MovieDetails>(text)
            }.onSuccess { cached ->
                val dbEntity = movieDao.getMovieById(tmdbId)
                if (dbEntity == null || dbEntity.syncType == cached.syncType) {
                    return@withContext Result.success(cached)
                }
            }
        }

        // 3. Fallback to remote CDN
        val remoteResult = cdnClient.fetchMovieDetails(tmdbId)
        remoteResult.onSuccess { details ->
            runCatching {
                diskFile.parentFile?.mkdirs()
                diskFile.writeText(json.encodeToString(MovieDetails.serializer(), details))
            }
        }
        remoteResult
    }

    override suspend fun getMovieSyncData(tmdbId: Int): Result<MovieSyncData> = withContext(ioDispatcher) {
        // 1. Check bundled assets first (assets/sync/{tmdbId}.json)
        val bundled = assetLoader.loadMovieSyncData(tmdbId)
        if (bundled != null) {
            return@withContext Result.success(bundled)
        }

        // 2. Check local disk cache (downloaded from CDN for unbundled movies)
        val diskFile = File(baseFilesDir, "sync/$tmdbId.json")
        if (diskFile.exists()) {
            runCatching {
                val text = diskFile.readText()
                json.decodeFromString<MovieSyncData>(text)
            }.onSuccess { cached ->
                val dbEntity = movieDao.getMovieById(tmdbId)
                if (dbEntity == null || dbEntity.syncType == cached.type) {
                    return@withContext Result.success(cached)
                }
            }
        }

        // 3. Fallback to remote CDN
        val remoteResult = cdnClient.fetchMovieSyncData(tmdbId)
        remoteResult.onSuccess { syncData ->
            runCatching {
                diskFile.parentFile?.mkdirs()
                diskFile.writeText(json.encodeToString(MovieSyncData.serializer(), syncData))
            }
        }
        remoteResult
    }

    override suspend fun syncCatalog(force: Boolean): Result<Boolean> = withContext(ioDispatcher) {
        deltaSyncManager?.performDeltaSync(force) ?: Result.success(false)
    }

    override suspend fun initializeCatalogIfNeeded(): Unit = withContext(ioDispatcher) {
        val count = movieDao.getMovieCount()
        if (count == 0) {
            val bundledCatalog = assetLoader.loadCatalog()
            if (bundledCatalog.isNotEmpty()) {
                val entities = bundledCatalog.map { MovieEntity.fromDomain(it) }
                movieDao.insertMovies(entities)
            }
        } else {
            // Reconcile and heal bundled movie metadata (posters, sync types) in Room DB
            val bundledCatalog = assetLoader.loadCatalog()
            for (item in bundledCatalog) {
                if (item.posterPath != null) {
                    movieDao.updatePosterPath(item.tmdbId, item.posterPath)
                }
                movieDao.updateSyncType(item.tmdbId, item.syncType)
            }
        }
    }

    override suspend fun recordMovieSynced(tmdbId: Int, timestamp: Long): Unit = withContext(ioDispatcher) {
        movieDao.updateLastSynced(tmdbId, timestamp)
    }

    override suspend fun getMovieCount(): Int = withContext(ioDispatcher) {
        movieDao.getMovieCount()
    }
}
