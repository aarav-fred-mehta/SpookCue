"""Jump scare anchor resolver.

Anchors each jump scare timestamp to the closest preceding dialogue line within 60s
so synchronization is resilient to different cuts and release editions.
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional
from spookcue_scraper.phonetics import encode_tokens_to_phonetics

logger = logging.getLogger(__name__)

TIME_PATTERN = re.compile(r"^(?:(?:(\d{1,2}):)?(\d{1,2}):)?(\d{1,2})(?:\.(\d{1,3}))?$")


def parse_timestamp_to_ms(time_val: Any) -> int:
    """Parse time string (HH:MM:SS, MM:SS, or seconds/ms) into integer milliseconds."""
    if isinstance(time_val, (int, float)):
        return int(time_val)

    if not isinstance(time_val, str):
        return 0

    s = time_val.strip()
    # Check if purely digits
    if s.isdigit():
        val = int(s)
        # If already in ms (> 100000 and not seconds)
        return val

    parts = s.split(":")
    if len(parts) == 3:
        h, m, sec = parts
        seconds = float(h) * 3600 + float(m) * 60 + float(sec)
        return int(seconds * 1000)
    elif len(parts) == 2:
        m, sec = parts
        seconds = float(m) * 60 + float(sec)
        return int(seconds * 1000)
    elif len(parts) == 1:
        try:
            return int(float(parts[0]) * 1000)
        except ValueError:
            return 0
    return 0


def resolve_scares_to_anchors(
    scares: List[Dict[str, Any]],
    subtitles_or_index: List[Dict[str, Any]],
    max_anchor_window_ms: int = 60000,
) -> List[Dict[str, Any]]:
    """Anchor each scare to the closest preceding dialogue line within max_anchor_window_ms (default 60s).

    If no subtitle line precedes the scare within the window, anchor_phonetic and
    offset_from_anchor_ms are set to None (clock-only mode).
    """
    # Normalize subtitles/index entries to (start_ms, phonetic_tokens)
    dialogue_anchors: List[tuple[int, List[str]]] = []
    for item in subtitles_or_index:
        start_ms = item.get("s") if "s" in item else item.get("start_ms", 0)
        p_tokens = item.get("p")
        if p_tokens is None and "words" in item:
            p_tokens = encode_tokens_to_phonetics(item["words"])
        if p_tokens:
            dialogue_anchors.append((start_ms, p_tokens))

    # Sort anchors by start_ms
    dialogue_anchors.sort(key=lambda a: a[0])

    resolved_scares: List[Dict[str, Any]] = []

    for idx, scare in enumerate(scares, start=1):
        scare_time_ms = scare.get("resolved_time_ms") or scare.get("scare_time_ms")
        if scare_time_ms is None:
            raw_time = scare.get("time") or scare.get("timestamp") or 0
            scare_time_ms = parse_timestamp_to_ms(raw_time)

        scare_type = scare.get("type")
        if not scare_type:
            is_major = scare.get("major", False) or scare.get("is_major", False)
            scare_type = "major" if is_major else "minor"

        description = scare.get("description") or scare.get("desc") or ""

        # Find closest preceding dialogue anchor within max_anchor_window_ms
        best_anchor_time: Optional[int] = None
        best_anchor_tokens: Optional[List[str]] = None
        min_offset: Optional[int] = None

        for anchor_time, tokens in dialogue_anchors:
            if anchor_time <= scare_time_ms:
                offset = scare_time_ms - anchor_time
                if offset <= max_anchor_window_ms:
                    if min_offset is None or offset < min_offset:
                        min_offset = offset
                        best_anchor_time = anchor_time
                        best_anchor_tokens = tokens

        resolved = {
            "id": f"scare_{idx:02d}",
            "resolved_time_ms": scare_time_ms,
            "type": scare_type,
            "description": description,
            "anchor_phonetic": best_anchor_tokens if best_anchor_tokens is not None else None,
            "offset_from_anchor_ms": min_offset if min_offset is not None else None,
        }
        resolved_scares.append(resolved)

    resolved_scares.sort(key=lambda s: s["resolved_time_ms"])
    # Re-index ids chronologically with zero-padded format
    for i, s in enumerate(resolved_scares, start=1):
        s["id"] = f"scare_{i:02d}"

    return resolved_scares
