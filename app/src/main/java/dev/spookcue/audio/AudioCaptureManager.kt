package dev.spookcue.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.spookcue.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

@Singleton
class AudioCaptureManager @Inject constructor(
    private val gainController: GainController,
    val audioEnergyMeter: AudioEnergyMeter
) {
    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_SAMPLES = 512 // 32 ms frame @ 16kHz
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isEmissionPaused = false
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _audioFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ShortArray> = _audioFrames.asSharedFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _speechDetected = MutableStateFlow(false)
    val speechDetected: StateFlow<Boolean> = _speechDetected.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCapture(): Boolean {
        if (isRecording) {
            AppLogger.d(TAG, "Audio capture already active")
            isEmissionPaused = false
            return true
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE_SAMPLES * 8)

        val record = createAudioRecord(bufferSize)
        if (record == null) {
            AppLogger.e(TAG, "Failed to initialize AudioRecord with any available audio source")
            return false
        }

        audioRecord = record
        try {
            record.startRecording()
            isRecording = true
            isEmissionPaused = false
            AppLogger.i(TAG, "AudioRecord started recording successfully at 16kHz MONO")
            startReadingLoop(record)
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception while starting AudioRecord", e)
            record.release()
            audioRecord = null
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (source in sources) {
            try {
                AppLogger.d(TAG, "Attempting to create AudioRecord with source: $source")
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    AppLogger.i(TAG, "Successfully initialized AudioRecord with source $source")
                    return record
                } else {
                    AppLogger.w(TAG, "AudioRecord source $source created with uninitialized state")
                    record.release()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "AudioRecord source $source creation failed", e)
            }
        }
        return null
    }

    private fun startReadingLoop(record: AudioRecord) {
        readJob = scope.launch {
            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            var frameCount = 0L

            while (isActive && isRecording) {
                val readCount = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

                if (readCount > 0) {
                    val actualFrame = if (readCount == buffer.size) {
                        buffer.clone()
                    } else {
                        buffer.copyOf(readCount)
                    }

                    // 1. Process AGC gain adjustment
                    val amplifiedFrame = gainController.processFrame(actualFrame)

                    // 2. Track energy via AudioEnergyMeter
                    val db = audioEnergyMeter.processFrame(amplifiedFrame)
                    _rmsDb.value = db
                    _speechDetected.value = db > 32f

                    // 3. Emit amplified audio only if not paused
                    if (!isEmissionPaused) {
                        _audioFrames.emit(amplifiedFrame)
                    }

                    frameCount++
                    if (frameCount % 100L == 0L) {
                        AppLogger.d(TAG, "Audio captured: frame #$frameCount, live RMS: ${"%.1f".format(db)} dB (emissionPaused=$isEmissionPaused)")
                    }
                } else if (readCount < 0) {
                    AppLogger.w(TAG, "AudioRecord read returned error code: $readCount")
                }
            }
            AppLogger.i(TAG, "Audio reading loop finished")
        }
    }

    fun pauseEmission() {
        isEmissionPaused = true
    }

    fun resumeEmission() {
        isEmissionPaused = false
    }

    fun isEmissionPaused(): Boolean = isEmissionPaused

    fun stopCapture() {
        AppLogger.i(TAG, "Stopping audio capture")
        isRecording = false
        isEmissionPaused = false
        readJob?.cancel()
        readJob = null
        _rmsDb.value = 0f
        _speechDetected.value = false
        audioEnergyMeter.reset()

        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error stopping AudioRecord", e)
            } finally {
                release()
            }
        }
        audioRecord = null
    }

    fun isCapturing(): Boolean = isRecording
}
