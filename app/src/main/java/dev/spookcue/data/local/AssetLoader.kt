package dev.spookcue.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

interface AssetLoader {
    fun loadCatalog(): List<MovieCatalogItem>
    fun loadMovieDetails(tmdbId: Int): MovieDetails?
}

@Singleton
class AssetLoaderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : AssetLoader {

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
}
