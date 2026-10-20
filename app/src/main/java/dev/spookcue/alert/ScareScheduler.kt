package dev.spookcue.alert

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
    private val movieClock: MovieClock
) {
    sealed class AlertEvent {
        data class Warning10s(val scare: JumpScare) : AlertEvent()
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

                for (scare in activeScares) {
                    val remaining = scare.resolvedTimeMs - movieTime

                    if (remaining in 9500..10500) {
                        val key = "${scare.id}_10s"
                        if (firedEvents.add(key)) {
                            if (scare.type == "major") {
                                hapticManager.vibrateMajorScare10s()
                            } else {
                                hapticManager.vibrateMinorScare()
                            }
                            _alertEvents.emit(AlertEvent.Warning10s(scare))
                        }
                    } else if (remaining in 4500..5500 && scare.type == "major") {
                        val key = "${scare.id}_5s"
                        if (firedEvents.add(key)) {
                            hapticManager.vibrateMajorScare5s()
                            _alertEvents.emit(AlertEvent.Warning5s(scare))
                        }
                    } else if (remaining in 1500..2500 && scare.type == "major") {
                        val key = "${scare.id}_2s"
                        if (firedEvents.add(key)) {
                            hapticManager.vibrateMajorScare2s()
                            _alertEvents.emit(AlertEvent.Warning2s(scare))
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
