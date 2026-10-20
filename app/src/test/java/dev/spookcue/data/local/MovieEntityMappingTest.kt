package dev.spookcue.data.local

import dev.spookcue.data.model.MovieCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieEntityMappingTest {

    @Test
    fun testFromDomainToEntity() {
        val domain = MovieCatalogItem(
            tmdbId = 138843,
            title = "The Conjuring",
            releaseYear = 2013,
            runtimeMinutes = 112,
            scareCount = 12,
            posterPath = "/conjuring.jpg",
            hasSubtitles = true,
            lastSyncedAt = 1700000000000L
        )

        val entity = MovieEntity.fromDomain(domain)

        assertEquals(138843, entity.tmdbId)
        assertEquals("The Conjuring", entity.title)
        assertEquals(2013, entity.releaseYear)
        assertEquals(112, entity.runtimeMinutes)
        assertEquals(12, entity.scareCount)
        assertEquals("/conjuring.jpg", entity.posterPath)
        assertTrue(entity.hasSubtitles)
        assertEquals(1700000000000L, entity.lastSyncedAt)
    }

    @Test
    fun testToDomain() {
        val entity = MovieEntity(
            tmdbId = 58138,
            title = "Insidious",
            releaseYear = 2010,
            runtimeMinutes = 103,
            scareCount = 14,
            posterPath = null,
            hasSubtitles = false,
            lastSyncedAt = null
        )

        val domain = entity.toDomain()

        assertEquals(58138, domain.tmdbId)
        assertEquals("Insidious", domain.title)
        assertEquals(2010, domain.releaseYear)
        assertEquals(103, domain.runtimeMinutes)
        assertEquals(14, domain.scareCount)
        assertNull(domain.posterPath)
        assertEquals(false, domain.hasSubtitles)
        assertNull(domain.lastSyncedAt)
    }

    @Test
    fun testBidirectionalRoundtrip() {
        val original = MovieCatalogItem(
            tmdbId = 82507,
            title = "Sinister",
            releaseYear = 2012,
            runtimeMinutes = 110,
            scareCount = 11,
            posterPath = "/sinister.jpg",
            hasSubtitles = true,
            lastSyncedAt = 1712345678900L
        )

        val entity = MovieEntity.fromDomain(original)
        val roundtrip = entity.toDomain()

        assertEquals(original, roundtrip)
    }
}
