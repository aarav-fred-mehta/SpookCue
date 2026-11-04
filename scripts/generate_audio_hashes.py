"""Generate peak constellation acoustic landmark hashes for movies.

Extracts spectral peak pairs compatible with SpookCue's AcousticFingerprinter.kt
and outputs to data/sync/<tmdb_id>.json.
"""

import argparse
import json
import math
import os
import sys
import numpy as np

SAMPLE_RATE = 16000
FFT_SIZE = 1024
HOP_SIZE = 512
MIN_DELTA_FRAMES = 2
MAX_DELTA_FRAMES = 35
FAN_VALUE = 4

FREQ_BANDS = [
    (16, 31),    # 250 Hz - 500 Hz
    (32, 63),    # 500 Hz - 1000 Hz
    (64, 127),   # 1000 Hz - 2000 Hz
    (128, 255),  # 2000 Hz - 4000 Hz
]
MIN_PEAK_MAGNITUDE = 6.0


def encode_hash(f1: int, f2: int, delta_frames: int) -> int:
    b1 = f1 & 0x1FF
    b2 = f2 & 0x1FF
    dt = delta_frames & 0x3F
    return (b1) | (b2 << 9) | (dt << 18)


def extract_landmark_hashes(audio_samples: np.ndarray, sample_rate: int = 16000) -> list:
    if len(audio_samples) < FFT_SIZE:
        return []

    window = np.hanning(FFT_SIZE)
    num_frames = (len(audio_samples) - FFT_SIZE) // HOP_SIZE + 1

    # Frame-level peak envelopes
    n_full_hops = len(audio_samples) // HOP_SIZE
    frame_maxes = np.max(np.abs(audio_samples[:n_full_hops * HOP_SIZE].reshape(-1, HOP_SIZE)), axis=1)

    # Sliding 3.5s window max (109 frames @ 32ms hop)
    win_frames = 109
    pad = win_frames // 2
    padded = np.pad(frame_maxes, (pad, pad), mode="edge")

    try:
        from numpy.lib.stride_tricks import sliding_window_view
        sw = sliding_window_view(padded, win_frames)
        local_peaks = np.max(sw[:num_frames], axis=-1)
    except Exception:
        try:
            from scipy.ndimage import maximum_filter1d
            local_peaks = maximum_filter1d(frame_maxes[:num_frames], size=win_frames)
        except Exception:
            local_peaks = np.maximum(frame_maxes[:num_frames], 1e-4)

    silence_gate = 60.0 / 32768.0
    scales = np.where(
        local_peaks < silence_gate,
        0.0,
        np.minimum(24.0, 0.95 / np.maximum(local_peaks, 1e-6))
    )

    peaks = []
    for frame_idx in range(num_frames):
        scale = float(scales[frame_idx])
        if scale == 0.0:
            continue
        offset = frame_idx * HOP_SIZE
        frame = audio_samples[offset:offset + FFT_SIZE] * scale * window
        spectrum = np.fft.rfft(frame)
        magnitudes = np.abs(spectrum)

        for b_low, b_high in FREQ_BANDS:
            band_mags = magnitudes[b_low:b_high + 1]
            if len(band_mags) == 0:
                continue
            max_idx = int(np.argmax(band_mags))
            max_val = float(band_mags[max_idx])
            if max_val > MIN_PEAK_MAGNITUDE:
                best_bin = b_low + max_idx
                peaks.append((frame_idx, best_bin))

    peaks.sort(key=lambda p: p[0])
    hashes = []

    for i in range(len(peaks)):
        anchor_frame, anchor_bin = peaks[i]
        pairs_found = 0
        for j in range(i + 1, len(peaks)):
            target_frame, target_bin = peaks[j]
            delta_frames = target_frame - anchor_frame
            if delta_frames > MAX_DELTA_FRAMES:
                break
            if abs(anchor_bin - target_bin) < 2:
                continue
            if delta_frames >= MIN_DELTA_FRAMES:
                h = encode_hash(anchor_bin, target_bin, delta_frames)
                t_ms = (anchor_frame * HOP_SIZE * 1000) // sample_rate
                hashes.append({"h": int(h), "t": int(t_ms)})
                pairs_found += 1
                if pairs_found >= FAN_VALUE:
                    break

    return hashes


def load_audio(file_path: str, target_sr: int = 16000) -> np.ndarray:
    """Load audio file and convert to 16kHz mono Float32 numpy array."""
    try:
        import soundfile as sf
        data, sr = sf.read(file_path, dtype='float32')
        if data.ndim > 1:
            data = data.mean(axis=1)
        if sr != target_sr:
            # Simple resampling or scipy if available
            try:
                import scipy.signal
                num_samples = int(len(data) * target_sr / sr)
                data = scipy.signal.resample(data, num_samples)
            except ImportError:
                indices = np.round(np.arange(0, len(data), sr / target_sr)).astype(int)
                indices = indices[indices < len(data)]
                data = data[indices]
        return data
    except Exception as e:
        print(f"Error reading audio with soundfile: {e}. Trying wave module...")
        import wave
        with wave.open(file_path, 'rb') as wf:
            channels = wf.getnchannels()
            width = wf.getsampwidth()
            rate = wf.getframerate()
            raw = wf.readframes(wf.getnframes())
            if width == 2:
                samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
            else:
                raise ValueError(f"Unsupported sample width: {width}")
            if channels > 1:
                samples = samples.reshape(-1, channels).mean(axis=1)
            return samples


def main():
    parser = argparse.ArgumentParser(description="Generate SpookCue acoustic landmark hashes.")
    parser.add_argument("--input", required=True, help="Path to input audio/video file.")
    parser.add_argument("--tmdb-id", type=int, required=True, help="TMDB ID of the movie.")
    parser.add_argument("--output", help="Custom output JSON path (default: data/sync/<tmdb_id>.json)")
    args = parser.parse_args()

    print(f"Loading audio from: {args.input}...")
    samples = load_audio(args.input, target_sr=SAMPLE_RATE)
    print(f"Audio loaded: {len(samples)} samples ({len(samples) / SAMPLE_RATE:.2f}s). Extracting landmark hashes...")

    hashes = extract_landmark_hashes(samples, sample_rate=SAMPLE_RATE)
    print(f"Extracted {len(hashes)} landmark hashes.")

    output_path = args.output or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "data", "sync", f"{args.tmdb_id}.json"
    )
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    payload = {
        "tmdb_id": args.tmdb_id,
        "type": "audio_hash",
        "audio_hashes": hashes
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, separators=(",", ":"))

    print(f"Saved acoustic hashes to: {output_path} ({os.path.getsize(output_path)} bytes)")


if __name__ == "__main__":
    main()
