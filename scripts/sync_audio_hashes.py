#!/usr/bin/env python3
"""SpookCue Audio Hasher and GitHub Sync Pipeline.

This standalone script sits in an external directory alongside:
  - "Raw Audio/"    : Place your .mp4 / .mp3 / .m4a movie files here (named by tmdb_id, e.g. 447332.mp4)
  - "Audio Hashes/" : Generated landmark hashes are saved here as <tmdb_id>.json

Once hashes are extracted, the script automatically commits and pushes them to GitHub
so SpookCue can sync with dialogue-light movies.

Works with:
  1. Local SpookCue Git repo (copies to data/sync/<tmdb_id>.json, commits & pushes)
  2. GitHub REST API (if GITHUB_TOKEN is set, uploads directly to GitHub without git clone)
"""

from __future__ import annotations

import argparse
import base64
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any, Dict, List, Optional, Tuple
import urllib.error
import urllib.request

try:
    import numpy as np
except ImportError:
    print("[ERROR] 'numpy' is required. Please install it with: pip install numpy")
    sys.exit(1)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("audio_hasher")

# ---------------------------------------------------------------------------
# Acoustic Fingerprinting Parameters (must match SpookCue AcousticFingerprinter)
# ---------------------------------------------------------------------------
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
MIN_PEAK_MAGNITUDE = 10.0

AUDIO_EXTENSIONS = {
    ".mp4", ".mp3", ".m4a", ".mkv", ".wav",
    ".flac", ".aac", ".ogg", ".webm", ".avi"
}

CONFIG_FILENAME = "config.json"
DEFAULT_SPOOKCUE_PATH = r"C:\Users\aarav\Documents\Hobbies\Code\Tools\SpookCue"
DEFAULT_GITHUB_REPO = "aarav-fred-mehta/SpookCue"


# ---------------------------------------------------------------------------
# Configuration Management
# ---------------------------------------------------------------------------
def load_or_create_config(base_dir: Path) -> Dict[str, Any]:
    """Load config.json from the script directory or generate default."""
    config_file = base_dir / CONFIG_FILENAME
    default_config: Dict[str, Any] = {
        "spookcue_repo_path": DEFAULT_SPOOKCUE_PATH if Path(DEFAULT_SPOOKCUE_PATH).exists() else "",
        "github_repo": DEFAULT_GITHUB_REPO,
        "github_token": os.environ.get("GITHUB_TOKEN", ""),
        "git_branch": "main",
        "auto_push": True,
    }

    if config_file.exists():
        try:
            with open(config_file, "r", encoding="utf-8") as f:
                user_config = json.load(f)
                default_config.update(user_config)
                return default_config
        except Exception as exc:
            logger.warning("Could not read %s: %s. Using default settings.", config_file, exc)

    # Save default config for convenience
    try:
        with open(config_file, "w", encoding="utf-8") as f:
            json.dump(default_config, f, indent=2)
    except Exception:
        pass

    return default_config


# ---------------------------------------------------------------------------
# Audio Decoding
# ---------------------------------------------------------------------------
def load_audio_ffmpeg(file_path: Path, target_sr: int = SAMPLE_RATE) -> np.ndarray:
    """Decode any audio/video file directly into 16kHz mono float32 PCM via FFmpeg."""
    cmd = [
        "ffmpeg",
        "-nostdin",
        "-v", "error",
        "-i", str(file_path),
        "-f", "f32le",
        "-acodec", "pcm_f32le",
        "-ac", "1",
        "-ar", str(target_sr),
        "pipe:1"
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout_bytes, stderr_bytes = proc.communicate()

    if proc.returncode != 0:
        err_msg = stderr_bytes.decode("utf-8", errors="ignore").strip()
        raise RuntimeError(f"FFmpeg failed with exit code {proc.returncode}: {err_msg}")

    if not stdout_bytes:
        raise ValueError("FFmpeg produced empty audio stream.")

    return np.frombuffer(stdout_bytes, dtype=np.float32)


def load_audio_fallback(file_path: Path, target_sr: int = SAMPLE_RATE) -> np.ndarray:
    """Fallback decoder using soundfile or wave if ffmpeg is unavailable."""
    try:
        import soundfile as sf
        data, sr = sf.read(str(file_path), dtype="float32")
        if data.ndim > 1:
            data = data.mean(axis=1)
        if sr != target_sr:
            indices = np.round(np.arange(0, len(data), sr / target_sr)).astype(int)
            indices = indices[indices < len(data)]
            data = data[indices]
        return data
    except Exception:
        import wave
        with wave.open(str(file_path), "rb") as wf:
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
            if rate != target_sr:
                indices = np.round(np.arange(0, len(samples), rate / target_sr)).astype(int)
                indices = indices[indices < len(samples)]
                samples = samples[indices]
            return samples


def load_audio(file_path: Path, target_sr: int = SAMPLE_RATE) -> np.ndarray:
    """Load audio from file using FFmpeg, with automatic fallback."""
    try:
        return load_audio_ffmpeg(file_path, target_sr)
    except FileNotFoundError:
        logger.warning("FFmpeg executable not found on PATH. Falling back to soundfile/wave...")
        return load_audio_fallback(file_path, target_sr)


# ---------------------------------------------------------------------------
# Landmark Fingerprint Extraction
# ---------------------------------------------------------------------------
def encode_hash(f1: int, f2: int, delta_frames: int) -> int:
    """Pack anchor frequency, target frequency, and frame delta into a 32-bit hash."""
    b1 = f1 & 0x1FF
    b2 = f2 & 0x1FF
    dt = delta_frames & 0x3F
    return (b1) | (b2 << 9) | (dt << 18)


def extract_landmark_hashes(audio_samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> List[Dict[str, int]]:
    """Extract spectral peak constellation landmark pairs compatible with SpookCue."""
    if len(audio_samples) < FFT_SIZE:
        return []

    window = np.hanning(FFT_SIZE)
    num_frames = (len(audio_samples) - FFT_SIZE) // HOP_SIZE + 1
    peaks: List[Tuple[int, int, float]] = []

    for frame_idx in range(num_frames):
        offset = frame_idx * HOP_SIZE
        frame = audio_samples[offset:offset + FFT_SIZE] * window
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
                peaks.append((frame_idx, best_bin, max_val))

    peaks.sort(key=lambda p: p[0])
    hashes: List[Dict[str, int]] = []

    for i in range(len(peaks)):
        anchor_frame, anchor_bin, _ = peaks[i]
        pairs_found = 0
        for j in range(i + 1, len(peaks)):
            target_frame, target_bin, _ = peaks[j]
            delta_frames = target_frame - anchor_frame
            if delta_frames > MAX_DELTA_FRAMES:
                break
            if delta_frames >= MIN_DELTA_FRAMES:
                h = encode_hash(anchor_bin, target_bin, delta_frames)
                t_ms = (anchor_frame * HOP_SIZE * 1000) // sample_rate
                hashes.append({"h": int(h), "t": int(t_ms)})
                pairs_found += 1
                if pairs_found >= FAN_VALUE:
                    break

    return hashes


# ---------------------------------------------------------------------------
# TMDB ID Resolution from Filename
# ---------------------------------------------------------------------------
def extract_tmdb_id(filename: str) -> Optional[int]:
    """Extract numeric TMDB ID from filename (e.g. '447332.mp4', '447332 - A Quiet Place.mp3')."""
    stem = Path(filename).stem
    m = re.search(r"\b(\d{3,8})\b", stem)
    if m:
        return int(m.group(1))
    return None


# ---------------------------------------------------------------------------
# GitHub Synchronization Methods
# ---------------------------------------------------------------------------
def sync_via_local_git(
    spookcue_path: Path,
    hash_files: List[Tuple[int, Path]],
    branch: str = "main",
    dry_run: bool = False
) -> bool:
    """Copy generated hashes into local SpookCue repo and execute git commit & push."""
    if not spookcue_path.exists():
        logger.warning("SpookCue repository path does not exist: %s", spookcue_path)
        return False

    sync_dir = spookcue_path / "data" / "sync"
    catalog_file = spookcue_path / "data" / "catalog.json"
    movies_dir = spookcue_path / "data" / "movies"

    if not sync_dir.exists():
        logger.warning("Could not find data/sync inside %s", spookcue_path)
        return False

    staged_files: List[Path] = []
    tmdb_ids_updated: List[int] = []

    for tmdb_id, hash_path in hash_files:
        dest_sync = sync_dir / f"{tmdb_id}.json"

        if dry_run:
            logger.info("[DRY RUN] Would copy %s -> %s", hash_path.name, dest_sync)
            staged_files.append(dest_sync)
            tmdb_ids_updated.append(tmdb_id)
            continue

        if not hash_path.exists():
            continue

        content = hash_path.read_bytes()
        dest_sync.write_bytes(content)
        staged_files.append(dest_sync)
        tmdb_ids_updated.append(tmdb_id)

        # Update sync_type in data/movies/<tmdb_id>.json if movie details exist
        movie_doc_file = movies_dir / f"{tmdb_id}.json"
        if movie_doc_file.exists():
            try:
                doc = json.loads(movie_doc_file.read_text(encoding="utf-8"))
                if doc.get("sync_type") != "audio_hash":
                    doc["sync_type"] = "audio_hash"
                    movie_doc_file.write_text(json.dumps(doc, indent=2), encoding="utf-8")
                    staged_files.append(movie_doc_file)
            except Exception as e:
                logger.debug("Could not update movie doc for %d: %s", tmdb_id, e)

        # Update sync_type in data/catalog.json if entry exists
        if catalog_file.exists():
            try:
                cat = json.loads(catalog_file.read_text(encoding="utf-8"))
                changed = False
                for item in cat:
                    if item.get("tmdb_id") == tmdb_id and item.get("sync_type") != "audio_hash":
                        item["sync_type"] = "audio_hash"
                        item["has_subtitles"] = False
                        changed = True
                if changed:
                    catalog_file.write_text(json.dumps(cat, indent=2), encoding="utf-8")
                    staged_files.append(catalog_file)
                    # Bump data/version.json to trigger DeltaSync in app
                    version_file = spookcue_path / "data" / "version.json"
                    if version_file.exists():
                        try:
                            from datetime import datetime, timezone
                            vdata = json.loads(version_file.read_text(encoding="utf-8"))
                            vdata["v"] = int(vdata.get("v", 1)) + 1
                            vdata["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                            version_file.write_text(json.dumps(vdata, indent=2), encoding="utf-8")
                            staged_files.append(version_file)
                        except Exception as e:
                            logger.debug("Could not bump version.json: %s", e)
            except Exception as e:
                logger.debug("Could not update catalog.json: %s", e)

    if not staged_files:
        return True

    if dry_run:
        logger.info("[DRY RUN] Would git add, commit, and push %d files to %s", len(staged_files), branch)
        return True

    try:
        # Check if working tree has changes
        status_proc = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=str(spookcue_path),
            capture_output=True,
            text=True
        )
        if not status_proc.stdout.strip():
            logger.info("Local SpookCue repository is already up to date (no git diff).")
            return True

        # Git Add
        add_cmd = ["git", "add"] + [str(p.relative_to(spookcue_path)) for p in staged_files]
        subprocess.run(add_cmd, cwd=str(spookcue_path), check=True, capture_output=True)

        # Git Commit
        id_str = ", ".join(str(tid) for tid in tmdb_ids_updated[:5])
        if len(tmdb_ids_updated) > 5:
            id_str += f" (+{len(tmdb_ids_updated) - 5} more)"
        commit_msg = f"feat(sync): add acoustic hashes for TMDB {id_str}"
        commit_proc = subprocess.run(
            ["git", "commit", "-m", commit_msg],
            cwd=str(spookcue_path),
            capture_output=True,
            text=True
        )
        if commit_proc.returncode != 0 and "nothing to commit" in commit_proc.stdout.lower():
            logger.info("Nothing new to commit in SpookCue repository.")
            return True

        logger.info("Created local commit: '%s'", commit_msg)

        # Git Push
        logger.info("Pushing to origin %s...", branch)
        push_proc = subprocess.run(
            ["git", "push", "origin", branch],
            cwd=str(spookcue_path),
            capture_output=True,
            text=True
        )
        if push_proc.returncode == 0:
            logger.info(" Successfully pushed acoustic hashes to SpookCue repository on GitHub!")
            return True
        else:
            logger.error("Git push failed: %s", push_proc.stderr.strip() or push_proc.stdout.strip())
            return False

    except Exception as exc:
        logger.error("Failed executing local Git commands: %s", exc)
        return False


def upload_via_github_api(
    github_repo: str,
    github_token: str,
    hash_files: List[Tuple[int, Path]],
    branch: str = "main",
    dry_run: bool = False
) -> bool:
    """Upload hash JSON files directly to GitHub using the GitHub REST Contents API."""
    if not github_token:
        logger.warning("No GitHub Token provided. Set GITHUB_TOKEN or 'github_token' in config.json.")
        return False

    headers = {
        "Authorization": f"token {github_token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "SpookCue-AudioHasher",
    }

    all_success = True

    for tmdb_id, hash_path in hash_files:
        path_in_repo = f"data/sync/{tmdb_id}.json"
        api_url = f"https://api.github.com/repos/{github_repo}/contents/{path_in_repo}"

        if dry_run:
            logger.info("[DRY RUN] Would upload %s to %s via GitHub API", hash_path.name, api_url)
            continue

        content_bytes = hash_path.read_bytes()
        encoded_content = base64.b64encode(content_bytes).decode("utf-8")

        # 1. Check if file already exists to obtain its current blob SHA (required for update)
        existing_sha = None
        try:
            req = urllib.request.Request(f"{api_url}?ref={branch}", headers=headers)
            with urllib.request.urlopen(req) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                existing_sha = data.get("sha")
        except urllib.error.HTTPError as err:
            if err.code != 404:
                logger.warning("Error querying GitHub API for %s: %s", path_in_repo, err)
        except Exception as exc:
            logger.debug("Error checking existing blob sha: %s", exc)

        # 2. Put file to GitHub
        payload: Dict[str, Any] = {
            "message": f"feat(sync): add acoustic hashes for TMDB {tmdb_id}",
            "content": encoded_content,
            "branch": branch,
        }
        if existing_sha:
            payload["sha"] = existing_sha

        try:
            req_data = json.dumps(payload).encode("utf-8")
            put_req = urllib.request.Request(api_url, data=req_data, headers=headers, method="PUT")
            with urllib.request.urlopen(put_req) as resp:
                if resp.status in (200, 201):
                    logger.info(" Successfully uploaded TMDB %d hashes to GitHub repo %s via API", tmdb_id, github_repo)
                else:
                    logger.warning("GitHub API returned status %s for TMDB %d", resp.status, tmdb_id)
                    all_success = False
        except Exception as exc:
            logger.error("Failed uploading TMDB %d to GitHub API: %s", tmdb_id, exc)
            all_success = False

    return all_success


# ---------------------------------------------------------------------------
# Main Pipeline
# ---------------------------------------------------------------------------
def run_hasher(
    script_dir: Path,
    spookcue_path: Optional[Path] = None,
    force: bool = False,
    no_push: bool = False,
    dry_run: bool = False
) -> int:
    """Execute hash generation for all raw audio files and push to GitHub."""
    raw_dir = script_dir / "Raw Audio"
    hashes_dir = script_dir / "Audio Hashes"

    # Ensure required directories exist
    raw_dir.mkdir(parents=True, exist_ok=True)
    hashes_dir.mkdir(parents=True, exist_ok=True)

    config = load_or_create_config(script_dir)
    effective_spookcue_path = spookcue_path or (Path(config["spookcue_repo_path"]) if config.get("spookcue_repo_path") else None)

    # 1. Scan for raw audio/video files
    raw_files = [f for f in raw_dir.iterdir() if f.is_file() and f.suffix.lower() in AUDIO_EXTENSIONS]

    if not raw_files:
        logger.info("No audio files found in: %s", raw_dir)
        print("\n" + "=" * 60)
        print("  SPOOKCUE AUDIO HASHER")
        print("=" * 60)
        print(f" Place your movie audio/video files in:\n   -> {raw_dir}")
        print("\n Name files with their numeric TMDB ID, for example:")
        print("   - 447332.mp4  (A Quiet Place)")
        print("   - 447332.mp3")
        print("   - 1038392.m4a")
        print("\n Supported formats: .mp4, .mp3, .m4a, .mkv, .wav, .flac, .aac")
        print("=" * 60 + "\n")
        return 0

    logger.info("Found %d audio file(s) in '%s'.", len(raw_files), raw_dir.name)

    processed_hashes: List[Tuple[int, Path]] = []

    # 2. Process each file
    for idx, raw_file in enumerate(raw_files, start=1):
        tmdb_id = extract_tmdb_id(raw_file.name)
        if not tmdb_id:
            logger.warning("[%d/%d] Skipping '%s': No numeric TMDB ID found in filename", idx, len(raw_files), raw_file.name)
            continue

        target_json = hashes_dir / f"{tmdb_id}.json"

        # Check if already generated
        if target_json.exists() and target_json.stat().st_size > 50 and not force:
            logger.info("[%d/%d] Hashes for TMDB %d already exist (%s); skipping (use --force to recompute)", idx, len(raw_files), tmdb_id, target_json.name)
            processed_hashes.append((tmdb_id, target_json))
            continue

        logger.info("[%d/%d] Decoding '%s' (TMDB ID %d)...", idx, len(raw_files), raw_file.name, tmdb_id)

        try:
            samples = load_audio(raw_file, target_sr=SAMPLE_RATE)
            duration_sec = len(samples) / SAMPLE_RATE
            logger.info("  Decoded %.1fs of audio (%.1f MB raw PCM). Extracting landmarks...", duration_sec, len(samples) * 4 / (1024 * 1024))

            hashes = extract_landmark_hashes(samples, sample_rate=SAMPLE_RATE)
            logger.info("  Extracted %d landmark hashes.", len(hashes))

            if not hashes:
                logger.warning("  No acoustic landmarks found in '%s'. Audio might be silent or below magnitude threshold.", raw_file.name)
                continue

            payload = {
                "tmdb_id": tmdb_id,
                "type": "audio_hash",
                "audio_hashes": hashes
            }

            if not dry_run:
                with open(target_json, "w", encoding="utf-8") as f:
                    json.dump(payload, f, separators=(",", ":"))
                logger.info("  Saved hashes to: %s (%d KB)", target_json.name, target_json.stat().st_size // 1024)
            else:
                logger.info("  [DRY RUN] Would write %d hashes to %s", len(hashes), target_json.name)

            processed_hashes.append((tmdb_id, target_json))

        except Exception as exc:
            logger.error("Failed processing '%s': %s", raw_file.name, exc, exc_info=True)

    if not processed_hashes:
        logger.info("No hash files generated.")
        return 0

    logger.info("Total %d movie hash file(s) ready in '%s'.", len(processed_hashes), hashes_dir.name)

    # 3. Synchronize with GitHub
    if no_push or not config.get("auto_push", True):
        logger.info("Skipping GitHub upload (--no-push or auto_push disabled). Hashes remain in '%s'.", hashes_dir)
        return 0

    logger.info("Synchronizing hashes with GitHub...")

    pushed = False

    # Attempt Method 1: Local SpookCue git repository
    if effective_spookcue_path and effective_spookcue_path.exists():
        logger.info("Attempting sync via local SpookCue repository at: %s", effective_spookcue_path)
        pushed = sync_via_local_git(
            spookcue_path=effective_spookcue_path,
            hash_files=processed_hashes,
            branch=config.get("git_branch", "main"),
            dry_run=dry_run
        )

    # Attempt Method 2: GitHub API fallback if local git not found or failed
    if not pushed and config.get("github_token"):
        logger.info("Attempting sync via GitHub REST API (repo: %s)...", config.get("github_repo"))
        pushed = upload_via_github_api(
            github_repo=config.get("github_repo", DEFAULT_GITHUB_REPO),
            github_token=config["github_token"],
            hash_files=processed_hashes,
            branch=config.get("git_branch", "main"),
            dry_run=dry_run
        )

    # Attempt Method 3: Self git repository in script folder
    if not pushed and (script_dir / ".git").exists():
        try:
            logger.info("Detected local .git repository in script directory. Committing and pushing...")
            subprocess.run(["git", "add", "Audio Hashes"], cwd=str(script_dir), check=True)
            subprocess.run(["git", "commit", "-m", "chore: update audio hashes"], cwd=str(script_dir))
            subprocess.run(["git", "push"], cwd=str(script_dir), check=True)
            logger.info(" Successfully pushed to local folder's git repository.")
            pushed = True
        except Exception as e:
            logger.warning("Local git repository push failed: %s", e)

    if not pushed and not dry_run:
        print("\n" + "!" * 60)
        print("  NOTICE: Hashes generated locally, but could not push to GitHub.")
        print("  To enable automatic GitHub push, configure either:")
        print(f"  1. 'spookcue_repo_path' in {CONFIG_FILENAME} pointing to your SpookCue clone")
        print(f"  2. 'github_token' in {CONFIG_FILENAME} with a GitHub Personal Access Token")
        print("!" * 60 + "\n")

    return 0


def main():
    parser = argparse.ArgumentParser(
        description="SpookCue Audio Hasher: Extract acoustic landmarks and sync with GitHub.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Folder structure:
  <folder>/
  |-- sync_audio_hashes.py   (this script)
  |-- config.json            (auto-created settings)
  |-- Raw Audio/             (place your 447332.mp4, 447332.mp3 here)
  \-- Audio Hashes/          (generated 447332.json files saved here)
"""
    )
    parser.add_argument("--spookcue-path", type=Path, default=None, help="Path to local SpookCue repository clone")
    parser.add_argument("--force", action="store_true", help="Force re-generation of existing hash files")
    parser.add_argument("--no-push", action="store_true", help="Generate hashes without pushing to GitHub")
    parser.add_argument("--dry-run", action="store_true", help="Simulate process without writing files or pushing")

    args = parser.parse_args()
    script_dir = Path(__file__).resolve().parent

    sys.exit(
        run_hasher(
            script_dir=script_dir,
            spookcue_path=args.spookcue_path,
            force=args.force,
            no_push=args.no_push,
            dry_run=args.dry_run
        )
    )


if __name__ == "__main__":
    main()
