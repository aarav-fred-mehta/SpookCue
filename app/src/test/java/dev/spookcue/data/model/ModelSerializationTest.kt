package dev.spookcue.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                "has_subtitles": true,
                "sync_type": "subtitles"
              }
            ]
        """.trimIndent()

        val items = json.decodeFromString<List<MovieCatalogItem>>(raw)
        assertEquals(1, items.size)
        assertEquals("The Conjuring", items[0].title)
        assertEquals(138843, items[0].tmdbId)
        assertEquals(12, items[0].scareCount)
        assertEquals("subtitles", items[0].syncType)
    }

    @Test
    fun testMovieDetailsDecoupledSerialization() {
        val raw = """
            {
              "tmdb_id": 138843,
              "title": "The Conjuring",
              "release_year": 2013,
              "runtime_minutes": 112,
              "sync_type": "subtitles",
              "scares": [
                {
                  "id": "scare_01",
                  "resolved_time_ms": 1245000,
                  "type": "major",
                  "description": "Clap clap in the cellar"
                }
              ]
            }
        """.trimIndent()

        val movie = json.decodeFromString<MovieDetails>(raw)
        assertEquals(138843, movie.tmdbId)
        assertEquals(1, movie.scares.size)
        assertEquals("major", movie.scares[0].type)
        assertEquals("subtitles", movie.syncType)
    }

    @Test
    fun testMovieSyncDataSubtitlesSerialization() {
        val raw = """
            {
              "tmdb_id": 138843,
              "type": "subtitles",
              "phonetic_index": [
                {
                  "s": 1240800,
                  "p": ["KL", "P"]
                }
              ]
            }
        """.trimIndent()

        val syncData = json.decodeFromString<MovieSyncData>(raw)
        assertEquals(138843, syncData.tmdbId)
        assertEquals("subtitles", syncData.type)
        assertEquals(1, syncData.phoneticIndex.size)
        assertEquals(1240800L, syncData.phoneticIndex[0].startMs)
    }

    @Test
    fun testMovieSyncDataAudioHashesSerialization() {
        val raw = """
            {
              "tmdb_id": 447332,
              "type": "audio_hash",
              "audio_hashes": [
                { "h": 21849182, "t": 12500 },
                { "h": 8392104, "t": 14200 }
              ]
            }
        """.trimIndent()

        val syncData = json.decodeFromString<MovieSyncData>(raw)
        assertEquals(447332, syncData.tmdbId)
        assertEquals("audio_hash", syncData.type)
        assertEquals(2, syncData.audioHashes.size)
        assertEquals(21849182, syncData.audioHashes[0].h)
        assertEquals(12500L, syncData.audioHashes[0].t)
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
        assertNull(items[0].releaseYear)
        assertEquals(3, items[0].scareCount)
    }
}
