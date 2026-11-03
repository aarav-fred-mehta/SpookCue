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
    @SerialName("sync_type") val syncType: String = "subtitles",
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class MovieDetails(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("runtime_minutes") val runtimeMinutes: Int = 0,
    @SerialName("sync_type") val syncType: String = "subtitles",
    val scares: List<JumpScare> = emptyList()
)

@Serializable
data class MovieSyncData(
    @SerialName("tmdb_id") val tmdbId: Int,
    val type: String = "subtitles", // "subtitles" or "audio_hash"
    @SerialName("phonetic_index") val phoneticIndex: List<PhoneticEntry> = emptyList(),
    @SerialName("audio_hashes") val audioHashes: List<AudioHashEntry> = emptyList()
)

@Serializable
data class AudioHashEntry(
    val h: Int, // 32-bit landmark peak hash
    val t: Long // Timestamp in milliseconds
)
