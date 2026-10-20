"""Subtitle fetcher supporting SubDL and OpenSubtitles fallback with caching."""

from __future__ import annotations

import io
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional
import zipfile

import httpx

logger = logging.getLogger(__name__)


class SubtitleFetcher:
    """Fetches English SRT subtitle tracks for horror movies."""

    SUBDL_API_BASE = "https://api.subdl.com/api/v1"
    SUBDL_DL_BASE = "https://dl.subdl.com"
    OPENSUBTITLES_API_BASE = "https://api.opensubtitles.com/api/v1"

    def __init__(
        self,
        subdl_api_key: Optional[str] = None,
        opensubtitles_api_key: Optional[str] = None,
        cache_dir: Optional[Path] = None,
        client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self.subdl_api_key = subdl_api_key or os.environ.get("SUBDL_API_KEY", "")
        self.opensubtitles_api_key = opensubtitles_api_key or os.environ.get("OPENSUBTITLES_API_KEY", "")
        self.cache_dir = cache_dir or Path(".subtitles_cache")
        self._client = client
        self._owns_client = client is None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=30.0, follow_redirects=True)
            self._owns_client = True
        return self._client

    async def close(self) -> None:
        if self._owns_client and self._client and not self._client.is_closed:
            await self._client.aclose()

    def _get_cache_path(self, tmdb_id: int) -> Path:
        return self.cache_dir / f"{tmdb_id}.srt"

    def read_cached_subtitles(self, tmdb_id: int) -> Optional[str]:
        path = self._get_cache_path(tmdb_id)
        if path.exists():
            try:
                return path.read_text(encoding="utf-8", errors="replace")
            except Exception as exc:
                logger.debug("Failed to read cached subtitles for %d: %s", tmdb_id, exc)
        return None

    def write_cached_subtitles(self, tmdb_id: int, srt_content: str) -> None:
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            path = self._get_cache_path(tmdb_id)
            path.write_text(srt_content, encoding="utf-8", errors="replace")
        except Exception as exc:
            logger.debug("Failed to cache subtitles for %d: %s", tmdb_id, exc)

    @staticmethod
    def extract_srt_from_bytes(data: bytes) -> Optional[str]:
        """Extract text from raw SRT bytes or an in-memory ZIP archive."""
        if not data:
            return None

        # Check for ZIP archive magic bytes ('PK\x03\x04')
        if data.startswith(b"PK\x03\x04"):
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as zf:
                    # Find all .srt files, pick the largest one (usually complete film track)
                    srt_names = [n for n in zf.namelist() if n.lower().endswith(".srt") and not n.startswith("__MACOSX")]
                    if not srt_names:
                        return None
                    largest_file = max(srt_names, key=lambda n: zf.getinfo(n).file_size)
                    raw_bytes = zf.read(largest_file)
                    return raw_bytes.decode("utf-8", errors="replace")
            except Exception as exc:
                logger.warning("Failed to extract zip archive for subtitles: %s", exc)
                return None

        try:
            return data.decode("utf-8", errors="replace")
        except Exception:
            return None

    @staticmethod
    def build_subdl_params(
        api_key: str,
        tmdb_id: int,
    ) -> Dict[str, Any]:
        """Construct SubDL API query params ensuring type='movie' (type='srt' returns 422)."""
        return {
            "api_key": api_key,
            "tmdb_id": tmdb_id,
            "languages": "en",
            "type": "movie",
        }

    @staticmethod
    def build_subdl_fallback_params(
        api_key: str,
        title: str,
        year: Optional[int] = None,
    ) -> Dict[str, Any]:
        """Construct SubDL API fallback query params using film_name and year."""
        params: Dict[str, Any] = {
            "api_key": api_key,
            "film_name": title,
            "languages": "en",
            "type": "movie",
        }
        if year:
            params["year"] = year
        return params

    async def fetch_from_subdl(
        self,
        tmdb_id: int,
        title: str = "",
        year: Optional[int] = None,
    ) -> Optional[str]:
        """Query SubDL API for English subtitles by TMDB ID (with fallback to title/year search)."""
        if not self.subdl_api_key:
            return None

        client = await self._get_client()
        # Note: SubDL requires type to be 'movie' or 'tv' (not 'srt', which triggers 422 Unprocessable Entity)
        params = self.build_subdl_params(self.subdl_api_key, tmdb_id)

        try:
            resp = await client.get(f"{self.SUBDL_API_BASE}/subtitles", params=params)
            resp.raise_for_status()
            data = resp.json()
            subtitles = data.get("subtitles", []) if data.get("status") else []

            # If no subtitles found by tmdb_id, fall back to searching by title and year
            if not subtitles and title:
                fallback_params = self.build_subdl_fallback_params(self.subdl_api_key, title, year)
                fb_resp = await client.get(f"{self.SUBDL_API_BASE}/subtitles", params=fallback_params)
                if fb_resp.status_code == 200:
                    fb_data = fb_resp.json()
                    if fb_data.get("status"):
                        subtitles = fb_data.get("subtitles", [])

            if not subtitles:
                return None

            # Pick the first English subtitle entry with a download URL
            dl_path = None
            for sub in subtitles:
                if sub.get("url"):
                    dl_path = sub["url"]
                    break

            if not dl_path:
                return None

            full_dl_url = f"{self.SUBDL_DL_BASE}{dl_path}" if dl_path.startswith("/") else dl_path
            dl_resp = await client.get(full_dl_url)
            dl_resp.raise_for_status()

            return self.extract_srt_from_bytes(dl_resp.content)
        except Exception as exc:
            logger.debug("SubDL fetch failed for TMDB ID %d (%s): %s", tmdb_id, title, exc)
            return None

    async def fetch_from_opensubtitles(
        self,
        tmdb_id: int,
        title: str = "",
        year: Optional[int] = None,
    ) -> Optional[str]:
        """Query OpenSubtitles REST API fallback."""
        if not self.opensubtitles_api_key:
            return None

        client = await self._get_client()
        headers = {
            "Api-Key": self.opensubtitles_api_key,
            "User-Agent": "SpookCue v1.0",
        }
        params: Dict[str, Any] = {
            "tmdb_id": tmdb_id,
            "languages": "en",
        }

        try:
            resp = await client.get(f"{self.OPENSUBTITLES_API_BASE}/subtitles", params=params, headers=headers)
            resp.raise_for_status()
            data = resp.json()
            results = data.get("data", [])

            # Fallback to query by title and year if tmdb_id yielded nothing
            if not results and title:
                fallback_params: Dict[str, Any] = {
                    "query": title,
                    "languages": "en",
                }
                if year:
                    fallback_params["year"] = year
                fb_resp = await client.get(f"{self.OPENSUBTITLES_API_BASE}/subtitles", params=fallback_params, headers=headers)
                if fb_resp.status_code == 200:
                    results = fb_resp.json().get("data", [])

            if not results:
                return None

            # Get download link from first result files
            files = results[0].get("attributes", {}).get("files", [])
            if not files:
                return None
            file_id = files[0].get("file_id")
            if not file_id:
                return None

            # Request download URL
            dl_resp = await client.post(
                f"{self.OPENSUBTITLES_API_BASE}/download",
                json={"file_id": file_id},
                headers=headers,
            )
            dl_resp.raise_for_status()
            dl_link = dl_resp.json().get("link")
            if not dl_link:
                return None

            content_resp = await client.get(dl_link)
            content_resp.raise_for_status()
            return self.extract_srt_from_bytes(content_resp.content)
        except Exception as exc:
            logger.debug("OpenSubtitles fetch failed for TMDB ID %d (%s): %s", tmdb_id, title, exc)
            return None

    async def fetch_subtitles(
        self,
        tmdb_id: int,
        title: str = "",
        year: Optional[int] = None,
    ) -> Optional[str]:
        """Fetch English SRT content for a movie. Checks cache first, then SubDL, then OpenSubtitles."""
        # Check local cache first
        cached = self.read_cached_subtitles(tmdb_id)
        if cached:
            return cached

        # Primary: SubDL
        srt_content = await self.fetch_from_subdl(tmdb_id, title=title, year=year)

        # Fallback: OpenSubtitles
        if not srt_content:
            srt_content = await self.fetch_from_opensubtitles(tmdb_id, title=title, year=year)

        if srt_content:
            self.write_cached_subtitles(tmdb_id, srt_content)
            return srt_content

        logger.info("No subtitles found for TMDB ID %d (%s); will fallback to clock-only mode", tmdb_id, title)
        return None
