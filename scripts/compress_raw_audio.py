#!/usr/bin/env python3
"""SpookCue Raw Audio Compressor (Tuned AAC & Lossless FLAC).

This standalone script sits in an external directory alongside:
  - "Raw Audio/"    : Place your movie video/audio files here (.mp4, .mkv, .flac, etc.)
  - "Audio Hashes/" : Generated landmark hashes

It scans "Raw Audio/" (including subdirectories), extracts the audio
using Variation 5A Tuned AAC (320k-640k dynamic, fast search, PNS=0, TNS=0) to .m4a format
(or lossless FLAC with --format flac), verifies audio integrity,
and replaces the large video/flac files with compact, hash-preserving audio files.

Supports multi-worker parallel batching (--jobs) and fast linear AAC quantization
to maximize processing throughput on multi-core CPUs.
"""

from __future__ import annotations

import argparse
import atexit
import concurrent.futures
import json
import logging
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
import uuid

try:
    import numpy as np
except ImportError:
    print("[ERROR] 'numpy' is required for compress_raw_audio.py. Please install numpy.")
    sys.exit(1)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("audio_compressor")

# Media extensions to scan
VIDEO_EXTENSIONS = {
    ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
    ".webm", ".m4v", ".ts", ".mts", ".m2ts", ".vob",
    ".ogv", ".3gp", ".divx"
}
AUDIO_UNCOMPRESSED_EXTENSIONS = {
    ".wav", ".aiff", ".aif", ".pcm", ".alac", ".flac"
}
AUDIO_LOSSY_EXTENSIONS = {
    ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wma"
}
ALL_MEDIA_EXTENSIONS = VIDEO_EXTENSIONS | AUDIO_UNCOMPRESSED_EXTENSIONS | AUDIO_LOSSY_EXTENSIONS

# Thread synchronization trackers
_ACTIVE_TEMP_FILES: set[Path] = set()
_TEMP_LOCK = threading.Lock()
_PRINT_LOCK = threading.Lock()


def cleanup_temp_files() -> None:
    """Ensure any unfinished temporary files are deleted if interrupted."""
    global _ACTIVE_TEMP_FILES
    with _TEMP_LOCK:
        for temp_file in list(_ACTIVE_TEMP_FILES):
            if temp_file.exists():
                try:
                    temp_file.unlink()
                    logger.info("Cleaned up temporary file: %s", temp_file.name)
                except Exception:
                    pass
        _ACTIVE_TEMP_FILES.clear()


atexit.register(cleanup_temp_files)


# ---------------------------------------------------------------------------
# Utility Helpers
# ---------------------------------------------------------------------------
def format_bytes(num_bytes: float) -> str:
    """Format bytes into human-readable string (KB, MB, GB)."""
    if num_bytes >= 1024 ** 3:
        return f"{num_bytes / (1024 ** 3):.2f} GB"
    if num_bytes >= 1024 ** 2:
        return f"{num_bytes / (1024 ** 2):.1f} MB"
    if num_bytes >= 1024:
        return f"{num_bytes / 1024:.1f} KB"
    return f"{int(num_bytes)} B"


def check_tool(tool_name: str) -> bool:
    """Check if command-line tool exists on PATH."""
    return shutil.which(tool_name) is not None


# ---------------------------------------------------------------------------
# Media Probing & Stream Analysis
# ---------------------------------------------------------------------------
class MediaProbeResult:
    """Container for media stream analysis results."""

    def __init__(
        self,
        file_path: Path,
        has_video: bool,
        audio_streams: List[Dict[str, Any]],
        best_audio_index: Optional[int],
        best_audio_desc: str,
        duration_sec: float = 0.0,
        error_msg: Optional[str] = None
    ):
        self.file_path = file_path
        self.has_video = has_video
        self.audio_streams = audio_streams
        self.best_audio_index = best_audio_index
        self.best_audio_desc = best_audio_desc
        self.duration_sec = duration_sec
        self.error_msg = error_msg

    @property
    def has_audio(self) -> bool:
        return len(self.audio_streams) > 0

    @property
    def is_valid(self) -> bool:
        return self.error_msg is None and self.has_audio


def probe_media_file(file_path: Path) -> MediaProbeResult:
    """Inspect file streams using ffprobe to detect video and pick best audio stream."""
    cmd = [
        "ffprobe",
        "-v", "error",
        "-show_entries",
        "format=duration:stream=index,codec_name,codec_type,channels,sample_rate,disposition:stream_tags=language,title,handler_name",
        "-of", "json",
        str(file_path)
    ]

    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    except Exception as exc:
        return MediaProbeResult(
            file_path=file_path,
            has_video=False,
            audio_streams=[],
            best_audio_index=None,
            best_audio_desc="",
            error_msg=f"Failed to execute ffprobe: {exc}"
        )

    if proc.returncode != 0:
        err = proc.stderr.strip() or f"ffprobe exited with code {proc.returncode}"
        return MediaProbeResult(
            file_path=file_path,
            has_video=False,
            audio_streams=[],
            best_audio_index=None,
            best_audio_desc="",
            error_msg=err
        )

    try:
        data = json.loads(proc.stdout)
    except Exception as exc:
        return MediaProbeResult(
            file_path=file_path,
            has_video=False,
            audio_streams=[],
            best_audio_index=None,
            best_audio_desc="",
            error_msg=f"Invalid JSON from ffprobe: {exc}"
        )

    streams = data.get("streams", [])
    format_info = data.get("format", {})
    try:
        duration_sec = float(format_info.get("duration", 0.0))
    except Exception:
        duration_sec = 0.0

    # 1. Check for real video streams (exclude embedded thumbnail/album art pictures)
    video_streams = [
        s for s in streams
        if s.get("codec_type") == "video"
        and s.get("disposition", {}).get("attached_pic") != 1
        and s.get("codec_name") not in ["mjpeg", "png", "bmp"]
    ]
    has_video = len(video_streams) > 0

    # 2. Collect audio streams
    audio_streams = [s for s in streams if s.get("codec_type") == "audio"]
    if not audio_streams:
        return MediaProbeResult(
            file_path=file_path,
            has_video=has_video,
            audio_streams=[],
            best_audio_index=None,
            best_audio_desc="",
            duration_sec=duration_sec,
            error_msg="No audio streams found in container"
        )

    # 3. Select best audio stream
    if len(audio_streams) == 1:
        s = audio_streams[0]
        desc = f"Stream #{s.get('index')} ({s.get('codec_name', 'audio')}, {s.get('channels', 2)}ch, {s.get('sample_rate', 48000)}Hz)"
        return MediaProbeResult(
            file_path=file_path,
            has_video=has_video,
            audio_streams=audio_streams,
            best_audio_index=int(s.get("index", 0)),
            best_audio_desc=desc,
            duration_sec=duration_sec
        )

    # Score each audio stream for multi-track files (e.g. film audio vs commentary vs dubs)
    def score_audio_stream(stream: Dict[str, Any]) -> int:
        score = 100
        tags = stream.get("tags") or {}
        lang = (tags.get("language") or "").lower()
        title = (tags.get("title") or "").lower()
        handler = (tags.get("handler_name") or "").lower()
        disposition = stream.get("disposition") or {}

        # Penalize commentary, director, visual impaired, or auxiliary tracks heavily
        penalty_keywords = ["commentary", "director", "description", "sdh", "comment", "review", "isolated"]
        for kw in penalty_keywords:
            if kw in title or kw in handler:
                score -= 300

        # Favor English or undefined original language tracks
        if lang in ["eng", "en", "und", ""]:
            score += 30

        # Favor higher channel counts (e.g. 5.1/7.1 surround sound over stereo)
        channels = int(stream.get("channels", 2))
        score += channels * 10

        # Favor default track flag
        if disposition.get("default") == 1:
            score += 20

        return score

    best_stream = max(audio_streams, key=score_audio_stream)
    best_index = int(best_stream.get("index", 0))
    tags = best_stream.get("tags") or {}
    lang_info = f", lang: {tags.get('language')}" if tags.get("language") else ""
    best_desc = (
        f"Stream #{best_index} ({best_stream.get('codec_name', 'audio')}, "
        f"{best_stream.get('channels', 2)}ch, {best_stream.get('sample_rate', 48000)}Hz{lang_info})"
    )

    return MediaProbeResult(
        file_path=file_path,
        has_video=has_video,
        audio_streams=audio_streams,
        best_audio_index=best_index,
        best_audio_desc=best_desc,
        duration_sec=duration_sec
    )


# ---------------------------------------------------------------------------
# Reference Lead-In / Bumper Detection & Trimming
# ---------------------------------------------------------------------------
class IntroPattern:
    """Pre-loaded audio spectral signature for reference lead-in / bumper matching."""

    def __init__(
        self,
        name: str,
        file_path: Path,
        duration_sec: float,
        features: np.ndarray,
        envelope: np.ndarray
    ):
        self.name = name
        self.file_path = file_path
        self.duration_sec = duration_sec
        self.features = features  # shape (num_bands, num_frames)
        self.envelope = envelope  # shape (num_frames,)

        # Precompute zero-mean centered vectors for fast normalized correlation
        self.feat_zero = features - np.mean(features, axis=1, keepdims=True)
        self.feat_norm = float(np.linalg.norm(self.feat_zero))
        self.env_zero = envelope - np.mean(envelope)
        self.env_norm = float(np.linalg.norm(self.env_zero))


def compute_stft_features(
    audio: np.ndarray,
    fs: int = 8000,
    nperseg: int = 256,
    hop: int = 128
) -> Tuple[Optional[np.ndarray], Optional[np.ndarray]]:
    """Compute 8-band logarithmic spectral energy features and amplitude envelope."""
    if len(audio) < nperseg:
        return None, None

    n_frames = (len(audio) - nperseg) // hop + 1
    window = np.hanning(nperseg)
    frames = np.lib.stride_tricks.as_strided(
        audio,
        shape=(n_frames, nperseg),
        strides=(audio.strides[0] * hop, audio.strides[0])
    ) * window

    spectra = np.abs(np.fft.rfft(frames, axis=1))  # (n_frames, nperseg // 2 + 1)
    freqs = np.fft.rfftfreq(nperseg, 1.0 / fs)

    edges = [50, 150, 300, 600, 1000, 1800, 2800, 4000]
    bands = []
    for i in range(len(edges) - 1):
        mask = (freqs >= edges[i]) & (freqs < edges[i + 1])
        if np.any(mask):
            bands.append(np.sum(spectra[:, mask], axis=1))
        else:
            bands.append(np.zeros(n_frames, dtype=np.float32))

    feat = np.log1p(np.stack(bands, axis=0))  # (num_bands, n_frames)
    env = np.mean(feat, axis=0)  # (n_frames,)
    return feat, env


def load_intro_pattern(
    file_path: Path,
    max_dur_sec: float = 20.0,
    sample_rate: int = 8000
) -> Optional[IntroPattern]:
    """Extract audio signature from a local reference intro media file (.mp4, .mp3, etc.)."""
    cmd = [
        "ffmpeg",
        "-nostdin",
        "-v", "error",
        "-t", str(max_dur_sec),
        "-i", str(file_path),
        "-ac", "1",
        "-ar", str(sample_rate),
        "-f", "f32le",
        "-"
    ]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        if proc.returncode != 0 or not proc.stdout:
            return None
        audio = np.frombuffer(proc.stdout, dtype=np.float32)
        if len(audio) < sample_rate:
            return None

        dur_sec = len(audio) / sample_rate
        feat, env = compute_stft_features(audio, fs=sample_rate)
        if feat is None or env is None:
            return None

        return IntroPattern(
            name=file_path.name,
            file_path=file_path,
            duration_sec=dur_sec,
            features=feat,
            envelope=env
        )
    except Exception as exc:
        logger.debug("Could not load reference intro from %s: %s", file_path.name, exc)
        return None


def find_reference_intro_files(raw_dir: Path, custom_dir: Optional[Path] = None) -> List[Path]:
    """Find local reference intro / bumper files across common local candidate directories."""
    candidate_paths: List[Path] = []
    seen_names: set[str] = set()
    supported_exts = {".mp4", ".mp3", ".m4a", ".wav", ".flac", ".mkv", ".aac"}

    search_dirs: List[Path] = []
    if custom_dir:
        search_dirs.append(custom_dir)

    search_dirs.extend([
        raw_dir.parent / "reference_intros",
        raw_dir.parent / "intros",
        raw_dir / "reference_intros",
        raw_dir / "intros",
        raw_dir.parent
    ])

    for d in search_dirs:
        if d.exists() and d.is_dir():
            for f in sorted(d.iterdir()):
                if f.is_file() and f.suffix.lower() in supported_exts:
                    if d == raw_dir.parent:
                        stem_lower = f.stem.lower()
                        if not any(k in stem_lower for k in ("intro", "bumper", "sting")):
                            continue
                    if f.name not in seen_names:
                        seen_names.add(f.name)
                        candidate_paths.append(f)

    return candidate_paths


def detect_intro_match(
    media_file: Path,
    patterns: List[IntroPattern],
    scan_duration_sec: float = 20.0,
    sample_rate: int = 8000,
    min_confidence: float = 0.65,
    max_start_offset_sec: float = 1.5
) -> Optional[Tuple[IntroPattern, float, float, float]]:
    """Scan the beginning of a candidate file to detect and measure matching lead-in bumpers.

    Returns:
        (pattern, offset_sec, trim_duration_sec, confidence) or None
    """
    if not patterns:
        return None

    cmd = [
        "ffmpeg",
        "-nostdin",
        "-v", "error",
        "-t", str(scan_duration_sec),
        "-i", str(media_file),
        "-ac", "1",
        "-ar", str(sample_rate),
        "-f", "f32le",
        "-"
    ]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        if proc.returncode != 0 or not proc.stdout:
            return None
        cand_audio = np.frombuffer(proc.stdout, dtype=np.float32)
        if len(cand_audio) < sample_rate:
            return None

        # Verify candidate audio is not near-silence in the first few seconds
        cand_rms = float(np.sqrt(np.mean(cand_audio[:min(len(cand_audio), sample_rate * 5)] ** 2)))
        if cand_rms < 0.005:
            return None

        cand_feat, cand_env = compute_stft_features(cand_audio, fs=sample_rate)
        if cand_feat is None or cand_env is None:
            return None
    except Exception:
        return None

    fps = sample_rate / 128.0  # 62.5 fps
    max_hops = int(max_start_offset_sec * fps)

    best_match: Optional[Tuple[IntroPattern, float, float, float]] = None
    best_score = 0.0

    for pat in patterns:
        M = pat.features.shape[1]
        if cand_feat.shape[1] < M:
            continue

        search_range = min(max_hops, cand_feat.shape[1] - M + 1)
        for t in range(search_range):
            # 1. Multi-band spectral cosine similarity
            w_feat = cand_feat[:, t:t + M]
            w_zero = w_feat - np.mean(w_feat, axis=1, keepdims=True)
            w_norm = np.linalg.norm(w_zero)
            if w_norm > 1e-3 and pat.feat_norm > 1e-3:
                spec_sim = float(np.sum(w_zero * pat.feat_zero) / (pat.feat_norm * w_norm))
            else:
                spec_sim = 0.0

            # 2. Envelope correlation
            w_env = cand_env[t:t + M]
            we_zero = w_env - np.mean(w_env)
            we_norm = np.linalg.norm(we_zero)
            if we_norm > 1e-3 and pat.env_norm > 1e-3:
                env_sim = float(np.dot(we_zero, pat.env_zero) / (pat.env_norm * we_norm))
            else:
                env_sim = 0.0

            combined = 0.5 * spec_sim + 0.5 * env_sim
            if combined > best_score:
                best_score = combined
                offset_sec = t / fps
                trim_sec = round(offset_sec + pat.duration_sec, 2)
                if spec_sim >= 0.60 and combined >= min_confidence:
                    best_match = (pat, offset_sec, trim_sec, combined)

    return best_match


# ---------------------------------------------------------------------------
# Audio Compression Pipeline (Variation 5A Tuned AAC & 16-bit FLAC)
# ---------------------------------------------------------------------------
def compress_file(
    source_file: Path,
    probe: MediaProbeResult,
    target_file: Path,
    output_format: str = "aac",
    keep_original: bool = False,
    trim_start_sec: float = 0.0,
    custom_bitrate: Optional[str] = None
) -> Tuple[bool, float, float, Optional[str]]:
    """Compress source video/audio into target format (.m4a or .flac), verify, and replace original.

    Returns:
        (success, orig_bytes, new_bytes, error_message)
    """
    global _ACTIVE_TEMP_FILES

    orig_bytes = float(source_file.stat().st_size)

    # Use a unique temporary filename in the target directory
    temp_out = target_file.parent / f"{target_file.stem}.{uuid.uuid4().hex[:8]}.temp{target_file.suffix}"
    with _TEMP_LOCK:
        _ACTIVE_TEMP_FILES.add(temp_out)

    # 1. Build FFmpeg command
    cmd = [
        "ffmpeg",
        "-nostdin",
        "-y",
        "-v", "error",
        "-stats",
    ]

    # Apply lead-in bumper trimming if detected
    if trim_start_sec > 0.0:
        cmd.extend(["-ss", f"{trim_start_sec:.3f}"])

    cmd.extend(["-i", str(source_file)])

    # Map the selected audio stream if ffprobe resolved one
    if probe.best_audio_index is not None:
        cmd.extend(["-map", f"0:{probe.best_audio_index}"])

    # Determine channel count for bitrate allocation
    channels = 2
    if probe.audio_streams and probe.best_audio_index is not None:
        for s in probe.audio_streams:
            if s.get("index") == probe.best_audio_index:
                channels = int(s.get("channels", 2))
                break
    elif probe.audio_streams:
        channels = int(probe.audio_streams[0].get("channels", 2))

    if output_format.lower() == "aac":
        # Variation 5A: Tuned AAC 320k-640k with fast search, PNS=0, and TNS=0
        if custom_bitrate:
            br_str = custom_bitrate if custom_bitrate.endswith("k") else f"{custom_bitrate}k"
        else:
            # 320k for stereo (1-2ch), 448k-512k for 5.1 (6ch), 640k for 7.1 (8ch)
            bitrate_k = min(640, max(320, channels * 80))
            br_str = f"{bitrate_k}k"

        cmd.extend([
            "-vn",
            "-sn",
            "-dn",
            "-c:a", "aac",
            "-b:a", br_str,
            "-aac_coder", "fast",
            "-aac_pns", "0",
            "-aac_tns", "0",
            str(temp_out)
        ])
    else:
        # Lossless 16-bit FLAC level 12
        cmd.extend([
            "-vn",
            "-sn",
            "-dn",
            "-c:a", "flac",
            "-sample_fmt", "s16",
            "-compression_level", "12",
            str(temp_out)
        ])

    try:
        proc = subprocess.run(cmd, check=False)
        if proc.returncode != 0:
            if temp_out.exists():
                temp_out.unlink()
            with _TEMP_LOCK:
                _ACTIVE_TEMP_FILES.discard(temp_out)
            return False, orig_bytes, 0.0, f"FFmpeg failed with exit code {proc.returncode}"
    except Exception as exc:
        if temp_out.exists():
            temp_out.unlink()
        with _TEMP_LOCK:
            _ACTIVE_TEMP_FILES.discard(temp_out)
        return False, orig_bytes, 0.0, f"Error launching FFmpeg: {exc}"

    # 2. Verify temporary file was produced and has reasonable size
    if not temp_out.exists():
        with _TEMP_LOCK:
            _ACTIVE_TEMP_FILES.discard(temp_out)
        return False, orig_bytes, 0.0, f"FFmpeg finished but output file was not created"

    new_bytes = float(temp_out.stat().st_size)
    if new_bytes < 10240:  # Minimum 10 KB
        temp_out.unlink()
        with _TEMP_LOCK:
            _ACTIVE_TEMP_FILES.discard(temp_out)
        return False, orig_bytes, 0.0, f"Output file is suspiciously small ({new_bytes} bytes)"

    # 3. Quick verification: test decoding the header and first 5 seconds
    verify_cmd = [
        "ffmpeg",
        "-nostdin",
        "-v", "error",
        "-i", str(temp_out),
        "-t", "5",
        "-f", "null",
        "-"
    ]
    try:
        v_proc = subprocess.run(verify_cmd, capture_output=True, text=True, check=False)
        if v_proc.returncode != 0:
            err = v_proc.stderr.strip() or "Verification decode failed"
            temp_out.unlink()
            with _TEMP_LOCK:
                _ACTIVE_TEMP_FILES.discard(temp_out)
            return False, orig_bytes, 0.0, f"Integrity check failed: {err}"
    except Exception as exc:
        temp_out.unlink()
        with _TEMP_LOCK:
            _ACTIVE_TEMP_FILES.discard(temp_out)
        return False, orig_bytes, 0.0, f"Integrity check execution error: {exc}"

    # 4. Atomic replacement: delete original (if requested) and rename temp to target
    try:
        if not keep_original:
            # Delete original source file (e.g. video file or old .flac)
            source_file.unlink()

        # Rename temp to final target
        if target_file.exists() and target_file != source_file:
            target_file.unlink()
        temp_out.replace(target_file)
        with _TEMP_LOCK:
            _ACTIVE_TEMP_FILES.discard(temp_out)

    except Exception as exc:
        with _TEMP_LOCK:
            _ACTIVE_TEMP_FILES.discard(temp_out)
        return False, orig_bytes, new_bytes, f"File replacement failed: {exc}"

    return True, orig_bytes, new_bytes, None


# ---------------------------------------------------------------------------
# Worker Task for Single File Processing
# ---------------------------------------------------------------------------
def process_single_candidate(
    idx: int,
    total_files: int,
    media_file: Path,
    raw_dir: Path,
    output_format: str,
    target_ext: str,
    keep_original: bool,
    dry_run: bool,
    force: bool,
    convert_lossy: bool,
    flatten: bool,
    intro_patterns: List[IntroPattern],
    disable_trim: bool,
    min_intro_score: float,
    custom_bitrate: Optional[str]
) -> Tuple[str, float, float]:
    """Process a single media candidate file.

    Returns:
        (status, orig_bytes, new_bytes) where status in ("compressed", "skipped", "failed")
    """
    rel_path = media_file.relative_to(raw_dir)
    orig_size = media_file.stat().st_size
    orig_size_str = format_bytes(orig_size)

    if flatten:
        target_out = raw_dir / f"{media_file.stem}{target_ext}"
    else:
        target_out = media_file.parent / f"{media_file.stem}{target_ext}"

    # Check if file is already in target format
    if media_file.suffix.lower() == target_ext and not force:
        with _PRINT_LOCK:
            print(f"[{idx}/{total_files}] {rel_path} ({orig_size_str})")
            print(f"  -> [SKIPPED] Already in target format ({target_ext}). Use --force to re-encode.\n")
        return "skipped", orig_size, orig_size

    # Check if target already exists and is non-empty
    if target_out.exists() and target_out != media_file and not force:
        target_size = target_out.stat().st_size
        if target_size > 10240:
            with _PRINT_LOCK:
                print(f"[{idx}/{total_files}] {rel_path} ({orig_size_str})")
                print(f"  -> [SKIPPED] Target '{target_out.name}' already exists ({format_bytes(target_size)}). Use --force to recompress.")
                if not keep_original and not dry_run:
                    print(f"     Removing redundant source file '{media_file.name}'...")
                    try:
                        media_file.unlink()
                        print(f"     Removed redundant source: {media_file.name}")
                    except Exception as exc:
                        print(f"     Could not remove source: {exc}")
                print()
            return "skipped", orig_size, target_size

    # Probe container
    probe = probe_media_file(media_file)
    if not probe.is_valid:
        with _PRINT_LOCK:
            print(f"[{idx}/{total_files}] {rel_path} ({orig_size_str})")
            print(f"  -> [ERROR] Failed to inspect streams: {probe.error_msg}")
            print(f"     Skipping file. Original file remains untouched.\n")
        return "failed", orig_size, 0.0

    # Handle audio-only lossy files
    if not probe.has_video and media_file.suffix.lower() in AUDIO_LOSSY_EXTENSIONS:
        if media_file.suffix.lower() == target_ext:
            with _PRINT_LOCK:
                print(f"[{idx}/{total_files}] {rel_path} ({orig_size_str})")
                print(f"  -> [SKIPPED] Already in target format ({target_ext}).\n")
            return "skipped", orig_size, orig_size
        elif not convert_lossy:
            with _PRINT_LOCK:
                print(f"[{idx}/{total_files}] {rel_path} ({orig_size_str})")
                print(f"  -> [SKIPPED] Audio-only lossy file ({media_file.suffix}). Transcoding lossy to lossy could degrade quality.")
                print(f"     (Use --convert-lossy to force conversion).\n")
            return "skipped", orig_size, orig_size

    with _PRINT_LOCK:
        print(f"[{idx}/{total_files}] {rel_path} ({orig_size_str})")
        print(f"  Selected Audio : {probe.best_audio_desc}")
        if probe.has_video:
            print(f"  Stripping Video: Yes ({output_format.upper()} conversion)")
        elif media_file.suffix.lower() == ".flac":
            print(f"  Audio Re-encode: Yes (Converting FLAC -> {output_format.upper()})")
        else:
            print(f"  Audio Re-encode: Yes (audio-only source)")

    # Lead-in bumper trimming (skip if source is already a pre-trimmed .flac)
    trim_start_sec = 0.0
    if intro_patterns and not disable_trim and media_file.suffix.lower() != ".flac":
        match = detect_intro_match(
            media_file=media_file,
            patterns=intro_patterns,
            min_confidence=min_intro_score
        )
        if match is not None:
            pat, offset_sec, trim_sec, conf = match
            trim_start_sec = trim_sec
            with _PRINT_LOCK:
                print(f"  Lead-In Detected: Matched '{pat.name}' (offset: {offset_sec:.2f}s, confidence: {conf:.2f})")
                print(f"                    Trimming {trim_start_sec:.2f}s from start of audio")

    if dry_run:
        trim_msg = f" (trimming first {trim_start_sec:.2f}s)" if trim_start_sec > 0.0 else ""
        with _PRINT_LOCK:
            print(f"  [DRY RUN] Would convert to '{target_out.name}'{trim_msg} and {'keep' if keep_original else 'delete'} '{media_file.name}'.\n")
        est_out = min(orig_size, 200 * 1024 * 1024) if output_format == "aac" else min(orig_size, 600 * 1024 * 1024)
        return "compressed", orig_size, est_out

    # Encode
    t0 = time.time()
    success, o_bytes, n_bytes, err = compress_file(
        source_file=media_file,
        probe=probe,
        target_file=target_out,
        output_format=output_format,
        keep_original=keep_original,
        trim_start_sec=trim_start_sec,
        custom_bitrate=custom_bitrate
    )
    elapsed = time.time() - t0

    with _PRINT_LOCK:
        if not success:
            print(f"  -> [FAILED] {err}")
            print(f"     Original file was left completely intact.\n")
            return "failed", orig_size, 0.0
        else:
            saved_bytes = o_bytes - n_bytes
            pct_saved = (saved_bytes / o_bytes * 100.0) if o_bytes > 0 else 0.0
            print(f"  -> [SUCCESS] {format_bytes(o_bytes)} -> {format_bytes(n_bytes)} in {elapsed:.1f}s")
            print(f"     Saved: {format_bytes(saved_bytes)} ({pct_saved:.1f}% reduction)")
            if not keep_original:
                print(f"     Replaced '{media_file.name}' with '{target_out.name}'")
            else:
                print(f"     Created '{target_out.name}' (original preserved)")
            print()
            return "compressed", o_bytes, n_bytes


# ---------------------------------------------------------------------------
# Main Routine
# ---------------------------------------------------------------------------
def run_compression(
    raw_dir: Path,
    output_format: str = "aac",
    dry_run: bool = False,
    keep_original: bool = False,
    force: bool = False,
    convert_lossy: bool = False,
    flatten: bool = False,
    single_filter: Optional[str] = None,
    disable_trim: bool = False,
    intro_dir: Optional[Path] = None,
    min_intro_score: float = 0.65,
    custom_bitrate: Optional[str] = None,
    jobs: int = 4
) -> int:
    """Execute audio compression on all matching files in raw_dir."""
    if not check_tool("ffmpeg"):
        logger.error("FFmpeg not found on system PATH. Please install FFmpeg to use this script.")
        return 1

    if not check_tool("ffprobe"):
        logger.warning("ffprobe not found on PATH. Stream selection and container probing will be limited.")

    if not raw_dir.exists():
        logger.error("Raw Audio directory does not exist: %s", raw_dir)
        return 1

    target_ext = ".m4a" if output_format.lower() == "aac" else ".flac"

    # Load reference intro patterns for lead-in bumper trimming
    intro_patterns: List[IntroPattern] = []
    if not disable_trim:
        ref_files = find_reference_intro_files(raw_dir, intro_dir)
        for rf in ref_files:
            pat = load_intro_pattern(rf)
            if pat is not None:
                intro_patterns.append(pat)

    # Scan all media files recursively (excluding temp files)
    candidate_files = [
        f for f in raw_dir.rglob("*")
        if f.is_file()
        and f.suffix.lower() in ALL_MEDIA_EXTENSIONS
        and not f.name.endswith(".temp.m4a")
        and not f.name.endswith(".temp.flac")
    ]

    if single_filter:
        s_lower = single_filter.lower()
        candidate_files = [f for f in candidate_files if s_lower in f.name.lower()]

    if not candidate_files:
        logger.info("No media files found in: %s", raw_dir)
        print(f"\nPlace your movie files (.mp4, .mkv, .flac, etc.) in: {raw_dir}\n")
        return 0

    trim_status = "DISABLED (--no-trim)" if disable_trim else (
        f"ENABLED ({len(intro_patterns)} pattern(s) loaded)" if intro_patterns
        else "ENABLED (No reference intro files found)"
    )

    fmt_desc = "Variation 5A Tuned AAC (320k-640k dynamic, fast search, PNS=0, TNS=0)" if output_format == "aac" else "16-bit FLAC Level 12"

    print("=" * 70)
    print("  SPOOKCUE RAW AUDIO COMPRESSOR")
    print("=" * 70)
    print(f" Directory        : {raw_dir}")
    print(f" Target Format    : {output_format.upper()} ({target_ext}) -> {fmt_desc}")
    print(f" Parallel Workers : {jobs} concurrent worker(s)")
    print(f" Mode             : {'KEEP ORIGINALS' if keep_original else 'REPLACE (Delete source files after verification)'}")
    print(f" Dry Run          : {'YES (No files modified)' if dry_run else 'NO'}")
    print(f" Convert Lossy    : {'YES' if convert_lossy else 'NO (Preserves existing MP3 files)'}")
    print(f" Subfolder Flatten: {'YES (Output directly to Raw Audio/)' if flatten else 'NO (Preserve relative subfolders)'}")
    print(f" Lead-In Trimming : {trim_status}")
    print(f" Total Candidates : {len(candidate_files)} file(s)")
    print("=" * 70 + "\n")

    files_compressed = 0
    files_skipped = 0
    files_failed = 0
    total_orig_bytes = 0.0
    total_new_bytes = 0.0

    start_total_time = time.time()

    if jobs <= 1:
        # Sequential execution
        for idx, media_file in enumerate(candidate_files, start=1):
            status, o_bytes, n_bytes = process_single_candidate(
                idx=idx,
                total_files=len(candidate_files),
                media_file=media_file,
                raw_dir=raw_dir,
                output_format=output_format,
                target_ext=target_ext,
                keep_original=keep_original,
                dry_run=dry_run,
                force=force,
                convert_lossy=convert_lossy,
                flatten=flatten,
                intro_patterns=intro_patterns,
                disable_trim=disable_trim,
                min_intro_score=min_intro_score,
                custom_bitrate=custom_bitrate
            )
            if status == "compressed":
                files_compressed += 1
                total_orig_bytes += o_bytes
                total_new_bytes += n_bytes
            elif status == "skipped":
                files_skipped += 1
                total_orig_bytes += o_bytes
                total_new_bytes += n_bytes
            else:
                files_failed += 1
    else:
        # Multi-worker parallel execution
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as executor:
            future_to_file = {
                executor.submit(
                    process_single_candidate,
                    idx,
                    len(candidate_files),
                    media_file,
                    raw_dir,
                    output_format,
                    target_ext,
                    keep_original,
                    dry_run,
                    force,
                    convert_lossy,
                    flatten,
                    intro_patterns,
                    disable_trim,
                    min_intro_score,
                    custom_bitrate
                ): media_file
                for idx, media_file in enumerate(candidate_files, start=1)
            }

            for future in concurrent.futures.as_completed(future_to_file):
                try:
                    status, o_bytes, n_bytes = future.result()
                    if status == "compressed":
                        files_compressed += 1
                        total_orig_bytes += o_bytes
                        total_new_bytes += n_bytes
                    elif status == "skipped":
                        files_skipped += 1
                        total_orig_bytes += o_bytes
                        total_new_bytes += n_bytes
                    else:
                        files_failed += 1
                except Exception as exc:
                    mf = future_to_file[future]
                    with _PRINT_LOCK:
                        print(f"[ERROR] Worker exception on {mf.name}: {exc}\n")
                    files_failed += 1

    total_elapsed = time.time() - start_total_time
    total_saved = max(0.0, total_orig_bytes - total_new_bytes)
    total_pct = (total_saved / total_orig_bytes * 100.0) if total_orig_bytes > 0 else 0.0

    print("=" * 70)
    print("  COMPRESSION SUMMARY")
    print("=" * 70)
    print(f" Files Scanned     : {len(candidate_files)}")
    print(f" Files Compressed  : {files_compressed}")
    print(f" Files Skipped     : {files_skipped}")
    print(f" Files Failed      : {files_failed}")
    print(f" Total Time        : {total_elapsed / 60:.1f} minutes ({total_elapsed:.1f} seconds)")
    print(f" Original Space    : {format_bytes(total_orig_bytes)}")
    print(f" Compressed Space  : {format_bytes(total_new_bytes)}")
    print(f" Total Space Saved : {format_bytes(total_saved)} ({total_pct:.1f}% reduction)")
    print("=" * 70 + "\n")

    return 0 if (dry_run or files_failed == 0 or files_compressed > 0) else 1


# ---------------------------------------------------------------------------
# CLI Entry Point
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compress video and audio files in Raw Audio to Tuned AAC (.m4a) or FLAC and replace originals."
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=None,
        help="Path to the 'Raw Audio' directory (defaults to 'Raw Audio' beside this script)."
    )
    parser.add_argument(
        "--format",
        choices=["aac", "flac"],
        default="aac",
        help="Target audio compression format: 'aac' (Variation 5A Tuned AAC 320k-640k, default) or 'flac' (16-bit FLAC level 12)."
    )
    parser.add_argument(
        "--bitrate",
        type=str,
        default=None,
        help="Custom AAC bitrate override (e.g. '320k', '448k', '640k'). Default dynamically scales from 320k to 640k based on channel count."
    )
    parser.add_argument(
        "-j", "--jobs",
        type=int,
        default=max(1, min(6, (os.cpu_count() or 4) // 2)),
        help="Number of concurrent worker threads for parallel file compression (default: half CPU cores, max 6)."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate the compression without modifying or deleting any files."
    )
    parser.add_argument(
        "--keep-original",
        action="store_true",
        help="Extract compressed audio but do NOT delete original video/audio files."
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Recompress files even if target output already exists."
    )
    parser.add_argument(
        "--convert-lossy",
        action="store_true",
        help="Also convert audio-only lossy files (.mp3) to target format."
    )
    parser.add_argument(
        "--flatten",
        action="store_true",
        help="Save all output audio files directly into root Raw Audio/ folder."
    )
    parser.add_argument(
        "--single",
        type=str,
        default=None,
        help="Process only a specific file matching name or TMDB ID."
    )
    parser.add_argument(
        "--intro-dir",
        type=Path,
        default=None,
        help="Custom folder containing reference lead-in / bumper media files (.mp4, .mp3, etc.)."
    )
    parser.add_argument(
        "--no-trim",
        action="store_true",
        help="Disable automatic lead-in / bumper detection and trimming."
    )
    parser.add_argument(
        "--min-intro-score",
        type=float,
        default=0.65,
        help="Minimum similarity score threshold for lead-in bumper detection (default: 0.65)."
    )

    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    raw_dir = args.raw_dir or (script_dir / "Raw Audio")

    try:
        sys.exit(
            run_compression(
                raw_dir=raw_dir,
                output_format=args.format,
                dry_run=args.dry_run,
                keep_original=args.keep_original,
                force=args.force,
                convert_lossy=args.convert_lossy,
                flatten=args.flatten,
                single_filter=args.single,
                disable_trim=args.no_trim,
                intro_dir=args.intro_dir,
                min_intro_score=args.min_intro_score,
                custom_bitrate=args.bitrate,
                jobs=args.jobs
            )
        )
    except KeyboardInterrupt:
        print("\n[ABORTED] Compression cancelled by user.")
        cleanup_temp_files()
        sys.exit(130)


if __name__ == "__main__":
    main()
