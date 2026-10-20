"""SpookCue Scraper & Data Pipeline package."""

from spookcue_scraper.main import run_pipeline
from spookcue_scraper.phonetics import build_phonetic_index, encode_tokens_to_phonetics
from spookcue_scraper.resolver import resolve_scares_to_anchors
from spookcue_scraper.sanitizer import sanitize_subtitles
from spookcue_scraper.subtitles import SubtitleFetcher
from spookcue_scraper.tmdb import TmdbClient
from spookcue_scraper.whenjumpscare import WhenJumpScareScraper

__version__ = "0.1.0"

__all__ = [
    "WhenJumpScareScraper",
    "TmdbClient",
    "SubtitleFetcher",
    "sanitize_subtitles",
    "encode_tokens_to_phonetics",
    "build_phonetic_index",
    "resolve_scares_to_anchors",
    "run_pipeline",
]
