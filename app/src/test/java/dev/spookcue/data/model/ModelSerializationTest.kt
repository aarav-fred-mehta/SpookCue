package dev.spookcue.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testCatalogSerialization() {
        val raw = """
            [
              {
                "tmdb_id": 138843,
                "title": "The Conjuring",
                "release_year": 2013,
                "runtime_minutes": 112,
                "scare_count": 12,
                "poster_path": "/wVYREutTvI2tmxr6ujrHT704wGF.jpg",
                "has_subtitles": true
              }
            ]
        """.trimIndent()

        val items = json.decodeFromString<List<MovieCatalogItem>>(raw)
        assertEquals(1, items.size)
        assertEquals("The Conjuring", items[0].title)
        assertEquals(138843, items[0].tmdbId)
        assertEquals(12, items[0].scareCount)
    }

    @Test
    fun testMovieDetailsSerialization() {
        val raw = """
            {
              "tmdb_id": 138843,
              "title": "The Conjuring",
              "release_year": 2013,
              "runtime_minutes": 112,
              "scares": [
                {
                  "id": "scare_01",
                  "resolved_time_ms": 1245000,
                  "type": "major",
                  "description": "Clap clap in the cellar",
                  "anchor_phonetic": ["KL", "P"],
                  "offset_from_anchor_ms": 4200
                }
              ],
              "phonetic_index": [
                {
                  "s": 1240800,
                  "p": ["KL", "P"]
                }
              ]
            }
        """.trimIndent()

        val movie = json.decodeFromString<MovieDetails>(raw)
        assertEquals(138843, movie.tmdbId)
        assertEquals(1, movie.scares.size)
        assertEquals("major", movie.scares[0].type)
        assertEquals(1, movie.phoneticIndex.size)
    }

    @Test
    fun testCatalogSerializationWithNullReleaseYear() {
        val raw = """
            [
              {
                "tmdb_id": 1107568,
                "title": "Liar Liar",
                "release_year": null,
                "runtime_minutes": 0,
                "scare_count": 3,
                "poster_path": "",
                "has_subtitles": false
              }
            ]
        """.trimIndent()

        val items = json.decodeFromString<List<MovieCatalogItem>>(raw)
        assertEquals(1, items.size)
        assertEquals("Liar Liar", items[0].title)
        assertEquals(1107568, items[0].tmdbId)
        org.junit.Assert.assertNull(items[0].releaseYear)
        assertEquals(3, items[0].scareCount)
    }
}
