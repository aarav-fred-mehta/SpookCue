"""SpookCue Scraper & Data Pipeline package."""

from spookcue_scraper.main import run_pipeline
from spookcue_scraper.tmdb import TmdbClient
from spookcue_scraper.whenjumpscare import WhenJumpScareScraper

__version__ = "0.1.0"

__all__ = [
    "WhenJumpScareScraper",
    "TmdbClient",
    "run_pipeline",
]
