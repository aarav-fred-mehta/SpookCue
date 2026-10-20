package dev.spookcue.alert

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.MainActivity
import dev.spookcue.R
import dev.spookcue.SpookCueApp
import dev.spookcue.service.SpookCueService
import dev.spookcue.sync.SyncState
import dev.spookcue.util.formatCountdownMs
import dev.spookcue.util.formatTimeMs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_OFFSET_MINUS_5S = "dev.spookcue.action.OFFSET_MINUS_5S"
        const val ACTION_OFFSET_PLUS_5S = "dev.spookcue.action.OFFSET_PLUS_5S"
        const val ACTION_FORCE_RESYNC = "dev.spookcue.action.FORCE_RESYNC"
        const val ACTION_STOP_SERVICE = "dev.spookcue.action.STOP_SERVICE"
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    fun buildNotification(
        movieTitle: String,
        state: SyncState
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val minus5PendingIntent = createServicePendingIntent(ACTION_OFFSET_MINUS_5S, 1)
        val plus5PendingIntent = createServicePendingIntent(ACTION_OFFSET_PLUS_5S, 2)
        val resyncPendingIntent = createServicePendingIntent(ACTION_FORCE_RESYNC, 3)
        val stopPendingIntent = createServicePendingIntent(ACTION_STOP_SERVICE, 4)

        val contentText = when (state) {
            is SyncState.Idle -> "Idle"
            is SyncState.Acquiring -> "Listening... acquiring sync lock"
            is SyncState.Locked -> {
                val timeStr = state.currentMovieTimeMs.formatTimeMs()
                val scareStr = state.nextScareCountdownMs?.let { " • Next scare in ${it.formatCountdownMs()}" } ?: ""
                "Synced at $timeStr$scareStr"
            }
            is SyncState.Verifying -> "Verifying audio sync..."
            is SyncState.Error -> "Error: ${state.message}"
        }

        return NotificationCompat.Builder(context, SpookCueApp.CHANNEL_ID)
            .setContentTitle("SpookCue: $movieTitle")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "-5s", minus5PendingIntent)
            .addAction(0, "+5s", plus5PendingIntent)
            .addAction(0, "Resync", resyncPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SpookCueService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun updateNotification(movieTitle: String, state: SyncState) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(movieTitle, state))
        } catch (_: SecurityException) {
        }
    }
}
