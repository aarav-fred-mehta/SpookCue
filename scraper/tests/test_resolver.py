"""Unit tests for jump scare anchor resolver."""

import unittest
from spookcue_scraper.resolver import resolve_scares_to_anchors


class TestResolver(unittest.TestCase):
    def test_resolve_scares_stub(self):
        """Verify resolver stub returns a list."""
        self.assertIsInstance(resolve_scares_to_anchors([], []), list)


if __name__ == "__main__":
    unittest.main()
