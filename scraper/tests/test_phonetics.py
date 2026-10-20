"""Unit tests for phonetic encoding."""

import unittest
from spookcue_scraper.phonetics import encode_tokens_to_phonetics


class TestPhonetics(unittest.TestCase):
    def test_encode_tokens_stub(self):
        """Verify phonetic encoder stub returns a list."""
        self.assertIsInstance(encode_tokens_to_phonetics([]), list)


if __name__ == "__main__":
    unittest.main()
