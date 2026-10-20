package dev.spookcue.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

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

    private val _audioFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ShortArray> = _audioFrames.asSharedFlow()

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
        return true
    }

    fun stopCapture() {
        isRecording = false
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
