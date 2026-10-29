"""Unit tests for the main scraper orchestrator pipeline."""

import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

from spookcue_scraper.main import (
    dump_json_bytes,
    extract_tmdb_id_from_slug,
    load_existing_catalog,
    load_existing_version,
    load_json_bytes,
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

    def test_pipeline_skips_existing_movie_with_subtitles(self):
        """Verify that an existing movie with subtitles is completely skipped (no web requests)."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                # Write existing movie with subtitles
                movie_doc = {
                    "tmdb_id": 138843,
                    "title": "The Conjuring",
                    "release_year": 2013,
                    "runtime_minutes": 112,
                    "scares": [],
                    "phonetic_index": [{"s": 1000, "e": 2000, "p": ["HH", "L"]}],
                }
                (movies_dir / "138843.json").write_text(dump_json_bytes(movie_doc).decode("utf-8"))

                catalog_data = [
                    {
                        "tmdb_id": 138843,
                        "title": "The Conjuring",
                        "release_year": 2013,
                        "runtime_minutes": 112,
                        "scare_count": 0,
                        "poster_path": "",
                        "has_subtitles": True,
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                mock_index = [{"title": "The Conjuring", "year": 2013, "tmdb_id": 138843}]
                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock()) as mock_parse, \
                     patch("spookcue_scraper.main.SubtitleFetcher.fetch_subtitles", AsyncMock()) as mock_fetch_subs:

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["added"], 0)
                    self.assertEqual(result["updated"], 0)
                    mock_parse.assert_not_called()
                    mock_fetch_subs.assert_not_called()

        asyncio.run(run_test())

    def test_pipeline_retries_missing_subtitles_without_rescraping(self):
        """Verify that existing movies lacking subtitles only fetch subtitles without re-scraping jumpscares."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                # Existing movie file missing subtitles (clock-only mode)
                movie_doc = {
                    "tmdb_id": 138843,
                    "title": "The Conjuring",
                    "release_year": 2013,
                    "runtime_minutes": 112,
                    "scares": [
                        {
                            "id": "scare_01",
                            "resolved_time_ms": 2438000,
                            "type": "major",
                            "description": "Hands clap behind Carolyn.",
                            "anchor_phonetic": None,
                            "offset_from_anchor_ms": None,
                        }
                    ],
                    "phonetic_index": [],
                }
                movie_file = movies_dir / "138843.json"
                movie_file.write_text(dump_json_bytes(movie_doc).decode("utf-8"))

                catalog_data = [
                    {
                        "tmdb_id": 138843,
                        "title": "The Conjuring",
                        "release_year": 2013,
                        "runtime_minutes": 112,
                        "scare_count": 1,
                        "poster_path": "",
                        "has_subtitles": False,
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                sample_srt = "1\n00:40:30,000 --> 00:40:35,000\nDo you hear clapping in the dark cellar?\n"
                mock_index = [{"title": "The Conjuring", "year": 2013, "tmdb_id": 138843}]

                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock()) as mock_parse, \
                     patch("spookcue_scraper.main.TmdbClient.search_movie", AsyncMock()) as mock_tmdb_search, \
                     patch("spookcue_scraper.main.SubtitleFetcher.fetch_subtitles", AsyncMock(return_value=sample_srt)) as mock_fetch_subs:

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["added"], 0)
                    self.assertEqual(result["updated"], 1)

                    # Ensure jump scares HTML and TMDB were NOT re-scraped
                    mock_parse.assert_not_called()
                    mock_tmdb_search.assert_not_called()

                    # Subtitle fetcher WAS called
                    mock_fetch_subs.assert_called_once_with(138843, title="The Conjuring", year=2013, imdb_id=None)

                    # Verify movie file was updated with phonetic index and anchor
                    updated_movie = load_json_bytes(movie_file.read_bytes())
                    self.assertGreater(len(updated_movie["phonetic_index"]), 0)
                    scare = updated_movie["scares"][0]
                    self.assertIsNotNone(scare["anchor_phonetic"])
                    self.assertIsNotNone(scare["offset_from_anchor_ms"])

                    # Verify catalog was updated to has_subtitles: True
                    updated_catalog = load_json_bytes((data_dir / "catalog.json").read_bytes())
                    self.assertTrue(updated_catalog[0]["has_subtitles"])

        asyncio.run(run_test())

    def test_pipeline_skips_retry_when_subtitles_exhausted(self):
        """Verify that when subtitle providers are exhausted, retry attempts are skipped."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                movie_doc = {
                    "tmdb_id": 138843,
                    "title": "The Conjuring",
                    "release_year": 2013,
                    "runtime_minutes": 112,
                    "scares": [],
                    "phonetic_index": [],
                }
                (movies_dir / "138843.json").write_text(dump_json_bytes(movie_doc).decode("utf-8"))

                catalog_data = [
                    {
                        "tmdb_id": 138843,
                        "title": "The Conjuring",
                        "release_year": 2013,
                        "runtime_minutes": 112,
                        "scare_count": 0,
                        "poster_path": "",
                        "has_subtitles": False,
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                mock_index = [{"title": "The Conjuring", "year": 2013, "tmdb_id": 138843}]

                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.SubtitleFetcher.is_exhausted", new_callable=lambda: True), \
                     patch("spookcue_scraper.main.SubtitleFetcher.fetch_subtitles", AsyncMock()) as mock_fetch_subs:

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["added"], 0)
                    self.assertEqual(result["updated"], 0)
                    mock_fetch_subs.assert_not_called()

        asyncio.run(run_test())


    def test_pipeline_reconciles_scares_and_preserves_subtitles(self):
        """Verify that existing subtitles are preserved while jump scares are reconciled from WhenJumpScare."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                existing_phonetic = [
                    {"s": 2430000, "p": ["K", "L", "P"]},
                    {"s": 2432000, "p": ["D", "R", "K"]},
                ]
                old_scares = [
                    {
                        "id": "scare_01",
                        "time": "00:40:00",
                        "resolved_time_ms": 2400000,
                        "type": "minor",
                        "description": "Old WTJ scare",
                        "anchor_phonetic": None,
                        "offset_from_anchor_ms": None,
                    }
                ]
                movie_doc = {
                    "tmdb_id": 138843,
                    "title": "The Conjuring",
                    "release_year": 2013,
                    "runtime_minutes": 112,
                    "scares": old_scares,
                    "phonetic_index": existing_phonetic,
                    "source": "wheresthejump",
                    "source_lastmod": None,
                }
                (movies_dir / "138843.json").write_text(dump_json_bytes(movie_doc).decode("utf-8"))

                catalog_data = [
                    {
                        "tmdb_id": 138843,
                        "title": "The Conjuring",
                        "release_year": 2013,
                        "runtime_minutes": 112,
                        "scare_count": 1,
                        "poster_path": "/poster.jpg",
                        "has_subtitles": True,
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                mock_index = [
                    {
                        "title": "The Conjuring",
                        "year": 2013,
                        "tmdb_id": 138843,
                        "url": "https://www.whenjumpscare.com/movie/138843-the-conjuring-2013",
                        "slug": "138843-the-conjuring-2013",
                        "lastmod": "2026-10-28T12:00:00Z",
                        "source": "whenjumpscare",
                    }
                ]

                fresh_scares = [
                    {
                        "time": "00:40:38",
                        "resolved_time_ms": 2438000,
                        "type": "major",
                        "description": "Hands clap behind Carolyn in cellar",
                    },
                    {
                        "time": "00:42:40",
                        "resolved_time_ms": 2560000,
                        "type": "major",
                        "description": "Monster appears on wardrobe",
                    },
                ]

                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock(return_value={"scares": fresh_scares, "runtime_minutes": 112})), \
                     patch("spookcue_scraper.main.SubtitleFetcher.fetch_subtitles", AsyncMock()) as mock_fetch_subs:

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["updated"], 1)
                    # Subtitles were NOT re-downloaded because they were preserved!
                    mock_fetch_subs.assert_not_called()

                    updated_doc = load_json_bytes((movies_dir / "138843.json").read_bytes())
                    self.assertEqual(len(updated_doc["scares"]), 2)
                    self.assertEqual(updated_doc["source"], "whenjumpscare")
                    self.assertEqual(updated_doc["source_lastmod"], "2026-10-28T12:00:00Z")
                    self.assertEqual(len(updated_doc["phonetic_index"]), 2)
                    # First scare re-anchored to 'clapping' at 2430000
                    scare1 = updated_doc["scares"][0]
                    self.assertEqual(scare1["resolved_time_ms"], 2438000)
                    self.assertEqual(scare1["type"], "major")
                    self.assertIsNotNone(scare1["anchor_phonetic"])

                    updated_catalog = load_json_bytes((data_dir / "catalog.json").read_bytes())
                    self.assertEqual(updated_catalog[0]["scare_count"], 2)

        asyncio.run(run_test())

    def test_pipeline_reconciles_legacy_tmdb_id(self):
        """Verify that when WhenJumpScare provides canonical TMDB ID, legacy files and IDs are migrated."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                legacy_doc = {
                    "tmdb_id": 999999,
                    "title": "Saw",
                    "release_year": 2004,
                    "runtime_minutes": 103,
                    "scares": [],
                    "phonetic_index": [],
                    "source": "wheresthejump",
                }
                (movies_dir / "999999.json").write_text(dump_json_bytes(legacy_doc).decode("utf-8"))

                catalog_data = [
                    {
                        "tmdb_id": 999999,
                        "title": "Saw",
                        "release_year": 2004,
                        "runtime_minutes": 103,
                        "scare_count": 0,
                        "poster_path": "",
                        "has_subtitles": False,
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                mock_index = [
                    {
                        "title": "Saw",
                        "year": 2004,
                        "tmdb_id": 176,
                        "url": "https://www.whenjumpscare.com/movie/176-saw-2004",
                        "slug": "176-saw-2004",
                        "lastmod": "2026-10-28T00:00:00Z",
                        "source": "whenjumpscare",
                    }
                ]

                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock(return_value={"scares": [], "runtime_minutes": 103})), \
                     patch("spookcue_scraper.main.SubtitleFetcher.fetch_subtitles", AsyncMock(return_value=None)):

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)

                    # Verify old file was deleted and migrated to 176.json
                    self.assertFalse((movies_dir / "999999.json").exists())
                    self.assertTrue((movies_dir / "176.json").exists())

                    migrated_doc = load_json_bytes((movies_dir / "176.json").read_bytes())
                    self.assertEqual(migrated_doc["tmdb_id"], 176)

                    updated_catalog = load_json_bytes((data_dir / "catalog.json").read_bytes())
                    cat_ids = [m["tmdb_id"] for m in updated_catalog]
                    self.assertIn(176, cat_ids)
                    self.assertNotIn(999999, cat_ids)

        asyncio.run(run_test())

    def test_pipeline_skips_scare_page_fetch_if_lastmod_unchanged(self):
        """Verify that if lastmod matches and source is whenjumpscare, scare page is not re-fetched."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                movie_doc = {
                    "tmdb_id": 138843,
                    "title": "The Conjuring",
                    "release_year": 2013,
                    "runtime_minutes": 112,
                    "scares": [{"time": "00:40:38", "resolved_time_ms": 2438000, "type": "major"}],
                    "phonetic_index": [{"phonetic": "T E S T", "time_ms": 1000, "word": "test"}],
                    "source": "whenjumpscare",
                    "source_lastmod": "2026-10-28T12:00:00Z",
                }
                (movies_dir / "138843.json").write_text(dump_json_bytes(movie_doc).decode("utf-8"))

                catalog_data = [
                    {
                        "tmdb_id": 138843,
                        "title": "The Conjuring",
                        "release_year": 2013,
                        "runtime_minutes": 112,
                        "scare_count": 1,
                        "poster_path": "",
                        "has_subtitles": True,
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                mock_index = [
                    {
                        "title": "The Conjuring",
                        "year": 2013,
                        "tmdb_id": 138843,
                        "url": "https://www.whenjumpscare.com/movie/138843-the-conjuring-2013",
                        "slug": "138843-the-conjuring-2013",
                        "lastmod": "2026-10-28T12:00:00Z",
                        "source": "whenjumpscare",
                    }
                ]

                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock()) as mock_parse_page:

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["updated"], 0)
                    mock_parse_page.assert_not_called()

        asyncio.run(run_test())

if __name__ == "__main__":
    unittest.main()
