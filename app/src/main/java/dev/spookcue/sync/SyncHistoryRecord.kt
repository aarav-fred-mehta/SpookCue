package dev.spookcue.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncHistoryRecord(
    val id: Long = System.currentTimeMillis(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: String, // "LOCK", "DRIFT", "RESUME", "PAUSE", "RESYNC", "CANDIDATE"
    val syncMode: String,  // "subtitles" or "audio_hash"
    val movieTimeMs: Long,
    val confidence: Float = 1.0f,
    val details: String = ""
)
