package dev.spookcue.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SpookCueService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Full audio capture, VAD, STT, and alert pipeline will be hooked in Phase 4-7
        return START_NOT_STICKY
    }
}
