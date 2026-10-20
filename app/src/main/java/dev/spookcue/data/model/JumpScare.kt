package dev.spookcue.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JumpScare(
    val id: String,
    @SerialName("resolved_time_ms") val resolvedTimeMs: Long,
    val type: String, // "minor" or "major"
    val description: String = "",
    @SerialName("anchor_phonetic") val anchorPhonetic: List<String>? = null,
    @SerialName("offset_from_anchor_ms") val offsetFromAnchorMs: Long? = null
)
