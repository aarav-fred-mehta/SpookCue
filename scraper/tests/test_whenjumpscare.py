"""Unit tests for WhenJumpScare / Where's The Jump? HTML parser and polite crawler."""

import asyncio
import unittest
from unittest.mock import AsyncMock, MagicMock
from urllib.robotparser import RobotFileParser

from spookcue_scraper.whenjumpscare import WhenJumpScareScraper, clean_slug_title

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

SAMPLE_HIGH_DELAY_ROBOTS_TXT = """
User-agent: *
Disallow: /admin/
Crawl-delay: 20
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

        self.assertEqual(scares[0]["id"], "scare_01")
        self.assertEqual(scares[0]["time"], "00:03:36")
        self.assertEqual(scares[0]["resolved_time_ms"], 216000)
        self.assertEqual(scares[0]["type"], "minor")
        self.assertIn("loud bang", scares[0]["description"])

        self.assertEqual(scares[1]["id"], "scare_02")
        self.assertEqual(scares[1]["time"], "00:40:38")
        self.assertEqual(scares[1]["resolved_time_ms"], 2438000)
        self.assertEqual(scares[1]["type"], "major")
        self.assertIn("Hands clap", scares[1]["description"])

    def test_parse_movie_html_year_in_title(self):
        html = "<html><body><h1>1922 (2017) Jump Scares | WhenJumpScare</h1></body></html>"
        data = WhenJumpScareScraper.parse_movie_html(html)
        self.assertEqual(data["title"], "1922")
        self.assertEqual(data["year"], 2017)

    def test_parse_sitemap_movie_url_fixes_ids_years_and_titles(self):
        cases = [
            ("https://www.whenjumpscare.com/movie/452507-1922-2017", 452507, "1922", 2017),
            ("https://www.whenjumpscare.com/movie/2082-halloween-2007", 2082, "Halloween", 2007),
            ("https://www.whenjumpscare.com/movie/530915-1917-2019", 530915, "1917", 2019),
            ("https://www.whenjumpscare.com/movie/10577-dracula-2000-2000", 10577, "Dracula 2000", 2000),
            ("https://www.whenjumpscare.com/movie/79316-devil-s-due-2014", 79316, "Devil's Due", 2014),
            ("https://www.whenjumpscare.com/movie/482373-don-t-breathe-2-2021", 482373, "Don't Breathe 2", 2021),
            ("https://www.whenjumpscare.com/movie/520763-a-quiet-place-part-ii-2021", 520763, "A Quiet Place Part II", 2021),
            ("https://www.whenjumpscare.com/movie/84348-v-h-s-2012", 84348, "V/H/S", 2012),
            ("https://www.whenjumpscare.com/movie/23730-c-h-u-d-1984", 23730, "C.H.U.D.", 1984),
        ]
        for url, exp_tid, exp_title, exp_year in cases:
            rec = WhenJumpScareScraper._parse_sitemap_movie_url(url)
            self.assertIsNotNone(rec, url)
            self.assertEqual(rec["tmdb_id"], exp_tid, url)
            self.assertEqual(rec["title"], exp_title, url)
            self.assertEqual(rec["year"], exp_year, url)

    def test_clean_slug_title_special_cases(self):
        self.assertEqual(clean_slug_title("friday-the-13th-part-viii-jason-takes-manhattan"), "Friday the 13th Part VIII Jason Takes Manhattan")
        self.assertEqual(clean_slug_title("hell-house-llc-ii-the-abaddon-hotel"), "Hell House LLC II the Abaddon Hotel")
        self.assertEqual(clean_slug_title("we-re-all-going-to-the-world-s-fair"), "We're All Going to the World's Fair")
        self.assertEqual(clean_slug_title("you-re-next"), "You're Next")
        self.assertEqual(clean_slug_title("i-m-thinking-of-ending-things"), "I'm Thinking of Ending Things")
        self.assertEqual(clean_slug_title("m3gan-2-0"), "M3GAN 2.0")

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

        self.assertEqual(scares[0]["time"], "00:03:36")
        self.assertEqual(scares[0]["resolved_time_ms"], 216000)
        self.assertEqual(scares[0]["type"], "minor")
        self.assertIn("loud bang", scares[0]["description"])

        self.assertEqual(scares[1]["time"], "00:40:38")
        self.assertEqual(scares[1]["resolved_time_ms"], 2438000)
        self.assertEqual(scares[1]["type"], "major")
        self.assertIn("Hands clap behind Carolyn", scares[1]["description"])

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

    def test_robots_txt_honors_crawl_delay_without_capping(self):
        async def run_test():
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.text = SAMPLE_HIGH_DELAY_ROBOTS_TXT
            client = MagicMock()
            client.get = AsyncMock(return_value=mock_resp)
            client.is_closed = False

            scraper = WhenJumpScareScraper(base_url="https://wheresthejump.com", delay_seconds=2.0, client=client)
            await scraper.init_robots_parser()
            self.assertEqual(scraper.delay_seconds, 20.0)
            self.assertEqual(scraper._host_delays["https://wheresthejump.com"], 20.0)
            self.assertFalse(scraper.can_fetch("https://wheresthejump.com/admin/panel"))
            self.assertTrue(scraper.can_fetch("https://wheresthejump.com/full-movie-list/"))

        asyncio.run(run_test())

    def test_fetch_html_respects_robots_disallow_and_403(self):
        async def run_test():
            robots_resp = MagicMock()
            robots_resp.status_code = 200
            robots_resp.text = SAMPLE_ROBOTS_TXT

            page_403 = MagicMock()
            page_403.status_code = 403
            page_403.text = "Forbidden"

            client = MagicMock()
            client.get = AsyncMock(side_effect=[robots_resp, page_403])
            client.is_closed = False

            scraper = WhenJumpScareScraper(delay_seconds=0.0, client=client)
            # Disallowed URL should never trigger a GET request
            disallowed = await scraper._fetch_html("https://www.whenjumpscare.com/private/test")
            self.assertIsNone(disallowed)
            self.assertEqual(client.get.call_count, 1)  # robots.txt only

            # Allowed URL returning 403 returns None cleanly
            allowed = await scraper._fetch_html("https://www.whenjumpscare.com/movie/test")
            self.assertIsNone(allowed)
            self.assertEqual(client.get.call_count, 2)

        asyncio.run(run_test())

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
        async def run_test():
            scraper = WhenJumpScareScraper(delay_seconds=0.0)
            scraper._robots_loaded = True
            scraper._robot_parsers["https://www.whenjumpscare.com"] = None

            async def mock_fetch_html(url: str):
                return None

            scraper._fetch_html = mock_fetch_html
            movies = await scraper.fetch_movie_index()
            self.assertTrue(scraper.last_index_from_fallback)
            self.assertGreaterEqual(len(movies), 1190)
            by_id = {m["tmdb_id"]: m for m in movies if m.get("tmdb_id")}
            self.assertTrue(by_id[452507].get("from_bundled_fallback"))
            self.assertEqual(by_id[452507]["title"], "1922")
            self.assertEqual(by_id[452507]["year"], 2017)
            self.assertEqual(by_id[2082]["title"], "Halloween")
            self.assertEqual(by_id[2082]["year"], 2007)
            self.assertEqual(by_id[79316]["title"], "Devil's Due")
            self.assertEqual(by_id[482373]["title"], "Don't Breathe 2")
            self.assertEqual(by_id[520763]["title"], "A Quiet Place Part II")

        asyncio.run(run_test())

    def test_fetch_html_blocks_origin_after_403_circuit_breaker(self):
        async def run_test():
            robots_resp = MagicMock()
            robots_resp.status_code = 200
            robots_resp.text = "User-agent: *\nAllow: /"

            page_403 = MagicMock()
            page_403.status_code = 403
            page_403.text = "Forbidden"

            client = MagicMock()
            client.get = AsyncMock(side_effect=[robots_resp, page_403])
            client.is_closed = False

            scraper = WhenJumpScareScraper(delay_seconds=0.0, client=client)
            # Sitemap returning 403 trips the origin circuit breaker immediately
            self.assertIsNone(await scraper._fetch_html("https://www.whenjumpscare.com/sitemap.xml"))
            self.assertTrue(scraper.is_origin_blocked("https://www.whenjumpscare.com"))
            self.assertEqual(client.get.call_count, 2)  # robots + sitemap only

            # Further requests to the same blocked origin should be skipped with zero HTTP calls
            self.assertIsNone(await scraper._fetch_html("https://www.whenjumpscare.com/movie/1091-the-thing-1982"))
            self.assertEqual(client.get.call_count, 2)

        asyncio.run(run_test())

    def test_parse_movie_page_respects_allow_wtj_fallback_and_fetch_failed(self):
        async def run_test():
            scraper = WhenJumpScareScraper(delay_seconds=0.0)
            fetched_urls = []

            async def mock_fetch_html(url: str):
                fetched_urls.append(url)
                return None

            scraper._fetch_html = mock_fetch_html

            res_no_fallback = await scraper.parse_movie_page(
                "https://www.whenjumpscare.com/movie/1091-the-thing-1982",
                allow_wtj_fallback=False,
            )
            self.assertTrue(res_no_fallback["fetch_failed"])
            self.assertEqual(res_no_fallback["source"], "whenjumpscare")
            self.assertEqual(res_no_fallback["scares"], [])
            self.assertEqual(fetched_urls, ["https://www.whenjumpscare.com/movie/1091-the-thing-1982"])

        asyncio.run(run_test())


if __name__ == "__main__":
    unittest.main()
