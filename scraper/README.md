# SpookCue Scraper & Data Pipeline

Pipeline for scraping jump scares from WhenJumpScare, enriching with TMDB metadata, downloading subtitles, sanitizing dialogue, and creating Double Metaphone phonetic indexes for real-time offline acoustic matching.

## Setup

```bash
pip install -e ".[dev]"
```

## Running

```bash
python -m spookcue_scraper.main
```
