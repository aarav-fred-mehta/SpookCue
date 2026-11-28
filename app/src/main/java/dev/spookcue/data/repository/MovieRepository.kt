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
        val dbEntity = movieDao.getMovieById(tmdbId)

        // 1. Check local disk cache if fresh against DB catalog metadata
        val diskFile = File(baseFilesDir, "movies/$tmdbId.json")
        val diskDetails = if (diskFile.exists()) {
            runCatching {
                json.decodeFromString<MovieDetails>(diskFile.readText())
            }.getOrNull()
        } else {
            null
        }
        if (diskDetails != null && (dbEntity == null || isDetailsFresh(diskDetails, dbEntity))) {
            return@withContext Result.success(diskDetails)
        }

        // 2. Check bundled assets (assets/movies/{tmdbId}.json) if fresh against DB catalog metadata
        val bundled = assetLoader.loadMovieDetails(tmdbId)
        if (bundled != null && (dbEntity == null || isDetailsFresh(bundled, dbEntity))) {
            return@withContext Result.success(bundled)
        }

        // 3. Fetch updated details from remote CDN
        val remoteResult = cdnClient.fetchMovieDetails(tmdbId)
        remoteResult.onSuccess { details ->
            runCatching {
                diskFile.parentFile?.mkdirs()
                diskFile.writeText(json.encodeToString(MovieDetails.serializer(), details))
            }
        }
        if (remoteResult.isSuccess) {
            return@withContext remoteResult
        }

        // 4. Offline fallback to stale disk cache or bundled asset
        if (diskDetails != null) {
            return@withContext Result.success(diskDetails)
        }
        if (bundled != null) {
            return@withContext Result.success(bundled)
        }
        remoteResult
    }

    private fun isDetailsFresh(details: MovieDetails, dbEntity: MovieEntity): Boolean {
        return dbEntity.syncType == details.syncType &&
            dbEntity.scareCount == details.scares.size &&
            dbEntity.title == details.title
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
        val bundledCatalog = assetLoader.loadCatalog()
        val bundledSyncIds = assetLoader.getBundledSyncTmdbIds()
        val bundledVersion = assetLoader.loadVersion()?.v ?: 0L

        if (bundledCatalog.isNotEmpty()) {
            val entities = bundledCatalog.map { item ->
                val hasSync = item.hasAcousticSync || bundledSyncIds.contains(item.tmdbId)
                MovieEntity.fromDomain(item.copy(hasAcousticSync = hasSync))
            }

            if (count == 0) {
                movieDao.insertMovies(entities)
                if (bundledVersion > 0L) {
                    deltaSyncManager?.syncBundledVersionIfHigher(bundledVersion)
                }
            } else if (bundledVersion > 0L && bundledVersion > (deltaSyncManager?.getLocalVersion() ?: 0L)) {
                // Bundled assets in APK update are newer than local DB: replace catalog while preserving sync history
                movieDao.replaceCatalogPreservingSync(entities)
                deltaSyncManager?.syncBundledVersionIfHigher(bundledVersion)
            } else {
                // Heal all metadata fields (titles, years, tags, posters, sync types) for existing rows in one transaction
                movieDao.reconcileCatalogMetadata(entities)
            }
        }

        // Ensure any bundled sync IDs not yet flagged are marked acoustic-ready
        val existingIds = movieDao.getAllMovieIds().toSet()
        for (tmdbId in bundledSyncIds) {
            if (tmdbId in existingIds) {
                movieDao.updateHasAcousticSync(tmdbId, true)
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
