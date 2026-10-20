"""Unit tests for TMDB client and token-bucket limiter."""

import asyncio
import tempfile
import unittest
from pathlib import Path
from spookcue_scraper.tmdb import TmdbClient, TokenBucket


class TestTmdb(unittest.IsolatedAsyncioTestCase):
    async def test_token_bucket_acquire(self):
        bucket = TokenBucket(capacity=5.0, refill_rate=100.0)
        # Should acquire 5 without blocking
        for _ in range(5):
            await bucket.acquire()
        self.assertLessEqual(bucket.tokens, 1.0)

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
