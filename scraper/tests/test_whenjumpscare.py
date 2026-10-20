"""Unit tests for WhenJumpScare / Where's The Jump? HTML parser and crawler."""

import unittest
from urllib.robotparser import RobotFileParser
from spookcue_scraper.whenjumpscare import WhenJumpScareScraper

SAMPLE_INDEX_HTML = """
<html>
<body>
<table>
  <tr>
    <th>Movie Name</th><th>Director</th><th>Year</th><th>Jump Count</th><th>Jump Scare Rating</th><th>Netflix (US)</th>
  </tr>
  <tr>
    <td><a href="/jump-scares-in-the-conjuring-2013/">The Conjuring</a></td>
    <td>James Wan</td>
    <td>2013</td>
    <td>12</td>
    <td>4.0</td>
    <td>Yes</td>
  </tr>
  <tr>
    <td><a href="/jump-scares-in-insidious-2010/">Insidious</a></td>
    <td>James Wan</td>
    <td>2010</td>
    <td>14</td>
    <td>4.5</td>
    <td>No</td>
  </tr>
</table>
<div class="pagination">
  <span class="page-numbers current">1</span>
  <a class="next page-numbers" href="/movies/page/2/">Next »</a>
</div>
</body>
</html>
"""

SAMPLE_MOVIE_HTML = """
<html>
<body>
<h1>Jump Scares in The Conjuring (2013)</h1>
<p><strong>Jump Scare Rating:</strong> Several very frightening jump scares.</p>
<p><strong>Runtime:</strong> 112 minutes</p>
<p><strong>Jump Scares:</strong> 12 (3 major, 9 minor)</p>
<p><a href="https://www.themoviedb.org/movie/138843-the-conjuring">TMDB Profile</a></p>
<p>00:03:36 – A loud bang startles the two girls.</p>
<p><strong>00:40:38 – Hands clap behind Carolyn while she is in the basement.</strong></p>
<p><strong>00:42:40 – A monster appears on the top of the girl's closet.</strong></p>
<p>01:07:28 – A sheet blows off the washing line and catches around a figure.</p>
</body>
</html>
"""

SAMPLE_ROBOTS_TXT = """
User-agent: *
Disallow: /wp-admin/
Disallow: /private/
Crawl-delay: 2
"""


class TestWhenJumpScare(unittest.TestCase):
    def test_parse_index_html(self):
        movies = WhenJumpScareScraper.parse_index_html(SAMPLE_INDEX_HTML)
        self.assertEqual(len(movies), 2)

        m1 = movies[0]
        self.assertEqual(m1["title"], "The Conjuring")
        self.assertEqual(m1["year"], 2013)
        self.assertEqual(m1["director"], "James Wan")
        self.assertEqual(m1["jump_count"], 12)
        self.assertEqual(m1["rating"], 4.0)
        self.assertEqual(m1["slug"], "jump-scares-in-the-conjuring-2013")

        m2 = movies[1]
        self.assertEqual(m2["title"], "Insidious")
        self.assertEqual(m2["year"], 2010)

    def test_parse_pagination_next_url(self):
        next_url = WhenJumpScareScraper.parse_pagination_next_url(
            SAMPLE_INDEX_HTML, "https://wheresthejump.com/movies/"
        )
        self.assertEqual(next_url, "https://wheresthejump.com/movies/page/2/")

    def test_parse_movie_html(self):
        data = WhenJumpScareScraper.parse_movie_html(SAMPLE_MOVIE_HTML)
        self.assertEqual(data["title"], "The Conjuring")
        self.assertEqual(data["year"], 2013)
        self.assertEqual(data["runtime_minutes"], 112)
        self.assertEqual(data["tmdb_id"], 138843)
        self.assertEqual(data["scare_count"], 4)

        scares = data["scares"]
        self.assertEqual(len(scares), 4)

        # First scare: minor
        self.assertEqual(scares[0]["id"], "scare_01")
        self.assertEqual(scares[0]["time"], "00:03:36")
        self.assertEqual(scares[0]["resolved_time_ms"], 216000)
        self.assertEqual(scares[0]["type"], "minor")
        self.assertIn("loud bang", scares[0]["description"])

        # Second scare: major (in strong tag)
        self.assertEqual(scares[1]["id"], "scare_02")
        self.assertEqual(scares[1]["time"], "00:40:38")
        self.assertEqual(scares[1]["resolved_time_ms"], 2438000)
        self.assertEqual(scares[1]["type"], "major")
        self.assertIn("Hands clap", scares[1]["description"])

    def test_robots_txt_checking(self):
        scraper = WhenJumpScareScraper()
        parser = RobotFileParser()
        parser.parse(SAMPLE_ROBOTS_TXT.splitlines())
        scraper._robot_parser = parser

        self.assertTrue(scraper.can_fetch("https://wheresthejump.com/movies/"))
        self.assertTrue(scraper.can_fetch("https://wheresthejump.com/full-movie-list/"))
        self.assertFalse(scraper.can_fetch("https://wheresthejump.com/private/secret"))
        self.assertFalse(scraper.can_fetch("https://wheresthejump.com/wp-admin/edit.php"))


if __name__ == "__main__":
    unittest.main()
