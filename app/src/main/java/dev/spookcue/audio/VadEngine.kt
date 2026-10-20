package dev.spookcue.audio

import javax.inject.Inject
import javax.inject.Singleton

data class SpeechSegment(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float
)

interface VadEngine {
    fun processFrame(samples: ShortArray): Boolean
    fun reset()
}

@Singleton
class VadEngineImpl @Inject constructor() : VadEngine {
    private var isSpeechActive = false

    override fun processFrame(samples: ShortArray): Boolean {
        return isSpeechActive
    }

    override fun reset() {
        isSpeechActive = false
    }
}
