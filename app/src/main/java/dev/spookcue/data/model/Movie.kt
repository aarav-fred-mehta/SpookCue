package dev.spookcue.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MovieCatalogItem(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("release_year") val releaseYear: Int,
    @SerialName("runtime_minutes") val runtimeMinutes: Int,
    @SerialName("scare_count") val scareCount: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("has_subtitles") val hasSubtitles: Boolean = true,
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null
)

@Serializable
data class MovieDetails(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("release_year") val releaseYear: Int,
    @SerialName("runtime_minutes") val runtimeMinutes: Int,
    val scares: List<JumpScare> = emptyList(),
    @SerialName("phonetic_index") val phoneticIndex: List<PhoneticEntry> = emptyList()
)
