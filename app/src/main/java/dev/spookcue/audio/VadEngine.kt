package dev.spookcue.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface VadEngine {
    val speechDetected: StateFlow<Boolean>
    fun processFrame(samples: ShortArray): List<FloatArray>
    fun isSpeechActive(): Boolean
    fun flush(): List<FloatArray>
    fun reset()
}

@Singleton
class VadEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VadEngine {

    companion object {
        private const val TAG = "VadEngine"
        private const val MAX_SPEECH_BURST_SAMPLES = 16000 * 7 // 7-second chunking ceiling
    }

    private var vad: Vad? = null
    private val lock = Any()
    private var continuousSpeechSamples = 0L

    private val _speechDetected = MutableStateFlow(false)
    override val speechDetected: StateFlow<Boolean> = _speechDetected.asStateFlow()

    init {
        initVad()
    }

    private fun initVad() {
        // 1. Try Silero VAD v5 (advanced noise & background music rejection)
        runCatching {
            val sileroV5Config = SileroVadModelConfig(
                model = "models/silero_vad_v5.onnx",
                threshold = 0.50f, // Balanced standard threshold: rejects ambient music/noise, catches clear voice
                minSilenceDuration = 0.40f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 7.0f
            )
            val config = VadModelConfig(
                sileroVadModelConfig = sileroV5Config,
                tenVadModelConfig = TenVadModelConfig(),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            vad = Vad(context.assets, config)
            AppLogger.i(TAG, "Silero VAD v5 initialized successfully from assets (threshold=0.50, window=512)")
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to initialize Silero VAD v5, falling back to Silero VAD v4", e)
            runCatching {
                val sileroV4Config = SileroVadModelConfig(
                    model = "models/silero_vad.onnx",
                    threshold = 0.50f,
                    minSilenceDuration = 0.40f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 7.0f
                )
                val config = VadModelConfig(
                    sileroVadModelConfig = sileroV4Config,
                    tenVadModelConfig = TenVadModelConfig(),
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                vad = Vad(context.assets, config)
                AppLogger.i(TAG, "Silero VAD v4 initialized successfully from assets (threshold=0.50)")
            }.onFailure { err ->
                AppLogger.e(TAG, "Failed to initialize VAD from assets", err)
            }
        }
    }

    override fun processFrame(samples: ShortArray): List<FloatArray> = synchronized(lock) {
        val currentVad = vad ?: return emptyList()
        if (samples.isEmpty()) return emptyList()

        val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
        currentVad.acceptWaveform(floatSamples)

        val isActive = currentVad.isSpeechDetected()
        _speechDetected.value = isActive

        if (isActive) {
            continuousSpeechSamples += samples.size
            if (continuousSpeechSamples >= MAX_SPEECH_BURST_SAMPLES) {
                AppLogger.i(TAG, "Continuous speech reached 7s ceiling without pause, forcing segment flush")
                currentVad.flush()
                continuousSpeechSamples = 0L
            }
        } else {
            continuousSpeechSamples = 0L
        }

        val completedSegments = mutableListOf<FloatArray>()
        while (!currentVad.empty()) {
            val segment = currentVad.front()
            val durationSec = segment.samples.size / 16000.0f
            AppLogger.i(TAG, "VAD speech segment detected: ${segment.samples.size} samples (${"%.2f".format(durationSec)}s)")
            completedSegments.add(segment.samples)
            currentVad.pop()
        }
        return completedSegments
    }

    override fun isSpeechActive(): Boolean = synchronized(lock) {
        vad?.isSpeechDetected() ?: false
    }

    override fun flush(): List<FloatArray> = synchronized(lock) {
        val currentVad = vad ?: return emptyList()
        currentVad.flush()
        _speechDetected.value = false
        continuousSpeechSamples = 0L
        val segments = mutableListOf<FloatArray>()
        while (!currentVad.empty()) {
            val segment = currentVad.front()
            AppLogger.i(TAG, "VAD flushed speech segment: ${segment.samples.size} samples")
            segments.add(segment.samples)
            currentVad.pop()
        }
        return segments
    }

    override fun reset() {
        synchronized(lock) {
            vad?.reset()
            _speechDetected.value = false
            continuousSpeechSamples = 0L
            AppLogger.d(TAG, "VAD state reset")
        }
    }
}
