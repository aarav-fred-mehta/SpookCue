package dev.spookcue.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PhoneticEntry(
    @SerialName("s") val startMs: Long,
    @SerialName("p") val phonetics: List<String>
)
