#!/usr/bin/env python3
"""
SpookCue Automated Pocket Sync Test Runner
Runs automated sync testing via USB ADB with the phone in your pocket at ~50% theater volume.
Plays audio from your MP4/FLAC file through PC speakers, controls SpookCue via ADB,
tracks real-time sync lock, latency, clock drift, and jump scare alerts, and generates
a comprehensive verification report.
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


class PocketTestRunner:
    def __init__(
        self,
        media_path: Path,
        tmdb_id: int,
        start_time_sec: float = 0.0,
        duration_sec: Optional[float] = None,
        volume_pct: int = 50,
        device_serial: Optional[str] = None,
        output_dir: Path = PROJECT_ROOT / "test_reports"
    ):
        self.media_path = media_path
        self.tmdb_id = tmdb_id
        self.start_time_sec = start_time_sec
        self.duration_sec = duration_sec
        self.volume_pct = volume_pct
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
        logger.info("Checking connected Android devices via ADB...")
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
        logger.info(f"Targeting device: {self.device_serial}")

        # Model and battery check
        model = self._run_adb(["shell", "getprop", "ro.product.model"]).stdout.strip()
        battery = self._run_adb(["shell", "dumpsys", "battery"]).stdout
        level = "unknown"
        for b_line in battery.splitlines():
            if "level:" in b_line:
                level = b_line.split(":")[1].strip() + "%"
        logger.info(f"Device: {model} | Battery: {level}")

    def prepare_phone_app(self):
        """Clear logcat and launch SpookCue into the target movie sync screen."""
        logger.info("Clearing device logcat buffer...")
        self._run_adb(["logcat", "-c"])

        logger.info(f"Launching SpookCue directly to movie '{self.movie_title}' (TMDB {self.tmdb_id})...")
        launch_res = self._run_adb([
            "shell", "am", "start",
            "-n", "dev.spookcue/.MainActivity",
            "--ei", "tmdb_id", str(self.tmdb_id)
        ])
        logger.debug(f"Launch output: {launch_res.stdout.strip()}")

        # Also send explicit deep link intent as fallback
        self._run_adb([
            "shell", "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", f"spookcue://sync/{self.tmdb_id}",
            "dev.spookcue"
        ], check=False)

        logger.info("Waiting 2.5 seconds for app and microphone engine initialization...")
        time.sleep(2.5)

    def _start_logcat_listener(self):
        """Background listener reading Android logcat events in real time."""
        adb_cmd = ["adb"]
        if self.device_serial:
            adb_cmd.extend(["-s", self.device_serial])
        adb_cmd.extend([
            "logcat", "-v", "time",
            "-s", "SyncEngine:V", "ScareScheduler:V", "AudioCaptureManager:V", "SyncExport:V", "SyncHistory:V"
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
                if "WarningLead" in line_str or "ScareScheduler" in line_str and "lead" in line_str.lower():
                    self.last_status_str = "PRE-WARNING (HAPTIC)"
                    self.warning_events.append({
                        "elapsed_sec": round(elapsed_since_start, 2),
                        "raw_log": line_str
                    })
                    logger.info(f"\n[PRE-WARNING ALERT] Haptic pulse triggered at T+{elapsed_since_start:.1f}s")

                if "ScareNow" in line_str or "ScareScheduler" in line_str and "now" in line_str.lower():
                    self.last_status_str = "SCARE NOW!"
                    self.scare_events.append({
                        "elapsed_sec": round(elapsed_since_start, 2),
                        "raw_log": line_str
                    })
                    logger.info(f"\n[SCARE TRIGGERED] Jump scare at T+{elapsed_since_start:.1f}s")

        t = threading.Thread(target=listen_loop, daemon=True)
        t.start()

    def run_test(self):
        """Execute the automated pocket sync test."""
        self.check_adb_device()
        self.prepare_phone_app()

        logger.info(f"Starting audio playback from '{self.media_path.name}' at {self.volume_pct}% volume...")
        logger.info(f"Test Configuration: Start Time = {format_seconds_to_time(self.start_time_sec)} | Duration = {self.duration_sec or 'Full'}s")
        logger.info("Put phone in pocket now. Monitoring sync in real time...\n")

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
        self.gather_export_and_report()

    def gather_export_and_report(self):
        """Pull exported history from phone and generate test report."""
        # 1. Try pulling exported history file directly if present
        try:
            pull_res = self._run_adb([
                "shell", "run-as", "dev.spookcue", "cat", "files/spookcue_sync_history.json"
            ], check=False)
            if pull_res.returncode == 0 and pull_res.stdout.strip().startswith("["):
                self.exported_history = json.loads(pull_res.stdout.strip())
        except Exception:
            pass

        # 2. Evaluate Pass/Fail
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

        # 3. Create Report Data
        timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        report_data = {
            "timestamp": datetime.datetime.now().isoformat(),
            "movie_title": self.movie_title,
            "tmdb_id": self.tmdb_id,
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

        # 4. Save JSON Report
        json_file = self.output_dir / f"pocket_test_{self.tmdb_id}_{timestamp_str}.json"
        with open(json_file, "w", encoding="utf-8") as f:
            json.dump(report_data, f, indent=2)

        # 5. Save Markdown Report
        md_file = self.output_dir / f"pocket_test_{self.tmdb_id}_{timestamp_str}.md"
        md_content = self._render_markdown_report(report_data)
        with open(md_file, "w", encoding="utf-8") as f:
            f.write(md_content)

        # 6. Display Summary to console
        print("\n" + "=" * 60)
        print(f"       POCKET SYNC TEST REPORT — {self.movie_title}")
        print("=" * 60)
        print(f"Verdict:              {'[PASS]' if passed else '[FAIL]'}")
        print(f"Target Movie:         {self.movie_title} (TMDB ID: {self.tmdb_id})")
        print(f"Playback Volume:      {self.volume_pct}% theater level")
        print(f"Phone Placement:      Pocket with ambient background noise")
        print(f"Initial Lock Latency: {lock_latency or 'N/A'} seconds")
        print(f"Offset Delta:         {self.lock_event['offset_delta_ms'] if self.lock_event else 'N/A'} ms")
        print(f"Drift Adjustments:    {len(self.drift_events)}")
        print(f"Pre-Warnings Alerted: {len(self.warning_events)}")
        print(f"Jump Scares Alerted:  {len(self.scare_events)}")
        print(f"Pocket Audio RMS:     {report_data['rms_readings_summary']['avg_db'] or 'N/A'} dB avg")
        print(f"Saved Report:         {md_file}")
        print("=" * 60 + "\n")

    def _render_markdown_report(self, d: Dict[str, Any]) -> str:
        lines = [
            f"# Pocket Sync Test Report: {d['movie_title']}",
            f"**Date:** {d['timestamp']}  ",
            f"**Verdict:** `{'PASS' if d['verdict'] == 'PASS' else 'FAIL'}`  ",
            f"**TMDB ID:** `{d['tmdb_id']}`  ",
            f"**Volume:** `{d['volume_pct']}%` (Pocket Test)  \n",
            "## Summary",
            f"- **Initial Lock Latency:** `{d['lock_event']['latency_sec'] if d['lock_event'] else 'N/A'}s`",
            f"- **Offset Error ($\Delta$):** `{d['lock_event']['offset_delta_ms'] if d['lock_event'] else 'N/A'}ms`",
            f"- **Sync Mode:** `{d['lock_event']['mode'] if d['lock_event'] else 'N/A'}`",
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


def main():
    parser = argparse.ArgumentParser(description="SpookCue Automated Pocket Sync Test Runner")
    parser.add_argument("--mp4", "--media", dest="media", type=Path, required=True, help="Path to video/audio MP4 or FLAC file")
    parser.add_argument("--tmdb", type=int, required=True, help="TMDB ID of the movie (e.g. 138843 for The Conjuring)")
    parser.add_argument("--start-time", "--start", dest="start_time", type=str, default="0", help="Start time in clip (e.g. 00:01:30 or 90)")
    parser.add_argument("--duration", "-t", type=float, default=None, help="Duration of test in seconds (optional)")
    parser.add_argument("--volume", type=int, default=50, help="Volume percentage (default: 50)")
    parser.add_argument("--device", type=str, default=None, help="ADB device serial if multiple connected")
    parser.add_argument("--output-dir", type=Path, default=PROJECT_ROOT / "test_reports", help="Directory for test reports")

    args = parser.parse_args()

    if not args.media.exists():
        logger.error(f"Media file not found: {args.media}")
        sys.exit(1)

    start_sec = parse_time_to_seconds(args.start_time)

    runner = PocketTestRunner(
        media_path=args.media,
        tmdb_id=args.tmdb,
        start_time_sec=start_sec,
        duration_sec=args.duration,
        volume_pct=args.volume,
        device_serial=args.device,
        output_dir=args.output_dir
    )
    runner.run_test()


if __name__ == "__main__":
    main()
