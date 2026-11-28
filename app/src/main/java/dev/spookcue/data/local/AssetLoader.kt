package dev.spookcue.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.MovieSyncData
import dev.spookcue.data.remote.VersionInfo
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

interface AssetLoader {
    fun loadCatalog(): List<MovieCatalogItem>
    fun loadMovieDetails(tmdbId: Int): MovieDetails?
    fun loadMovieSyncData(tmdbId: Int): MovieSyncData?
    fun getBundledSyncTmdbIds(): Set<Int>
    fun loadVersion(): VersionInfo? = null
}

@Singleton
class AssetLoaderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : AssetLoader {

    private val bundledSyncIds: Set<Int> by lazy {
        runCatching {
            context.assets.list("sync")?.mapNotNull { filename ->
                filename.removeSuffix(".json").toIntOrNull()
            }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    override fun getBundledSyncTmdbIds(): Set<Int> = bundledSyncIds

    override fun loadCatalog(): List<MovieCatalogItem> {
        return runCatching {
            context.assets.open("catalog.json").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    json.decodeFromString<List<MovieCatalogItem>>(reader.readText())
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun loadMovieDetails(tmdbId: Int): MovieDetails? {
        return runCatching {
            context.assets.open("movies/$tmdbId.json").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    json.decodeFromString<MovieDetails>(reader.readText())
                }
            }
        }.getOrNull()
    }

    override fun loadMovieSyncData(tmdbId: Int): MovieSyncData? {
        return runCatching {
            context.assets.open("sync/$tmdbId.json").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    json.decodeFromString<MovieSyncData>(reader.readText())
                }
            }
        }.getOrNull()
    }

    override fun loadVersion(): VersionInfo? {
        return runCatching {
            context.assets.open("version.json").use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    json.decodeFromString<VersionInfo>(reader.readText())
                }
            }
        }.getOrNull()
    }
}
