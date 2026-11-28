"""TMDB API client for movie metadata, discovery, and poster retrieval."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
import json
import logging
import os
from pathlib import Path
import re
import time
from typing import Any, Dict, List, Optional, Set

import httpx

logger = logging.getLogger(__name__)

# TMDB Terms of Use cap cached API data at 6 months; use 150 days to stay safely under cap.
CACHE_TTL_SECONDS = 150 * 24 * 3600
CONFIG_CACHE_TTL_SECONDS = 7 * 24 * 3600
HORROR_GENRE_ID = 27
MAX_RETRIES = 3

RE_EDITION_SUFFIX = re.compile(
    r"\s*(?:[-–—:]\s*|\()\s*(?:Director['’]s Cut|Extended Cut|Unrated.*|Original Australian.*|Blu-Ray.*|Collector['’]s Edition|Alternate Ending|Special Edition|Remastered|Theatrical Cut|Ultimate Edition|International Cut|Final Cut|Restored|\d+fps).*",
    re.IGNORECASE,
)

KNOWN_TITLE_ALIASES: Dict[str, int] = {
    "vacancy 3: terror trap": 48574,
    "terror trap": 48574,
    "things heard and seen": 581644,
    "things heard & seen": 581644,
}


def generate_title_candidates(title: str) -> List[str]:
    """Generate search candidates for a title by normalizing punctuation, stripping edition qualifiers, and trying sub-titles."""
    if not title:
        return []

    normalized = title.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"').strip()
    candidates = [normalized]

    if " and " in normalized.lower():
        candidates.append(re.sub(r"\band\b", "&", normalized, flags=re.IGNORECASE).strip())
    if " & " in normalized:
        candidates.append(normalized.replace(" & ", " and ").strip())

    cleaned = RE_EDITION_SUFFIX.sub("", normalized).rstrip(" )").strip()
    if cleaned and cleaned not in candidates:
        candidates.append(cleaned)

    for sep in (" – ", " — ", " - "):
        if sep in normalized:
            base = normalized.split(sep)[0].strip()
            if base and base not in candidates:
                candidates.append(base)

    no_parens = re.sub(r"\s*\([^)]*\)", "", normalized).strip()
    if no_parens and no_parens not in candidates:
        candidates.append(no_parens)

    if ":" in normalized:
        parts = [p.strip() for p in normalized.split(":", 1) if p.strip()]
        if len(parts) == 2:
            prefix, subtitle = parts[0], parts[1]
            if len(subtitle) >= 3 and subtitle not in candidates:
                candidates.append(subtitle)
            if len(prefix) >= 3 and prefix not in candidates:
                candidates.append(prefix)
            space_ver = f"{prefix} {subtitle}".strip()
            if space_ver not in candidates:
                candidates.append(space_ver)

    seen = set()
    result = []
    for c in candidates:
        low = c.lower()
        if low not in seen:
            seen.add(low)
            result.append(c)

    return result


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
    """Interacts with TMDB API v3 with rate limiting, 429 Retry-After backoff, and TTL caching."""

    BASE_URL = "https://api.themoviedb.org/3"
    USER_AGENT = "SpookCue-Pipeline/0.1 (+https://github.com/aarav-fred-mehta/SpookCue)"

    def __init__(
        self,
        api_key: Optional[str] = None,
        access_token: Optional[str] = None,
        cache_dir: Optional[Path] = None,
        client: Optional[httpx.AsyncClient] = None,
        rate_limiter: Optional[TokenBucket] = None,
    ) -> None:
        raw_key = (api_key if api_key is not None else os.environ.get("TMDB_API_KEY", "")).strip()
        raw_token = (access_token if access_token is not None else os.environ.get("TMDB_ACCESS_TOKEN", "")).strip()

        # Auto-detect TMDB v4 Read Access Token (JWT) even if passed via TMDB_API_KEY
        if not raw_token and raw_key.startswith("eyJ") and "." in raw_key:
            self.access_token = raw_key
            self.api_key = ""
        else:
            self.access_token = raw_token
            self.api_key = raw_key

        self.cache_dir = cache_dir or Path(".tmdb_cache")
        self._client = client
        self._owns_client = client is None
        self._not_found_endpoints: Set[str] = set()
        self.rate_limiter = rate_limiter or TokenBucket()

    @property
    def has_credentials(self) -> bool:
        return bool(self.access_token or self.api_key)

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed is True:
            headers = {
                "Accept": "application/json",
                "User-Agent": self.USER_AGENT,
            }
            if self.access_token:
                headers["Authorization"] = f"Bearer {self.access_token}"
            self._client = httpx.AsyncClient(headers=headers, timeout=15.0)
            self._owns_client = True
        return self._client

    async def close(self) -> None:
        if self._owns_client and self._client and not self._client.is_closed:
            await self._client.aclose()

    def _get_cache_path(self, key: str) -> Path:
        safe_key = "".join(c if c.isalnum() or c in "-_" else "_" for c in key)
        return self.cache_dir / f"{safe_key}.json"

    def _read_cache(self, key: str, ttl_seconds: int = CACHE_TTL_SECONDS) -> Optional[Dict[str, Any]]:
        path = self._get_cache_path(key)
        if not path.exists():
            return None
        try:
            with open(path, "r", encoding="utf-8") as f:
                entry = json.load(f)
            cached_at = entry.get("cached_at", 0)
            if time.time() - cached_at < ttl_seconds:
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

    def _auth_params(self, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        merged = dict(params or {})
        if not self.access_token and self.api_key:
            merged["api_key"] = self.api_key
        return merged

    def _auth_headers(self) -> Dict[str, str]:
        if self.access_token:
            return {"Authorization": f"Bearer {self.access_token}", "Accept": "application/json"}
        return {"Accept": "application/json"}

    async def _request_json(
        self,
        endpoint: str,
        params: Optional[Dict[str, Any]] = None,
    ) -> Optional[Dict[str, Any]]:
        """Execute a TMDB API GET request with rate limiting, Retry-After 429 handling, and safe logging."""
        if not self.has_credentials:
            return None

        client = await self._get_client()
        url = f"{self.BASE_URL}{endpoint}"
        query_params = self._auth_params(params)

        for attempt in range(MAX_RETRIES):
            await self.rate_limiter.acquire()
            try:
                kwargs: Dict[str, Any] = {"params": query_params}
                if self.access_token and not self._owns_client:
                    kwargs["headers"] = self._auth_headers()
                resp = await client.get(url, **kwargs)

                if resp.status_code == 429:
                    retry_after_raw = resp.headers.get("Retry-After", "") if hasattr(resp, "headers") else ""
                    try:
                        wait_s = max(float(retry_after_raw), float(2 ** attempt))
                    except (ValueError, TypeError):
                        wait_s = float(2 ** (attempt + 1))
                    wait_s = min(wait_s, 30.0)
                    logger.warning("TMDB HTTP 429 on %s; backing off %.1fs (attempt %d/%d)", endpoint, wait_s, attempt + 1, MAX_RETRIES)
                    await asyncio.sleep(wait_s)
                    continue

                if resp.status_code in (500, 502, 503, 504) and attempt < MAX_RETRIES - 1:
                    wait_s = float(2 ** attempt)
                    logger.warning("TMDB transient HTTP %d on %s; retrying in %.1fs", resp.status_code, endpoint, wait_s)
                    await asyncio.sleep(wait_s)
                    continue

                if resp.status_code == 404:
                    self._not_found_endpoints.add(endpoint)
                    logger.debug("TMDB HTTP 404 on %s", endpoint)
                    return None

                resp.raise_for_status()
                return resp.json()
            except httpx.HTTPStatusError as exc:
                logger.debug("TMDB HTTP %d on %s", exc.response.status_code, endpoint)
                return None
            except Exception as exc:
                logger.debug("TMDB request failed for %s: %s", endpoint, type(exc).__name__)
                if attempt < MAX_RETRIES - 1:
                    await asyncio.sleep(float(2 ** attempt))
                    continue
                return None
        return None

    async def get_configuration(self) -> Dict[str, Any]:
        """Fetch and cache TMDB API image configuration per TMDB guidelines."""
        if not self.has_credentials:
            return {}
        cached = self._read_cache("tmdb_configuration", ttl_seconds=CONFIG_CACHE_TTL_SECONDS)
        if cached is not None:
            return cached
        data = await self._request_json("/configuration")
        if data:
            self._write_cache("tmdb_configuration", data)
            return data
        return {}

    async def _query_tmdb_search(
        self,
        query: str,
        year: Optional[int] = None,
    ) -> Optional[int]:
        """Execute a single TMDB search query with rate limiting."""
        params: Dict[str, Any] = {
            "query": query,
            "include_adult": False,
        }
        if year:
            params["year"] = year

        data = await self._request_json("/search/movie", params=params)
        if not data:
            return None
        results = data.get("results", [])
        if results:
            return results[0].get("id")
        return None

    async def search_movie(self, title: str, year: Optional[int] = None) -> Optional[int]:
        """Search for TMDB ID given movie title and release year, using candidate fallback."""
        if not self.has_credentials:
            logger.warning("No TMDB credentials provided; skipping TMDB search for '%s'", title)
            return None

        normalized_title = title.strip().lower()
        if normalized_title in KNOWN_TITLE_ALIASES:
            return KNOWN_TITLE_ALIASES[normalized_title]

        cache_key = f"search_{title}_{year}"
        cached = self._read_cache(cache_key)
        if cached is not None:
            return cached.get("tmdb_id")

        candidates = generate_title_candidates(title)

        for candidate in candidates:
            tmdb_id = await self._query_tmdb_search(candidate, year)
            if tmdb_id:
                self._write_cache(cache_key, {"tmdb_id": tmdb_id})
                return tmdb_id

        if year:
            for offset in (-1, 1):
                for candidate in candidates:
                    tmdb_id = await self._query_tmdb_search(candidate, year + offset)
                    if tmdb_id:
                        self._write_cache(cache_key, {"tmdb_id": tmdb_id})
                        return tmdb_id

        for candidate in candidates:
            tmdb_id = await self._query_tmdb_search(candidate, None)
            if tmdb_id:
                self._write_cache(cache_key, {"tmdb_id": tmdb_id})
                return tmdb_id

        return None

    async def get_movie_details(self, tmdb_id: int) -> Dict[str, Any]:
        """Fetch details: runtime, poster_path, genres, original_language, release_date, title."""
        if not self.has_credentials:
            logger.warning("No TMDB credentials provided; returning empty details for TMDB ID %d", tmdb_id)
            return {}

        cache_key = f"movie_{tmdb_id}"
        cached = self._read_cache(cache_key)
        if cached is not None:
            return cached

        endpoint = f"/movie/{tmdb_id}"
        data = await self._request_json(endpoint)
        if not data:
            if endpoint in self._not_found_endpoints:
                logger.warning("TMDB ID %d returned HTTP 404 (not found on TMDB); caching miss", tmdb_id)
                not_found_marker: Dict[str, Any] = {"_not_found": True}
                self._write_cache(cache_key, not_found_marker)
                return not_found_marker
            logger.error("TMDB get_movie_details failed for ID %d", tmdb_id)
            return {}

        details = {
            "runtime": data.get("runtime"),
            "poster_path": data.get("poster_path"),
            "genres": [g.get("name") for g in data.get("genres", []) if "name" in g],
            "original_language": data.get("original_language"),
            "release_date": data.get("release_date"),
            "title": data.get("title"),
            "imdb_id": data.get("imdb_id"),
        }
        self._write_cache(cache_key, details)
        return details

    async def get_now_playing_movie_ids(self) -> Set[int]:
        """Fetch currently running theatrical horror TMDB IDs using valid TMDB endpoints & params."""
        if not self.has_credentials:
            return set()

        now_playing_ids: Set[int] = set()
        now_utc = datetime.now(timezone.utc)
        min_year = now_utc.year - 2

        # 1. /movie/now_playing (valid params: language, page; filter horror genre & recent release date client-side)
        for page in (1, 2, 3):
            data = await self._request_json(
                "/movie/now_playing",
                params={"language": "en-US", "page": page},
            )
            if not data:
                break
            results = data.get("results", [])
            for item in results:
                mid = item.get("id")
                genre_ids = item.get("genre_ids", [])
                rel_date = str(item.get("release_date") or "")
                rel_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else None
                is_recent = rel_year is None or rel_year >= min_year
                if mid and HORROR_GENRE_ID in genre_ids and is_recent:
                    now_playing_ids.add(mid)
            if page >= int(data.get("total_pages", 1)):
                break

        # 2. /discover/movie for recent theatrical horror (last 90 days)
        start_date = (now_utc - timedelta(days=90)).strftime("%Y-%m-%d")
        end_date = now_utc.strftime("%Y-%m-%d")
        for page in (1, 2):
            data = await self._request_json(
                "/discover/movie",
                params={
                    "language": "en-US",
                    "include_adult": False,
                    "with_genres": HORROR_GENRE_ID,
                    "with_release_type": "2|3",
                    "primary_release_date.gte": start_date,
                    "primary_release_date.lte": end_date,
                    "sort_by": "popularity.desc",
                    "page": page,
                },
            )
            if not data:
                break
            for item in data.get("results", []):
                mid = item.get("id")
                if mid:
                    now_playing_ids.add(mid)
            if page >= int(data.get("total_pages", 1)):
                break

        return now_playing_ids

    async def get_popular_horror_ids(self) -> Set[int]:
        """Fetch top popular & weekly trending horror movie TMDB IDs."""
        if not self.has_credentials:
            return set()

        popular_ids: Set[int] = set()

        # 1. Discover top popular horror films (pages 1..5 = up to 100 films)
        for page in range(1, 6):
            data = await self._request_json(
                "/discover/movie",
                params={
                    "language": "en-US",
                    "include_adult": False,
                    "with_genres": HORROR_GENRE_ID,
                    "sort_by": "popularity.desc",
                    "vote_count.gte": 300,
                    "page": page,
                },
            )
            if not data:
                break
            for item in data.get("results", []):
                mid = item.get("id")
                if mid:
                    popular_ids.add(mid)
            if page >= int(data.get("total_pages", 1)):
                break

        # 2. Weekly trending movies filtered to horror
        trending_data = await self._request_json(
            "/trending/movie/week",
            params={"language": "en-US"},
        )
        if trending_data:
            for item in trending_data.get("results", []):
                mid = item.get("id")
                genre_ids = item.get("genre_ids", [])
                if mid and HORROR_GENRE_ID in genre_ids:
                    popular_ids.add(mid)

        return popular_ids
