package dev.spookcue.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hapticsEnabled = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, true))
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _pulseEnabled = MutableStateFlow(prefs.getBoolean(KEY_PULSE, true))
    val pulseEnabled: StateFlow<Boolean> = _pulseEnabled.asStateFlow()

    private val _majorScaresOnly = MutableStateFlow(prefs.getBoolean(KEY_MAJOR_ONLY, false))
    val majorScaresOnly: StateFlow<Boolean> = _majorScaresOnly.asStateFlow()

    private val _alertLeadTimeSeconds = MutableStateFlow(prefs.getInt(KEY_LEAD_TIME, 10))
    val alertLeadTimeSeconds: StateFlow<Int> = _alertLeadTimeSeconds.asStateFlow()

    private val _hapticPattern = MutableStateFlow(prefs.getString(KEY_HAPTIC_PATTERN, PATTERN_HEARTBEAT) ?: PATTERN_HEARTBEAT)
    val hapticPattern: StateFlow<String> = _hapticPattern.asStateFlow()

    private val _audioDebugEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUDIO_DEBUG, false))
    val audioDebugEnabled: StateFlow<Boolean> = _audioDebugEnabled.asStateFlow()

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
        _hapticsEnabled.value = enabled
    }

    fun setPulseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PULSE, enabled).apply()
        _pulseEnabled.value = enabled
    }

    fun setMajorScaresOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MAJOR_ONLY, enabled).apply()
        _majorScaresOnly.value = enabled
    }

    fun setAlertLeadTimeSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_LEAD_TIME, seconds).apply()
        _alertLeadTimeSeconds.value = seconds
    }

    fun setHapticPattern(pattern: String) {
        prefs.edit().putString(KEY_HAPTIC_PATTERN, pattern).apply()
        _hapticPattern.value = pattern
    }

    fun setAudioDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO_DEBUG, enabled).apply()
        _audioDebugEnabled.value = enabled
    }

    fun isMajorOnly(): Boolean = _majorScaresOnly.value
    fun getLeadTime(): Int = _alertLeadTimeSeconds.value
    fun getPattern(): String = _hapticPattern.value
    fun isHaptics(): Boolean = _hapticsEnabled.value
    fun isAudioDebug(): Boolean = _audioDebugEnabled.value

    companion object {
        private const val PREFS_NAME = "spookcue_user_prefs"
        private const val KEY_HAPTICS = "key_haptics"
        private const val KEY_PULSE = "key_pulse"
        private const val KEY_MAJOR_ONLY = "key_major_only"
        private const val KEY_LEAD_TIME = "key_lead_time"
        private const val KEY_HAPTIC_PATTERN = "key_haptic_pattern"
        private const val KEY_AUDIO_DEBUG = "key_audio_debug"

        const val PATTERN_HEARTBEAT = "Heartbeat"
        const val PATTERN_STANDARD = "Standard"
    }
}
