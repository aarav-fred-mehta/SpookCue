package dev.spookcue.audio

import javax.inject.Inject
import javax.inject.Singleton

data class TranscriptionResult(
    val text: String,
    val confidence: Float
)

interface SttEngine {
    suspend fun transcribe(pcmSamples: FloatArray): TranscriptionResult
    fun reset()
}

@Singleton
class SttEngineImpl @Inject constructor() : SttEngine {
    override suspend fun transcribe(pcmSamples: FloatArray): TranscriptionResult {
        return TranscriptionResult(text = "", confidence = 0.0f)
    }

    override fun reset() {
    }
}
