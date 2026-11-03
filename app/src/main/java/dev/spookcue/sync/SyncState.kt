package dev.spookcue.sync

sealed class SyncState {
    data object Idle : SyncState()

    // Stage 1: Finding Movie Progress
    data class FindingProgress(
        val startedAtMs: Long,
        val candidatesFound: Int = 0
    ) : SyncState()

    // Stage 2: Movie Playing
    data class MoviePlaying(
        val currentMovieTimeMs: Long,
        val confidence: Float,
        val nextScareCountdownMs: Long? = null,
        val nextCheckpointInMs: Long? = null
    ) : SyncState()

    // Stage 3: Movie Paused (detected or manual)
    data class MoviePaused(
        val pausedAtMovieTimeMs: Long,
        val isManual: Boolean = false,
        val listeningDutyActive: Boolean = false
    ) : SyncState()

    data class Error(
        val message: String
    ) : SyncState()
}
