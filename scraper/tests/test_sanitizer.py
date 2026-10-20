"""Unit tests for subtitle sanitization."""

import unittest
from spookcue_scraper.sanitizer import sanitize_subtitles


class TestSanitizer(unittest.TestCase):
    def test_sanitize_subtitles_stub(self):
        """Verify sanitizer stub returns a list."""
        self.assertIsInstance(sanitize_subtitles(""), list)


if __name__ == "__main__":
    unittest.main()
