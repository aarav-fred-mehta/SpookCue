"""WhenJumpScare scraper (with Where's The Jump? fallback).

Scrapes movie listings, jump scare timestamps, major/minor designations,
and text descriptions from whenjumpscare.com, with robots.txt compliance and pagination.
"""

from __future__ import annotations

import asyncio
import logging
import re
from typing import Any, Dict, List, Optional
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

import httpx
from bs4 import BeautifulSoup
from spookcue_scraper.resolver import parse_timestamp_to_ms

logger = logging.getLogger(__name__)

TIME_PATTERN = re.compile(r"\b(\d{1,2}:\d{2}:\d{2}|\d{1,2}:\d{2})\b")
RUNTIME_PATTERN = re.compile(r"(\d+)\s*(?:minutes?|mins?|m\b)", re.IGNORECASE)
YEAR_PATTERN = re.compile(r"\((\d{4})\)|\b(19\d{2}|20\d{2})\b")


class WhenJumpScareScraper:
    """Scrapes movie indices and jump scare timestamps from whenjumpscare.com and wheresthejump.com."""

    DEFAULT_BASE_URL = "https://www.whenjumpscare.com"
    USER_AGENT = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    )
    DEFAULT_HEADERS = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Sec-Ch-Ua": '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
        "Sec-Ch-Ua-Mobile": "?0",
        "Sec-Ch-Ua-Platform": '"Windows"',
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-User": "?1",
        "Upgrade-Insecure-Requests": "1",
    }

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        delay_seconds: float = 2.0,
        client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.delay_seconds = delay_seconds
        self._client = client
        self._owns_client = client is None
        self._robot_parser: Optional[RobotFileParser] = None
        self._robots_loaded = False

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                headers=self.DEFAULT_HEADERS,
                follow_redirects=True,
                http2=True,
                timeout=30.0,
            )
            self._owns_client = True
        return self._client

    async def close(self) -> None:
        if self._owns_client and self._client and not self._client.is_closed:
            await self._client.aclose()

    async def init_robots_parser(self) -> None:
        """Fetch and parse robots.txt to verify crawling permissions and crawl delay."""
        if self._robots_loaded:
            return

        robots_url = f"{self.base_url}/robots.txt"
        client = await self._get_client()
        parser = RobotFileParser()

        try:
            resp = await client.get(robots_url, timeout=10.0)
            if resp.status_code == 200:
                parser.parse(resp.text.splitlines())
                self._robot_parser = parser
                # Check crawl-delay for our user-agent or wildcard
                crawl_delay = parser.crawl_delay(self.USER_AGENT) or parser.crawl_delay("*")
                if crawl_delay is not None:
                    # Cap crawl-delay to avoid stalling runs (e.g. Where's The Jump's 20s rule)
                    capped_delay = min(float(crawl_delay), max(self.delay_seconds, 2.0))
                    logger.info("Robots.txt specified crawl-delay %.1fs (using %.1fs)", float(crawl_delay), capped_delay)
                    self.delay_seconds = capped_delay
                logger.info("Successfully loaded robots.txt from %s", robots_url)
            else:
                logger.debug("robots.txt returned status %d; proceeding with default delay", resp.status_code)
        except Exception as exc:
            logger.debug("Could not fetch robots.txt (%s); proceeding with default delay", exc)

        self._robots_loaded = True

    def can_fetch(self, url: str) -> bool:
        """Check if robots.txt permits crawling the given URL."""
        if self._robot_parser:
            return self._robot_parser.can_fetch(self.USER_AGENT, url)
        return True

    @staticmethod
    def parse_pagination_next_url(html: str, current_url: str) -> Optional[str]:
        """Detect and extract 'next' page URL from pagination controls if present."""
        soup = BeautifulSoup(html, "html.parser")
        # Check standard pagination patterns: class contains 'next', rel='next', aria-label, etc.
        next_tag = soup.find("a", class_=lambda c: c and "next" in c.lower(), href=True)
        if not next_tag:
            next_tag = soup.find("a", rel=lambda r: r and "next" in str(r).lower(), href=True)
        if not next_tag:
            next_tag = soup.find("a", attrs={"aria-label": re.compile(r"next", re.I)}, href=True)
        if not next_tag:
            next_tag = soup.find("a", string=re.compile(r"next|»|›|>", re.I), href=True)
        if not next_tag:
            next_tag = soup.find("link", rel=lambda r: r and "next" in str(r).lower(), href=True)
        if next_tag and next_tag.get("href"):
            return urljoin(current_url, next_tag["href"])
        return None

    @staticmethod
    def parse_index_html(html: str, base_url: str = DEFAULT_BASE_URL) -> List[Dict[str, Any]]:
        """Parse movie index table or card HTML into a list of movie records."""
        soup = BeautifulSoup(html, "html.parser")
        movies: List[Dict[str, Any]] = []

        table = soup.find("table")
        if not table:
            # Fallback for whenjumpscare.com or modern card-based lists: look for all movie links
            non_movie_slugs = {
                "movie", "movies", "all-jumpscares", "popular-movies", "recently-added",
                "find-movie", "submit-jumpscare", "contact", "about", "privacy-policy",
                "terms", "terms-of-service", "disclaimer", "watch-along", "live-companion"
            }
            links = soup.find_all("a", href=True)
            for link in links:
                href = link["href"]
                clean_href = href.strip("/")
                parts = clean_href.split("/")
                if (
                    ("/movie/" in href or "jump-scares-in-" in href or "jumpscare" in href)
                    and clean_href not in non_movie_slugs
                    and len(parts) >= 1
                ):
                    slug = parts[-1]
                    if slug in non_movie_slugs or slug.isdigit() or not slug:
                        continue

                    title = link.get_text(strip=True)
                    if title and len(title) > 1:
                        # Clean title if it contains "Jump Scares", branding suffixes, etc.
                        clean_title = re.sub(r"^Jump Scares in\s+", "", title, flags=re.IGNORECASE)
                        clean_title = re.sub(r"\s+Jump Scares.*$", "", clean_title, flags=re.IGNORECASE)
                        clean_title = re.sub(r"\s*[|–—-]\s*WhenJumpScare.*$", "", clean_title, flags=re.IGNORECASE).strip()

                        year = None
                        year_match = YEAR_PATTERN.search(clean_title)
                        if year_match:
                            y_str = year_match.group(1) or year_match.group(2)
                            if y_str:
                                year = int(y_str)
                                clean_title = clean_title[:year_match.start()].strip()

                        movies.append({
                            "title": clean_title,
                            "url": urljoin(base_url, href),
                            "slug": slug,
                            "year": year,
                            "director": "",
                            "jump_count": 0,
                            "rating": 0.0,
                        })
            return movies

        rows = table.find_all("tr")
        for row in rows:
            cols = row.find_all("td")
            if not cols:
                continue

            name_col = cols[0]
            link = name_col.find("a", href=True)
            if not link:
                continue

            title = link.get_text(strip=True)
            href = link["href"]
            full_url = urljoin(base_url, href)
            slug = href.strip("/").split("/")[-1]

            director = cols[1].get_text(strip=True) if len(cols) > 1 else ""
            year_text = cols[2].get_text(strip=True) if len(cols) > 2 else ""
            year = int(year_text) if year_text.isdigit() else None

            jump_count_text = cols[3].get_text(strip=True) if len(cols) > 3 else "0"
            try:
                jump_count = int(jump_count_text)
            except ValueError:
                jump_count = 0

            rating_text = cols[4].get_text(strip=True) if len(cols) > 4 else "0.0"
            try:
                rating = float(rating_text)
            except ValueError:
                rating = 0.0

            movies.append({
                "title": title,
                "url": full_url,
                "slug": slug,
                "year": year,
                "director": director,
                "jump_count": jump_count,
                "rating": rating,
            })

        return movies

    @staticmethod
    def parse_movie_html(html: str) -> Dict[str, Any]:
        """Parse individual movie jump scare page HTML into structured metadata and scares."""
        soup = BeautifulSoup(html, "html.parser")

        # 1. Title and Year
        title = ""
        year: Optional[int] = None

        h1 = soup.find("h1")
        if h1:
            h1_text = h1.get_text(strip=True)
            # Remove "Jump Scares in " prefix, trailing "Jump Scares", or branding
            clean_title = re.sub(r"^Jump Scares in\s+", "", h1_text, flags=re.IGNORECASE)
            clean_title = re.sub(r"\s+Jump Scares.*$", "", clean_title, flags=re.IGNORECASE)
            clean_title = re.sub(r"\s*[|–—-]\s*WhenJumpScare.*$", "", clean_title, flags=re.IGNORECASE).strip()

            # Check for year in title e.g. "The Conjuring (2013)"
            year_match = YEAR_PATTERN.search(clean_title)
            if year_match:
                year_str = year_match.group(1) or year_match.group(2)
                if year_str:
                    year = int(year_str)
                    clean_title = clean_title[:year_match.start()].strip()
            title = clean_title

        # Check for year in span if not found in title (e.g. WhenJumpScare <span class="timecode text-bone-2">2013</span>)
        if year is None:
            span_year = soup.find("span", class_=lambda c: c and "timecode" in c and any(cls in c for cls in ["text-bone-2", "text-bone"]))
            if span_year and span_year.get_text().strip().isdigit():
                y_val = int(span_year.get_text().strip())
                if 1900 <= y_val <= 2100:
                    year = y_val

        # 2. Runtime
        runtime_minutes = 0
        content_text = soup.get_text()
        # Check "1h 52m" format first
        hm_match = re.search(r"(\d+)\s*h(?:ours?)?\s*(\d+)?\s*m", content_text, re.IGNORECASE)
        if hm_match:
            try:
                hrs = int(hm_match.group(1))
                mins = int(hm_match.group(2)) if hm_match.group(2) else 0
                runtime_minutes = hrs * 60 + mins
            except ValueError:
                pass
        if not runtime_minutes:
            runtime_match = re.search(r"Runtime:\s*(\d+)\s*(?:minutes?|mins?|m\b)", content_text, re.IGNORECASE)
            if runtime_match:
                try:
                    runtime_minutes = int(runtime_match.group(1))
                except ValueError:
                    pass

        # 3. Check for embedded TMDB ID or IMDb link
        tmdb_id: Optional[int] = None
        for a in soup.find_all("a", href=True):
            href = a["href"]
            m_tmdb = re.search(r"themoviedb\.org/movie/(\d+)", href, re.IGNORECASE)
            if m_tmdb:
                tmdb_id = int(m_tmdb.group(1))
                break

        # 4. Scares
        scares: List[Dict[str, Any]] = []
        seen_timestamps = set()

        # Check modern WhenJumpScare layout with <time> tags in <li> elements
        time_tags = soup.find_all("time")
        if time_tags:
            for t_tag in time_tags:
                time_str = t_tag.get_text(strip=True)
                m = TIME_PATTERN.search(time_str)
                if not m:
                    continue
                time_ms = parse_timestamp_to_ms(time_str)
                if time_ms in seen_timestamps:
                    continue
                seen_timestamps.add(time_ms)

                li = t_tag.find_parent("li")
                if li:
                    li_text = li.get_text(separator=" ", strip=True).lower()
                    is_major = "major" in li_text
                    p_desc = li.find("p")
                    desc = p_desc.get_text(strip=True) if p_desc else ""
                else:
                    is_major = False
                    desc = ""

                scares.append({
                    "time": time_str,
                    "resolved_time_ms": time_ms,
                    "type": "major" if is_major else "minor",
                    "description": desc,
                })
        else:
            for tag in soup.find_all(["p", "li", "tr", "div"]):
                # Skip if this element has child block tags that also contain timestamps
                # to avoid double-processing container divs
                child_has_time = any(
                    child.name in ["p", "li", "tr", "div"] and TIME_PATTERN.search(child.get_text())
                    for child in tag.find_all(["p", "li", "tr", "div"])
                )
                if child_has_time:
                    continue

                tag_text = tag.get_text(separator=" ", strip=True)
                m = TIME_PATTERN.search(tag_text)
                if not m:
                    continue

                # Skip header or metadata sections (e.g. "Runtime: 112 minutes")
                lower_text = tag_text.lower()
                if "runtime" in lower_text and len(tag_text) < 40:
                    continue

                time_str = m.group(1)
                time_ms = parse_timestamp_to_ms(time_str)

                if time_ms in seen_timestamps:
                    continue
                seen_timestamps.add(time_ms)

                # Determine major vs minor:
                bold_tag = tag.find(["strong", "b"])
                is_bold = bold_tag is not None and time_str in bold_tag.get_text()
                tag_class = " ".join(tag.get("class", [])) if isinstance(tag.get("class"), list) else str(tag.get("class", ""))
                has_major_marker = bool(
                    re.search(r"\bmajor\b", lower_text)
                    or "major" in tag_class.lower()
                    or tag.get("data-scare-type") == "major"
                )
                is_major = has_major_marker or is_bold

                # Extract description text after timestamp
                desc_part = tag_text[m.end():]
                desc = re.sub(r"^\s*\[?(?:major|minor)\]?\s*[-–—:]?\s*", "", desc_part, flags=re.IGNORECASE)
                desc = desc.strip(" \t\n\r–—-:")
                desc = re.sub(r"\s+", " ", desc).strip()

                scares.append({
                    "time": time_str,
                    "resolved_time_ms": time_ms,
                    "type": "major" if is_major else "minor",
                    "description": desc,
                })

        # Sort scares chronologically
        scares.sort(key=lambda s: s["resolved_time_ms"])
        for idx, s in enumerate(scares, start=1):
            s["id"] = f"scare_{idx:02d}"

        return {
            "title": title,
            "year": year,
            "runtime_minutes": runtime_minutes,
            "tmdb_id": tmdb_id,
            "scares": scares,
            "scare_count": len(scares),
        }

    async def fetch_movie_index(self, max_pages: int = 50) -> List[Dict[str, Any]]:
        """Fetch all movies from whenjumpscare.com index (sitemap or HTML pagination), with wheresthejump.com fallback."""
        await self.init_robots_parser()
        client = await self._get_client()

        all_movies: List[Dict[str, Any]] = []
        seen_slugs = set()
        got_403 = False

        # Strategy 1: Sitemap from whenjumpscare.com (Contains all 1,164 films)
        if "whenjumpscare.com" in self.base_url:
            sitemap_url = f"{self.base_url}/sitemap.xml"
            try:
                logger.info("Attempting to load movie index from sitemap: %s", sitemap_url)
                resp = await client.get(sitemap_url)
                if resp.status_code == 200 and "<urlset" in resp.text:
                    import xml.etree.ElementTree as ET
                    root = ET.fromstring(resp.text)
                    for loc in root.iter("{http://www.sitemaps.org/schemas/sitemap/0.9}loc"):
                        if not loc.text:
                            continue
                        url = loc.text.strip()
                        if "/movie/" in url:
                            slug = url.rstrip("/").split("/")[-1]
                            if slug not in seen_slugs:
                                seen_slugs.add(slug)
                                year = None
                                year_match = YEAR_PATTERN.search(slug)
                                if year_match:
                                    y_str = year_match.group(1) or year_match.group(2)
                                    if y_str:
                                        year = int(y_str)
                                clean_slug = re.sub(r"^\d+-", "", slug)
                                if year:
                                    clean_slug = re.sub(rf"-{year}$", "", clean_slug)
                                title = clean_slug.replace("-", " ").title()
                                all_movies.append({
                                    "title": title,
                                    "url": url,
                                    "slug": slug,
                                    "year": year,
                                    "director": "",
                                    "jump_count": 0,
                                    "rating": 0.0,
                                })
                    if all_movies:
                        logger.info("Successfully fetched %d movies from sitemap.xml", len(all_movies))
                        return all_movies
                elif resp.status_code == 403:
                    logger.warning("whenjumpscare.com sitemap returned 403 Forbidden (Cloudflare WAF on datacenter IP)")
                    got_403 = True
            except Exception as exc:
                logger.warning("Could not parse sitemap.xml (%s); falling back to HTML crawling", exc)

        # Strategy 2: HTML crawling
        if "whenjumpscare.com" in self.base_url and not got_403:
            current_url: Optional[str] = f"{self.base_url}/all-jumpscares"
        else:
            current_url = f"{self.base_url}/full-movie-list/"
        visited_urls = set()
        page_num = 1

        while current_url and current_url not in visited_urls and page_num <= max_pages:
            visited_urls.add(current_url)

            if not self.can_fetch(current_url):
                logger.warning("robots.txt disallows crawling %s; halting index pagination", current_url)
                break

            logger.info("Fetching movie index (page %d): %s", page_num, current_url)
            try:
                resp = await client.get(current_url)
                if resp.status_code == 403:
                    logger.warning("Index page %s returned 403 Forbidden", current_url)
                    got_403 = True
                    break
                resp.raise_for_status()

                page_movies = self.parse_index_html(resp.text, base_url=self.base_url)
                new_movies_on_page = 0
                for m in page_movies:
                    slug = m.get("slug")
                    if slug not in seen_slugs:
                        seen_slugs.add(slug)
                        all_movies.append(m)
                        new_movies_on_page += 1

                logger.info("Page %d yielded %d new movies (total: %d)", page_num, new_movies_on_page, len(all_movies))

                # Check if there is a next page
                next_url = self.parse_pagination_next_url(resp.text, current_url)
                if next_url and next_url not in visited_urls:
                    current_url = next_url
                    page_num += 1
                    if self.delay_seconds > 0:
                        await asyncio.sleep(self.delay_seconds)
                elif "whenjumpscare.com" in self.base_url and new_movies_on_page > 0:
                    page_num += 1
                    current_url = f"{self.base_url}/all-jumpscares?page={page_num}"
                    if self.delay_seconds > 0:
                        await asyncio.sleep(self.delay_seconds)
                else:
                    current_url = None

            except Exception as exc:
                logger.error("Failed to fetch index page %s: %s", current_url, exc)
                break

        # Strategy 3: Automatic fallback to wheresthejump.com if whenjumpscare was blocked by Cloudflare (403)
        if not all_movies and (got_403 or "whenjumpscare.com" in self.base_url):
            fallback_url = "https://wheresthejump.com/full-movie-list/"
            logger.info("No movies fetched from whenjumpscare.com; attempting fallback to %s", fallback_url)
            try:
                fb_resp = await client.get(fallback_url)
                if fb_resp.status_code == 200:
                    fb_movies = self.parse_index_html(fb_resp.text, base_url="https://wheresthejump.com")
                    for m in fb_movies:
                        slug = m.get("slug")
                        if slug not in seen_slugs:
                            seen_slugs.add(slug)
                            all_movies.append(m)
                    logger.info("Fallback to wheresthejump.com yielded %d movies", len(all_movies))
            except Exception as exc:
                logger.error("Fallback to wheresthejump.com failed: %s", exc)

        return all_movies

    async def parse_movie_page(self, movie_url_or_slug: str) -> Dict[str, Any]:
        """Fetch and parse an individual movie jump scare page, respecting robots.txt and rate limits."""
        await self.init_robots_parser()
        client = await self._get_client()

        if movie_url_or_slug.startswith("http://") or movie_url_or_slug.startswith("https://"):
            url = movie_url_or_slug
        else:
            clean_slug = movie_url_or_slug.strip("/")
            if "whenjumpscare.com" in self.base_url:
                if not clean_slug.startswith("movie/"):
                    url = f"{self.base_url}/movie/{clean_slug}/"
                else:
                    url = f"{self.base_url}/{clean_slug}/"
            else:
                url = f"{self.base_url}/{clean_slug}/"

        if not self.can_fetch(url):
            logger.warning("robots.txt disallows crawling %s", url)
            return {
                "title": "",
                "year": None,
                "runtime_minutes": 0,
                "tmdb_id": None,
                "scares": [],
                "scare_count": 0,
            }

        if self.delay_seconds > 0:
            await asyncio.sleep(self.delay_seconds)

        try:
            resp = await client.get(url)
            # If whenjumpscare returns 403 on movie page, attempt fallback to wheresthejump
            if resp.status_code == 403 and "whenjumpscare.com" in url:
                clean_slug = url.rstrip("/").split("/")[-1]
                slug_no_id = re.sub(r"^\d+-", "", clean_slug)
                wtj_url = f"https://wheresthejump.com/jump-scares-in-{slug_no_id}/"
                try:
                    fb_resp = await client.get(wtj_url)
                    if fb_resp.status_code == 200:
                        return self.parse_movie_html(fb_resp.text)
                except Exception:
                    pass
            resp.raise_for_status()
            data = self.parse_movie_html(resp.text)
            return data
        except Exception as exc:
            logger.error("Failed to parse movie page %s: %s", url, exc)
            return {
                "title": "",
                "year": None,
                "runtime_minutes": 0,
                "tmdb_id": None,
                "scares": [],
                "scare_count": 0,
            }
