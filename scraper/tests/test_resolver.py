"""Unit tests for jump scare anchor resolver."""

import unittest
from spookcue_scraper.resolver import parse_timestamp_to_ms, resolve_scares_to_anchors


class TestResolver(unittest.TestCase):
    def test_parse_timestamp_to_ms(self):
        self.assertEqual(parse_timestamp_to_ms("00:40:38"), 2438000)
        self.assertEqual(parse_timestamp_to_ms("40:38"), 2438000)
        self.assertEqual(parse_timestamp_to_ms("01:12:40"), 4360000)
        self.assertEqual(parse_timestamp_to_ms("00:03:36"), 216000)
        self.assertEqual(parse_timestamp_to_ms(50000), 50000)

    def test_resolve_scares_with_preceding_dialogue(self):
        scares = [
            {
                "time": "00:01:00",  # 60,000 ms
                "type": "major",
                "description": "Monster jumps from shadows",
            }
        ]
        # Dialogue at 55,000 ms (5s before scare <= 60s)
        index = [
            {"s": 55000, "p": ["MNSTR", "JMP"]},
            {"s": 70000, "p": ["LTr"]},  # after scare
        ]

        resolved = resolve_scares_to_anchors(scares, index)
        self.assertEqual(len(resolved), 1)
        r = resolved[0]
        self.assertEqual(r["id"], "scare_01")
        self.assertEqual(r["resolved_time_ms"], 60000)
        self.assertEqual(r["type"], "major")
        self.assertEqual(r["anchor_phonetic"], ["MNSTR", "JMP"])
        self.assertEqual(r["offset_from_anchor_ms"], 5000)

    def test_resolve_scares_dialogue_free(self):
        scares = [
            {
                "time": "00:05:00",  # 300,000 ms
                "type": "minor",
                "description": "Ghost appears in mirror",
            }
        ]
        # Dialogue at 100,000 ms (200s before scare > 60s max window)
        index = [
            {"s": 100000, "p": ["WTR"]},
        ]

        resolved = resolve_scares_to_anchors(scares, index)
        self.assertEqual(len(resolved), 1)
        r = resolved[0]
        self.assertEqual(r["id"], "scare_01")
        self.assertEqual(r["resolved_time_ms"], 300000)
        self.assertIsNone(r["anchor_phonetic"])
        self.assertIsNone(r["offset_from_anchor_ms"])


if __name__ == "__main__":
    unittest.main()
