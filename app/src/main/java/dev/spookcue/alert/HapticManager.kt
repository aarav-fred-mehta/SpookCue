package dev.spookcue.alert

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.data.local.UserPreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: UserPreferencesManager
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun vibrateMinorScare() {
        if (!preferencesManager.isHaptics()) return

        when (preferencesManager.getPattern()) {
            UserPreferencesManager.PATTERN_HEARTBEAT -> {
                val timings = longArrayOf(0, 100, 80, 150)
                val amplitudes = intArrayOf(0, 120, 0, 120)
                playWaveform(timings, amplitudes)
            }
            else -> {
                val timings = longArrayOf(0, 150, 100, 150)
                val amplitudes = intArrayOf(0, 140, 0, 140)
                playWaveform(timings, amplitudes)
            }
        }
    }

    fun vibrateMajorLeadScare() {
        if (!preferencesManager.isHaptics()) return

        when (preferencesManager.getPattern()) {
            UserPreferencesManager.PATTERN_HEARTBEAT -> {
                // Rhythmic double heartbeat thump
                val timings = longArrayOf(0, 180, 120, 320)
                val amplitudes = intArrayOf(0, 200, 0, 200)
                playWaveform(timings, amplitudes)
            }
            else -> {
                val timings = longArrayOf(0, 200, 120, 350)
                val amplitudes = intArrayOf(0, 200, 0, 200)
                playWaveform(timings, amplitudes)
            }
        }
    }

    fun vibrateMajorScare5s() {
        if (!preferencesManager.isHaptics()) return
        val timings = longArrayOf(0, 200, 120, 350)
        val amplitudes = intArrayOf(0, 230, 0, 230)
        playWaveform(timings, amplitudes)
    }

    fun vibrateMajorScare2s() {
        if (!preferencesManager.isHaptics()) return
        val timings = longArrayOf(0, 250, 120, 450)
        val amplitudes = intArrayOf(0, 255, 0, 255)
        playWaveform(timings, amplitudes)
    }

    private fun playWaveform(timings: LongArray, amplitudes: IntArray) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vib.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(timings, -1)
        }
    }

    fun cancel() {
        vibrator?.cancel()
    }
}
