"""Unit tests for TMDB client and token-bucket limiter."""

import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock
from spookcue_scraper.tmdb import TmdbClient, TokenBucket, generate_title_candidates


class TestTmdb(unittest.IsolatedAsyncioTestCase):
    async def test_token_bucket_acquire(self):
        bucket = TokenBucket(capacity=5.0, refill_rate=100.0)
        # Should acquire 5 without blocking
        for _ in range(5):
            await bucket.acquire()
        self.assertLessEqual(bucket.tokens, 1.0)

    def test_generate_title_candidates(self):
        """Verify edition/cut qualifiers are properly stripped into clean search candidates."""
        candidates_army = generate_title_candidates("Army of Darkness – Director’s Cut")
        self.assertIn("Army of Darkness", candidates_army)

        candidates_babadook = generate_title_candidates("The Babadook – Original Australian Blu-Ray at 25fps")
        self.assertIn("The Babadook", candidates_babadook)

        candidates_colon = generate_title_candidates("1974: The Possession of Altair")
        self.assertIn("1974 The Possession of Altair", candidates_colon)

        candidates_and = generate_title_candidates("Things Heard and Seen")
        self.assertIn("Things Heard & Seen", candidates_and)

        candidates_vacancy = generate_title_candidates("Vacancy 3: Terror Trap")
        self.assertIn("Terror Trap", candidates_vacancy)

    async def test_search_movie_candidate_fallback(self):
        """Verify search_movie falls back to stripped candidate title when raw title yields 0 results."""
        client_mock = MagicMock()
        resp_empty = MagicMock()
        resp_empty.status_code = 200
        resp_empty.json.return_value = {"results": []}

        resp_found = MagicMock()
        resp_found.status_code = 200
        resp_found.json.return_value = {"results": [{"id": 766}]}

        # First call with raw title yields empty results; second call with candidate yields 766
        client_mock.get = AsyncMock(side_effect=[resp_empty, resp_found])

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmdb_client = TmdbClient(api_key="mock_key", cache_dir=Path(tmp_dir), client=client_mock)
            tmdb_id = await tmdb_client.search_movie("Army of Darkness – Director’s Cut", 1992)
            self.assertEqual(tmdb_id, 766)

    def test_cache_read_write(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            cache_path = Path(tmp_dir)
            client = TmdbClient(api_key="mock_key", cache_dir=cache_path)

            test_data = {"runtime": 120, "poster_path": "/test.jpg"}
            client._write_cache("test_key", test_data)

            cached = client._read_cache("test_key")
            self.assertEqual(cached, test_data)


if __name__ == "__main__":
    unittest.main()
