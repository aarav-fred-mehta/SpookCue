package dev.spookcue.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val gainController: GainController
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_SAMPLES = 512 // 32 ms frame @ 16kHz
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
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
        if (isRecording) return true

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            FRAME_SIZE_SAMPLES * 4
        )

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }.getOrElse {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        audioRecord = record
        record.startRecording()
        isRecording = true

        startReadingLoop(record)
        return true
    }

    private fun startReadingLoop(record: AudioRecord) {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            while (isActive && isRecording) {
                val readCount = record.read(buffer, 0, buffer.size)
                if (readCount > 0) {
                    // Compute live RMS energy
                    var sum = 0.0
                    for (i in 0 until readCount) {
                        val sample = buffer[i].toDouble()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / readCount)
                    val db = (20.0 * log10(rms.coerceAtLeast(1.0))).toFloat().coerceIn(0f, 90f)

                    _rmsDb.value = db
                    _speechDetected.value = db > 38f // speech energy threshold

                    val copy = buffer.copyOf(readCount)
                    _audioFrames.emit(copy)
                }
            }
        }
    }

    fun stopCapture() {
        isRecording = false
        readJob?.cancel()
        readJob = null
        _rmsDb.value = 0f
        _speechDetected.value = false

        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        audioRecord = null
    }

    fun isCapturing(): Boolean = isRecording
}
