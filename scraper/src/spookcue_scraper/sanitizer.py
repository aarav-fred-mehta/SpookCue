"""Subtitle sanitization pipeline.

Strips SDH annotations, HTML/ASS tags, uploader credits, removes low-information
stopwords, concatenates short temporal clauses (<400ms gap), and filters by minimum entropy.
"""

import logging
from typing import Any, List

logger = logging.getLogger(__name__)


def sanitize_subtitles(raw_srt_content: str) -> List[Any]:
    """Parse raw SRT and apply text cleaning rules."""
    # Phase 1 implementation
    return []
