#!/usr/bin/env python3
"""
SpookCue Automated Pocket Sync Test Runner & Batch Suite
Runs automated sync testing via USB ADB with the phone in your pocket at ~50% theater volume.

Supports:
1. Single Test Run:
   python scripts/run_pocket_test.py --media "path/to/138843.flac" --tmdb 138843 --start-time 00:01:30 --mode subtitles

2. Full Automated Batch Suite across all 5 Netflix India movies:
   Tests TWO points per movie (Start & After Middle Ad Break), switches between movies and points,
   and repeats the entire suite once with Subtitle Sync and once with Landmark Constellation Sync.
   python scripts/run_pocket_test.py --batch --audio-dir "C:/Users/aarav/Documents/Hobbies/Code/Tools/SpookCue Audio Data/Raw Audio"
"""

from __future__ import annotations

import argparse
import datetime
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
from typing import Any, Dict, List, Optional

# Logging setup
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("pocket_test")

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# 5 Target Horror Movies on Netflix India with 2 test points each
TEST_MOVIES_CONFIG = [
    {
        "tmdb_id": 138843,
        "title": "The Conjuring",
        "points": [
            {
                "point_id": "start",
                "label": "Movie Start (00:01:30)",
                "start_time_sec": 90.0,
                "duration_sec": 180.0,
                "dialogue": "It's not about the doll. It's about what was using the doll.",
                "target_scare_sec": 216.0,
                "scare_desc": "Bang startles nurses outside bedroom"
            },
            {
                "point_id": "mid_ad_break",
                "label": "After Middle Ad Break (00:39:00)",
                "start_time_sec": 2340.0,
                "duration_sec": 180.0,
                "dialogue": "Who's down there? Cindy? Andrea?",
                "target_scare_sec": 2438.0,
                "scare_desc": "Hands clap behind Carolyn in cellar"
            }
        ]
    },
    {
        "tmdb_id": 345911,
        "title": "Lights Out",
        "points": [
            {
                "point_id": "start",
                "label": "Movie Start (00:01:00)",
                "start_time_sec": 60.0,
                "duration_sec": 180.0,
                "dialogue": "Paul, are you coming? Just locking up the sample room.",
                "target_scare_sec": 202.0,
                "scare_desc": "Esther flicks light switch; silhouette jumps closer"
            },
            {
                "point_id": "mid_ad_break",
                "label": "After Middle Ad Break (00:38:40)",
                "start_time_sec": 2320.0,
                "duration_sec": 180.0,
                "dialogue": "Did she say her name was Diana? She told me stories about an old friend...",
                "target_scare_sec": 2430.0,
                "scare_desc": "Diana directly above Martin in bed"
            }
        ]
    },
    {
        "tmdb_id": 575774,
        "title": "His House",
        "points": [
            {
                "point_id": "start",
                "label": "Movie Start (00:09:30)",
                "start_time_sec": 570.0,
                "duration_sec": 240.0,
                "dialogue": "It's bigger than my place... You must stay at this address.",
                "target_scare_sec": 782.0,
                "scare_desc": "Loud noise behind Bol, object flies past cabinet"
            },
            {
                "point_id": "mid_ad_break",
                "label": "After Middle Ad Break (00:37:00)",
                "start_time_sec": 2220.0,
                "duration_sec": 200.0,
                "dialogue": "Come out! Where are you? (Bol hammering wall)",
                "target_scare_sec": 2329.0,
                "scare_desc": "The girl turns around to face him in wall hole"
            }
        ]
    },
    {
        "tmdb_id": 433808,
        "title": "The Ritual",
        "points": [
            {
                "point_id": "start",
                "label": "Movie Start (00:01:15)",
                "start_time_sec": 75.0,
                "duration_sec": 240.0,
                "dialogue": "Nobody wants to go to Amsterdam again, Hutch... King's Trail in northern Sweden.",
                "target_scare_sec": 378.0,
                "scare_desc": "Liquor store robbery ambush"
            },
            {
                "point_id": "mid_ad_break",
                "label": "After Middle Ad Break (00:43:00)",
                "start_time_sec": 2580.0,
                "duration_sec": 200.0,
                "dialogue": "Hutch, look over here! There's a shelter!",
                "target_scare_sec": 2741.0,
                "scare_desc": "Pagan effigy upstairs and creature sounds in the rain"
            }
        ]
    },
    {
        "tmdb_id": 335796,
        "title": "Ouija: Origin of Evil",
        "points": [
            {
                "point_id": "start",
                "label": "Movie Start (00:00:45)",
                "start_time_sec": 45.0,
                "duration_sec": 240.0,
                "dialogue": "Is someone there? Please, give us a sign. Mother, is that you?",
                "target_scare_sec": 273.0,
                "scare_desc": "Dark silhouette crashes through curtains"
            },
            {
                "point_id": "mid_ad_break",
                "label": "After Middle Ad Break (00:38:00)",
                "start_time_sec": 2280.0,
                "duration_sec": 200.0,
                "dialogue": "Doris, what are you doing? Someone wanted to say hello...",
                "target_scare_sec": 2361.0,
                "scare_desc": "Doris suddenly sits up and attacks"
            }
        ]
    }
]


def parse_time_to_seconds(time_str: str) -> float:
    """Parse 'MM:SS', 'HH:MM:SS', or seconds string to float seconds."""
    parts = time_str.strip().split(":")
    if len(parts) == 1:
        return float(parts[0])
    elif len(parts) == 2:
        return int(parts[0]) * 60 + float(parts[1])
    elif len(parts) == 3:
        return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
    raise ValueError(f"Unrecognized time format: '{time_str}'")


def format_seconds_to_time(sec: float) -> str:
    """Format seconds into HH:MM:SS."""
    sec = max(0, sec)
    m, s = divmod(int(sec), 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"


def find_audio_file_for_tmdb(tmdb_id: int, search_dirs: List[Path]) -> Optional[Path]:
    """Find <tmdb_id>.flac or <tmdb_id>.mp4 across designated candidate directories."""
    for d in search_dirs:
        if not d.exists():
            continue
        # Direct matches in folder
        for ext in [".flac", ".mp4", ".mkv", ".wav"]:
            candidate = d / f"{tmdb_id}{ext}"
            if candidate.exists():
                return candidate
        # Recursive scan
        for f in d.rglob(f"{tmdb_id}.*"):
            if f.suffix.lower() in [".flac", ".mp4", ".mkv", ".wav"]:
                return f
    return None


class PocketTestRunner:
    def __init__(
        self,
        media_path: Path,
        tmdb_id: int,
        start_time_sec: float = 0.0,
        duration_sec: Optional[float] = None,
        volume_pct: int = 50,
        sync_mode: Optional[str] = None,
        point_name: str = "start",
        device_serial: Optional[str] = None,
        output_dir: Path = PROJECT_ROOT / "test_reports"
    ):
        self.media_path = media_path
        self.tmdb_id = tmdb_id
        self.start_time_sec = start_time_sec
        self.duration_sec = duration_sec
        self.volume_pct = volume_pct
        self.sync_mode = sync_mode  # "subtitles" or "audio_hash"
        self.point_name = point_name
        self.device_serial = device_serial
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)

        self.movie_title = f"TMDB {tmdb_id}"
        self.expected_scares: List[Dict[str, Any]] = []
        self._load_movie_metadata()

        # Test state
        self.audio_process: Optional[subprocess.Popen] = None
        self.logcat_process: Optional[subprocess.Popen] = None
        self.playback_start_epoch: float = 0.0
        self.is_running = False

        # Metrics collected
        self.rms_readings: List[float] = []
        self.lock_event: Optional[Dict[str, Any]] = None
        self.first_candidate_time: Optional[float] = None
        self.drift_events: List[Dict[str, Any]] = []
        self.warning_events: List[Dict[str, Any]] = []
        self.scare_events: List[Dict[str, Any]] = []
        self.exported_history: List[Dict[str, Any]] = []
        self.phone_movie_time_ms: int = 0
        self.last_status_str = "SEARCHING"

    def _load_movie_metadata(self):
        movie_file = PROJECT_ROOT / "data" / "movies" / f"{self.tmdb_id}.json"
        if movie_file.exists():
            try:
                with open(movie_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.movie_title = data.get("title", self.movie_title)
                    self.expected_scares = data.get("scares", [])
            except Exception as e:
                logger.warning(f"Could not read movie metadata: {e}")

    def _run_adb(self, cmd: List[str], check: bool = True) -> subprocess.CompletedProcess:
        full_cmd = ["adb"]
        if self.device_serial:
            full_cmd.extend(["-s", self.device_serial])
        full_cmd.extend(cmd)
        return subprocess.run(full_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=check, encoding="utf-8", errors="replace")

    def check_adb_device(self):
        """Verify ADB connection to the phone."""
        res = self._run_adb(["devices"])
        lines = [line.strip() for line in res.stdout.splitlines() if line.strip() and not line.startswith("List")]
        if not lines:
            raise RuntimeError("No ADB device connected. Ensure your phone is connected via USB with USB debugging enabled.")
        
        device_lines = [l for l in lines if "\tdevice" in l]
        if not device_lines:
            raise RuntimeError(f"ADB device not authorized or offline: {lines}")

        chosen = device_lines[0].split("\t")[0]
        if not self.device_serial:
            self.device_serial = chosen

        # Query model and battery
        model = self._run_adb(["shell", "getprop", "ro.product.model"]).stdout.strip()
        battery = self._run_adb(["shell", "dumpsys", "battery"]).stdout
        level = "unknown"
        for b_line in battery.splitlines():
            if "level:" in b_line:
                level = b_line.split(":")[1].strip() + "%"
        logger.info(f"Target ADB Device: {model} ({self.device_serial}) | Battery: {level}")

    def prepare_phone_app(self):
        """Clear logcat and launch SpookCue into the target movie sync screen with optional mode override."""
        logger.info("Clearing device logcat buffer...")
        self._run_adb(["logcat", "-c"])

        mode_str = f" [Mode Override: {self.sync_mode}]" if self.sync_mode else ""
        logger.info(f"Launching SpookCue directly to movie '{self.movie_title}' (TMDB {self.tmdb_id}){mode_str}...")

        cmd = [
            "shell", "am", "start",
            "-n", "dev.spookcue/.MainActivity",
            "--ei", "tmdb_id", str(self.tmdb_id)
        ]
        if self.sync_mode:
            cmd.extend(["--es", "sync_mode", self.sync_mode])
        self._run_adb(cmd)

        logger.info("Waiting 2.5 seconds for app and audio capture engine initialization...")
        time.sleep(2.5)

    def stop_phone_app(self):
        """Force stop SpookCue to terminate service and mic recording between tests."""
        logger.debug("Stopping SpookCue app process on device...")
        self._run_adb(["shell", "am", "force-stop", "dev.spookcue"], check=False)

    def _start_logcat_listener(self):
        """Background listener reading Android logcat events in real time."""
        adb_cmd = ["adb"]
        if self.device_serial:
            adb_cmd.extend(["-s", self.device_serial])
        adb_cmd.extend([
            "logcat", "-v", "time",
            "-s", "SyncEngine:V", "ScareScheduler:V", "AudioCaptureManager:V", "SyncExport:V", "SyncHistory:V", "SpookCueService:V"
        ])

        self.logcat_process = subprocess.Popen(
            adb_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace"
        )

        def listen_loop():
            export_collecting = False
            export_lines = []

            for line in iter(self.logcat_process.stdout.readline, ''):
                if not self.is_running:
                    break
                line_str = line.strip()
                if not line_str:
                    continue

                now_epoch = time.time()
                elapsed_since_start = now_epoch - self.playback_start_epoch if self.playback_start_epoch > 0 else 0

                # 1. Capture Export JSON Block
                if "=== SPOOKCUE_SYNC_EXPORT_START ===" in line_str:
                    export_collecting = True
                    export_lines = []
                    continue
                if "=== SPOOKCUE_SYNC_EXPORT_END ===" in line_str:
                    export_collecting = False
                    try:
                        raw_json = "\n".join(export_lines)
                        self.exported_history = json.loads(raw_json)
                        logger.info(f"Captured {len(self.exported_history)} sync history events exported from device!")
                    except Exception as e:
                        logger.warning(f"Failed to parse captured sync export: {e}")
                    continue
                if export_collecting:
                    export_lines.append(line_str)
                    continue

                # 2. Track RMS Audio Level from Pocket
                if "AudioCaptureManager" in line_str and "rmsDb=" in line_str:
                    m = re.search(r"rmsDb=([0-9\.]+)", line_str)
                    if m:
                        try:
                            val = float(m.group(1))
                            self.rms_readings.append(val)
                        except Exception:
                            pass

                # 3. Track Candidate
                if "CANDIDATE" in line_str or "candidate @" in line_str or "Phonetic candidate" in line_str:
                    if self.first_candidate_time is None and self.playback_start_epoch > 0:
                        self.first_candidate_time = elapsed_since_start
                        self.last_status_str = "CANDIDATE_DETECTED"

                # 4. Track Sync Lock Event
                if ">>> FAST-LOCKED!" in line_str or ">>> CONSENSUS LOCKED!" in line_str or ">>> ACOUSTIC HASH LOCKED!" in line_str:
                    m_time = re.search(r"Snapped to movie time: (\d+)s", line_str)
                    movie_sec = int(m_time.group(1)) if m_time else 0
                    mode = "acoustic_hash" if "ACOUSTIC HASH" in line_str else "subtitles"
                    is_fast = "FAST-LOCKED" in line_str

                    gt_sec = self.start_time_sec + elapsed_since_start
                    delta_ms = int((movie_sec - gt_sec) * 1000)

                    self.phone_movie_time_ms = movie_sec * 1000
                    self.last_status_str = f"LOCKED ({mode})"

                    if self.lock_event is None:
                        self.lock_event = {
                            "latency_sec": round(elapsed_since_start, 2),
                            "mode": mode,
                            "is_fast_lock": is_fast,
                            "phone_movie_time_sec": movie_sec,
                            "ground_truth_sec": round(gt_sec, 2),
                            "offset_delta_ms": delta_ms,
                            "raw_log": line_str
                        }
                        logger.info(f"\n[LOCK DETECTED] Mode: {mode} | Lock Latency: {elapsed_since_start:.1f}s | Offset Delta: {delta_ms:+d}ms")

                # 5. Track Drift Correction
                if "Applied micro-drift correction" in line_str or "Applied acoustic hash drift correction" in line_str:
                    m_drift = re.search(r"correction: (-?\d+)ms", line_str)
                    drift_amt = int(m_drift.group(1)) if m_drift else 0
                    self.drift_events.append({
                        "elapsed_sec": round(elapsed_since_start, 2),
                        "correction_ms": drift_amt,
                        "raw_log": line_str
                    })

                # 6. Track Scare Pre-warning & Scare Alerts
                if "WarningLead" in line_str or ("ScareScheduler" in line_str and "lead" in line_str.lower()):
                    self.last_status_str = "PRE-WARNING (HAPTIC)"
                    self.warning_events.append({
                        "elapsed_sec": round(elapsed_since_start, 2),
                        "raw_log": line_str
                    })
                    logger.info(f"\n[PRE-WARNING ALERT] Haptic pulse triggered at T+{elapsed_since_start:.1f}s")

                if "ScareNow" in line_str or ("ScareScheduler" in line_str and "now" in line_str.lower()):
                    self.last_status_str = "SCARE NOW!"
                    self.scare_events.append({
                        "elapsed_sec": round(elapsed_since_start, 2),
                        "raw_log": line_str
                    })
                    logger.info(f"\n[SCARE TRIGGERED] Jump scare at T+{elapsed_since_start:.1f}s")

        t = threading.Thread(target=listen_loop, daemon=True)
        t.start()

    def run_test(self) -> Dict[str, Any]:
        """Execute the automated pocket sync test and return report data dictionary."""
        self.check_adb_device()
        self.prepare_phone_app()

        mode_desc = f" ({self.sync_mode.upper()})" if self.sync_mode else ""
        logger.info(f"Starting audio playback from '{self.media_path.name}' at {self.volume_pct}% volume{mode_desc}...")
        logger.info(f"Test Configuration: Point '{self.point_name}' | Start = {format_seconds_to_time(self.start_time_sec)} | Duration = {self.duration_sec or 'Full'}s")
        logger.info("Phone is in pocket. Monitoring sync events in real time...\n")

        # Start logcat background thread
        self.is_running = True
        self._start_logcat_listener()

        # Build FFplay command
        ffplay_cmd = [
            "ffplay", "-nodisp", "-autoexit",
            "-ss", str(self.start_time_sec),
            "-volume", str(self.volume_pct)
        ]
        if self.duration_sec:
            ffplay_cmd.extend(["-t", str(self.duration_sec)])
        ffplay_cmd.append(str(self.media_path))

        self.playback_start_epoch = time.time()
        self.audio_process = subprocess.Popen(
            ffplay_cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )

        try:
            while self.audio_process.poll() is None:
                elapsed = time.time() - self.playback_start_epoch
                gt_time = self.start_time_sec + elapsed

                # Estimated phone movie time
                phone_time_str = "--:--"
                delta_str = "N/A"
                if self.lock_event:
                    est_phone_sec = self.lock_event["phone_movie_time_sec"] + (elapsed - self.lock_event["latency_sec"])
                    phone_time_str = format_seconds_to_time(est_phone_sec)
                    delta_ms = int((est_phone_sec - gt_time) * 1000)
                    delta_str = f"{delta_ms:+d}ms"

                avg_rms = round(sum(self.rms_readings[-10:]) / max(1, len(self.rms_readings[-10:])), 1) if self.rms_readings else 0.0

                # Print clean updating dashboard line
                sys.stdout.write(
                    f"\r[T+{format_seconds_to_time(elapsed)}] "
                    f"Ground Truth: {format_seconds_to_time(gt_time)} | "
                    f"Phone Sync: {phone_time_str} | "
                    f"Δ: {delta_str} | "
                    f"Pocket RMS: {avg_rms:4.1f}dB | "
                    f"Status: {self.last_status_str:<22}"
                )
                sys.stdout.flush()
                time.sleep(0.5)

        except KeyboardInterrupt:
            logger.info("\nTest interrupted by user (Ctrl+C). Terminating playback...")
            if self.audio_process and self.audio_process.poll() is None:
                self.audio_process.terminate()

        finally:
            self.is_running = False
            if self.audio_process and self.audio_process.poll() is None:
                self.audio_process.kill()
            if self.logcat_process and self.logcat_process.poll() is None:
                self.logcat_process.terminate()

        logger.info("\nPlayback complete. Gathering device sync history and diagnostics...")
        time.sleep(1.0)
        report = self.gather_export_and_report()
        self.stop_phone_app()
        return report

    def gather_export_and_report(self) -> Dict[str, Any]:
        """Pull exported history from phone and generate test report."""
        try:
            pull_res = self._run_adb([
                "shell", "run-as", "dev.spookcue", "cat", "files/spookcue_sync_history.json"
            ], check=False)
            if pull_res.returncode == 0 and pull_res.stdout.strip().startswith("["):
                self.exported_history = json.loads(pull_res.stdout.strip())
        except Exception:
            pass

        has_lock = self.lock_event is not None
        lock_latency = self.lock_event["latency_sec"] if self.lock_event else None
        offset_error = abs(self.lock_event["offset_delta_ms"]) if self.lock_event else None

        passed = False
        verdict_notes = []

        if not has_lock:
            verdict_notes.append("FAIL: No sync lock achieved during test duration.")
        elif lock_latency > 60.0:
            verdict_notes.append(f"WARNING: Lock latency high ({lock_latency}s > 60s).")
        elif offset_error > 3500:
            verdict_notes.append(f"FAIL: Timing offset error too high ({offset_error}ms > 3500ms).")
        else:
            passed = True
            verdict_notes.append(f"PASS: Fast and accurate lock ({lock_latency}s latency, {offset_error}ms accuracy).")

        timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        report_data = {
            "timestamp": datetime.datetime.now().isoformat(),
            "movie_title": self.movie_title,
            "tmdb_id": self.tmdb_id,
            "point_name": self.point_name,
            "sync_mode": self.sync_mode or (self.lock_event["mode"] if self.lock_event else "unknown"),
            "start_time_sec": self.start_time_sec,
            "duration_sec": self.duration_sec,
            "volume_pct": self.volume_pct,
            "verdict": "PASS" if passed else "FAIL",
            "verdict_notes": verdict_notes,
            "lock_event": self.lock_event,
            "first_candidate_latency_sec": self.first_candidate_time,
            "drift_corrections_count": len(self.drift_events),
            "drift_events": self.drift_events,
            "warnings_count": len(self.warning_events),
            "scares_triggered_count": len(self.scare_events),
            "rms_readings_summary": {
                "count": len(self.rms_readings),
                "min_db": round(min(self.rms_readings), 1) if self.rms_readings else None,
                "avg_db": round(sum(self.rms_readings) / max(1, len(self.rms_readings)), 1) if self.rms_readings else None,
                "max_db": round(max(self.rms_readings), 1) if self.rms_readings else None,
            },
            "exported_history_events": self.exported_history
        }

        mode_tag = f"_{self.sync_mode}" if self.sync_mode else ""
        json_file = self.output_dir / f"pocket_test_{self.tmdb_id}_{self.point_name}{mode_tag}_{timestamp_str}.json"
        with open(json_file, "w", encoding="utf-8") as f:
            json.dump(report_data, f, indent=2)

        md_file = self.output_dir / f"pocket_test_{self.tmdb_id}_{self.point_name}{mode_tag}_{timestamp_str}.md"
        md_content = self._render_markdown_report(report_data)
        with open(md_file, "w", encoding="utf-8") as f:
            f.write(md_content)

        print("\n" + "=" * 60)
        print(f"       POCKET SYNC TEST REPORT — {self.movie_title}")
        print("=" * 60)
        print(f"Verdict:              {'[PASS]' if passed else '[FAIL]'}")
        print(f"Target Movie:         {self.movie_title} (TMDB ID: {self.tmdb_id})")
        print(f"Test Point:           {self.point_name} ({format_seconds_to_time(self.start_time_sec)})")
        print(f"Sync Mode:            {report_data['sync_mode'].upper()}")
        print(f"Playback Volume:      {self.volume_pct}% theater level")
        print(f"Initial Lock Latency: {lock_latency or 'N/A'} seconds")
        print(f"Offset Delta:         {self.lock_event['offset_delta_ms'] if self.lock_event else 'N/A'} ms")
        print(f"Drift Adjustments:    {len(self.drift_events)}")
        print(f"Pre-Warnings Alerted: {len(self.warning_events)}")
        print(f"Jump Scares Alerted:  {len(self.scare_events)}")
        print(f"Pocket Audio RMS:     {report_data['rms_readings_summary']['avg_db'] or 'N/A'} dB avg")
        print(f"Saved Report:         {md_file}")
        print("=" * 60 + "\n")

        return report_data

    def _render_markdown_report(self, d: Dict[str, Any]) -> str:
        lines = [
            f"# Pocket Sync Test Report: {d['movie_title']}",
            f"**Date:** {d['timestamp']}  ",
            f"**Verdict:** `{'PASS' if d['verdict'] == 'PASS' else 'FAIL'}`  ",
            f"**TMDB ID:** `{d['tmdb_id']}`  ",
            f"**Test Point:** `{d['point_name']}` ({format_seconds_to_time(d['start_time_sec'])})  ",
            f"**Sync Mode:** `{d['sync_mode'].upper()}`  ",
            f"**Volume:** `{d['volume_pct']}%` (Pocket Test)  \n",
            "## Summary",
            f"- **Initial Lock Latency:** `{d['lock_event']['latency_sec'] if d['lock_event'] else 'N/A'}s`",
            f"- **Offset Error ($\Delta$):** `{d['lock_event']['offset_delta_ms'] if d['lock_event'] else 'N/A'}ms`",
            f"- **Sync Mode:** `{d['sync_mode']}`",
            f"- **Drift Adjustments:** `{d['drift_corrections_count']}`",
            f"- **Haptic Pre-Warnings:** `{d['warnings_count']}`",
            f"- **Jump Scares Alerted:** `{d['scares_triggered_count']}`",
            f"- **Pocket RMS Noise:** `Min: {d['rms_readings_summary']['min_db']}dB | Avg: {d['rms_readings_summary']['avg_db']}dB | Max: {d['rms_readings_summary']['max_db']}dB`\n",
            "## Verification Notes",
        ]
        for note in d["verdict_notes"]:
            lines.append(f"- {note}")

        lines.extend([
            "\n## Event Log",
            "| Elapsed | Event Type | Details |",
            "|---|---|---|"
        ])

        if d["lock_event"]:
            lines.append(f"| T+{d['lock_event']['latency_sec']}s | **LOCK** | Snapped to {format_seconds_to_time(d['lock_event']['phone_movie_time_sec'])} ($\Delta={d['lock_event']['offset_delta_ms']:+d}$ms) |")

        for drift in d["drift_events"]:
            lines.append(f"| T+{drift['elapsed_sec']}s | DRIFT | Micro-correction: {drift['correction_ms']}ms |")

        for warn in d["warning_events"]:
            lines.append(f"| T+{warn['elapsed_sec']}s | PRE-WARNING | Haptic warning lead pulse |")

        for scare in d["scare_events"]:
            lines.append(f"| T+{scare['elapsed_sec']}s | SCARE | Countdown reached zero |")

        return "\n".join(lines)


class BatchTestOrchestrator:
    """Orchestrates automated batch testing across all 5 movies, 2 test points, and 2 sync modes."""

    def __init__(
        self,
        audio_dirs: List[Path],
        selected_movies: Optional[List[int]] = None,
        selected_points: Optional[List[str]] = None,
        modes: Optional[List[str]] = None,
        volume_pct: int = 50,
        device_serial: Optional[str] = None,
        output_dir: Path = PROJECT_ROOT / "test_reports"
    ):
        self.audio_dirs = audio_dirs
        self.selected_movies = selected_movies or [m["tmdb_id"] for m in TEST_MOVIES_CONFIG]
        self.selected_points = selected_points or ["start", "mid_ad_break"]
        self.modes = modes or ["subtitles", "audio_hash"]
        self.volume_pct = volume_pct
        self.device_serial = device_serial
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)

        self.results_matrix: List[Dict[str, Any]] = []

    def run_all(self):
        """Execute the full 2-pass matrix test automatically."""
        movie_configs = [m for m in TEST_MOVIES_CONFIG if m["tmdb_id"] in self.selected_movies]
        total_runs = len(movie_configs) * len(self.selected_points) * len(self.modes)

        print("\n" + "=" * 70)
        print("       SPOOKCUE AUTOMATED BATCH POCKET TEST ORCHESTRATOR")
        print("=" * 70)
        print(f"Target Movies:  {len(movie_configs)} ({', '.join(str(m['tmdb_id']) for m in movie_configs)})")
        print(f"Test Points:    {', '.join(self.selected_points)}")
        print(f"Sync Modes:     {', '.join(m.upper() for m in self.modes)}")
        print(f"Total Runs:     {total_runs} test segments")
        print(f"Volume Level:   {self.volume_pct}% theater level (phone in pocket)")
        print(f"Audio Folders:  {', '.join(str(p) for p in self.audio_dirs)}")
        print("=" * 70 + "\n")

        current_run_idx = 0

        for mode_idx, mode in enumerate(self.modes, start=1):
            print("\n" + "#" * 70)
            print(f"  PASS {mode_idx}/{len(self.modes)}: {mode.upper()} SYNC MODE")
            print("#" * 70)

            for movie in movie_configs:
                tmdb_id = movie["tmdb_id"]
                title = movie["title"]

                # Find audio file
                audio_file = find_audio_file_for_tmdb(tmdb_id, self.audio_dirs)
                if not audio_file:
                    logger.warning(f"SKIPPED: Could not find audio file for {title} (TMDB {tmdb_id}) in search paths!")
                    for pt in movie["points"]:
                        if pt["point_id"] in self.selected_points:
                            self.results_matrix.append({
                                "tmdb_id": tmdb_id,
                                "movie_title": title,
                                "point_id": pt["point_id"],
                                "point_label": pt["label"],
                                "sync_mode": mode,
                                "status": "SKIPPED_MISSING_AUDIO",
                                "verdict": "SKIPPED"
                            })
                    continue

                for pt in movie["points"]:
                    point_id = pt["point_id"]
                    if point_id not in self.selected_points:
                        continue

                    current_run_idx += 1
                    print("\n" + "-" * 70)
                    print(f"[{current_run_idx}/{total_runs}] Running: {title} | Point: {pt['label']} | Mode: {mode.upper()}")
                    print(f"Audio Source: {audio_file.name}")
                    print("-" * 70)

                    runner = PocketTestRunner(
                        media_path=audio_file,
                        tmdb_id=tmdb_id,
                        start_time_sec=pt["start_time_sec"],
                        duration_sec=pt["duration_sec"],
                        volume_pct=self.volume_pct,
                        sync_mode=mode,
                        point_name=point_id,
                        device_serial=self.device_serial,
                        output_dir=self.output_dir
                    )

                    report = runner.run_test()
                    report["point_label"] = pt["label"]
                    report["dialogue"] = pt["dialogue"]
                    report["target_scare_sec"] = pt["target_scare_sec"]
                    self.results_matrix.append(report)

                    # Inter-test cooldown to settle Android audio HAL
                    logger.info("Test segment finished. Cooling down 4 seconds before next segment...")
                    time.sleep(4.0)

        # Generate comparative matrix report
        self.generate_matrix_report()

    def generate_matrix_report(self):
        """Format and save the complete multi-movie, multi-point comparative matrix."""
        timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        json_path = self.output_dir / f"batch_sync_matrix_{timestamp_str}.json"
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(self.results_matrix, f, indent=2)

        md_path = self.output_dir / f"batch_sync_matrix_{timestamp_str}.md"

        # Organize by movie and point
        matrix_rows = []
        for movie in TEST_MOVIES_CONFIG:
            tmdb_id = movie["tmdb_id"]
            title = movie["title"]

            for pt in movie["points"]:
                point_id = pt["point_id"]
                sub_res = next((r for r in self.results_matrix if r["tmdb_id"] == tmdb_id and r.get("point_id") == point_id and r.get("sync_mode") == "subtitles"), None)
                hash_res = next((r for r in self.results_matrix if r["tmdb_id"] == tmdb_id and r.get("point_id") == point_id and r.get("sync_mode") == "audio_hash"), None)

                sub_lock = f"{sub_res['lock_event']['latency_sec']}s" if (sub_res and sub_res.get("lock_event")) else "N/A"
                sub_delta = f"{sub_res['lock_event']['offset_delta_ms']:+d}ms" if (sub_res and sub_res.get("lock_event")) else "N/A"
                hash_lock = f"{hash_res['lock_event']['latency_sec']}s" if (hash_res and hash_res.get("lock_event")) else "N/A"
                hash_delta = f"{hash_res['lock_event']['offset_delta_ms']:+d}ms" if (hash_res and hash_res.get("lock_event")) else "N/A"

                winner = "N/A"
                if sub_res and sub_res.get("lock_event") and hash_res and hash_res.get("lock_event"):
                    sub_lat = sub_res["lock_event"]["latency_sec"]
                    hash_lat = hash_res["lock_event"]["latency_sec"]
                    winner = "HASH" if hash_lat < sub_lat else "SUBTITLE"

                matrix_rows.append({
                    "title": title,
                    "point": pt["label"],
                    "sub_lock": sub_lock,
                    "sub_delta": sub_delta,
                    "hash_lock": hash_lock,
                    "hash_delta": hash_delta,
                    "winner": winner
                })

        # Markdown format
        md_lines = [
            "# SpookCue Batch Pocket Sync Test Matrix",
            f"**Date:** {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  ",
            f"**Volume Level:** `{self.volume_pct}%` (Pocket Test)  \n",
            "| Movie | Test Point | Subtitle Lock | Subtitle $\Delta$ | Hash Lock | Hash $\Delta$ | Winner |",
            "|---|---|---|---|---|---|---|"
        ]
        for row in matrix_rows:
            md_lines.append(f"| **{row['title']}** | {row['point']} | {row['sub_lock']} | {row['sub_delta']} | {row['hash_lock']} | {row['hash_delta']} | **{row['winner']}** |")

        with open(md_path, "w", encoding="utf-8") as f:
            f.write("\n".join(md_lines))

        # Print console table
        print("\n" + "=" * 90)
        print("                         SPOOKCUE BATCH SYNC COMPARISON MATRIX")
        print("=" * 90)
        print(f"{'Movie':<20} {'Point':<30} {'Sub Lock':<10} {'Sub Δ':<10} {'Hash Lock':<10} {'Hash Δ':<10} {'Winner':<8}")
        print("-" * 90)
        for row in matrix_rows:
            print(f"{row['title']:<20} {row['point']:<30} {row['sub_lock']:<10} {row['sub_delta']:<10} {row['hash_lock']:<10} {row['hash_delta']:<10} {row['winner']:<8}")
        print("=" * 90)
        print(f"Matrix report saved to: {md_path}\n")


def main():
    parser = argparse.ArgumentParser(description="SpookCue Automated Pocket Sync Test Runner")
    parser.add_argument("--batch", action="store_true", help="Run full automated batch suite across all 5 movies, points, and modes")
    parser.add_argument("--audio-dir", type=Path, action="append", help="Directory where <tmdb_id>.flac or <tmdb_id>.mp4 audio files are stored (can specify multiple)")
    parser.add_argument("--movies", type=int, nargs="+", help="Specific TMDB IDs to test (default: all 5 movies)")
    parser.add_argument("--points", type=str, nargs="+", choices=["start", "mid_ad_break"], help="Test points: 'start', 'mid_ad_break', or both")
    parser.add_argument("--modes", type=str, nargs="+", choices=["subtitles", "audio_hash"], help="Sync modes to test: 'subtitles', 'audio_hash', or both")

    # Single test options
    parser.add_argument("--media", "--mp4", dest="media", type=Path, help="Path to single video/audio MP4 or FLAC file")
    parser.add_argument("--tmdb", type=int, help="TMDB ID of single movie to test")
    parser.add_argument("--start-time", "--start", dest="start_time", type=str, default="0", help="Start time in clip (e.g. 00:01:30 or 90)")
    parser.add_argument("--duration", "-t", type=float, default=None, help="Duration of test in seconds (optional)")
    parser.add_argument("--mode", type=str, choices=["subtitles", "audio_hash"], help="Single run sync mode override")
    parser.add_argument("--point", type=str, default="start", help="Test point name (e.g. start or mid_ad_break)")

    # Common options
    parser.add_argument("--volume", type=int, default=50, help="Volume percentage (default: 50)")
    parser.add_argument("--device", type=str, default=None, help="ADB device serial if multiple connected")
    parser.add_argument("--output-dir", type=Path, default=PROJECT_ROOT / "test_reports", help="Directory for test reports")

    args = parser.parse_args()

    # Determine default candidate search directories for audio
    default_audio_dirs = [
        PROJECT_ROOT.parent / "SpookCue Audio Data" / "Raw Audio",
        PROJECT_ROOT.parent / "SpookCue Audio Data",
        Path(r"C:\Users\aarav\Documents\Hobbies\Code\Tools\SpookCue Audio Data\Raw Audio"),
        Path(r"C:\Users\aarav\Documents\Hobbies\Code\Tools\SpookCue Audio Data"),
        PROJECT_ROOT / "data" / "audio",
        PROJECT_ROOT
    ]
    if args.audio_dir:
        search_dirs = args.audio_dir + default_audio_dirs
    else:
        search_dirs = default_audio_dirs

    if args.batch:
        orchestrator = BatchTestOrchestrator(
            audio_dirs=search_dirs,
            selected_movies=args.movies,
            selected_points=args.points,
            modes=args.modes,
            volume_pct=args.volume,
            device_serial=args.device,
            output_dir=args.output_dir
        )
        orchestrator.run_all()
    else:
        if not args.media:
            # Check if user passed --tmdb without --media: attempt to auto-find flac
            if args.tmdb:
                found = find_audio_file_for_tmdb(args.tmdb, search_dirs)
                if found:
                    args.media = found
                    logger.info(f"Auto-discovered audio file for TMDB {args.tmdb}: {found}")
                else:
                    parser.error("Must specify --media <file> or provide --audio-dir containing <tmdb_id>.flac")
            else:
                parser.error("Must specify either --batch or --media <file> and --tmdb <id>")

        if not args.tmdb:
            parser.error("Must specify --tmdb <id> for single test run")

        start_sec = parse_time_to_seconds(args.start_time)

        runner = PocketTestRunner(
            media_path=args.media,
            tmdb_id=args.tmdb,
            start_time_sec=start_sec,
            duration_sec=args.duration,
            volume_pct=args.volume,
            sync_mode=args.mode,
            point_name=args.point,
            device_serial=args.device,
            output_dir=args.output_dir
        )
        runner.run_test()


if __name__ == "__main__":
    main()
