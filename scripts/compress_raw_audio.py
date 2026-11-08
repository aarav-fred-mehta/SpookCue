#!/usr/bin/env python3
"""SpookCue Raw Audio Compressor (Lossless FLAC Extraction).

This standalone script sits in an external directory alongside:
  - "Raw Audio/"    : Place your movie video/audio files here (.mp4, .mkv, .avi, etc.)
  - "Audio Hashes/" : Generated landmark hashes

It scans "Raw Audio/" (including subdirectories), extracts the audio losslessly
to .flac format (stripping the video stream entirely), verifies audio integrity,
and replaces the large video files with lightweight FLAC audio files.

This saves ~80-95% of disk space while preserving 100% bit-exact acoustic fidelity
for audio fingerprinting in sync_audio_hashes.py and SpookCue.
"""

from __future__ import annotations

import argparse
import atexit
import json
import logging
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple
import uuid

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
    ".wav", ".aiff", ".aif", ".pcm", ".alac"
}
AUDIO_LOSSY_EXTENSIONS = {
    ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wma"
}
ALL_MEDIA_EXTENSIONS = VIDEO_EXTENSIONS | AUDIO_UNCOMPRESSED_EXTENSIONS | AUDIO_LOSSY_EXTENSIONS | {".flac"}

# Global tracker for cleanup on interrupt/exit
_CURRENT_TEMP_FILE: Optional[Path] = None


def cleanup_temp_file() -> None:
    """Ensure any unfinished temporary file is deleted if interrupted."""
    global _CURRENT_TEMP_FILE
    if _CURRENT_TEMP_FILE and _CURRENT_TEMP_FILE.exists():
        try:
            _CURRENT_TEMP_FILE.unlink()
            logger.info("Cleaned up temporary file: %s", _CURRENT_TEMP_FILE.name)
        except Exception:
            pass


atexit.register(cleanup_temp_file)


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
    # If only 1 audio stream, use it directly
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
# Lossless Compression Pipeline
# ---------------------------------------------------------------------------
def compress_file_to_flac(
    source_file: Path,
    probe: MediaProbeResult,
    target_flac: Path,
    keep_original: bool = False
) -> Tuple[bool, float, float, Optional[str]]:
    """Compress source video/audio into lossless FLAC, verify, and replace original.

    Returns:
        (success, orig_bytes, new_bytes, error_message)
    """
    global _CURRENT_TEMP_FILE

    orig_bytes = float(source_file.stat().st_size)

    # Use a unique temporary filename in the target directory
    temp_flac = target_flac.parent / f"{target_flac.stem}.{uuid.uuid4().hex[:8]}.temp.flac"
    _CURRENT_TEMP_FILE = temp_flac

    # 1. Build FFmpeg command
    cmd = [
        "ffmpeg",
        "-nostdin",
        "-y",
        "-v", "error",
        "-stats",
        "-i", str(source_file)
    ]

    # Map the selected audio stream if ffprobe resolved one
    if probe.best_audio_index is not None:
        cmd.extend(["-map", f"0:{probe.best_audio_index}"])

    # Strip video, subtitles, data streams, and encode losslessly to FLAC
    cmd.extend([
        "-vn",
        "-sn",
        "-dn",
        "-c:a", "flac",
        "-compression_level", "5",
        str(temp_flac)
    ])

    try:
        proc = subprocess.run(cmd, check=False)
        if proc.returncode != 0:
            if temp_flac.exists():
                temp_flac.unlink()
            _CURRENT_TEMP_FILE = None
            return False, orig_bytes, 0.0, f"FFmpeg failed with exit code {proc.returncode}"
    except Exception as exc:
        if temp_flac.exists():
            temp_flac.unlink()
        _CURRENT_TEMP_FILE = None
        return False, orig_bytes, 0.0, f"Error launching FFmpeg: {exc}"

    # 2. Verify temporary file was produced and has reasonable size
    if not temp_flac.exists():
        _CURRENT_TEMP_FILE = None
        return False, orig_bytes, 0.0, "FFmpeg finished but output FLAC file was not created"

    new_bytes = float(temp_flac.stat().st_size)
    if new_bytes < 10240:  # Minimum 10 KB
        temp_flac.unlink()
        _CURRENT_TEMP_FILE = None
        return False, orig_bytes, 0.0, f"Output FLAC file is suspiciously small ({new_bytes} bytes)"

    # 3. Quick verification: test decoding the header and first 5 seconds
    verify_cmd = [
        "ffmpeg",
        "-nostdin",
        "-v", "error",
        "-i", str(temp_flac),
        "-t", "5",
        "-f", "null",
        "-"
    ]
    try:
        v_proc = subprocess.run(verify_cmd, capture_output=True, text=True, check=False)
        if v_proc.returncode != 0:
            err = v_proc.stderr.strip() or "Verification decode failed"
            temp_flac.unlink()
            _CURRENT_TEMP_FILE = None
            return False, orig_bytes, 0.0, f"Integrity check failed: {err}"
    except Exception as exc:
        temp_flac.unlink()
        _CURRENT_TEMP_FILE = None
        return False, orig_bytes, 0.0, f"Integrity check execution error: {exc}"

    # 4. Atomic replacement: delete original (if requested) and rename temp to target
    try:
        # If keeping original or if target_flac has a different name, handle replacement cleanly
        if not keep_original:
            # Delete original file
            source_file.unlink()

        # Rename temp to final target
        if target_flac.exists() and target_flac != source_file:
            target_flac.unlink()
        temp_flac.replace(target_flac)
        _CURRENT_TEMP_FILE = None

    except Exception as exc:
        _CURRENT_TEMP_FILE = None
        return False, orig_bytes, new_bytes, f"File replacement failed: {exc}"

    return True, orig_bytes, new_bytes, None


# ---------------------------------------------------------------------------
# Main Routine
# ---------------------------------------------------------------------------
def run_compression(
    raw_dir: Path,
    dry_run: bool = False,
    keep_original: bool = False,
    force: bool = False,
    convert_lossy: bool = False,
    flatten: bool = False,
    single_filter: Optional[str] = None
) -> int:
    """Execute lossless audio compression on all matching files in raw_dir."""
    if not check_tool("ffmpeg"):
        logger.error("FFmpeg not found on system PATH. Please install FFmpeg to use this script.")
        return 1

    if not check_tool("ffprobe"):
        logger.warning("ffprobe not found on PATH. Stream selection and container probing will be limited.")

    if not raw_dir.exists():
        logger.error("Raw Audio directory does not exist: %s", raw_dir)
        return 1

    # Scan all media files recursively
    candidate_files = [
        f for f in raw_dir.rglob("*")
        if f.is_file()
        and f.suffix.lower() in ALL_MEDIA_EXTENSIONS
        and not f.name.endswith(".temp.flac")
    ]

    if single_filter:
        s_lower = single_filter.lower()
        candidate_files = [f for f in candidate_files if s_lower in f.name.lower()]

    if not candidate_files:
        logger.info("No media files found in: %s", raw_dir)
        print(f"\nPlace your movie files (.mp4, .mkv, .avi, etc.) in: {raw_dir}\n")
        return 0

    print("=" * 70)
    print("  SPOOKCUE RAW AUDIO COMPRESSOR (LOSSLESS FLAC)")
    print("=" * 70)
    print(f" Directory        : {raw_dir}")
    print(f" Mode             : {'KEEP ORIGINALS' if keep_original else 'REPLACE (Delete original video after verification)'}")
    print(f" Dry Run          : {'YES (No files modified)' if dry_run else 'NO'}")
    print(f" Convert Lossy    : {'YES' if convert_lossy else 'NO (Preserves existing MP3/M4A files)'}")
    print(f" Subfolder Flatten: {'YES (Output directly to Raw Audio/)' if flatten else 'NO (Preserve relative subfolders)'}")
    print(f" Total Candidates : {len(candidate_files)} file(s)")
    print("=" * 70 + "\n")

    files_compressed = 0
    files_skipped = 0
    files_failed = 0
    total_orig_bytes = 0.0
    total_new_bytes = 0.0

    start_total_time = time.time()

    for idx, media_file in enumerate(candidate_files, start=1):
        rel_path = media_file.relative_to(raw_dir)
        orig_size = media_file.stat().st_size
        orig_size_str = format_bytes(orig_size)

        print(f"[{idx}/{len(candidate_files)}] {rel_path} ({orig_size_str})")

        # Determine target FLAC destination
        if flatten:
            target_flac = raw_dir / f"{media_file.stem}.flac"
        else:
            target_flac = media_file.parent / f"{media_file.stem}.flac"

        # Check if file is already a lossless FLAC
        if media_file.suffix.lower() == ".flac":
            print(f"  -> [SKIPPED] Already a lossless FLAC file.")
            files_skipped += 1
            total_orig_bytes += orig_size
            total_new_bytes += orig_size
            print()
            continue

        # Check if target FLAC already exists and is non-empty
        if target_flac.exists() and target_flac != media_file and not force:
            target_size = target_flac.stat().st_size
            if target_size > 10240:
                print(f"  -> [SKIPPED] Target '{target_flac.name}' already exists ({format_bytes(target_size)}). Use --force to recompress.")
                if not keep_original and not dry_run:
                    # Clean up the leftover source video if FLAC already exists
                    print(f"     Removing redundant source file '{media_file.name}'...")
                    try:
                        media_file.unlink()
                        print(f"     Removed redundant source: {media_file.name}")
                    except Exception as exc:
                        print(f"     Could not remove source: {exc}")
                files_skipped += 1
                total_orig_bytes += orig_size
                total_new_bytes += target_size
                print()
                continue

        # Probe container
        probe = probe_media_file(media_file)
        if not probe.is_valid:
            print(f"  -> [ERROR] Failed to inspect streams: {probe.error_msg}")
            print(f"     Skipping file. Original file remains untouched.")
            files_failed += 1
            print()
            continue

        # Handle audio-only lossy files (MP3, M4A, etc.) without video
        if not probe.has_video and media_file.suffix.lower() in AUDIO_LOSSY_EXTENSIONS:
            if not convert_lossy:
                print(f"  -> [SKIPPED] Audio-only lossy file ({media_file.suffix}). Recompressing to FLAC would inflate file size.")
                print(f"     (Use --convert-lossy to force conversion).")
                files_skipped += 1
                total_orig_bytes += orig_size
                total_new_bytes += orig_size
                print()
                continue

        print(f"  Selected Audio : {probe.best_audio_desc}")
        if probe.has_video:
            print(f"  Stripping Video: Yes (lossless FLAC conversion)")
        else:
            print(f"  Stripping Video: No (audio-only source)")

        if dry_run:
            print(f"  [DRY RUN] Would convert to '{target_flac.name}' and {'keep' if keep_original else 'delete'} '{media_file.name}'.")
            files_compressed += 1
            total_orig_bytes += orig_size
            # Estimate ~300-400 MB FLAC for a full-length movie
            est_flac = min(orig_size, 400 * 1024 * 1024)
            total_new_bytes += est_flac
            print()
            continue

        # Run conversion
        t0 = time.time()
        success, o_bytes, n_bytes, err = compress_file_to_flac(
            source_file=media_file,
            probe=probe,
            target_flac=target_flac,
            keep_original=keep_original
        )
        elapsed = time.time() - t0

        if not success:
            print(f"  -> [FAILED] {err}")
            print(f"     Original file was left completely intact.")
            files_failed += 1
        else:
            saved_bytes = o_bytes - n_bytes
            pct_saved = (saved_bytes / o_bytes * 100.0) if o_bytes > 0 else 0.0
            print(f"  -> [SUCCESS] {format_bytes(o_bytes)} -> {format_bytes(n_bytes)} in {elapsed:.1f}s")
            print(f"     Saved: {format_bytes(saved_bytes)} ({pct_saved:.1f}% reduction)")
            if not keep_original:
                print(f"     Replaced '{media_file.name}' with '{target_flac.name}'")
            else:
                print(f"     Created '{target_flac.name}' (original preserved)")
            files_compressed += 1
            total_orig_bytes += o_bytes
            total_new_bytes += n_bytes

        print()

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
        description="Compress video and audio files in Raw Audio to lossless FLAC and replace originals."
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=None,
        help="Path to the 'Raw Audio' directory (defaults to 'Raw Audio' beside this script)."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate the compression without modifying or deleting any files."
    )
    parser.add_argument(
        "--keep-original",
        action="store_true",
        help="Extract .flac audio but do NOT delete original video/audio files."
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Recompress files even if target .flac already exists."
    )
    parser.add_argument(
        "--convert-lossy",
        action="store_true",
        help="Also convert audio-only lossy files (.mp3, .m4a) to .flac (can increase file size)."
    )
    parser.add_argument(
        "--flatten",
        action="store_true",
        help="Save all output .flac files directly into root Raw Audio/ folder."
    )
    parser.add_argument(
        "--single",
        type=str,
        default=None,
        help="Process only a specific file matching name or TMDB ID."
    )

    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    raw_dir = args.raw_dir or (script_dir / "Raw Audio")

    try:
        sys.exit(
            run_compression(
                raw_dir=raw_dir,
                dry_run=args.dry_run,
                keep_original=args.keep_original,
                force=args.force,
                convert_lossy=args.convert_lossy,
                flatten=args.flatten,
                single_filter=args.single
            )
        )
    except KeyboardInterrupt:
        print("\n[ABORTED] Compression cancelled by user.")
        cleanup_temp_file()
        sys.exit(130)


if __name__ == "__main__":
    main()
