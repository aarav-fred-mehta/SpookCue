"""TMDB API client for movie metadata and poster retrieval."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from pathlib import Path
from typing import Any, Dict, Optional

import httpx

logger = logging.getLogger(__name__)

# Cache duration: 6 months (~180 days)
CACHE_TTL_SECONDS = 180 * 24 * 3600


class TokenBucket:
    """Token-bucket rate limiter (defaults to 40 requests per 10s = 4.0 req/s)."""

    def __init__(self, capacity: float = 40.0, refill_rate: float = 4.0) -> None:
        self.capacity = capacity
        self.refill_rate = refill_rate
        self.tokens = capacity
        self.last_update = time.monotonic()
        self._lock: Optional[asyncio.Lock] = None

    async def acquire(self) -> None:
        if self._lock is None:
            self._lock = asyncio.Lock()
        async with self._lock:
            now = time.monotonic()
            elapsed = now - self.last_update
            self.last_update = now
            self.tokens = min(self.capacity, self.tokens + elapsed * self.refill_rate)
            if self.tokens < 1.0:
                wait_time = (1.0 - self.tokens) / self.refill_rate
                await asyncio.sleep(wait_time)
                self.tokens = 0.0
                self.last_update = time.monotonic()
            else:
                self.tokens -= 1.0


class TmdbClient:
    """Interacts with TMDB API v3 with token-bucket rate limiting and caching."""

    BASE_URL = "https://api.themoviedb.org/3"

    def __init__(
        self,
        api_key: Optional[str] = None,
        cache_dir: Optional[Path] = None,
        client: Optional[httpx.AsyncClient] = None,
        rate_limiter: Optional[TokenBucket] = None,
    ) -> None:
        self.api_key = api_key or os.environ.get("TMDB_API_KEY", "")
        self.cache_dir = cache_dir or Path(".tmdb_cache")
        self._client = client
        self._owns_client = client is None
        self.rate_limiter = rate_limiter or TokenBucket()

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=15.0)
            self._owns_client = True
        return self._client

    async def close(self) -> None:
        if self._owns_client and self._client and not self._client.is_closed:
            await self._client.aclose()

    def _get_cache_path(self, key: str) -> Path:
        safe_key = "".join(c if c.isalnum() or c in "-_" else "_" for c in key)
        return self.cache_dir / f"{safe_key}.json"

    def _read_cache(self, key: str) -> Optional[Dict[str, Any]]:
        path = self._get_cache_path(key)
        if not path.exists():
            return None
        try:
            with open(path, "r", encoding="utf-8") as f:
                entry = json.load(f)
            cached_at = entry.get("cached_at", 0)
            if time.time() - cached_at < CACHE_TTL_SECONDS:
                return entry.get("data")
        except Exception as exc:
            logger.debug("Error reading cache for %s: %s", key, exc)
        return None

    def _write_cache(self, key: str, data: Dict[str, Any]) -> None:
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            path = self._get_cache_path(key)
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"cached_at": time.time(), "data": data}, f)
        except Exception as exc:
            logger.debug("Error writing cache for %s: %s", key, exc)

    async def search_movie(self, title: str, year: Optional[int] = None) -> Optional[int]:
        """Search for TMDB ID given movie title and release year."""
        if not self.api_key:
            logger.warning("No TMDB_API_KEY provided; skipping TMDB search for '%s'", title)
            return None

        cache_key = f"search_{title}_{year}"
        cached = self._read_cache(cache_key)
        if cached is not None:
            return cached.get("tmdb_id")

        client = await self._get_client()
        await self.rate_limiter.acquire()

        params: Dict[str, Any] = {
            "api_key": self.api_key,
            "query": title,
            "include_adult": False,
        }
        if year:
            params["year"] = year

        try:
            resp = await client.get(f"{self.BASE_URL}/search/movie", params=params)
            resp.raise_for_status()
            data = resp.json()
            results = data.get("results", [])
            if results:
                tmdb_id = results[0].get("id")
                self._write_cache(cache_key, {"tmdb_id": tmdb_id})
                return tmdb_id
        except Exception as exc:
            logger.error("TMDB search failed for '%s' (%s): %s", title, year, exc)

        return None

    async def get_movie_details(self, tmdb_id: int) -> Dict[str, Any]:
        """Fetch details: runtime, poster_path, genres, original_language."""
        if not self.api_key:
            logger.warning("No TMDB_API_KEY provided; returning empty details for TMDB ID %d", tmdb_id)
            return {}

        cache_key = f"movie_{tmdb_id}"
        cached = self._read_cache(cache_key)
        if cached is not None:
            return cached

        client = await self._get_client()
        await self.rate_limiter.acquire()

        params = {"api_key": self.api_key}
        try:
            resp = await client.get(f"{self.BASE_URL}/movie/{tmdb_id}", params=params)
            resp.raise_for_status()
            data = resp.json()
            details = {
                "runtime": data.get("runtime"),
                "poster_path": data.get("poster_path"),
                "genres": [g.get("name") for g in data.get("genres", []) if "name" in g],
                "original_language": data.get("original_language"),
                "release_date": data.get("release_date"),
                "title": data.get("title"),
            }
            self._write_cache(cache_key, details)
            return details
        except Exception as exc:
            logger.error("TMDB get_movie_details failed for ID %d: %s", tmdb_id, exc)
            return {}
