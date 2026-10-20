"""Unit tests for subtitle fetcher and zip extraction."""

import io
import tempfile
import unittest
import zipfile
from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
