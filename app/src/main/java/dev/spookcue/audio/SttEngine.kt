package dev.spookcue.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spookcue.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

data class TranscriptionResult(
    val text: String,
    val confidence: Float
)

interface SttEngine {
    val transcriptions: SharedFlow<TranscriptionResult>
    suspend fun transcribe(pcmSamples: FloatArray): TranscriptionResult
    fun reset()
}

@Singleton
class SttEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SttEngine {

    companion object {
        private const val TAG = "SttEngine"
    }

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()

    private val _transcriptions = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 16)
    override val transcriptions: SharedFlow<TranscriptionResult> = _transcriptions.asSharedFlow()

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        runCatching {
            // 1. Try SenseVoice-Small first (Fast, non-autoregressive, superior whisper handling)
            val hasSenseVoice = runCatching {
                context.assets.open("models/sense_voice/model.int8.onnx").close()
                true
            }.getOrDefault(false)

            if (hasSenseVoice) {
                AppLogger.i(TAG, "Initializing SenseVoice-Small OfflineRecognizer...")
                val senseVoice = OfflineSenseVoiceModelConfig(
                    model = "models/sense_voice/model.int8.onnx",
                    language = "auto",
                    useInverseTextNormalization = true
                )
                val modelConfig = OfflineModelConfig(
                    senseVoice = senseVoice,
                    tokens = "models/sense_voice/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "sense_voice"
                )
                val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
                val config = OfflineRecognizerConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig
                )
                recognizer = OfflineRecognizer(context.assets, config)
                AppLogger.i(TAG, "SenseVoice-Small OfflineRecognizer initialized successfully")
                return
            }

            // 2. Fallback to Whisper if SenseVoice is not present
            val hasBase = runCatching {
                context.assets.open("models/whisper/base.en-encoder.int8.onnx").close()
                true
            }.getOrDefault(false)

            val modelPrefix = if (hasBase) "base.en" else "tiny.en"
            AppLogger.i(TAG, "SenseVoice not found. Initializing Whisper recognizer ($modelPrefix)...")

            val whisper = OfflineWhisperModelConfig(
                encoder = "models/whisper/$modelPrefix-encoder.int8.onnx",
                decoder = "models/whisper/$modelPrefix-decoder.int8.onnx",
                language = "en",
                task = "transcribe",
                tailPaddings = -1,
                enableTokenTimestamps = false,
                enableSegmentTimestamps = false
            )
            val modelConfig = OfflineModelConfig(
                whisper = whisper,
                tokens = "models/whisper/$modelPrefix-tokens.txt",
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "whisper"
            )
            val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
            val config = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig
            )
            recognizer = OfflineRecognizer(context.assets, config)
            AppLogger.i(TAG, "Whisper-$modelPrefix OfflineRecognizer initialized successfully")
        }.onFailure { e ->
            AppLogger.e(TAG, "Failed to initialize STT OfflineRecognizer from assets", e)
        }
    }

    private fun normalizeSegment(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var sumSquares = 0.0
        var maxPeak = 0.0f
        for (s in samples) {
            sumSquares += (s * s).toDouble()
            val absS = abs(s)
            if (absS > maxPeak) maxPeak = absS
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        if (rms <= 0.0001f || maxPeak <= 0.0001f) return samples

        // Target nominal speech RMS: ~0.10 (-20 dBFS)
        val targetRms = 0.10f
        var gain = (targetRms / rms).coerceIn(0.5f, 10.0f)
        if (maxPeak * gain > 0.95f) {
            gain = 0.95f / maxPeak
        }

        val normalized = FloatArray(samples.size)
        for (i in samples.indices) {
            normalized[i] = samples[i] * gain
        }
        return normalized
    }

    override suspend fun transcribe(pcmSamples: FloatArray): TranscriptionResult = withContext(Dispatchers.Default) {
        if (pcmSamples.isEmpty()) return@withContext TranscriptionResult("", 0.0f)

        synchronized(lock) {
            val rec = recognizer ?: run {
                AppLogger.w(TAG, "Recognizer not initialized, skipping transcription")
                return@withContext TranscriptionResult("", 0.0f)
            }
            val normalizedSamples = normalizeSegment(pcmSamples)
            val stream = rec.createStream()
            try {
                val t0 = System.currentTimeMillis()
                stream.acceptWaveform(normalizedSamples, 16000)
                rec.decode(stream)
                val result = rec.getResult(stream)
                val rawText = result.text.trim()

                // Strip SenseVoice emotion/language tags like <|zh|>, <|en|>, <|HAPPY|>, <|EMOJI_WHISPER|>
                // and non-verbal audio events like (sighs), [groans], *music*
                val cleanText = rawText
                    .replace(Regex("""<\|[^>]*\|>"""), " ")
                    .replace(Regex("""\([^\)]*\)|\[[^\]]*\]|\*[^\*]*\*"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                val elapsed = System.currentTimeMillis() - t0
                AppLogger.i(TAG, "STT Transcribed (${elapsed}ms): \"$cleanText\" (raw: \"$rawText\")")

                val transcription = TranscriptionResult(
                    text = cleanText,
                    confidence = if (cleanText.isNotBlank()) 1.0f else 0.0f
                )
                if (cleanText.isNotBlank()) {
                    _transcriptions.tryEmit(transcription)
                }
                transcription
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception during STT decode", e)
                TranscriptionResult("", 0.0f)
            } finally {
                stream.release()
            }
        }
    }

    override fun reset() = synchronized(lock) {
        // Stateless between utterances
    }
}
