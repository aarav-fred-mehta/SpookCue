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

    def test_subdl_429_exhaustion(self):
        """Verify consecutive 429 responses trigger exhaustion and fast fail."""
        async def run_test():
            client = MagicMock()
            resp_429 = MagicMock()
            resp_429.status_code = 429
            resp_429.headers = {}
            client.get = AsyncMock(return_value=resp_429)

            fetcher = SubtitleFetcher(subdl_api_key="mock_key", client=client)
            self.assertFalse(fetcher.is_exhausted)

            res1 = await fetcher.fetch_from_subdl(12345, "Scary Movie", 2000)
            self.assertIsNone(res1)
            self.assertFalse(fetcher.is_exhausted)

            # Second 429 triggers exhaustion
            res2 = await fetcher.fetch_from_subdl(12346, "Scary Movie 2", 2001)
            self.assertIsNone(res2)
            self.assertTrue(fetcher.is_exhausted)

            # Third call returns None immediately without calling client.get
            client.get.reset_mock()
            res3 = await fetcher.fetch_from_subdl(12347, "Scary Movie 3", 2003)
            self.assertIsNone(res3)
            client.get.assert_not_called()

        asyncio.run(run_test())

    def test_subdl_download_429_exhaustion(self):
        """Verify 429 on the binary download endpoint also triggers exhaustion."""
        async def run_test():
            client = MagicMock()
            resp_search_ok = MagicMock()
            resp_search_ok.status_code = 200
            resp_search_ok.json.return_value = {
                "status": True,
                "subtitles": [{"url": "/subtitle/123.zip"}],
            }

            resp_dl_429 = MagicMock()
            resp_dl_429.status_code = 429
            resp_dl_429.headers = {}

            # First call: search returns OK, download returns 429
            # Second call: search returns OK, download returns 429 -> exhaustion
            client.get = AsyncMock(side_effect=[resp_search_ok, resp_dl_429, resp_search_ok, resp_dl_429])

            fetcher = SubtitleFetcher(subdl_api_key="mock_key", client=client)
            self.assertFalse(fetcher.is_exhausted)

            res1 = await fetcher.fetch_from_subdl(1001, "The Ring", 2002)
            self.assertIsNone(res1)
            self.assertFalse(fetcher.is_exhausted)

            res2 = await fetcher.fetch_from_subdl(1002, "The Grudge", 2004)
            self.assertIsNone(res2)
            self.assertTrue(fetcher.is_exhausted)

        asyncio.run(run_test())

    def test_extract_srt_from_utf16le_with_bom(self):
        """Verify UTF-16 LE with BOM is decoded correctly without null bytes."""
        raw_text = "1\r\n00:00:10,000 --> 00:00:12,000\r\nHello Freddy\r\n"
        data = raw_text.encode("utf-16")  # writes BOM \xff\xfe
        extracted = SubtitleFetcher.extract_srt_from_bytes(data)
        self.assertNotIn("\x00", extracted)
        self.assertIn("Hello Freddy", extracted)

    def test_extract_srt_from_utf16le_without_bom(self):
        """Verify UTF-16 LE without BOM (Error 3) is decoded cleanly without null bytes."""
        raw_text = "1\r\n00:00:10,000 --> 00:00:12,000\r\nJesse runs away from the door\r\n"
        data = raw_text.encode("utf-16-le")  # no BOM
        extracted = SubtitleFetcher.extract_srt_from_bytes(data)
        self.assertNotIn("\x00", extracted)
        self.assertIn("Jesse runs away", extracted)

    def test_extract_srt_from_zip_utf16le(self):
        """Verify ZIP archive containing UTF-16 LE subtitle is extracted cleanly."""
        raw_text = "1\r\n00:01:00,000 --> 00:01:05,000\r\nFreddy grabs Lisa's face\r\n"
        zip_buffer = io.BytesIO()
        with zipfile.ZipFile(zip_buffer, "w") as zf:
            zf.writestr("nightmare2.srt", raw_text.encode("utf-16-le"))

        extracted = SubtitleFetcher.extract_srt_from_bytes(zip_buffer.getvalue())
        self.assertNotIn("\x00", extracted)
        self.assertIn("Freddy grabs Lisa's face", extracted)


if __name__ == "__main__":
    unittest.main()
