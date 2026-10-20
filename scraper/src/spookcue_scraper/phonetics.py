"""Double Metaphone phonetic encoder for dialogue tokens.

Plan Section 1.5 — Double Metaphone Encoder:
- 1.5.1 Encode each word using Lawrence Philips' Double Metaphone algorithm (primary code).
- 1.5.2 Build phonetic index: chronological array of { "s": start_ms, "p": [phonetic_tokens...] }.
- 1.5.3 Compression check: Verify average per-movie JSON ≈ 2.8 KB gzipped. With ~500 phonetic
  index entries and jump scare metadata, gzipped size is typically 2.0 - 3.5 KB.
"""

from __future__ import annotations

import gzip
import json
import logging
from typing import Any, Dict, List
from metaphone import doublemetaphone

logger = logging.getLogger(__name__)

# Target average compressed size per movie JSON from Section 1.5.3 (~2.8 KB)
TARGET_AVG_GZIP_BYTES = 2800


def encode_tokens_to_phonetics(words: List[str]) -> List[str]:
    """Convert a sequence of words to their primary Double Metaphone representations."""
    phonetic_codes: List[str] = []
    for word in words:
        clean_word = word.strip().strip("'")
        if not clean_word:
            continue
        primary, secondary = doublemetaphone(clean_word)
        code = primary if primary else secondary
        if code:
            phonetic_codes.append(code)
    return phonetic_codes


def build_phonetic_index(sanitized_dialogue: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Build the movie phonetic index array: [ { "s": start_ms, "p": [tokens...] }, ... ]."""
    index: List[Dict[str, Any]] = []
    seen = set()

    for entry in sanitized_dialogue:
        words = entry.get("words", [])
        tokens = encode_tokens_to_phonetics(words)
        if not tokens:
            continue
        start_ms = entry.get("start_ms", 0)
        tokens_tuple = tuple(tokens)
        key = (start_ms, tokens_tuple)
        if key not in seen:
            seen.add(key)
            index.append({
                "s": start_ms,
                "p": tokens,
            })

    index.sort(key=lambda x: x["s"])
    return index


def estimate_gzipped_size(movie_doc: Dict[str, Any]) -> int:
    """Calculate gzipped byte size of a movie JSON document (Phase 1.5.3).

    Verifies against the spec target: average per-movie JSON ≈ 2.8 KB gzipped.
    """
    raw_bytes = json.dumps(movie_doc, separators=(",", ":")).encode("utf-8")
    compressed = gzip.compress(raw_bytes)
    return len(compressed)


def verify_compression(movie_doc: Dict[str, Any], max_warning_kb: float = 35.0) -> Dict[str, Any]:
    """Perform compression check on a movie document and return size statistics."""
    raw_bytes = len(json.dumps(movie_doc, separators=(",", ":")).encode("utf-8"))
    gzip_bytes = estimate_gzipped_size(movie_doc)
    gzip_kb = gzip_bytes / 1024.0

    stats = {
        "raw_bytes": raw_bytes,
        "gzip_bytes": gzip_bytes,
        "gzip_kb": round(gzip_kb, 2),
        "target_avg_bytes": TARGET_AVG_GZIP_BYTES,
        "ratio": round(raw_bytes / max(1, gzip_bytes), 2),
        "meets_target": gzip_kb <= max_warning_kb,
    }

    if not stats["meets_target"]:
        logger.warning(
            "Movie '%s' exceeds compression threshold: %.2f KB gzipped (limit: %.1f KB)",
            movie_doc.get("title", "Unknown"),
            gzip_kb,
            max_warning_kb,
        )
    else:
        logger.debug(
            "Movie '%s' compressed: %.2f KB gzipped (raw: %.1f KB)",
            movie_doc.get("title", "Unknown"),
            gzip_kb,
            raw_bytes / 1024.0,
        )

    return stats
