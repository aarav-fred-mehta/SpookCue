package dev.spookcue.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MovieCatalogItem(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("runtime_minutes") val runtimeMinutes: Int = 0,
    @SerialName("scare_count") val scareCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("has_subtitles") val hasSubtitles: Boolean = true,
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class MovieDetails(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("runtime_minutes") val runtimeMinutes: Int = 0,
    val scares: List<JumpScare> = emptyList(),
    @SerialName("phonetic_index") val phoneticIndex: List<PhoneticEntry> = emptyList()
)
