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
    val hasSubtitles: Boolean = true,
    val lastSyncedAt: Long? = null
) {
    fun toDomain(): MovieCatalogItem = MovieCatalogItem(
        tmdbId = tmdbId,
        title = title,
        releaseYear = releaseYear,
        runtimeMinutes = runtimeMinutes,
        scareCount = scareCount,
        posterPath = posterPath,
        hasSubtitles = hasSubtitles,
        lastSyncedAt = lastSyncedAt
    )

    companion object {
        fun fromDomain(item: MovieCatalogItem): MovieEntity = MovieEntity(
            tmdbId = item.tmdbId,
            title = item.title,
            releaseYear = item.releaseYear,
            runtimeMinutes = item.runtimeMinutes,
            scareCount = item.scareCount,
            posterPath = item.posterPath,
            hasSubtitles = item.hasSubtitles,
            lastSyncedAt = item.lastSyncedAt
        )
    }
}
