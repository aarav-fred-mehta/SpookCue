"""WhenJumpScare scraper (with Where's The Jump? fallback).

Scrapes movie listings, jump scare timestamps, major/minor designations,
and text descriptions from whenjumpscare.com, with per-host robots.txt
compliance, crawl-delay enforcement, and polite rate limiting.
"""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path
import re
import time
from typing import Any, Dict, List, Optional
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser
import xml.etree.ElementTree as ET

from bs4 import BeautifulSoup
import httpx

logger = logging.getLogger(__name__)

TIME_PATTERN = re.compile(r"\b(\d{1,2}:\d{2}:\d{2}|\d{1,2}:\d{2})\b")
RUNTIME_PATTERN = re.compile(r"(\d+)\s*(?:minutes?|mins?|m\b)", re.IGNORECASE)
YEAR_PATTERN = re.compile(r"\((\d{4})\)|\b(19\d{2}|20\d{2})\b")
TRAILING_PAREN_YEAR_RE = re.compile(r"\s*\(((?:18|19|20)\d{2})\)\s*$")
TRAILING_BARE_YEAR_RE = re.compile(r"\s+((?:19|20)\d{2})\s*$")
SLUG_TRAILING_YEAR_RE = re.compile(r"-((?:19|20)\d{2})$")

_MINOR_TITLE_WORDS = frozenset({
    "a", "an", "and", "as", "at", "by", "for", "from",
    "in", "nor", "of", "on", "or", "the", "to", "with",
})
_ROMAN_NUMERALS = frozenset({
    "ii", "iii", "iv", "vi", "vii", "viii", "ix", "xi", "xii", "xiii",
})
_CONTRACTIONS_T = frozenset({
    "aren", "can", "couldn", "didn", "doesn", "don", "hadn", "hasn",
    "haven", "isn", "mustn", "shouldn", "wasn", "weren", "won", "wouldn",
})
_SPECIAL_SLUGS: Dict[str, str] = {
    "c-h-u-d": "C.H.U.D.",
    "m3gan": "M3GAN",
    "m3gan-2-0": "M3GAN 2.0",
    "1br": "1BR",
    "vfw": "VFW",
    "se7en": "Se7en",
    "alien3": "Alien³",
    "rec": "[REC]",
    "rec-2": "[REC] 2",
    "rec-3-genesis": "[REC] 3: Genesis",
    "rec-4-apocalypse": "[REC] 4: Apocalypse",
    "they-them": "They/Them",
    "trick-r-treat": "Trick 'r Treat",
    "all-hallows-eve": "All Hallows' Eve",
    "bubba-ho-tep": "Bubba Ho-Tep",
    "re-animator": "Re-Animator",
    "bride-of-re-animator": "Bride of Re-Animator",
    "jaws-3-d": "Jaws 3-D",
    "piranha-3dd": "Piranha 3DD",
    "spider-man": "Spider-Man",
    "spider-man-2": "Spider-Man 2",
    "gretel-and-hansel": "Gretel & Hansel",
}


def parse_timestamp_to_ms(time_str: str) -> Optional[int]:
    """Parse HH:MM:SS or MM:SS to milliseconds."""
    if not time_str:
        return None
    parts = time_str.strip().split(":")
    try:
        if len(parts) == 3:
            h, m, s = int(parts[0]), int(parts[1]), float(parts[2])
            return int((h * 3600 + m * 60 + s) * 1000)
        if len(parts) == 2:
            m, s = int(parts[0]), float(parts[1])
            return int((m * 60 + s) * 1000)
    except (ValueError, TypeError):
        return None
    return None


def clean_slug_title(slug_body: str) -> str:
    """Convert a cleaned movie slug (without TMDB ID or release year) into a readable title."""
    raw = slug_body.strip("- ").lower()
    if not raw:
        return ""

    if raw in _SPECIAL_SLUGS:
        return _SPECIAL_SLUGS[raw]

    if raw == "v-h-s":
        return "V/H/S"
    if raw.startswith("v-h-s-"):
        rest = clean_slug_title(raw[len("v-h-s-"):])
        return f"V/H/S/{rest}" if rest else "V/H/S"

    tokens = [t for t in raw.split("-") if t]
    merged: List[str] = []
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        next_tok = tokens[i + 1] if i + 1 < len(tokens) else None

        if next_tok == "s" and tok not in ("v", "h"):
            merged.append(f"{tok}'s")
            i += 2
            continue
        if next_tok == "t" and tok in _CONTRACTIONS_T:
            merged.append(f"{tok}'t")
            i += 2
            continue
        if next_tok == "re" and tok in ("you", "we", "they"):
            merged.append(f"{tok}'re")
            i += 2
            continue
        if tok == "i" and next_tok in ("m", "ve", "ll", "d"):
            merged.append(f"I'{next_tok}")
            i += 2
            continue
        if tok == "re" and next_tok == "animator":
            merged.append("Re-Animator")
            i += 2
            continue
        if tok == "spider" and next_tok == "man":
            merged.append("Spider-Man")
            i += 2
            continue
        if tok == "bubba" and next_tok == "ho" and i + 2 < len(tokens) and tokens[i + 2] == "tep":
            merged.append("Bubba Ho-Tep")
            i += 3
            continue
        merged.append(tok)
        i += 1

    formatted: List[str] = []
    total = len(merged)
    for idx, tok in enumerate(merged):
        low = tok.lower()
        if low in _ROMAN_NUMERALS:
            formatted.append(low.upper())
        elif low == "llc":
            formatted.append("LLC")
        elif low == "vfw":
            formatted.append("VFW")
        elif low == "1br":
            formatted.append("1BR")
        elif low == "m3gan":
            formatted.append("M3GAN")
        elif low == "3d":
            formatted.append("3D")
        elif low == "3dd":
            formatted.append("3DD")
        elif low == "vs":
            formatted.append("vs.")
        elif re.fullmatch(r"\d+(?:st|nd|rd|th)", low):
            formatted.append(low)
        elif "'" in tok:
            head, tail = tok.split("'", 1)
            head_fmt = "I" if head.lower() == "i" else head.capitalize()
            formatted.append(f"{head_fmt}'{tail.lower()}")
        elif low in _MINOR_TITLE_WORDS and 0 < idx < total - 1:
            formatted.append(low)
        elif any(ch.isupper() for ch in tok):
            formatted.append(tok)
        else:
            formatted.append(tok.capitalize())

    return " ".join(formatted)


def _extract_title_and_year(raw_title: str) -> tuple[str, Optional[int]]:
    """Extract release year from the end of a title string without consuming years inside titles."""
    clean = raw_title.strip()
    year: Optional[int] = None

    paren_match = TRAILING_PAREN_YEAR_RE.search(clean)
    if paren_match:
        year = int(paren_match.group(1))
        clean = clean[:paren_match.start()].strip()
        return clean, year

    # Check any parenthetical 4-digit year near the end (e.g. "Title (2013) [Unrated]")
    all_parens = list(re.finditer(r"\(((?:18|19|20)\d{2})\)", clean))
    if all_parens:
        last_paren = all_parens[-1]
        prefix = clean[:last_paren.start()].strip()
        if prefix:
            year = int(last_paren.group(1))
            clean = prefix
            return clean, year

    bare_match = TRAILING_BARE_YEAR_RE.search(clean)
    if bare_match:
        prefix = clean[:bare_match.start()].strip()
        if prefix:
            year = int(bare_match.group(1))
            clean = prefix

    return clean, year


class WhenJumpScareScraper:
    """Scrapes movie indices and jump scare timestamps from whenjumpscare.com and wheresthejump.com."""

    DEFAULT_BASE_URL = "https://www.whenjumpscare.com"
    USER_AGENT = "SpookCueBot/0.1 (+https://github.com/aarav-fred-mehta/SpookCue)"
    DEFAULT_HEADERS = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
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
        self._robot_parsers: Dict[str, Optional[RobotFileParser]] = {}
        self._robots_loaded = False
        self._host_delays: Dict[str, float] = {}
        self._last_request_at: Dict[str, float] = {}
        self._403_counts: Dict[str, int] = {}
        self._blocked_origins: set[str] = set()
        self.last_index_from_fallback: bool = False

    @staticmethod
    def _origin_for(url: str) -> str:
        parsed = urlparse(url)
        if parsed.scheme and parsed.netloc:
            return f"{parsed.scheme}://{parsed.netloc}".lower()
        return ""

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

    async def _respect_rate_limit(self, url: str) -> None:
        origin = self._origin_for(url) or self.base_url.lower()
        required_delay = max(self.delay_seconds, self._host_delays.get(origin, 0.0))
        if required_delay <= 0:
            return
        now = time.monotonic()
        last = self._last_request_at.get(origin)
        if last is not None:
            remaining = required_delay - (now - last)
            if remaining > 0:
                await asyncio.sleep(remaining)
        self._last_request_at[origin] = time.monotonic()

    async def init_robots_parser(self, origin: Optional[str] = None) -> None:
        """Fetch and parse robots.txt per host to verify crawling permissions and crawl-delay."""
        target_origin = (origin or self.base_url).rstrip("/").lower()
        if target_origin in self._robot_parsers:
            return
        if origin is None and self._robots_loaded and self._robot_parser is not None:
            self._robot_parsers[target_origin] = self._robot_parser
            return

        robots_url = f"{target_origin}/robots.txt"
        client = await self._get_client()
        parser = RobotFileParser()

        try:
            resp = await client.get(robots_url, timeout=10.0)
            self._last_request_at[target_origin] = time.monotonic()
            if resp.status_code == 200:
                parser.parse(resp.text.splitlines())
                self._robot_parsers[target_origin] = parser
                if target_origin == self.base_url.lower():
                    self._robot_parser = parser
                crawl_delay = parser.crawl_delay(self.USER_AGENT) or parser.crawl_delay("*")
                if crawl_delay is not None:
                    effective_delay = max(self.delay_seconds, float(crawl_delay))
                    self._host_delays[target_origin] = effective_delay
                    if target_origin == self.base_url.lower():
                        self.delay_seconds = effective_delay
                    logger.info(
                        "robots.txt (%s) specified Crawl-delay %.1fs (enforcing %.1fs)",
                        target_origin,
                        float(crawl_delay),
                        effective_delay,
                    )
                logger.info("Successfully loaded robots.txt from %s", robots_url)
            else:
                self._robot_parsers[target_origin] = None
                logger.debug("robots.txt returned status %d for %s; proceeding with default delay", resp.status_code, robots_url)
        except Exception as exc:
            self._last_request_at[target_origin] = time.monotonic()
            self._robot_parsers[target_origin] = None
            logger.debug("Could not fetch robots.txt from %s (%s); proceeding with default delay", robots_url, exc)

        if origin is None:
            self._robots_loaded = True

    def is_origin_blocked(self, url_or_origin: str) -> bool:
        """Return True if this origin repeatedly blocked requests with HTTP 403 during this run."""
        origin = self._origin_for(url_or_origin) or url_or_origin.rstrip("/").lower()
        return origin in self._blocked_origins

    def can_fetch(self, url: str) -> bool:
        """Check if robots.txt permits crawling the given URL."""
        origin = self._origin_for(url)
        parser = self._robot_parsers.get(origin) if origin else None
        if parser is None:
            parser = self._robot_parser
        if parser is not None:
            return parser.can_fetch(self.USER_AGENT, url)
        return True

    async def _fetch_html(self, target_url: str) -> Optional[str]:
        """Fetch HTML or XML politely using httpx while honoring robots.txt, crawl-delay, and HTTP 429/403."""
        origin = self._origin_for(target_url) or self.base_url.lower()
        if origin in self._blocked_origins:
            logger.debug("Skipping %s; %s previously returned HTTP 403", target_url, origin)
            return None

        await self.init_robots_parser(origin)
        if not self.can_fetch(target_url):
            logger.warning("robots.txt disallows crawling %s", target_url)
            return None

        client = await self._get_client()
        for attempt in range(2):
            await self._respect_rate_limit(target_url)
            try:
                resp = await client.get(target_url, timeout=15.0)
            except Exception as exc:
                self._last_request_at[origin] = time.monotonic()
                logger.debug("httpx fetch failed for %s: %s", target_url, exc)
                return None

            self._last_request_at[origin] = time.monotonic()
            if resp.status_code == 200:
                self._403_counts[origin] = 0
                return resp.text
            if resp.status_code == 429 and attempt == 0:
                retry_after_raw = resp.headers.get("Retry-After", "")
                try:
                    wait_s = max(float(retry_after_raw), max(self.delay_seconds, 5.0))
                except ValueError:
                    wait_s = max(self.delay_seconds, 5.0)
                wait_s = min(wait_s, 60.0)
                logger.warning("HTTP 429 from %s; honoring Retry-After %.1fs", target_url, wait_s)
                await asyncio.sleep(wait_s)
                continue
            if resp.status_code == 403:
                count = self._403_counts.get(origin, 0) + 1
                self._403_counts[origin] = count
                logger.info("Access to %s returned 403 Forbidden; skipping without bypass", target_url)
                if count >= 2 or target_url.rstrip("/").endswith("/sitemap.xml"):
                    self._blocked_origins.add(origin)
                    logger.warning(
                        "Origin %s is blocking requests with HTTP 403; skipping further requests to this host for this run",
                        origin,
                    )
                return None
            logger.debug("Unexpected status %d for %s", resp.status_code, target_url)
            return None
        return None

    @staticmethod
    def _parse_sitemap_movie_url(url: str, lastmod: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """Parse a WhenJumpScare sitemap movie URL into structured metadata."""
        if "/movie/" not in url:
            return None
        slug = url.rstrip("/").split("/")[-1]
        if not slug:
            return None

        m_tmdb = re.search(r"/movie/(\d+)-", url)
        tmdb_id = int(m_tmdb.group(1)) if m_tmdb else None

        # Strip leading TMDB ID first so IDs like 2082 or 1992 are never parsed as years
        clean_slug = re.sub(r"^\d+-", "", slug)

        # Extract trailing release year only from the end of the slug (so 1922-2017 -> year=2017, title="1922")
        year: Optional[int] = None
        year_match = SLUG_TRAILING_YEAR_RE.search(clean_slug)
        if year_match:
            year = int(year_match.group(1))
            clean_slug = clean_slug[:year_match.start()]

        title = clean_slug_title(clean_slug)
        if not title:
            return None

        return {
            "title": title,
            "url": url,
            "slug": slug,
            "year": year,
            "tmdb_id": tmdb_id,
            "lastmod": lastmod,
            "source": "whenjumpscare",
            "director": "",
            "jump_count": 0,
            "rating": 0.0,
        }

    @staticmethod
    def parse_pagination_next_url(html: str, current_url: str) -> Optional[str]:
        """Detect and extract 'next' page URL from pagination controls if present."""
        soup = BeautifulSoup(html, "html.parser")
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
            non_movie_slugs = {
                "movie", "movies", "all-jumpscares", "popular-movies", "recently-added",
                "find-movie", "submit-jumpscare", "contact", "about", "privacy-policy",
                "terms", "terms-of-service", "disclaimer", "watch-along", "live-companion",
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
                        clean_title = re.sub(r"^Jump Scares in\s+", "", title, flags=re.IGNORECASE)
                        clean_title = re.sub(r"\s+Jump Scares.*$", "", clean_title, flags=re.IGNORECASE)
                        clean_title = re.sub(r"\s*[|–—-]\s*WhenJumpScare.*$", "", clean_title, flags=re.IGNORECASE).strip()

                        clean_title, year = _extract_title_and_year(clean_title)

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
            if not title or title.strip().lower() in ("tv shows", "tv show", "tv series", "television", "home"):
                continue
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

        title = ""
        year: Optional[int] = None

        h1 = soup.find("h1")
        if h1:
            h1_text = h1.get_text(strip=True)
            clean_title = re.sub(r"^Jump Scares in\s+", "", h1_text, flags=re.IGNORECASE)
            clean_title = re.sub(r"\s+Jump Scares.*$", "", clean_title, flags=re.IGNORECASE)
            clean_title = re.sub(r"\s*[|–—-]\s*WhenJumpScare.*$", "", clean_title, flags=re.IGNORECASE).strip()

            title, year = _extract_title_and_year(clean_title)

        if year is None:
            span_year = soup.find(
                "span",
                class_=lambda c: c and "timecode" in c and any(cls in c for cls in ["text-bone-2", "text-bone"]),
            )
            if span_year and span_year.get_text().strip().isdigit():
                y_val = int(span_year.get_text().strip())
                if 1900 <= y_val <= 2100:
                    year = y_val

        runtime_minutes = 0
        content_text = soup.get_text()
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

        tmdb_id: Optional[int] = None
        for a in soup.find_all("a", href=True):
            href = a["href"]
            m_tmdb = re.search(r"themoviedb\.org/movie/(\d+)", href, re.IGNORECASE)
            if m_tmdb:
                tmdb_id = int(m_tmdb.group(1))
                break

        scares: List[Dict[str, Any]] = []
        seen_timestamps = set()

        time_tags = soup.find_all("time")
        if time_tags:
            for t_tag in time_tags:
                time_str = t_tag.get_text(strip=True)
                m = TIME_PATTERN.search(time_str)
                if not m:
                    continue
                time_ms = parse_timestamp_to_ms(time_str)
                if time_ms is None or time_ms in seen_timestamps:
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

                lower_text = tag_text.lower()
                if "runtime" in lower_text and len(tag_text) < 40:
                    continue

                time_str = m.group(1)
                time_ms = parse_timestamp_to_ms(time_str)
                if time_ms is None or time_ms in seen_timestamps:
                    continue
                seen_timestamps.add(time_ms)

                bold_tag = tag.find(["strong", "b"])
                is_bold = bold_tag is not None and time_str in bold_tag.get_text()
                tag_class = " ".join(tag.get("class", [])) if isinstance(tag.get("class"), list) else str(tag.get("class", ""))
                has_major_marker = bool(
                    re.search(r"\bmajor\b", lower_text)
                    or "major" in tag_class.lower()
                    or tag.get("data-scare-type") == "major"
                )
                is_major = has_major_marker or is_bold

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
        self.last_index_from_fallback = False

        all_movies: List[Dict[str, Any]] = []
        seen_slugs = set()
        got_blocked = False

        # Strategy 1: Sitemap from whenjumpscare.com (with bundled XML fallback when live fetch is unavailable)
        if "whenjumpscare.com" in self.base_url:
            sitemap_url = f"{self.base_url}/sitemap.xml"
            try:
                logger.info("Attempting to load movie index from sitemap: %s", sitemap_url)
                sitemap_text = await self._fetch_html(sitemap_url)
                used_bundled_fallback = False
                if not sitemap_text or "<urlset" not in sitemap_text:
                    bundled_path = Path(__file__).parent / "data" / "whenjumpscare_sitemap.xml"
                    if bundled_path.exists():
                        logger.info("Live sitemap unavailable; loading bundled sitemap fallback (%s)", bundled_path)
                        sitemap_text = bundled_path.read_text(encoding="utf-8")
                        used_bundled_fallback = True

                if sitemap_text and "<urlset" in sitemap_text:
                    root = ET.fromstring(sitemap_text)
                    ns = "{http://www.sitemaps.org/schemas/sitemap/0.9}"
                    url_elements = list(root.iter(f"{ns}url"))
                    if url_elements:
                        for url_elem in url_elements:
                            loc_elem = url_elem.find(f"{ns}loc")
                            if loc_elem is None or not loc_elem.text:
                                continue
                            lastmod_elem = url_elem.find(f"{ns}lastmod")
                            lastmod = (
                                lastmod_elem.text.strip()
                                if (lastmod_elem is not None and lastmod_elem.text)
                                else None
                            )
                            record = self._parse_sitemap_movie_url(loc_elem.text.strip(), lastmod=lastmod)
                            if record and record["slug"] not in seen_slugs:
                                if used_bundled_fallback:
                                    record["from_bundled_fallback"] = True
                                seen_slugs.add(record["slug"])
                                all_movies.append(record)
                    else:
                        for loc in root.iter(f"{ns}loc"):
                            if not loc.text:
                                continue
                            record = self._parse_sitemap_movie_url(loc.text.strip(), lastmod=None)
                            if record and record["slug"] not in seen_slugs:
                                if used_bundled_fallback:
                                    record["from_bundled_fallback"] = True
                                seen_slugs.add(record["slug"])
                                all_movies.append(record)

                    if all_movies:
                        self.last_index_from_fallback = used_bundled_fallback
                        logger.info(
                            "Successfully loaded %d movies from sitemap.xml%s",
                            len(all_movies),
                            " (bundled offline fallback)" if used_bundled_fallback else "",
                        )
                        return all_movies
                else:
                    got_blocked = True
            except Exception as exc:
                logger.warning("Could not parse sitemap.xml (%s); falling back to HTML crawling", exc)

        # Strategy 2: HTML crawling (strictly respecting robots.txt and crawl-delay)
        if got_blocked or self.is_origin_blocked(self.base_url):
            crawl_base_url = "https://wheresthejump.com"
            current_url: Optional[str] = f"{crawl_base_url}/full-movie-list/"
        elif "whenjumpscare.com" in self.base_url:
            crawl_base_url = self.base_url
            current_url = f"{crawl_base_url}/all-jumpscares"
        else:
            crawl_base_url = self.base_url
            current_url = f"{crawl_base_url}/full-movie-list/"

        visited_urls = set()
        page_num = 1

        while current_url and current_url not in visited_urls and page_num <= max_pages:
            visited_urls.add(current_url)
            logger.info("Fetching movie index (page %d): %s", page_num, current_url)
            html_text = await self._fetch_html(current_url)
            if not html_text:
                got_blocked = True
                break

            page_movies = self.parse_index_html(html_text, base_url=crawl_base_url)
            new_movies_on_page = 0
            for m in page_movies:
                slug = m.get("slug")
                if slug not in seen_slugs:
                    seen_slugs.add(slug)
                    all_movies.append(m)
                    new_movies_on_page += 1

            logger.info("Page %d yielded %d new movies (total: %d)", page_num, new_movies_on_page, len(all_movies))

            next_url = self.parse_pagination_next_url(html_text, current_url)
            if next_url and next_url not in visited_urls:
                current_url = next_url
                page_num += 1
            elif "whenjumpscare.com" in crawl_base_url and new_movies_on_page > 0:
                page_num += 1
                current_url = f"{crawl_base_url}/all-jumpscares?page={page_num}"
            else:
                current_url = None

        # Strategy 3: Fallback to wheresthejump.com respecting its robots.txt & crawl-delay
        if not all_movies and (got_blocked or "whenjumpscare.com" in self.base_url):
            fallback_url = "https://wheresthejump.com/full-movie-list/"
            if fallback_url not in visited_urls:
                logger.info("No movies fetched from whenjumpscare.com; attempting fallback to %s", fallback_url)
                fb_html = await self._fetch_html(fallback_url)
                if fb_html:
                    fb_movies = self.parse_index_html(fb_html, base_url="https://wheresthejump.com")
                    for m in fb_movies:
                        slug = m.get("slug")
                        if slug not in seen_slugs:
                            seen_slugs.add(slug)
                            all_movies.append(m)
                    logger.info("Fallback to wheresthejump.com yielded %d movies", len(all_movies))

        return all_movies

    async def parse_movie_page(
        self,
        movie_url_or_slug: str,
        allow_wtj_fallback: bool = True,
    ) -> Dict[str, Any]:
        """Fetch and parse an individual movie jump scare page, respecting robots.txt and rate limits."""
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

        fetched_source = "wheresthejump" if "wheresthejump.com" in url else "whenjumpscare"
        empty_result: Dict[str, Any] = {
            "title": "",
            "year": None,
            "runtime_minutes": 0,
            "tmdb_id": None,
            "scares": [],
            "scare_count": 0,
            "source": fetched_source,
            "fetch_failed": True,
        }

        try:
            html_text = await self._fetch_html(url)
            if not html_text and allow_wtj_fallback and "whenjumpscare.com" in url:
                clean_slug = url.rstrip("/").split("/")[-1]
                slug_no_id = re.sub(r"^\d+-", "", clean_slug)
                wtj_url = f"https://wheresthejump.com/jump-scares-in-{slug_no_id}/"
                logger.info("whenjumpscare unavailable for %s; attempting WTJ fallback: %s", url, wtj_url)
                html_text = await self._fetch_html(wtj_url)
                if html_text:
                    fetched_source = "wheresthejump"

            if not html_text:
                logger.warning("Could not fetch movie page: %s", url)
                return empty_result

            parsed = self.parse_movie_html(html_text)
            parsed["source"] = fetched_source
            parsed["fetch_failed"] = False
            return parsed
        except Exception as exc:
            logger.error("Failed to parse movie page %s: %s", url, exc)
            return empty_result
