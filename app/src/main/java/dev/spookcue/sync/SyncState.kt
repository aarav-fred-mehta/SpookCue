package dev.spookcue.sync

sealed class SyncState {
    data object Idle : SyncState()

    data class Acquiring(
        val startedAtMs: Long,
        val candidateMatches: Int = 0
    ) : SyncState()

    data class Locked(
        val currentMovieTimeMs: Long,
        val confidence: Float,
        val nextScareCountdownMs: Long? = null
    ) : SyncState()

    data class Verifying(
        val currentMovieTimeMs: Long,
        val driftMs: Long = 0L
    ) : SyncState()

    data class Error(
        val message: String
    ) : SyncState()
}
