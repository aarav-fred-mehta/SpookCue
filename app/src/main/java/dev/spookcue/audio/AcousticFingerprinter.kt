package dev.spookcue.audio

import dev.spookcue.data.model.AudioHashEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Acoustic Landmark / Peak Constellation Fingerprinter.
 * Converts 16kHz audio waveforms into robust 32-bit peak-pair landmark hashes.
 * Compatible with Python generator `scripts/generate_audio_hashes.py`.
 */
@Singleton
class AcousticFingerprinter @Inject constructor() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FFT_SIZE = 1024
        const val HOP_SIZE = 512 // 32ms hop @ 16kHz
        const val MIN_DELTA_FRAMES = 2
        const val MAX_DELTA_FRAMES = 35 // Up to ~1.1s ahead
        const val FAN_VALUE = 4 // Max forward peak pairs per anchor peak

        // Frequency ranges (bins) for 4 log bands (15.625 Hz per bin)
        val FREQ_BANDS = listOf(
            16..31,   // 250 Hz - 500 Hz
            32..63,   // 500 Hz - 1000 Hz
            64..127,  // 1000 Hz - 2000 Hz
            128..255  // 2000 Hz - 4000 Hz
        )

        const val MIN_PEAK_MAGNITUDE = 8.0f
    }

    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    data class Peak(val frameIdx: Int, val freqBin: Int, val magnitude: Float)

    /**
     * Extracts landmark hashes from PCM FloatArray audio samples (normalized -1.0..1.0 or raw).
     */
    fun extractFingerprints(samples: FloatArray, startTimestampOffsetMs: Long = 0L): List<AudioHashEntry> {
        if (samples.size < FFT_SIZE) return emptyList()

        val numFrames = (samples.size - FFT_SIZE) / HOP_SIZE + 1
        val peaks = mutableListOf<Peak>()

        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val magnitudes = FloatArray(FFT_SIZE / 2)

        for (frame in 0 until numFrames) {
            val offset = frame * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                real[i] = samples[offset + i] * hannWindow[i]
                imag[i] = 0f
            }

            fftRadix2(real, imag)

            // Compute magnitudes
            for (k in 0 until FFT_SIZE / 2) {
                magnitudes[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
            }

            // Find peak in each frequency band
            for (band in FREQ_BANDS) {
                var maxMag = MIN_PEAK_MAGNITUDE
                var bestBin = -1
                for (b in band) {
                    if (magnitudes[b] > maxMag) {
                        maxMag = magnitudes[b]
                        bestBin = b
                    }
                }
                if (bestBin != -1) {
                    peaks.add(Peak(frameIdx = frame, freqBin = bestBin, magnitude = maxMag))
                }
            }
        }

        // Pair peaks to form landmark hashes
        val hashes = mutableListOf<AudioHashEntry>()
        peaks.sortBy { it.frameIdx }

        for (i in peaks.indices) {
            val anchor = peaks[i]
            var pairsFound = 0
            for (j in (i + 1) until peaks.size) {
                val target = peaks[j]
                val deltaFrames = target.frameIdx - anchor.frameIdx
                if (deltaFrames > MAX_DELTA_FRAMES) break
                if (deltaFrames >= MIN_DELTA_FRAMES) {
                    val hash = encodeHash(anchor.freqBin, target.freqBin, deltaFrames)
                    val timestampMs = startTimestampOffsetMs + (anchor.frameIdx.toLong() * HOP_SIZE * 1000L / SAMPLE_RATE)
                    hashes.add(AudioHashEntry(h = hash, t = timestampMs))
                    pairsFound++
                    if (pairsFound >= FAN_VALUE) break
                }
            }
        }

        return hashes
    }

    /**
     * Extracts fingerprints directly from 16-bit ShortArray audio frames.
     */
    fun extractFingerprintsFromShorts(samples: ShortArray, startTimestampOffsetMs: Long = 0L): List<AudioHashEntry> {
        if (samples.isEmpty()) return emptyList()

        var maxSample = 0
        for (s in samples) {
            val a = kotlin.math.abs(s.toInt())
            if (a > maxSample) maxSample = a
        }

        // Gate out pure silence / ambient room murmur without movie audio
        if (maxSample < 40) return emptyList()

        // Dynamic gain normalization: scales phone mic audio to digital master range
        val scale = 0.95f / (maxSample.toFloat() / 32768.0f)
        val floats = FloatArray(samples.size) { (samples[it] / 32768.0f) * scale }
        return extractFingerprints(floats, startTimestampOffsetMs)
    }

    /**
     * Combines f1 (9 bits), f2 (9 bits), deltaFrames (6 bits) into a 32-bit integer.
     */
    fun encodeHash(f1: Int, f2: Int, deltaFrames: Int): Int {
        val b1 = f1 and 0x1FF
        val b2 = f2 and 0x1FF
        val dt = deltaFrames and 0x3F
        return (b1) or (b2 shl 9) or (dt shl 18)
    }

    /**
     * In-place Cooley-Tukey Radix-2 FFT.
     */
    private fun fftRadix2(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until half) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + half] * wR - imag[i + k + half] * wI
                    val vI = real[i + k + half] * wI + imag[i + k + half] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + half] = uR - vR
                    imag[i + k + half] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }
}
