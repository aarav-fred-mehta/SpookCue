package dev.spookcue.util

import java.util.Locale

fun Long.formatTimeMs(): String {
    if (this < 0) return "00:00"
    val totalSeconds = this / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun Long.formatCountdownMs(): String {
    if (this <= 0) return "0s"
    val seconds = (this + 999) / 1000
    return "${seconds}s"
}

fun formatLastSynced(timestampMs: Long?): String {
    if (timestampMs == null || timestampMs <= 0L) return "Never synced"
    val diffMs = System.currentTimeMillis() - timestampMs
    if (diffMs < 60_000L) return "Just now"
    val minutes = diffMs / 60_000L
    if (minutes < 60L) return "$minutes min ago"
    val hours = minutes / 60L
    if (hours < 24L) return "$hours hr ago"
    val days = hours / 24L
    if (days < 7L) return "$days day${if (days > 1) "s" else ""} ago"
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy", Locale.US)
    return sdf.format(java.util.Date(timestampMs))
}
