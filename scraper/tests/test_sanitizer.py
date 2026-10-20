"""Unit tests for subtitle sanitization."""

import unittest
from spookcue_scraper.sanitizer import (
    clean_line_text,
    extract_filtered_words,
    passes_entropy_filter,
    sanitize_subtitles,
)


class TestSanitizer(unittest.TestCase):
    def test_strip_html_and_ass_tags(self):
        raw = "<i><b>Hello</b></i> world, {\\an8}this is a test."
        cleaned = clean_line_text(raw)
        self.assertNotIn("<i>", cleaned)
        self.assertNotIn("<b>", cleaned)
        self.assertNotIn("{\\an8}", cleaned)
        self.assertEqual(cleaned, "Hello world, this is a test.")

    def test_strip_sdh_and_music(self):
        raw = "[creaking] (GASPS) Look at that door! ♪ eerie music ♪"
        cleaned = clean_line_text(raw)
        self.assertNotIn("creaking", cleaned)
        self.assertNotIn("GASPS", cleaned)
        self.assertNotIn("♪", cleaned)
        self.assertEqual(cleaned, "Look at that door!")

    def test_strip_speaker_prefixes(self):
        raw = "NARRATOR: The house was empty.\n- MAN: Or so they thought."
        cleaned = clean_line_text(raw)
        self.assertNotIn("NARRATOR:", cleaned)
        self.assertNotIn("MAN:", cleaned)
        self.assertEqual(cleaned, "The house was empty. Or so they thought.")

    def test_strip_watermarks_and_credits(self):
        self.assertIsNone(clean_line_text("Subtitles by John Doe for OpenSubtitles.org"))
        self.assertIsNone(clean_line_text("Synced and corrected by MovieTeam www.opensubtitles.com"))
        self.assertIsNone(clean_line_text("Downloaded from subdl.com"))

    def test_stopword_removal(self):
        text = "Oh yeah, hey, look over there right now!"
        words = extract_filtered_words(text)
        self.assertNotIn("oh", words)
        self.assertNotIn("yeah", words)
        self.assertNotIn("hey", words)
        self.assertEqual(words, ["look", "over", "there", "right", "now"])

    def test_minimum_entropy_filter(self):
        # < 4 words: should fail
        self.assertFalse(passes_entropy_filter(["help", "me", "now"], "Help me now!"))

        # >= 4 words but < 12 consonants: should fail
        # "I see a bee here" -> consonants: s, b, h, r (4 consonants)
        self.assertFalse(passes_entropy_filter(["i", "see", "a", "bee", "here"], "I see a bee here"))

        # >= 4 words and >= 12 consonants: should pass
        # "Something strange is happening in the cellar" -> consonants: s,m,th,n,g,s,t,r,n,g,s,h,p,p,n,n,g,n,th,c,l,l,r = 23
        self.assertTrue(
            passes_entropy_filter(
                ["something", "strange", "happening", "cellar"],
                "Something strange is happening in the cellar",
            )
        )

    def test_clause_concatenation_short_gaps(self):
        srt_content = """1
00:01:00,000 --> 00:01:01,500
Do you hear that?

2
00:01:01,700 --> 00:01:03,500
Down in the dark basement?

3
00:01:05,000 --> 00:01:07,000
Way too far away.
"""
        # Gap between cue 1 and 2 is 200ms (< 400ms)
        # Cue 1 alone has 4 words ("do", "you", "hear", "that") and consonants: d, y, h, r, th, t = 6 (< 12) -> fails alone
        # Cue 2 has 5 words ("down", "in", "the", "dark", "basement") -> consonants: d, w, n, n, th, d, r, k, b, s, m, n, t = 13 -> passes alone
        # Merged 1+2 starts at 60000 and ends at 63500 and passes!
        entries = sanitize_subtitles(srt_content)
        self.assertTrue(len(entries) >= 1)
        # Find merged or passing entry
        start_times = [e["start_ms"] for e in entries]
        self.assertIn(60000, start_times)


if __name__ == "__main__":
    unittest.main()
