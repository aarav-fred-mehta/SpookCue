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
    def setUp(self):
        self.patcher1 = patch("spookcue_scraper.main.TmdbClient.get_now_playing_movie_ids", AsyncMock(return_value=set()))
        self.patcher2 = patch("spookcue_scraper.main.TmdbClient.get_popular_horror_ids", AsyncMock(return_value=set()))
        self.patcher1.start()
        self.patcher2.start()

    def tearDown(self):
        self.patcher1.stop()
        self.patcher2.stop()
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

            with tempfile.TemporaryDirectory() as tmp_dir:
                sync_dir = Path(tmp_dir) / "sync"
                sync_dir.mkdir(parents=True)

                result = await process_movie(
                    item=item,
                    scraper=scraper,
                    tmdb_client=tmdb_client,
                    known_tmdb_ids={},
                    sync_dir=sync_dir,
                )

                self.assertIsNotNone(result)
                movie_doc = result["movie_doc"]
                catalog_entry = result["catalog_entry"]

                self.assertEqual(movie_doc["tmdb_id"], 138843)
                self.assertEqual(movie_doc["title"], "The Conjuring")
                self.assertEqual(movie_doc["sync_type"], "audio_hash")
                self.assertFalse(catalog_entry["has_acoustic_sync"])
                self.assertEqual(len(movie_doc["scares"]), 1)
                scare = movie_doc["scares"][0]
                self.assertEqual(scare["id"], "scare_01")
                self.assertEqual(scare["resolved_time_ms"], 2438000)
                self.assertEqual(scare["type"], "major")
                self.assertNotIn("anchor_phonetic", scare)
                self.assertNotIn("offset_from_anchor_ms", scare)

        asyncio.run(run_test())

    def test_process_movie_extracts_tmdb_id_from_slug_primary(self):
        """Verify TMDB ID extraction from slug bypasses search API call."""
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

            with tempfile.TemporaryDirectory() as tmp_dir:
                sync_dir = Path(tmp_dir) / "sync"
                sync_dir.mkdir(parents=True)

                result = await process_movie(
                    item=item,
                    scraper=scraper,
                    tmdb_client=tmdb_client,
                    known_tmdb_ids={},
                    sync_dir=sync_dir,
                )

                self.assertIsNotNone(result)
                self.assertEqual(result["movie_doc"]["tmdb_id"], 138843)
                tmdb_client.search_movie.assert_not_called()

        asyncio.run(run_test())

    def test_pipeline_dry_run_idempotency(self):
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=[])):
                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["added"], 0)
                    self.assertEqual(result["updated"], 0)

        asyncio.run(run_test())

    def test_pipeline_skips_existing_movie_up_to_date(self):
        """Verify that an existing movie up to date is completely skipped."""
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
                    "scares": [{"id": "scare_01", "resolved_time_ms": 1000, "type": "minor", "description": ""}],
                    "source": "whenjumpscare",
                    "source_lastmod": "2026-10-28T12:00:00Z",
                    "sync_type": "audio_hash",
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
                        "has_acoustic_sync": False,
                        "sync_type": "audio_hash",
                    }
                ]
                (data_dir / "catalog.json").write_text(dump_json_bytes(catalog_data).decode("utf-8"))

                mock_index = [
                    {
                        "title": "The Conjuring",
                        "year": 2013,
                        "tmdb_id": 138843,
                        "source": "whenjumpscare",
                        "lastmod": "2026-10-28T12:00:00Z",
                        "jump_count": 1,
                    }
                ]
                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock()) as mock_parse:

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["added"], 0)
                    self.assertEqual(result["updated"], 0)
                    mock_parse.assert_not_called()

        asyncio.run(run_test())

    def test_pipeline_reconciles_scares_from_whenjumpscare(self):
        """Verify that jump scares are reconciled from WhenJumpScare when lastmod changes."""
        async def run_test():
            with tempfile.TemporaryDirectory() as tmp_dir:
                data_dir = Path(tmp_dir)
                movies_dir = data_dir / "movies"
                movies_dir.mkdir(parents=True)

                old_scares = [
                    {
                        "id": "scare_01",
                        "resolved_time_ms": 2400000,
                        "type": "minor",
                        "description": "Old WTJ scare",
                    }
                ]
                movie_doc = {
                    "tmdb_id": 138843,
                    "title": "The Conjuring",
                    "release_year": 2013,
                    "runtime_minutes": 112,
                    "scares": old_scares,
                    "source": "wheresthejump",
                    "source_lastmod": None,
                    "sync_type": "audio_hash",
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
                        "has_acoustic_sync": False,
                        "sync_type": "audio_hash",
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
                        "resolved_time_ms": 2438000,
                        "type": "major",
                        "description": "Hands clap behind Carolyn in cellar",
                    },
                    {
                        "resolved_time_ms": 2560000,
                        "type": "major",
                        "description": "Monster appears on wardrobe",
                    },
                ]

                with patch("spookcue_scraper.main.WhenJumpScareScraper.fetch_movie_index", AsyncMock(return_value=mock_index)), \
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock(return_value={"scares": fresh_scares, "runtime_minutes": 112})):

                    result = await run_pipeline(data_dir=data_dir, delay_seconds=0)
                    self.assertEqual(result["updated"], 1)

                    updated_doc = load_json_bytes((movies_dir / "138843.json").read_bytes())
                    self.assertEqual(len(updated_doc["scares"]), 2)
                    self.assertEqual(updated_doc["source"], "whenjumpscare")
                    self.assertEqual(updated_doc["source_lastmod"], "2026-10-28T12:00:00Z")

                    scare1 = updated_doc["scares"][0]
                    self.assertEqual(scare1["resolved_time_ms"], 2438000)
                    self.assertEqual(scare1["type"], "major")

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
                    "source": "wheresthejump",
                    "sync_type": "audio_hash",
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
                        "has_acoustic_sync": False,
                        "sync_type": "audio_hash",
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
                     patch("spookcue_scraper.main.WhenJumpScareScraper.parse_movie_page", AsyncMock(return_value={"scares": [], "runtime_minutes": 103})):

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
                    "scares": [{"resolved_time_ms": 2438000, "type": "major"}],
                    "source": "whenjumpscare",
                    "source_lastmod": "2026-10-28T12:00:00Z",
                    "sync_type": "audio_hash",
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
                        "has_acoustic_sync": False,
                        "sync_type": "audio_hash",
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
