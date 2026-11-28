"""Unit tests for TMDB client, rate limiting, and API guideline compliance."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

from spookcue_scraper.tmdb import TmdbClient, TokenBucket, generate_title_candidates


class TestTmdb(unittest.IsolatedAsyncioTestCase):
    async def test_token_bucket_acquire(self):
        bucket = TokenBucket(capacity=5.0, refill_rate=100.0)
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
        client_mock.is_closed = False
        resp_empty = MagicMock()
        resp_empty.status_code = 200
        resp_empty.json.return_value = {"results": []}

        resp_found = MagicMock()
        resp_found.status_code = 200
        resp_found.json.return_value = {"results": [{"id": 766}]}

        client_mock.get = AsyncMock(side_effect=[resp_empty, resp_found])

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmdb_client = TmdbClient(api_key="mock_key", cache_dir=Path(tmp_dir), client=client_mock)
            tmdb_id = await tmdb_client.search_movie("Army of Darkness – Director’s Cut", 1992)
            self.assertEqual(tmdb_id, 766)

    async def test_bearer_token_auth_and_429_retry_after(self):
        """Verify JWT access tokens use Bearer auth and 429 Retry-After triggers retry."""
        client_mock = MagicMock()
        client_mock.is_closed = False

        resp_429 = MagicMock()
        resp_429.status_code = 429
        resp_429.headers = {"Retry-After": "0"}

        resp_ok = MagicMock()
        resp_ok.status_code = 200
        resp_ok.json.return_value = {
            "title": "1922",
            "release_date": "2017-10-20",
            "runtime": 102,
            "poster_path": "/poster.jpg",
            "genres": [{"name": "Horror"}],
        }

        client_mock.get = AsyncMock(side_effect=[resp_429, resp_ok])
        jwt_token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ0ZXN0In0.sig"

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmdb_client = TmdbClient(api_key=jwt_token, cache_dir=Path(tmp_dir), client=client_mock)
            self.assertEqual(tmdb_client.access_token, jwt_token)
            self.assertEqual(tmdb_client.api_key, "")

            details = await tmdb_client.get_movie_details(452507)
            self.assertEqual(details["title"], "1922")
            self.assertEqual(details["release_date"], "2017-10-20")
            self.assertEqual(client_mock.get.call_count, 2)
            _, call_kwargs = client_mock.get.call_args
            self.assertNotIn("api_key", call_kwargs["params"])
            self.assertEqual(call_kwargs["headers"]["Authorization"], f"Bearer {jwt_token}")

    async def test_now_playing_and_popular_follow_tmdb_params(self):
        """Verify /movie/now_playing omits invalid with_genres and filters horror/year client-side."""
        client_mock = MagicMock()
        client_mock.is_closed = False

        now_playing_resp = MagicMock()
        now_playing_resp.status_code = 200
        now_playing_resp.json.return_value = {
            "total_pages": 1,
            "results": [
                {"id": 1001, "genre_ids": [27], "release_date": "2026-10-01"},
                {"id": 1002, "genre_ids": [35], "release_date": "2026-10-01"},  # Non-horror
                {"id": 948, "genre_ids": [27], "release_date": "1978-10-25"},   # Classic re-release
            ],
        }
        discover_new_resp = MagicMock()
        discover_new_resp.status_code = 200
        discover_new_resp.json.return_value = {
            "total_pages": 1,
            "results": [{"id": 1003, "genre_ids": [27]}],
        }
        discover_pop_resp = MagicMock()
        discover_pop_resp.status_code = 200
        discover_pop_resp.json.return_value = {
            "total_pages": 1,
            "results": [{"id": 138843}],
        }
        trending_resp = MagicMock()
        trending_resp.status_code = 200
        trending_resp.json.return_value = {
            "results": [
                {"id": 348, "genre_ids": [27, 878]},
                {"id": 999, "genre_ids": [12]},
            ],
        }

        client_mock.get = AsyncMock(
            side_effect=[now_playing_resp, discover_new_resp, discover_pop_resp, trending_resp]
        )

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmdb_client = TmdbClient(api_key="mock_key", cache_dir=Path(tmp_dir), client=client_mock)
            now_ids = await tmdb_client.get_now_playing_movie_ids()
            self.assertEqual(now_ids, {1001, 1003})

            # First call was /movie/now_playing: must NOT pass unsupported with_genres param
            np_call_args = client_mock.get.call_args_list[0]
            self.assertIn("/movie/now_playing", np_call_args.args[0])
            self.assertNotIn("with_genres", np_call_args.kwargs["params"])

            pop_ids = await tmdb_client.get_popular_horror_ids()
            self.assertEqual(pop_ids, {138843, 348})

    async def test_get_movie_details_caches_404_not_found(self):
        """HTTP 404 from TMDB caches a _not_found marker instead of failing as a server outage."""
        client_mock = MagicMock()
        client_mock.is_closed = False
        resp_404 = MagicMock()
        resp_404.status_code = 404
        client_mock.get = AsyncMock(return_value=resp_404)

        with tempfile.TemporaryDirectory() as tmp_dir:
            tmdb_client = TmdbClient(api_key="mock_key", cache_dir=Path(tmp_dir), client=client_mock)
            first = await tmdb_client.get_movie_details(1107568)
            self.assertEqual(first, {"_not_found": True})
            self.assertEqual(client_mock.get.call_count, 1)

            second = await tmdb_client.get_movie_details(1107568)
            self.assertEqual(second, {"_not_found": True})
            self.assertEqual(client_mock.get.call_count, 1)

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
