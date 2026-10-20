"""Unit tests for subtitle fetcher and zip extraction."""

import asyncio
import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock
import httpx
from spookcue_scraper.subtitles import SubtitleFetcher


class TestSubtitles(unittest.TestCase):
    def test_extract_srt_from_plain_bytes(self):
        raw_text = "1\n00:00:10,000 --> 00:00:12,000\nHello world\n"
        data = raw_text.encode("utf-8")
        extracted = SubtitleFetcher.extract_srt_from_bytes(data)
        self.assertEqual(extracted, raw_text)

    def test_extract_srt_from_zip_bytes(self):
        srt_content = "1\n00:01:00,000 --> 00:01:05,000\nDialogue in zip\n"
        zip_buffer = io.BytesIO()
        with zipfile.ZipFile(zip_buffer, "w") as zf:
            zf.writestr("movie_subtitles.srt", srt_content.encode("utf-8"))
            zf.writestr("readme.txt", b"Some info")

        zip_bytes = zip_buffer.getvalue()
        extracted = SubtitleFetcher.extract_srt_from_bytes(zip_bytes)
        self.assertEqual(extracted, srt_content)

    def test_cache_subtitles(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            cache_path = Path(tmp_dir)
            fetcher = SubtitleFetcher(cache_dir=cache_path)

            srt_content = "1\n00:00:01,000 --> 00:00:02,000\nCached subtitle"
            fetcher.write_cached_subtitles(12345, srt_content)

            cached = fetcher.read_cached_subtitles(12345)
            self.assertEqual(cached, srt_content)

    def test_subdl_params_use_movie_type_not_srt(self):
        """Verify SubDL uses type='movie' to prevent 422 Unprocessable Entity."""
        params = SubtitleFetcher.build_subdl_params(
            api_key="test_api_key",
            tmdb_id=8329,
        )
        self.assertEqual(params["type"], "movie")
        self.assertNotEqual(params["type"], "srt")
        self.assertEqual(params["tmdb_id"], 8329)
        self.assertEqual(params["api_key"], "test_api_key")
        self.assertEqual(params["languages"], "en")

    def test_subdl_fallback_params_construction(self):
        """Verify SubDL fallback params use film_name and year."""
        params = SubtitleFetcher.build_subdl_fallback_params(
            api_key="test_api_key",
            title="[Rec]",
            year=2007,
        )
        self.assertEqual(params["type"], "movie")
        self.assertEqual(params["film_name"], "[Rec]")
        self.assertEqual(params["year"], 2007)
        self.assertEqual(params["languages"], "en")


if __name__ == "__main__":
    unittest.main()
