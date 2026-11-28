package dev.spookcue.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.spookcue.data.model.MovieCatalogItem

@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey
    val tmdbId: Int,
    val title: String,
    val releaseYear: Int,
    val runtimeMinutes: Int,
    val scareCount: Int,
    val posterPath: String? = null,
    val hasAcousticSync: Boolean = false,
    val syncType: String = "audio_hash",
    val lastSyncedAt: Long? = null,
    val tags: String = ""
) {
    fun toDomain(): MovieCatalogItem = MovieCatalogItem(
        tmdbId = tmdbId,
        title = title,
        releaseYear = releaseYear,
        runtimeMinutes = runtimeMinutes,
        scareCount = scareCount,
        posterPath = posterPath?.takeIf { it.isNotBlank() },
        hasAcousticSync = hasAcousticSync,
        syncType = syncType,
        lastSyncedAt = lastSyncedAt,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    )

    companion object {
        fun fromDomain(item: MovieCatalogItem): MovieEntity = MovieEntity(
            tmdbId = item.tmdbId,
            title = item.title,
            releaseYear = item.releaseYear ?: 0,
            runtimeMinutes = item.runtimeMinutes,
            scareCount = item.scareCount,
            posterPath = item.posterPath?.takeIf { it.isNotBlank() },
            hasAcousticSync = item.hasAcousticSync,
            syncType = item.syncType,
            lastSyncedAt = item.lastSyncedAt,
            tags = item.tags.joinToString(",")
        )
    }
}
