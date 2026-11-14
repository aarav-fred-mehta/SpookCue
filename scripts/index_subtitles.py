#!/usr/bin/env python3
"""
SpookCue Subtitle Indexer CLI
Indexes subtitle files (.srt) or embedded MP4 subtitle streams into Double Metaphone phonetic tokens
and updates SpookCue reference sync data for theater synchronisation.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Optional

# Add scraper/src to sys.path
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
SCRAPER_SRC = PROJECT_ROOT / "scraper" / "src"
if str(SCRAPER_SRC) not in sys.path:
    sys.path.insert(0, str(SCRAPER_SRC))

from spookcue_scraper.sanitizer import sanitize_subtitles
from spookcue_scraper.phonetics import build_phonetic_index
from spookcue_scraper.resolver import resolve_scares_to_anchors

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("index_subtitles")


def extract_subtitles_from_mp4(mp4_path: Path) -> Optional[str]:
    """Extract English subtitle text from MP4 container using FFmpeg."""
    logger.info(f"Probing subtitle streams in '{mp4_path.name}'...")
    
    # Try language eng first, fall back to first subtitle stream
    candidates = [
        ["ffmpeg", "-nostdin", "-y", "-i", str(mp4_path), "-map", "0:s:m:language:eng", "-f", "srt", "-"],
        ["ffmpeg", "-nostdin", "-y", "-i", str(mp4_path), "-map", "0:s:0", "-f", "srt", "-"]
    ]

    for cmd in candidates:
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
            if res.returncode == 0 and "-->" in res.stdout:
                logger.info(f"Successfully extracted subtitle track ({len(res.stdout)} chars)")
                return res.stdout
        except Exception as e:
            logger.debug(f"FFmpeg extraction attempt failed: {e}")

    logger.warning("Could not automatically extract subtitle stream from MP4.")
    return None


def read_srt_file(srt_path: Path) -> str:
    """Read SRT file handling UTF-8 (with/without BOM) and CP1252/Latin-1."""
    raw = srt_path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw[3:].decode("utf-8", errors="replace")
    if raw.startswith(b"\xff\xfe"):
        return raw[2:].decode("utf-16-le", errors="replace")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("latin-1", errors="replace")


def index_movie_subtitles(tmdb_id: int, srt_content: str, copy_assets: bool = True) -> int:
    """Sanitize subtitles, compute phonetic index, anchor scares, and save to data/sync."""
    logger.info(f"Sanitizing subtitles for TMDB {tmdb_id}...")
    sanitized = sanitize_subtitles(srt_content)
    if not sanitized:
        logger.error("No valid dialogue lines found after sanitization!")
        return 0

    logger.info(f"Sanitized into {len(sanitized)} cues. Building Double Metaphone phonetic index...")
    phonetic_index = build_phonetic_index(sanitized)
    logger.info(f"Generated {len(phonetic_index)} phonetic entries.")

    # 1. Update/anchor scares in data/movies/<tmdb_id>.json if present
    movie_path = PROJECT_ROOT / "data" / "movies" / f"{tmdb_id}.json"
    if movie_path.exists():
        with open(movie_path, "r", encoding="utf-8") as f:
            movie_data = json.load(f)
        
        raw_scares = movie_data.get("scares", [])
        if raw_scares:
            logger.info(f"Anchoring {len(raw_scares)} scares to phonetic dialogue...")
            resolved_scares = resolve_scares_to_anchors(raw_scares, phonetic_index)
            movie_data["scares"] = resolved_scares
            with open(movie_path, "w", encoding="utf-8") as f:
                json.dump(movie_data, f, indent=2, ensure_ascii=False)
            logger.info(f"Updated {movie_path}")

            if copy_assets:
                asset_movie = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "movies" / f"{tmdb_id}.json"
                if asset_movie.parent.exists():
                    shutil.copy2(movie_path, asset_movie)
                    logger.info(f"Copied to bundled assets: {asset_movie}")

    # 2. Write data/sync/<tmdb_id>.json
    sync_path = PROJECT_ROOT / "data" / "sync" / f"{tmdb_id}.json"
    sync_path.parent.mkdir(parents=True, exist_ok=True)

    # Retain audio_hashes if already generated
    existing_hashes = []
    if sync_path.exists():
        try:
            with open(sync_path, "r", encoding="utf-8") as f:
                old = json.load(f)
                existing_hashes = old.get("audio_hashes", [])
        except Exception:
            pass

    sync_payload = {
        "tmdb_id": tmdb_id,
        "type": "audio_hash" if existing_hashes else "subtitles",
        "phonetic_index": phonetic_index,
        "audio_hashes": existing_hashes
    }

    with open(sync_path, "w", encoding="utf-8") as f:
        json.dump(sync_payload, f, separators=(",", ":"), ensure_ascii=False)
    logger.info(f"Saved sync file: {sync_path} ({os.path.getsize(sync_path)} bytes)")

    # 3. Copy to app assets if requested
    if copy_assets:
        asset_sync = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "sync" / f"{tmdb_id}.json"
        if asset_sync.parent.exists():
            shutil.copy2(sync_path, asset_sync)
            logger.info(f"Copied to bundled assets: {asset_sync}")

    return len(phonetic_index)


def main():
    parser = argparse.ArgumentParser(description="Index subtitles into SpookCue phonetic sync data")
    parser.add_argument("--tmdb", type=int, required=True, help="TMDB movie ID (e.g. 138843)")
    parser.add_argument("--srt", type=Path, help="Path to .srt file")
    parser.add_argument("--mp4", type=Path, help="Path to .mp4 / video file with embedded subtitle stream")
    parser.add_argument("--no-copy-assets", action="store_true", help="Do not copy to app/src/main/assets/")

    args = parser.parse_args()

    srt_content = None
    if args.srt:
        if not args.srt.exists():
            logger.error(f"SRT file not found: {args.srt}")
            sys.exit(1)
        srt_content = read_srt_file(args.srt)
    elif args.mp4:
        if not args.mp4.exists():
            logger.error(f"MP4 file not found: {args.mp4}")
            sys.exit(1)
        srt_content = extract_subtitles_from_mp4(args.mp4)
    else:
        # Check if local subtitle exists in data/subtitles/<tmdb>.srt
        local_srt = PROJECT_ROOT / "data" / "subtitles" / f"{args.tmdb}.srt"
        if local_srt.exists():
            logger.info(f"Found local subtitle file: {local_srt}")
            srt_content = read_srt_file(local_srt)
        else:
            logger.error("Must provide either --srt, --mp4, or place an .srt in data/subtitles/<tmdb>.srt")
            sys.exit(1)

    if not srt_content:
        logger.error("Failed to obtain subtitle content!")
        sys.exit(1)

    count = index_movie_subtitles(args.tmdb, srt_content, copy_assets=not args.no_copy_assets)
    logger.info(f"Done! Successfully indexed {count} phonetic cues for TMDB {args.tmdb}.")


if __name__ == "__main__":
    main()
