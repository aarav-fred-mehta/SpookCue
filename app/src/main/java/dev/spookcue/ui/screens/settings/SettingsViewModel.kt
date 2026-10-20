package dev.spookcue.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spookcue.data.remote.DeltaSyncManager
import dev.spookcue.data.repository.MovieRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val hapticsEnabled: Boolean = true,
    val pulseEnabled: Boolean = true,
    val localVersion: Long = 0L,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deltaSyncManager: DeltaSyncManager,
    private val movieRepository: MovieRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(localVersion = deltaSyncManager.getLocalVersion())
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun toggleHaptics(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hapticsEnabled = enabled)
    }

    fun togglePulse(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(pulseEnabled = enabled)
    }

    fun syncCatalogNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncMessage = null)
            val result = movieRepository.syncCatalog(force = true)
            val message = if (result.isSuccess) {
                val updated = result.getOrNull() == true
                if (updated) "Catalog updated successfully" else "Catalog is already up to date"
            } else {
                "Sync failed: ${result.exceptionOrNull()?.localizedMessage ?: "Network error"}"
            }
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                localVersion = deltaSyncManager.getLocalVersion(),
                syncMessage = message
            )
        }
    }
}
