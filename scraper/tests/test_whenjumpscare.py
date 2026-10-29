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


SAMPLE_WHENJUMPSCARE_INDEX_HTML = """
<html>
<body>
<nav>
  <a href="/">Home</a>
  <a href="/all-jumpscares/">All Jumpscares</a>
  <a href="/popular-movies/">Popular Movies</a>
</nav>
<div class="movie-grid">
  <div class="card">
    <a href="/movie/the-conjuring-2013/">
      <h3>The Conjuring (2013)</h3>
    </a>
  </div>
  <div class="card">
    <a href="/movie/insidious-2010/">
      <h3>Insidious (2010)</h3>
    </a>
  </div>
  <div class="card">
    <a href="/movie/rec-2007/">
      <h3>[REC] (2007)</h3>
    </a>
  </div>
</div>
<div class="pagination">
  <a class="next" href="/all-jumpscares/page/2/">Next Page »</a>
</div>
</body>
</html>
"""

SAMPLE_WHENJUMPSCARE_MOVIE_HTML = """
<html>
<body>
<h1>The Conjuring (2013) Jump Scares | WhenJumpScare</h1>
<p>Runtime: 112 minutes</p>
<p><a href="https://www.themoviedb.org/movie/138843">TMDB Link</a></p>
<div class="timeline">
  <div class="scare-row">
    <span class="timestamp">00:03:36</span>
    <span class="desc">(minor) – A loud bang startles the two girls.</span>
  </div>
  <div class="scare-row major">
    <span class="timestamp">00:40:38</span>
    <span class="desc">(major) – Hands clap behind Carolyn in the dark basement.</span>
  </div>
  <div class="scare-row">
    <span class="timestamp">01:07:28</span>
    <span class="desc">A sheet blows off the washing line and catches around a figure.</span>
  </div>
</div>
</body>
</html>
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

    def test_parse_whenjumpscare_index_html(self):
        movies = WhenJumpScareScraper.parse_index_html(
            SAMPLE_WHENJUMPSCARE_INDEX_HTML, base_url="https://www.whenjumpscare.com"
        )
        self.assertEqual(len(movies), 3)

        m1 = movies[0]
        self.assertEqual(m1["title"], "The Conjuring")
        self.assertEqual(m1["year"], 2013)
        self.assertEqual(m1["slug"], "the-conjuring-2013")
        self.assertEqual(m1["url"], "https://www.whenjumpscare.com/movie/the-conjuring-2013/")

        m2 = movies[1]
        self.assertEqual(m2["title"], "Insidious")
        self.assertEqual(m2["year"], 2010)
        self.assertEqual(m2["slug"], "insidious-2010")

        m3 = movies[2]
        self.assertEqual(m3["title"], "[REC]")
        self.assertEqual(m3["year"], 2007)
        self.assertEqual(m3["slug"], "rec-2007")

    def test_parse_whenjumpscare_pagination(self):
        next_url = WhenJumpScareScraper.parse_pagination_next_url(
            SAMPLE_WHENJUMPSCARE_INDEX_HTML, "https://www.whenjumpscare.com/all-jumpscares/"
        )
        self.assertEqual(next_url, "https://www.whenjumpscare.com/all-jumpscares/page/2/")

    def test_parse_whenjumpscare_movie_html(self):
        data = WhenJumpScareScraper.parse_movie_html(SAMPLE_WHENJUMPSCARE_MOVIE_HTML)
        self.assertEqual(data["title"], "The Conjuring")
        self.assertEqual(data["year"], 2013)
        self.assertEqual(data["runtime_minutes"], 112)
        self.assertEqual(data["tmdb_id"], 138843)
        self.assertEqual(data["scare_count"], 3)

        scares = data["scares"]
        self.assertEqual(len(scares), 3)

        # Scare 1: minor
        self.assertEqual(scares[0]["time"], "00:03:36")
        self.assertEqual(scares[0]["resolved_time_ms"], 216000)
        self.assertEqual(scares[0]["type"], "minor")
        self.assertIn("loud bang", scares[0]["description"])

        # Scare 2: major
        self.assertEqual(scares[1]["time"], "00:40:38")
        self.assertEqual(scares[1]["resolved_time_ms"], 2438000)
        self.assertEqual(scares[1]["type"], "major")
        self.assertIn("Hands clap behind Carolyn", scares[1]["description"])

        # Scare 3: default minor
        self.assertEqual(scares[2]["time"], "01:07:28")
        self.assertEqual(scares[2]["type"], "minor")

    def test_robots_txt_checking(self):
        scraper = WhenJumpScareScraper()
        parser = RobotFileParser()
        parser.parse(SAMPLE_ROBOTS_TXT.splitlines())
        scraper._robot_parser = parser

        self.assertTrue(scraper.can_fetch("https://wheresthejump.com/movies/"))
        self.assertTrue(scraper.can_fetch("https://wheresthejump.com/full-movie-list/"))
        self.assertFalse(scraper.can_fetch("https://wheresthejump.com/private/secret"))
        self.assertFalse(scraper.can_fetch("https://wheresthejump.com/wp-admin/edit.php"))

    def test_parse_index_html_filters_tv_shows(self):
        html_with_tv = """
        <table>
          <tr><td><a href="/tv-shows/">TV Shows</a></td><td></td><td>2020</td><td>0</td><td>0</td></tr>
          <tr><td><a href="/jump-scares-in-saw-2004/">Saw</a></td><td>James Wan</td><td>2004</td><td>5</td><td>3.0</td></tr>
        </table>
        """
        movies = WhenJumpScareScraper.parse_index_html(html_with_tv)
        self.assertEqual(len(movies), 1)
        self.assertEqual(movies[0]["title"], "Saw")

    def test_fetch_movie_index_bundled_sitemap_fallback(self):
        import asyncio
        async def run_test():
            scraper = WhenJumpScareScraper()
            async def mock_fetch_html(url: str):
                return None
            scraper._fetch_html = mock_fetch_html
            movies = await scraper.fetch_movie_index()
            self.assertGreaterEqual(len(movies), 1160)
            first = movies[0]
            self.assertIsNotNone(first.get("tmdb_id"))
            self.assertIn("url", first)
            self.assertIn("whenjumpscare.com/movie/", first["url"])
        asyncio.run(run_test())


if __name__ == "__main__":
    unittest.main()
