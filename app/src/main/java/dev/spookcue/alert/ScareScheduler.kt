package dev.spookcue.alert

import dev.spookcue.data.local.UserPreferencesManager
import dev.spookcue.data.model.JumpScare
import dev.spookcue.sync.MovieClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScareScheduler @Inject constructor(
    private val hapticManager: HapticManager,
    private val movieClock: MovieClock,
    private val preferencesManager: UserPreferencesManager
) {
    sealed class AlertEvent {
        data class WarningLead(val scare: JumpScare, val leadTimeSeconds: Int) : AlertEvent()
        data class Warning5s(val scare: JumpScare) : AlertEvent()
        data class Warning2s(val scare: JumpScare) : AlertEvent()
        data class ScareNow(val scare: JumpScare) : AlertEvent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var schedulerJob: Job? = null

    private val firedEvents = mutableSetOf<String>()
    private var activeScares: List<JumpScare> = emptyList()

    private val _alertEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents.asSharedFlow()

    fun startScheduling(scares: List<JumpScare>) {
        activeScares = scares.sortedBy { it.resolvedTimeMs }
        firedEvents.clear()

        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (isActive) {
                delay(200)
                if (!movieClock.isRunning) continue

                val movieTime = movieClock.getCurrentMovieTimeMs()
                val majorOnly = preferencesManager.isMajorOnly()
                val leadTimeSec = preferencesManager.getLeadTime()
                val leadTimeMs = leadTimeSec * 1000L

                for (scare in activeScares) {
                    val isMajor = scare.type.equals("major", ignoreCase = true)
                    if (majorOnly && !isMajor) {
                        continue // Skip minor scare when major-only filter is active
                    }

                    val remaining = scare.resolvedTimeMs - movieTime

                    // Dynamic Lead Time Warning (e.g. 5s, 10s, 15s, 20s)
                    if (remaining in (leadTimeMs - 500)..(leadTimeMs + 500)) {
                        val key = "${scare.id}_lead_${leadTimeSec}"
                        if (firedEvents.add(key)) {
                            if (isMajor) {
                                hapticManager.vibrateMajorLeadScare()
                            } else {
                                hapticManager.vibrateMinorScare()
                            }
                            _alertEvents.emit(AlertEvent.WarningLead(scare, leadTimeSec))
                        }
                    } else if (remaining in 4500..5500 && isMajor && leadTimeSec > 5) {
                        val key = "${scare.id}_5s"
                        if (firedEvents.add(key)) {
                            hapticManager.vibrateMajorScare5s()
                            _alertEvents.emit(AlertEvent.Warning5s(scare))
                        }
                    } else if (remaining in 1500..2500 && isMajor && leadTimeSec > 2) {
                        val key = "${scare.id}_2s"
                        if (firedEvents.add(key)) {
                            hapticManager.vibrateMajorScare2s()
                            _alertEvents.emit(AlertEvent.Warning2s(scare))
                        }
                    } else if (remaining in -200..300) {
                        val key = "${scare.id}_now"
                        if (firedEvents.add(key)) {
                            _alertEvents.emit(AlertEvent.ScareNow(scare))
                        }
                    }
                }
            }
        }
    }

    fun stopScheduling() {
        schedulerJob?.cancel()
        schedulerJob = null
        firedEvents.clear()
        activeScares = emptyList()
        hapticManager.cancel()
    }
}
