package dev.spookcue.data.remote

import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class VersionInfo(
    val v: Long,
    val updated: String = "",
    val movie_count: Int = 0,
    val scare_count: Int = 0
)

interface CdnClient {
    suspend fun fetchVersion(): Result<VersionInfo>
    suspend fun fetchCatalog(): Result<List<MovieCatalogItem>>
    suspend fun fetchMovieDetails(tmdbId: Int): Result<MovieDetails>
}

@Singleton
class CdnClientImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : CdnClient {

    companion object {
        private const val JSDELIVR_BASE = "https://cdn.jsdelivr.net/gh/aarav-fred-mehta/SpookCue@main/data"
        private const val RAW_GITHUB_BASE = "https://raw.githubusercontent.com/aarav-fred-mehta/SpookCue/main/data"
        private const val GITHUB_PAGES_BASE = "https://aarav-fred-mehta.github.io/SpookCue/data"
    }

    override suspend fun fetchVersion(): Result<VersionInfo> = withContext(Dispatchers.IO) {
        fetchWithFallback("version.json") { body ->
            json.decodeFromString<VersionInfo>(body)
        }
    }

    override suspend fun fetchCatalog(): Result<List<MovieCatalogItem>> = withContext(Dispatchers.IO) {
        fetchWithFallback("catalog.json") { body ->
            json.decodeFromString<List<MovieCatalogItem>>(body)
        }
    }

    override suspend fun fetchMovieDetails(tmdbId: Int): Result<MovieDetails> = withContext(Dispatchers.IO) {
        fetchWithFallback("movies/$tmdbId.json") { body ->
            json.decodeFromString<MovieDetails>(body)
        }
    }

    private fun <T> fetchWithFallback(
        path: String,
        parser: (String) -> T
    ): Result<T> {
        val urls = listOf(
            "$JSDELIVR_BASE/$path",
            "$RAW_GITHUB_BASE/$path",
            "$GITHUB_PAGES_BASE/$path"
        )
        var lastException: Throwable = IOException("No CDN endpoints attempted")

        for (url in urls) {
            try {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: throw IOException("Empty response body from $url")
                        return Result.success(parser(body))
                    } else {
                        lastException = IOException("HTTP ${response.code} from $url")
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        return Result.failure(lastException)
    }
}
