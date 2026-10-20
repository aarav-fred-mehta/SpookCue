"""Unit tests for the main scraper orchestrator pipeline."""

import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

from spookcue_scraper.main import (
    extract_tmdb_id_from_slug,
    load_existing_catalog,
    load_existing_version,
    process_movie,
    run_pipeline,
)


class TestMainPipeline(unittest.TestCase):
    def test_load_existing_catalog_missing(self):
        result = load_existing_catalog(Path("non_existent_catalog.json"))
        self.assertEqual(result, [])

    def test_load_existing_version_missing(self):
        result = load_existing_version(Path("non_existent_version.json"))
        self.assertEqual(result["v"], 0)

    def test_extract_tmdb_id_from_slug(self):
        self.assertEqual(extract_tmdb_id_from_slug("138843-the-conjuring"), 138843)
        self.assertEqual(extract_tmdb_id_from_slug("the-conjuring-138843"), 138843)
        self.assertEqual(extract_tmdb_id_from_slug("https://example.com/movie/tmdb-138843/"), 138843)
        self.assertEqual(extract_tmdb_id_from_slug("movie?tmdb_id=138843"), 138843)
        # Year only (e.g. 2013) should not be mistaken for 5+ digit TMDB ID
        self.assertIsNone(extract_tmdb_id_from_slug("jump-scares-in-the-conjuring-2013"))

    def test_process_movie_with_mocked_clients(self):
        async def run_test():
            item = {
                "title": "The Conjuring",
                "year": 2013,
                "url": "https://wheresthejump.com/jump-scares-in-the-conjuring-2013/",
            }

            scraper = MagicMock()
            scraper.parse_movie_page = AsyncMock(
                return_value={
                    "title": "The Conjuring",
                    "year": 2013,
                    "runtime_minutes": 112,
                    "scares": [
                        {
                            "time": "00:40:38",
                            "resolved_time_ms": 2438000,
                            "type": "major",
                            "description": "Hands clap behind Carolyn while she is in the basement.",
                        }
                    ],
                }
            )

            tmdb_client = MagicMock()
            tmdb_client.search_movie = AsyncMock(return_value=138843)
            tmdb_client.get_movie_details = AsyncMock(
                return_value={
                    "runtime": 112,
                    "poster_path": "/wVYREutTvI2tmxr6ujrHT704wGF.jpg",
                }
            )

            sample_srt = """1
00:40:30,000 --> 00:40:35,000
Do you hear clapping in the dark cellar?
"""
            subtitles_fetcher = MagicMock()
            subtitles_fetcher.fetch_subtitles = AsyncMock(return_value=sample_srt)

            result = await process_movie(
                item=item,
                scraper=scraper,
                tmdb_client=tmdb_client,
                subtitles_fetcher=subtitles_fetcher,
                known_tmdb_ids={},
            )

            self.assertIsNotNone(result)
            movie_doc = result["movie_doc"]
            catalog_entry = result["catalog_entry"]

            self.assertEqual(movie_doc["tmdb_id"], 138843)
            self.assertEqual(movie_doc["title"], "The Conjuring")
            self.assertEqual(catalog_entry["has_subtitles"], True)
            self.assertEqual(len(movie_doc["scares"]), 1)
            # Verify scare was resolved to anchor
            scare = movie_doc["scares"][0]
            self.assertEqual(scare["id"], "scare_01")
            self.assertEqual(scare["resolved_time_ms"], 2438000)
            self.assertIsNotNone(scare["anchor_phonetic"])
            self.assertEqual(scare["offset_from_anchor_ms"], 2438000 - 2430000)

        asyncio.run(run_test())

    def test_process_movie_extracts_tmdb_id_from_slug_primary(self):
        """Verify TMDB ID extraction from slug bypasses search API call (Plan 1.1.3)."""
        async def run_test():
            item = {
                "title": "The Conjuring",
                "year": 2013,
                "slug": "138843-the-conjuring",
            }

            scraper = MagicMock()
            scraper.parse_movie_page = AsyncMock(
                return_value={
                    "title": "The Conjuring",
                    "year": 2013,
                    "runtime_minutes": 112,
                    "scares": [],
                }
            )

            tmdb_client = MagicMock()
            tmdb_client.search_movie = AsyncMock()
            tmdb_client.get_movie_details = AsyncMock(return_value={})

            subtitles_fetcher = MagicMock()
            subtitles_fetcher.fetch_subtitles = AsyncMock(return_value=None)

            result = await process_movie(
                item=item,
                scraper=scraper,
                tmdb_client=tmdb_client,
                subtitles_fetcher=subtitles_fetcher,
                known_tmdb_ids={},
            )

            self.assertIsNotNone(result)
            self.assertEqual(result["movie_doc"]["tmdb_id"], 138843)
            # Ensure search_movie was NOT called because ID came from slug!
            tmdb_client.search_movie.assert_not_called()

        asyncio.run(run_test())

    def test_pipeline_dry_run_idempotency(self):
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                # Run with empty index (no internet)
                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=[])):
                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["added"], 0)
                    self.assertEqual(result["updated"], 0)

        asyncio.run(run_test())


if __name__ == "__main__":
    unittest.main()
