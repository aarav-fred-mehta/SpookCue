package dev.spookcue.di

import dev.spookcue.data.model.JumpScare
import dev.spookcue.data.model.MovieCatalogItem
import dev.spookcue.data.model.MovieDetails
import dev.spookcue.data.model.PhoneticEntry
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModuleTest {

    @Test
    fun testProvideJsonIgnoresUnknownKeysAndLenient() {
        val json = AppModule.provideJson()

        val jsonWithExtraFields = """
            {
                "tmdb_id": 138843,
                "title": "The Conjuring",
                "release_year": 2013,
                "runtime_minutes": 112,
                "scare_count": 12,
                "poster_path": "/conjuring.jpg",
                "has_subtitles": true,
                "extra_field_from_future_version": "ignored_value",
                "another_unused_field": 12345
            }
        """.trimIndent()

        val item = json.decodeFromString<MovieCatalogItem>(jsonWithExtraFields)
        assertEquals(138843, item.tmdbId)
        assertEquals("The Conjuring", item.title)
    }

    @Test
    fun testProvideJsonRoundtripFullMovieDetails() {
        val json = AppModule.provideJson()

        val original = MovieDetails(
            tmdbId = 138843,
            title = "The Conjuring",
            releaseYear = 2013,
            runtimeMinutes = 112,
            scares = listOf(
                JumpScare(
                    id = "scare_01",
                    resolvedTimeMs = 1245000L,
                    type = "major",
                    description = "Clap clap in the cellar",
                    anchorPhonetic = listOf("KL", "P"),
                    offsetFromAnchorMs = 4200L
                )
            ),
            phoneticIndex = listOf(
                PhoneticEntry(startMs = 1240800L, phonetics = listOf("KL", "P"))
            )
        )

        val serialized = json.encodeToString(MovieDetails.serializer(), original)
        val deserialized = json.decodeFromString<MovieDetails>(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun testProvideOkHttpClientConfiguration() {
        val client = AppModule.provideOkHttpClient()

        assertEquals(5000, client.connectTimeoutMillis)
        assertEquals(10000, client.readTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
    }

    @Test
    fun testProvideDispatchers() {
        assertEquals(Dispatchers.IO, AppModule.provideIoDispatcher())
        assertNotNull(AppModule.provideMainDispatcher())
    }
}
