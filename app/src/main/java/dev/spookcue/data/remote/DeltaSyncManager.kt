package dev.spookcue.data.remote

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.data.local.MovieDao
import dev.spookcue.data.local.MovieEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeltaSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cdnClient: CdnClient,
    private val movieDao: MovieDao
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("spookcue_sync_prefs", Context.MODE_PRIVATE)
    }

    fun getLocalVersion(): Long = prefs.getLong(KEY_LOCAL_VERSION, 0L)

    private fun setLocalVersion(version: Long) {
        prefs.edit().putLong(KEY_LOCAL_VERSION, version).apply()
    }

    suspend fun performDeltaSync(force: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        val versionResult = cdnClient.fetchVersion()
        if (versionResult.isFailure && !force) {
            return@withContext Result.failure(
                versionResult.exceptionOrNull() ?: Exception("Failed to check remote version")
            )
        }

        val remoteVersion = versionResult.getOrNull()?.v ?: 0L
        val localVersion = getLocalVersion()

        if (!force && remoteVersion <= localVersion && localVersion > 0) {
            return@withContext Result.success(false)
        }

        val catalogResult = cdnClient.fetchCatalog()
        if (catalogResult.isFailure) {
            return@withContext Result.failure(
                catalogResult.exceptionOrNull() ?: Exception("Failed to fetch catalog")
            )
        }

        val remoteItems = catalogResult.getOrThrow()
        val entities = remoteItems.map { MovieEntity.fromDomain(it) }
        movieDao.insertMovies(entities)

        if (remoteVersion > 0) {
            setLocalVersion(remoteVersion)
        }

        Result.success(true)
    }

    companion object {
        private const val KEY_LOCAL_VERSION = "local_catalog_version"
    }
}
