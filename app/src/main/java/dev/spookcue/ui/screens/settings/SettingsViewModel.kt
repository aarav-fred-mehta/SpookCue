package dev.spookcue.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spookcue.data.local.UserPreferencesManager
import dev.spookcue.data.remote.DeltaSyncManager
import dev.spookcue.data.repository.MovieRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val hapticsEnabled: Boolean = true,
    val pulseEnabled: Boolean = true,
    val majorScaresOnly: Boolean = false,
    val alertLeadTimeSeconds: Int = 10,
    val hapticPattern: String = UserPreferencesManager.PATTERN_HEARTBEAT,
    val audioDebugEnabled: Boolean = false,
    val localVersion: Long = 0L,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deltaSyncManager: DeltaSyncManager,
    private val movieRepository: MovieRepository,
    private val preferencesManager: UserPreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            hapticsEnabled = preferencesManager.isHaptics(),
            pulseEnabled = preferencesManager.pulseEnabled.value,
            majorScaresOnly = preferencesManager.isMajorOnly(),
            alertLeadTimeSeconds = preferencesManager.getLeadTime(),
            hapticPattern = preferencesManager.getPattern(),
            audioDebugEnabled = preferencesManager.isAudioDebug(),
            localVersion = deltaSyncManager.getLocalVersion()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun toggleHaptics(enabled: Boolean) {
        preferencesManager.setHapticsEnabled(enabled)
        _uiState.update { it.copy(hapticsEnabled = enabled) }
    }

    fun toggleAudioDebug(enabled: Boolean) {
        preferencesManager.setAudioDebugEnabled(enabled)
        _uiState.update { it.copy(audioDebugEnabled = enabled) }
    }

    fun togglePulse(enabled: Boolean) {
        preferencesManager.setPulseEnabled(enabled)
        _uiState.update { it.copy(pulseEnabled = enabled) }
    }

    fun toggleMajorScaresOnly(enabled: Boolean) {
        preferencesManager.setMajorScaresOnly(enabled)
        _uiState.update { it.copy(majorScaresOnly = enabled) }
    }

    fun setAlertLeadTime(seconds: Int) {
        preferencesManager.setAlertLeadTimeSeconds(seconds)
        _uiState.update { it.copy(alertLeadTimeSeconds = seconds) }
    }

    fun setHapticPattern(pattern: String) {
        preferencesManager.setHapticPattern(pattern)
        _uiState.update { it.copy(hapticPattern = pattern) }
    }

    fun syncCatalogNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = null) }
            val result = movieRepository.syncCatalog(force = true)
            val message = if (result.isSuccess) {
                val updated = result.getOrNull() == true
                if (updated) "Catalog updated successfully" else "Catalog is already up to date"
            } else {
                "Sync failed: ${result.exceptionOrNull()?.localizedMessage ?: "Network error"}"
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    localVersion = deltaSyncManager.getLocalVersion(),
                    syncMessage = message
                )
            }
        }
    }
}
