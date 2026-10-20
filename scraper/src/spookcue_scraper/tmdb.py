"""TMDB API client for movie metadata and poster retrieval."""

import logging
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)


class TmdbClient:
    """Interacts with TMDB API v3 with rate limiting."""

    BASE_URL = "https://api.themoviedb.org/3"

    def __init__(self, api_key: Optional[str] = None) -> None:
        self.api_key = api_key

    async def search_movie(self, title: str, year: Optional[int] = None) -> Optional[int]:
        """Search for TMDB ID given movie title and release year."""
        # Phase 1 implementation
        return None

    async def get_movie_details(self, tmdb_id: int) -> Dict[str, Any]:
        """Fetch details: runtime, poster_path, genres, original_language."""
        # Phase 1 implementation
        return {}
