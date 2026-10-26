"""Subtitle sanitization pipeline.

Strips SDH annotations, HTML/ASS tags, uploader credits, removes low-information
stopwords, concatenates short temporal clauses (<400ms gap), and filters by minimum entropy.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
import srt

logger = logging.getLogger(__name__)

# Low-information stopwords to strip from phonetic dialogue indexing
STOPWORDS = {
    "oh", "ah", "uh", "um", "whoa", "hey", "yeah", "hmm", "huh", "okay", "ok"
}

# Consonants for minimum entropy calculation
CONSONANTS = set("bcdfghjklmnpqrstvwxyz")

# Watermark / credit regexes
WATERMARK_PATTERNS = [
    re.compile(r"subtitles?\s+by", re.IGNORECASE),
    re.compile(r"synced\s+(?:and|&)\s+corrected", re.IGNORECASE),
    re.compile(r"opensubtitles", re.IGNORECASE),
    re.compile(r"downloaded\s+from", re.IGNORECASE),
    re.compile(r"encoded\s+by", re.IGNORECASE),
    re.compile(r"caption(?:ed|ing)\s+by", re.IGNORECASE),
    re.compile(r"translated\s+by", re.IGNORECASE),
    re.compile(r"https?://", re.IGNORECASE),
    re.compile(r"www\.[a-z0-9\-]+\.[a-z]+", re.IGNORECASE),
    re.compile(r"\b[a-z0-9\-]+\.(?:com|org|net|io|co|cc)\b", re.IGNORECASE),
    re.compile(r"subdl", re.IGNORECASE),
    re.compile(r"addic7ed", re.IGNORECASE),
    re.compile(r"yify", re.IGNORECASE),
]

# SDH / formatting regexes
RE_HTML = re.compile(r"<[^>]+>")
RE_ASS = re.compile(r"\{[^}]+\}")
RE_BRACKETS = re.compile(r"\[[^\]]*\]")
RE_PARENS = re.compile(r"\([^\)]*\)")
RE_MUSIC_SPAN = re.compile(r"[♪♫#♩][^♪♫#♩\n]*[♪♫#♩]?")
RE_MUSIC_SOLO = re.compile(r"[♪♫#♩]+")
RE_SPEAKER_PREFIX = re.compile(r"^(?:-\s*)?[A-Z0-9\s_'-]{1,30}:\s*")
RE_DIALOGUE_DASH = re.compile(r"^-\s*")
RE_WORD = re.compile(r"\b[a-zA-Z']+\b")


@dataclass
class CleanedCue:
    start_ms: int
    end_ms: int
    text: str
    words: List[str]


def clean_line_text(text: str) -> Optional[str]:
    """Clean a single subtitle line: strip HTML, ASS, SDH, speaker labels, and check watermarks."""
    if not text:
        return None

    # Check for watermark
    for pattern in WATERMARK_PATTERNS:
        if pattern.search(text):
            return None

    # Strip HTML and ASS tags
    cleaned = RE_HTML.sub(" ", text)
    cleaned = RE_ASS.sub(" ", cleaned)

    # Strip SDH annotations in brackets and parentheses
    cleaned = RE_BRACKETS.sub(" ", cleaned)
    cleaned = RE_PARENS.sub(" ", cleaned)

    # Strip music notes and spans between them
    cleaned = RE_MUSIC_SPAN.sub(" ", cleaned)
    cleaned = RE_MUSIC_SOLO.sub(" ", cleaned)

    # Clean lines split by newline
    lines = [l.strip() for l in cleaned.splitlines() if l.strip()]
    processed_lines = []
    for line in lines:
        for pattern in WATERMARK_PATTERNS:
            if pattern.search(line):
                line = ""
                break
        if not line:
            continue
        # Strip speaker prefix (e.g. "MAN:", "- NARRATOR:")
        line = RE_SPEAKER_PREFIX.sub("", line)
        line = RE_DIALOGUE_DASH.sub("", line).strip()
        if line:
            processed_lines.append(line)

    result = " ".join(processed_lines)
    result = re.sub(r"\s+", " ", result).strip()
    return result if result else None


def extract_filtered_words(text: str) -> List[str]:
    """Extract individual words, stripping punctuation and low-information stopwords."""
    raw_tokens = RE_WORD.findall(text)
    words = []
    for token in raw_tokens:
        clean = token.strip("'").lower()
        if clean and clean not in STOPWORDS:
            words.append(clean)
    return words


def passes_entropy_filter(words: List[str], text: str) -> bool:
    """Filter requiring >= 4 remaining words AND >= 12 total consonant characters."""
    if len(words) < 4:
        return False
    consonant_count = sum(1 for char in text.lower() if char in CONSONANTS)
    return consonant_count >= 12


def parse_srt_safely(raw_srt_content: str) -> List[srt.Subtitle]:
    """Parse raw SRT content safely with normalization fallbacks."""
    if not raw_srt_content:
        return []

    # Strip null bytes (defense-in-depth against UTF-16 misdecoding)
    content = raw_srt_content.replace("\x00", "")
    # Strip UTF-8 BOM if present
    content = content.lstrip("\ufeff")
    # Normalize line endings to \n
    content = content.replace("\r\n", "\n").replace("\r", "\n")

    try:
        return list(srt.parse(content))
    except Exception as exc:
        logger.warning("Standard SRT parsing failed (%s), attempting line recovery...", exc)
        try:
            # Try parsing with lenience
            recovered = []
            for block in content.split("\n\n"):
                block = block.strip()
                if not block:
                    continue
                try:
                    for sub in srt.parse(block):
                        recovered.append(sub)
                except Exception:
                    continue
            return recovered
        except Exception:
            return []


def sanitize_subtitles(raw_srt_content: str) -> List[Dict[str, Any]]:
    """Parse raw SRT and apply text cleaning, clause concatenation, and entropy filtering.

    Returns a list of sanitized dialogue entries:
    [
        {
            "start_ms": int,
            "end_ms": int,
            "text": str,
            "words": List[str]
        },
        ...
    ]
    """
    raw_subs = parse_srt_safely(raw_srt_content)
    if not raw_subs:
        return []

    # 1. Clean individual subtitle blocks
    cleaned_cues: List[CleanedCue] = []
    for sub in raw_subs:
        cleaned_text = clean_line_text(sub.content)
        if not cleaned_text:
            continue
        words = extract_filtered_words(cleaned_text)
        start_ms = int(sub.start.total_seconds() * 1000)
        end_ms = int(sub.end.total_seconds() * 1000)
        cleaned_cues.append(
            CleanedCue(
                start_ms=start_ms,
                end_ms=end_ms,
                text=cleaned_text,
                words=words,
            )
        )

    if not cleaned_cues:
        return []

    # Sort cues by start_ms
    cleaned_cues.sort(key=lambda c: c.start_ms)

    # 2. Build candidate entries:
    #    - Individual 1-blocks
    #    - 2-block sliding window (gap < 400ms)
    #    - 3-block sliding window (all consecutive gaps < 400ms)
    candidates: List[Dict[str, Any]] = []
    n = len(cleaned_cues)

    for i in range(n):
        cue_i = cleaned_cues[i]
        # 1-block candidate
        if passes_entropy_filter(cue_i.words, cue_i.text):
            candidates.append({
                "start_ms": cue_i.start_ms,
                "end_ms": cue_i.end_ms,
                "text": cue_i.text,
                "words": cue_i.words,
            })

        # 2-block candidate
        if i + 1 < n:
            cue_next = cleaned_cues[i + 1]
            gap_1 = cue_next.start_ms - cue_i.end_ms
            if 0 <= gap_1 < 400:
                combined_text_2 = f"{cue_i.text} {cue_next.text}"
                combined_words_2 = cue_i.words + cue_next.words
                if passes_entropy_filter(combined_words_2, combined_text_2):
                    candidates.append({
                        "start_ms": cue_i.start_ms,
                        "end_ms": cue_next.end_ms,
                        "text": combined_text_2,
                        "words": combined_words_2,
                    })

                # 3-block candidate
                if i + 2 < n:
                    cue_next2 = cleaned_cues[i + 2]
                    gap_2 = cue_next2.start_ms - cue_next.end_ms
                    if 0 <= gap_2 < 400:
                        combined_text_3 = f"{combined_text_2} {cue_next2.text}"
                        combined_words_3 = combined_words_2 + cue_next2.words
                        if passes_entropy_filter(combined_words_3, combined_text_3):
                            candidates.append({
                                "start_ms": cue_i.start_ms,
                                "end_ms": cue_next2.end_ms,
                                "text": combined_text_3,
                                "words": combined_words_3,
                            })

    # Deduplicate candidates by (start_ms, tuple(words))
    seen = set()
    deduped: List[Dict[str, Any]] = []
    for cand in candidates:
        key = (cand["start_ms"], tuple(cand["words"]))
        if key not in seen:
            seen.add(key)
            deduped.append(cand)

    deduped.sort(key=lambda x: (x["start_ms"], x["end_ms"]))
    return deduped
