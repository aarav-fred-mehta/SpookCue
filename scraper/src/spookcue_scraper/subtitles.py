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


def decode_subtitle_bytes(raw_bytes: bytes) -> Optional[str]:
    """Decode raw subtitle bytes supporting UTF-8 (with/without BOM), UTF-16 LE/BE, and CP1252."""
    if not raw_bytes:
        return None

    # 1. Check Byte Order Marks (BOM)
    if raw_bytes.startswith(b"\xef\xbb\xbf"):
        return raw_bytes[3:].decode("utf-8", errors="replace").replace("\x00", "")
    if raw_bytes.startswith(b"\xff\xfe"):
        return raw_bytes[2:].decode("utf-16-le", errors="replace").replace("\x00", "")
    if raw_bytes.startswith(b"\xfe\xff"):
        return raw_bytes[2:].decode("utf-16-be", errors="replace").replace("\x00", "")

    # 2. Only attempt UTF-16 detection if raw_bytes actually contains null bytes
    if b"\x00" in raw_bytes:
        # Check UTF-16 LE (most common Windows format)
        try:
            decoded = raw_bytes.decode("utf-16-le", errors="replace")
            # In valid UTF-16 LE text, decoding consumes all null bytes and yields timestamps or newlines
            if "\x00" not in decoded and ("-->" in decoded or "\n" in decoded):
                return decoded
        except Exception:
            pass

        # Check UTF-16 BE
        try:
            decoded = raw_bytes.decode("utf-16-be", errors="replace")
            if "\x00" not in decoded and ("-->" in decoded or "\n" in decoded):
                return decoded
        except Exception:
            pass

    # 3. Standard UTF-8 (strict first)
    try:
        decoded = raw_bytes.decode("utf-8")
        if "\x00" not in decoded:
            return decoded
        return decoded.replace("\x00", "")
    except UnicodeDecodeError:
        pass

    # 4. Fallback: CP1252 / Latin-1
    try:
        decoded = raw_bytes.decode("cp1252", errors="replace")
    except Exception:
        decoded = raw_bytes.decode("latin-1", errors="replace")

    return decoded.replace("\x00", "")


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
        self._subdl_exhausted = False
        self._subdl_consecutive_429 = 0
        self._opensubtitles_exhausted = False

    @property
    def is_exhausted(self) -> bool:
        """Check if configured subtitle providers have hit rate limits or quotas."""
        configured_providers = 0
        exhausted_providers = 0
        if self.subdl_api_key:
            configured_providers += 1
            if self._subdl_exhausted:
                exhausted_providers += 1
        if self.opensubtitles_api_key:
            configured_providers += 1
            if self._opensubtitles_exhausted:
                exhausted_providers += 1

        if configured_providers == 0:
            return False

        return configured_providers == exhausted_providers

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed is True:
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
                    return decode_subtitle_bytes(raw_bytes)
            except Exception as exc:
                logger.warning("Failed to extract zip archive for subtitles: %s", exc)
                return None

        return decode_subtitle_bytes(data)

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
        imdb_id: Optional[str] = None,
    ) -> Optional[str]:
        """Query SubDL API for English subtitles by TMDB ID or IMDb ID (with fallback to title/year search)."""
        if not self.subdl_api_key or self._subdl_exhausted:
            return None

        client = await self._get_client()
        subtitles: List[Dict[str, Any]] = []

        # 1. Try search by IMDb ID if available (highest accuracy on SubDL)
        if imdb_id:
            imdb_params = {
                "api_key": self.subdl_api_key,
                "imdb_id": imdb_id,
                "languages": "en",
                "type": "movie",
            }
            try:
                resp = await client.get(f"{self.SUBDL_API_BASE}/subtitles", params=imdb_params)
                if resp.status_code == 429:
                    self._subdl_consecutive_429 += 1
                    logger.warning("SubDL API rate limit reached (HTTP 429) for TMDB ID %d (%s)", tmdb_id, title)
                    if self._subdl_consecutive_429 >= 2:
                        logger.warning("SubDL quota appears exhausted; disabling SubDL for remainder of run.")
                        self._subdl_exhausted = True
                    return None
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("status") and data.get("subtitles"):
                        subtitles = data.get("subtitles", [])
            except Exception as exc:
                logger.debug("SubDL imdb_id query failed for %s (%s): %s", imdb_id, title, exc)

        # 2. Try search by TMDB ID
        if not subtitles and not self._subdl_exhausted:
            params = self.build_subdl_params(self.subdl_api_key, tmdb_id)
            try:
                resp = await client.get(f"{self.SUBDL_API_BASE}/subtitles", params=params)
                if resp.status_code == 429:
                    self._subdl_consecutive_429 += 1
                    logger.warning("SubDL API rate limit reached (HTTP 429) for TMDB ID %d (%s)", tmdb_id, title)
                    if self._subdl_consecutive_429 >= 2:
                        logger.warning("SubDL quota appears exhausted; disabling SubDL for remainder of run.")
                        self._subdl_exhausted = True
                    return None
                if resp.status_code == 200:
                    data = resp.json()
                    if data.get("status") and data.get("subtitles"):
                        subtitles = data.get("subtitles", [])
            except Exception as exc:
                logger.debug("SubDL tmdb_id query failed for %d (%s): %s", tmdb_id, title, exc)

        # 3. Fallback: try searching by title candidates and year
        if not subtitles and title and not self._subdl_exhausted:
            from spookcue_scraper.tmdb import generate_title_candidates
            candidates = generate_title_candidates(title)
            for candidate in candidates:
                fallback_params = self.build_subdl_fallback_params(self.subdl_api_key, candidate, year)
                try:
                    fb_resp = await client.get(f"{self.SUBDL_API_BASE}/subtitles", params=fallback_params)
                    if fb_resp.status_code == 429:
                        self._subdl_consecutive_429 += 1
                        logger.warning("SubDL API rate limit reached (HTTP 429) for TMDB ID %d (%s)", tmdb_id, title)
                        if self._subdl_consecutive_429 >= 2:
                            logger.warning("SubDL quota appears exhausted; disabling SubDL for remainder of run.")
                            self._subdl_exhausted = True
                        return None

                    if fb_resp.status_code == 200:
                        fb_data = fb_resp.json()
                        if fb_data.get("status") and fb_data.get("subtitles"):
                            subtitles = fb_data.get("subtitles", [])
                            break
                except Exception:
                    pass

            # Also try candidates without year if year was provided and no results found
            if not subtitles and year and not self._subdl_exhausted:
                for candidate in candidates:
                    fb_params = self.build_subdl_fallback_params(self.subdl_api_key, candidate, None)
                    try:
                        fb_resp = await client.get(f"{self.SUBDL_API_BASE}/subtitles", params=fb_params)
                        if fb_resp.status_code == 200:
                            fb_data = fb_resp.json()
                            if fb_data.get("status") and fb_data.get("subtitles"):
                                subtitles = fb_data.get("subtitles", [])
                                break
                    except Exception:
                        pass

        if not subtitles:
            if not self._subdl_exhausted:
                logger.info("SubDL returned 0 English subtitles for '%s' (TMDB %d)", title, tmdb_id)
            return None

        logger.info("SubDL found %d English subtitle track(s) for '%s' (TMDB %d)", len(subtitles), title, tmdb_id)

        # Pick the first English subtitle entry with a download URL
        dl_path = None
        for sub in subtitles:
            if sub.get("url"):
                dl_path = sub["url"]
                break

        if not dl_path:
            return None

        full_dl_url = f"{self.SUBDL_DL_BASE}{dl_path}" if dl_path.startswith("/") else dl_path
        try:
            dl_resp = await client.get(full_dl_url)
            if dl_resp.status_code == 429:
                self._subdl_consecutive_429 += 1
                logger.warning(
                    "SubDL download rate limit reached (HTTP 429) for TMDB ID %d (%s)",
                    tmdb_id,
                    title,
                )
                if self._subdl_consecutive_429 >= 2:
                    logger.warning("SubDL download quota appears exhausted; disabling SubDL for remainder of run.")
                    self._subdl_exhausted = True
                return None

            dl_resp.raise_for_status()
            self._subdl_consecutive_429 = 0
            return self.extract_srt_from_bytes(dl_resp.content)
        except Exception as exc:
            logger.debug("SubDL fetch failed for TMDB ID %d (%s): %s", tmdb_id, title, exc)
            return None

    async def fetch_from_opensubtitles(
        self,
        tmdb_id: int,
        title: str = "",
        year: Optional[int] = None,
        imdb_id: Optional[str] = None,
    ) -> Optional[str]:
        """Query OpenSubtitles REST API fallback."""
        if not self.opensubtitles_api_key or self._opensubtitles_exhausted:
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
        if imdb_id:
            num = imdb_id.lstrip("t")
            if num.isdigit():
                params["imdb_id"] = int(num)

        try:
            resp = await client.get(f"{self.OPENSUBTITLES_API_BASE}/subtitles", params=params, headers=headers)
            if resp.status_code == 429:
                logger.warning("OpenSubtitles API rate limit reached (HTTP 429)")
                self._opensubtitles_exhausted = True
                return None

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
                if fb_resp.status_code == 429:
                    logger.warning("OpenSubtitles API rate limit reached (HTTP 429)")
                    self._opensubtitles_exhausted = True
                    return None
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
            if dl_resp.status_code == 429:
                logger.warning("OpenSubtitles download rate limit reached (HTTP 429)")
                self._opensubtitles_exhausted = True
                return None

            dl_resp.raise_for_status()
            dl_link = dl_resp.json().get("link")
            if not dl_link:
                return None

            content_resp = await client.get(dl_link)
            if content_resp.status_code == 429:
                logger.warning("OpenSubtitles download rate limit reached (HTTP 429)")
                self._opensubtitles_exhausted = True
                return None

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
        imdb_id: Optional[str] = None,
    ) -> Optional[str]:
        """Fetch English SRT content for a movie. Checks cache first, then SubDL, then OpenSubtitles."""
        # Check local cache first
        cached = self.read_cached_subtitles(tmdb_id)
        if cached:
            return cached

        if self.is_exhausted:
            return None

        # Primary: SubDL
        srt_content = await self.fetch_from_subdl(tmdb_id, title=title, year=year, imdb_id=imdb_id)

        # Fallback: OpenSubtitles
        if not srt_content:
            srt_content = await self.fetch_from_opensubtitles(tmdb_id, title=title, year=year, imdb_id=imdb_id)

        if srt_content:
            self.write_cached_subtitles(tmdb_id, srt_content)
            return srt_content

        if not self.is_exhausted:
            logger.info("No subtitles found for TMDB ID %d (%s); will fallback to clock-only mode", tmdb_id, title)
        return None
