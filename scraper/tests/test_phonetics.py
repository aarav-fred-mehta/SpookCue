"""Unit tests for phonetic encoding and compression checks."""

import unittest
from spookcue_scraper.phonetics import (
    build_phonetic_index,
    encode_tokens_to_phonetics,
    estimate_gzipped_size,
    verify_compression,
)


class TestPhonetics(unittest.TestCase):
    def test_known_phonetic_pairs(self):
        """Verify homophones produce identical Double Metaphone primary codes."""
        pairs = [
            ("night", "knight"),
            ("cellar", "seller"),
            ("pale", "pail"),
            ("hear", "here"),
            ("stairs", "stares"),
        ]
        for w1, w2 in pairs:
            c1 = encode_tokens_to_phonetics([w1])
            c2 = encode_tokens_to_phonetics([w2])
            self.assertEqual(c1, c2, f"Expected {w1} and {w2} to match phonetically: {c1} vs {c2}")

    def test_encode_multiple_words(self):
        words = ["the", "conjuring", "cellar"]
        codes = encode_tokens_to_phonetics(words)
        self.assertEqual(len(codes), 3)
        self.assertIsInstance(codes[0], str)
        self.assertTrue(all(len(c) > 0 for c in codes))

    def test_build_phonetic_index(self):
        dialogue = [
            {"start_ms": 12000, "words": ["shadows", "moving", "quietly"]},
            {"start_ms": 5000, "words": ["listen", "carefully", "now"]},
        ]
        index = build_phonetic_index(dialogue)
        self.assertEqual(len(index), 2)
        # Should be sorted chronologically
        self.assertEqual(index[0]["s"], 5000)
        self.assertEqual(index[1]["s"], 12000)
        self.assertIn("p", index[0])
        self.assertIn("p", index[1])
        self.assertTrue(len(index[0]["p"]) > 0)

    def test_compression_check(self):
        """Verify Phase 1.5.3 compression check calculation."""
        movie_doc = {
            "tmdb_id": 138843,
            "title": "The Conjuring",
            "release_year": 2013,
            "runtime_minutes": 112,
            "scares": [
                {
                    "id": "scare_01",
                    "resolved_time_ms": 1245000,
                    "type": "major",
                    "description": "Clap clap game in the cellar hands appear behind Carolyn",
                    "anchor_phonetic": ["KL", "P", "KL", "P"],
                    "offset_from_anchor_ms": 4200,
                }
            ],
            "phonetic_index": [
                {"s": 1240800, "p": ["KL", "P", "KL", "P"]}
            ],
        }
        size_bytes = estimate_gzipped_size(movie_doc)
        self.assertGreater(size_bytes, 0)
        self.assertLess(size_bytes, 2800)  # Well under target ~2.8 KB average

        stats = verify_compression(movie_doc)
        self.assertTrue(stats["meets_target"])
        self.assertEqual(stats["gzip_bytes"], size_bytes)


if __name__ == "__main__":
    unittest.main()
