"""Subtitle fetcher supporting SubDL and OpenSubtitles fallback."""

import logging
from typing import Optional

logger = logging.getLogger(__name__)


class SubtitleFetcher:
    """Fetches English SRT subtitle tracks for horror movies."""

    def __init__(self, subdl_api_key: Optional[str] = None) -> None:
        self.subdl_api_key = subdl_api_key

    async def fetch_subtitles(self, tmdb_id: int, title: str) -> Optional[str]:
        """Fetch raw SRT text by TMDB ID or title."""
        # Phase 1 implementation
        return None
