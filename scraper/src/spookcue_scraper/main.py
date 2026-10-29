"""Orchestrator for the SpookCue data scraping and indexing pipeline."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone
import logging
import os
from pathlib import Path
import re
from typing import Any, Dict, List, Optional, Set

try:
    import orjson

    def dump_json_bytes(obj: Any) -> bytes:
        return orjson.dumps(obj, option=orjson.OPT_INDENT_2 | orjson.OPT_SORT_KEYS)

    def load_json_bytes(data: bytes) -> Any:
        return orjson.loads(data)
except ImportError:
    import json

    def dump_json_bytes(obj: Any) -> bytes:
        return json.dumps(obj, indent=2, sort_keys=True).encode("utf-8")

    def load_json_bytes(data: bytes) -> Any:
        return json.loads(data.decode("utf-8"))


from spookcue_scraper.phonetics import build_phonetic_index, verify_compression
from spookcue_scraper.resolver import resolve_scares_to_anchors
from spookcue_scraper.sanitizer import sanitize_subtitles
from spookcue_scraper.subtitles import SubtitleFetcher
from spookcue_scraper.tmdb import TmdbClient
from spookcue_scraper.whenjumpscare import WhenJumpScareScraper

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("spookcue_scraper")
# Suppress low-level HTTP transport noise (avoids confusing HTTP 200 OK with data found)
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)

RE_TMDB_EXPLICIT = re.compile(r"(?:tmdb|themoviedb)(?:[_-]?id)?[/_=-]?(\d{3,8})\b", re.IGNORECASE)
RE_TMDB_LEAD = re.compile(r"^(\d{3,8})-[a-z0-9]")
RE_TMDB_TRAIL = re.compile(r"-(\d{5,8})$")


def extract_tmdb_id_from_slug(slug_or_url: str) -> Optional[int]:
    """Extract numeric TMDB ID from slug or URL if embedded (Phase 1.1.3)."""
    if not slug_or_url:
        return None

    m_tmdb = RE_TMDB_EXPLICIT.search(slug_or_url)
    if m_tmdb:
        return int(m_tmdb.group(1))

    slug = slug_or_url.rstrip("/").split("/")[-1].lower()
    m_lead = RE_TMDB_LEAD.match(slug)
    if m_lead:
        return int(m_lead.group(1))

    m_trail = RE_TMDB_TRAIL.search(slug)
    if m_trail:
        return int(m_trail.group(1))

    return None


def load_existing_catalog(catalog_path: Path) -> List[Dict[str, Any]]:
    """Load existing catalog.json if it exists."""
    if not catalog_path.exists():
        return []
    try:
        data = catalog_path.read_bytes()
        return load_json_bytes(data)
    except Exception as exc:
        logger.warning("Could not read existing catalog %s: %s", catalog_path, exc)
        return []


def load_existing_version(version_path: Path) -> Dict[str, Any]:
    """Load existing version.json if it exists."""
    if not version_path.exists():
        return {"v": 0, "updated": "", "movie_count": 0, "scare_count": 0}
    try:
        data = version_path.read_bytes()
        return load_json_bytes(data)
    except Exception as exc:
        logger.warning("Could not read existing version %s: %s", version_path, exc)
        return {"v": 0, "updated": "", "movie_count": 0, "scare_count": 0}


async def process_movie(
    item: Dict[str, Any],
    scraper: WhenJumpScareScraper,
    tmdb_client: TmdbClient,
    subtitles_fetcher: SubtitleFetcher,
    known_tmdb_ids: Dict[str, int],
) -> Optional[Dict[str, Any]]:
    """Scrape jump scares, fetch TMDB metadata & subtitles, sanitize dialogue, and index."""
    title = item.get("title", "").strip()
    year = item.get("year")
    slug = item.get("slug", "")

    if not title:
        return None

    # 1. Check known TMDB IDs first
    lookup_key = f"{title.lower()}::{year}"
    tmdb_id = item.get("tmdb_id") or known_tmdb_ids.get(lookup_key)

    # 2. Extract TMDB ID from slug or URL if embedded (Phase 1.1.3 primary path)
    if tmdb_id is None:
        tmdb_id = extract_tmdb_id_from_slug(slug) or extract_tmdb_id_from_slug(item.get("url", ""))

    # 3. Parse jump scares from movie page (and check for embedded TMDB link)
    movie_page_data = await scraper.parse_movie_page(item.get("url") or slug)
    if tmdb_id is None:
        tmdb_id = movie_page_data.get("tmdb_id")

    # 4. Fallback to TMDB search API if still unknown
    if tmdb_id is None:
        tmdb_id = await tmdb_client.search_movie(title, year)

    if tmdb_id is None:
        logger.warning("Could not resolve TMDB ID for '%s' (%s); skipping", title, year)
        return None

    raw_scares = movie_page_data.get("scares", [])
    runtime = movie_page_data.get("runtime_minutes") or 0

    # 2. Get TMDB details (poster, confirmed runtime, release date, imdb_id)
    tmdb_details = await tmdb_client.get_movie_details(tmdb_id)
    poster_path = tmdb_details.get("poster_path") or ""
    confirmed_runtime = tmdb_details.get("runtime") or runtime or 0
    imdb_id = tmdb_details.get("imdb_id")

    # 3. Fetch subtitles
    srt_content = await subtitles_fetcher.fetch_subtitles(
        tmdb_id,
        title=title,
        year=year,
        imdb_id=imdb_id,
    )
    has_subtitles = srt_content is not None and len(srt_content.strip()) > 0

    phonetic_index: List[Dict[str, Any]] = []
    resolved_scares: List[Dict[str, Any]] = []

    if has_subtitles and srt_content:
        # 4. Sanitize subtitles and build phonetic index
        sanitized_dialogue = sanitize_subtitles(srt_content)
        phonetic_index = build_phonetic_index(sanitized_dialogue)
        # 5. Resolve scare anchors
        resolved_scares = resolve_scares_to_anchors(raw_scares, phonetic_index)
    else:
        # Clock-only mode: resolve with no anchors
        resolved_scares = resolve_scares_to_anchors(raw_scares, [])

    movie_doc = {
        "tmdb_id": tmdb_id,
        "title": title,
        "release_year": year,
        "runtime_minutes": confirmed_runtime,
        "scares": resolved_scares,
        "phonetic_index": phonetic_index,
        "source": item.get("source", "whenjumpscare" if "whenjumpscare.com" in item.get("url", "") else "wheresthejump"),
        "source_lastmod": item.get("lastmod"),
    }

    catalog_entry = {
        "tmdb_id": tmdb_id,
        "title": title,
        "release_year": year,
        "runtime_minutes": confirmed_runtime,
        "scare_count": len(resolved_scares),
        "poster_path": poster_path,
        "has_subtitles": has_subtitles,
    }

    return {
        "movie_doc": movie_doc,
        "catalog_entry": catalog_entry,
    }


async def run_pipeline(
    data_dir: Path,
    limit: Optional[int] = None,
    force: bool = False,
    delay_seconds: float = 2.0,
) -> Dict[str, Any]:
    """Execute the full data pipeline."""
    movies_dir = data_dir / "movies"
    movies_dir.mkdir(parents=True, exist_ok=True)

    catalog_path = data_dir / "catalog.json"
    version_path = data_dir / "version.json"

    existing_catalog = load_existing_catalog(catalog_path)
    existing_version = load_existing_version(version_path)

    # Map existing items by tmdb_id and (title, year)
    catalog_by_id: Dict[int, Dict[str, Any]] = {
        item["tmdb_id"]: item for item in existing_catalog if "tmdb_id" in item
    }
    known_tmdb_ids: Dict[str, int] = {
        f"{item['title'].lower()}::{item.get('release_year')}": item["tmdb_id"]
        for item in existing_catalog if "title" in item and "tmdb_id" in item
    }

    # Ensure any movie files on disk are indexed even if catalog was deleted or partial
    if movies_dir.exists():
        for mf in movies_dir.glob("*.json"):
            try:
                doc = load_json_bytes(mf.read_bytes())
                tid = doc.get("tmdb_id")
                if tid and tid not in catalog_by_id:
                    catalog_by_id[tid] = {
                        "tmdb_id": tid,
                        "title": doc.get("title", ""),
                        "release_year": doc.get("release_year"),
                        "runtime_minutes": doc.get("runtime_minutes", 0),
                        "scare_count": len(doc.get("scares", [])),
                        "poster_path": doc.get("poster_path", ""),
                        "has_subtitles": len(doc.get("phonetic_index", [])) > 0,
                    }
                if tid and doc.get("title"):
                    known_tmdb_ids.setdefault(f"{doc['title'].lower()}::{doc.get('release_year')}", tid)
            except Exception:
                pass

    scraper = WhenJumpScareScraper(delay_seconds=delay_seconds)
    tmdb_client = TmdbClient()
    subtitles_fetcher = SubtitleFetcher()

    try:
        # Fetch movie index
        movie_items = await scraper.fetch_movie_index()
        if not movie_items:
            if catalog_by_id:
                logger.warning("No movies found from index; falling back to existing catalog for subtitle checks")
                movie_items = [
                    {
                        "title": m["title"],
                        "year": m.get("release_year"),
                        "tmdb_id": m["tmdb_id"],
                    }
                    for m in catalog_by_id.values()
                ]
            else:
                logger.warning("No movies found from index; checking existing catalog files")
                return {
                    "added": 0,
                    "updated": 0,
                    "total_movies": 0,
                    "total_scares": 0,
                }

        if limit:
            movie_items = movie_items[:limit]

        added_count = 0
        updated_count = 0

        for item in movie_items:
            title = item.get("title", "")
            year = item.get("year")
            slug = item.get("slug", "")
            item_url = item.get("url", "")
            item_lastmod = item.get("lastmod")
            item_source = item.get("source", "whenjumpscare" if "whenjumpscare.com" in item_url else "wheresthejump")
            lookup_key = f"{title.lower()}::{year}"

            # 1. Authoritative TMDB ID from WhenJumpScare slug/sitemap vs legacy TMDB ID
            sitemap_tmdb_id = (
                item.get("tmdb_id")
                or extract_tmdb_id_from_slug(slug)
                or extract_tmdb_id_from_slug(item_url)
            )
            legacy_tmdb_id = known_tmdb_ids.get(lookup_key)

            # Reconcile ID if an old file exists under legacy ID but WhenJumpScare gave canonical ID:
            if sitemap_tmdb_id and legacy_tmdb_id and sitemap_tmdb_id != legacy_tmdb_id:
                old_file = movies_dir / f"{legacy_tmdb_id}.json"
                new_file = movies_dir / f"{sitemap_tmdb_id}.json"
                if old_file.exists() and not new_file.exists():
                    logger.info("Reconciling legacy TMDB ID %d -> %d for '%s'", legacy_tmdb_id, sitemap_tmdb_id, title)
                    try:
                        old_doc = load_json_bytes(old_file.read_bytes())
                        old_doc["tmdb_id"] = sitemap_tmdb_id
                        new_file.write_bytes(dump_json_bytes(old_doc))
                        old_file.unlink(missing_ok=True)
                        if legacy_tmdb_id in catalog_by_id:
                            old_cat = catalog_by_id.pop(legacy_tmdb_id)
                            old_cat["tmdb_id"] = sitemap_tmdb_id
                            catalog_by_id[sitemap_tmdb_id] = old_cat
                        known_tmdb_ids[lookup_key] = sitemap_tmdb_id
                        updated_count += 1
                    except Exception as m_exc:
                        logger.warning("Could not migrate legacy movie file %s: %s", old_file, m_exc)

            existing_tmdb_id = sitemap_tmdb_id or legacy_tmdb_id
            movie_file: Optional[Path] = movies_dir / f"{existing_tmdb_id}.json" if existing_tmdb_id else None

            # 2. Check if movie already exists on disk
            if movie_file and movie_file.exists() and not force:
                cat_entry = catalog_by_id.get(existing_tmdb_id, {})
                try:
                    movie_doc = load_json_bytes(movie_file.read_bytes())
                except Exception as exc:
                    logger.warning("Could not read %s: %s; re-scraping movie", movie_file, exc)
                    movie_doc = None

                if movie_doc is not None:
                    existing_source = movie_doc.get("source")
                    existing_lastmod = movie_doc.get("source_lastmod")
                    existing_scares = movie_doc.get("scares", [])
                    existing_phonetic = movie_doc.get("phonetic_index", [])
                    has_subs = len(existing_phonetic) > 0

                    # Does this movie need jumpscare verification/reconciliation?
                    # 1. Scraped originally from WTJ (existing_source != "whenjumpscare" while WhenJumpScare is now live)
                    # 2. Or WhenJumpScare lastmod is newer/different from existing recorded lastmod
                    # 3. Or existing file had 0 scares while sitemap/index indicates scares exist
                    needs_scare_verification = (
                        (item_source == "whenjumpscare" and existing_source != "whenjumpscare")
                        or (item_lastmod is not None and item_lastmod != existing_lastmod)
                        or (len(existing_scares) == 0 and item.get("jump_count", 0) > 0)
                    )

                    if needs_scare_verification:
                        logger.info(
                            "Verifying & reconciling jump scares from %s for '%s' (%s, TMDB %d)...",
                            item_source,
                            title,
                            year,
                            existing_tmdb_id,
                        )
                        fresh_page = await scraper.parse_movie_page(item_url or slug)
                        fresh_scares = fresh_page.get("scares", [])
                        fresh_runtime = fresh_page.get("runtime_minutes") or movie_doc.get("runtime_minutes", 0)

                        if fresh_scares:
                            # Retain correct data: Keep existing subtitles & phonetic index!
                            # Re-anchor fresh scares to the existing phonetic index:
                            resolved_scares = resolve_scares_to_anchors(fresh_scares, existing_phonetic)

                            # Compare to see if scare timings, counts, or types changed:
                            scares_changed = (
                                len(resolved_scares) != len(existing_scares)
                                or any(
                                    s1.get("resolved_time_ms") != s2.get("resolved_time_ms")
                                    or s1.get("type") != s2.get("type")
                                    for s1, s2 in zip(resolved_scares, existing_scares)
                                )
                            )

                            movie_doc["scares"] = resolved_scares
                            movie_doc["runtime_minutes"] = fresh_runtime
                            movie_doc["source"] = item_source
                            movie_doc["source_lastmod"] = item_lastmod

                            cat_entry["scare_count"] = len(resolved_scares)
                            if fresh_runtime:
                                cat_entry["runtime_minutes"] = fresh_runtime

                            if scares_changed:
                                logger.info(
                                    "Jump scare update for '%s' (TMDB %d): %d -> %d scares (timings reconciled)",
                                    title,
                                    existing_tmdb_id,
                                    len(existing_scares),
                                    len(resolved_scares),
                                )
                                updated_count += 1
                            else:
                                logger.debug(
                                    "Jump scares verified for '%s' (TMDB %d); verified intact",
                                    title,
                                    existing_tmdb_id,
                                )

                            movie_file.write_bytes(dump_json_bytes(movie_doc))
                            catalog_by_id[existing_tmdb_id] = cat_entry
                        else:
                            # Fresh page had no scares; mark verified to avoid repeating requests
                            movie_doc["source"] = item_source
                            movie_doc["source_lastmod"] = item_lastmod
                            movie_file.write_bytes(dump_json_bytes(movie_doc))

                    # 3. Subtitle Fetch (if missing and quota not exhausted)
                    if not has_subs and not subtitles_fetcher.is_exhausted:
                        logger.info(
                            "Movie '%s' (%s, TMDB %d) lacks subtitles; attempting subtitle fetch...",
                            title,
                            year,
                            existing_tmdb_id,
                        )
                        tmdb_details = await tmdb_client.get_movie_details(existing_tmdb_id)
                        imdb_id = tmdb_details.get("imdb_id")
                        srt_content = await subtitles_fetcher.fetch_subtitles(
                            existing_tmdb_id,
                            title=title,
                            year=year,
                            imdb_id=imdb_id,
                        )
                        if srt_content and len(srt_content.strip()) > 0:
                            sanitized_dialogue = sanitize_subtitles(srt_content)
                            phonetic_index = build_phonetic_index(sanitized_dialogue)
                            resolved_scares = resolve_scares_to_anchors(movie_doc.get("scares", []), phonetic_index)

                            movie_doc["phonetic_index"] = phonetic_index
                            movie_doc["scares"] = resolved_scares
                            movie_file.write_bytes(dump_json_bytes(movie_doc))
                            verify_compression(movie_doc)

                            cat_entry["has_subtitles"] = True
                            catalog_by_id[existing_tmdb_id] = cat_entry
                            updated_count += 1
                            logger.info(
                                "Successfully attached %d subtitle anchors to '%s' (TMDB %d)",
                                len(phonetic_index),
                                title,
                                existing_tmdb_id,
                            )
                        else:
                            if subtitles_fetcher.is_exhausted:
                                logger.info(
                                    "Subtitle download quota reached (50/day); '%s' (TMDB %d) will be retried on next run",
                                    title,
                                    existing_tmdb_id,
                                )
                            else:
                                logger.info(
                                    "Subtitles still unavailable for '%s' (TMDB %d); retaining clock-only mode",
                                    title,
                                    existing_tmdb_id,
                                )

                    # Move to next movie
                    continue

            logger.info("Processing movie '%s' (%s)...", title, year)
            result = await process_movie(
                item=item,
                scraper=scraper,
                tmdb_client=tmdb_client,
                subtitles_fetcher=subtitles_fetcher,
                known_tmdb_ids=known_tmdb_ids,
            )

            if not result:
                continue

            movie_doc = result["movie_doc"]
            catalog_entry = result["catalog_entry"]
            tmdb_id = movie_doc["tmdb_id"]

            # Save movie JSON
            out_file = movies_dir / f"{tmdb_id}.json"
            is_new = not out_file.exists()
            out_file.write_bytes(dump_json_bytes(movie_doc))

            # Phase 1.5.3: Verify per-movie JSON compression (~2.8 KB gzipped)
            verify_compression(movie_doc)

            catalog_by_id[tmdb_id] = catalog_entry
            known_tmdb_ids[lookup_key] = tmdb_id

            if is_new:
                added_count += 1
            else:
                updated_count += 1

        # Dynamic Tagging from TMDB free API & current release calendar
        try:
            now_playing_ids = await tmdb_client.get_now_playing_movie_ids()
            popular_ids = await tmdb_client.get_popular_horror_ids()
            cur_year = datetime.now(timezone.utc).year
            for entry in catalog_by_id.values():
                entry_tags = []
                tid = entry.get("tmdb_id")
                yr = entry.get("release_year")
                scares = entry.get("scare_count", 0)
                if (tid in now_playing_ids) or (yr and yr >= cur_year - 1):
                    entry_tags.append("NEW")
                if (tid in popular_ids) or (scares >= 12):
                    entry_tags.append("HOT")
                entry["tags"] = entry_tags
        except Exception as exc:
            logger.debug("Dynamic tagging fallback: %s", exc)

        # Only update catalog and version if changes occurred or catalog was missing
        if added_count > 0 or updated_count > 0 or not catalog_path.exists():
            sorted_catalog = sorted(catalog_by_id.values(), key=lambda m: (m.get("title", "").lower()))
            catalog_path.write_bytes(dump_json_bytes(sorted_catalog))

            total_movies = len(sorted_catalog)
            total_scares = sum(m.get("scare_count", 0) for m in sorted_catalog)
            new_version_num = existing_version.get("v", 0) + (1 if (added_count > 0 or updated_count > 0) else 0)

            version_doc = {
                "v": max(1, new_version_num),
                "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "movie_count": total_movies,
                "scare_count": total_scares,
            }
            version_path.write_bytes(dump_json_bytes(version_doc))

            logger.info(
                "Data pipeline complete: %d added, %d updated. Total movies: %d, Total scares: %d (v%d)",
                added_count,
                updated_count,
                total_movies,
                total_scares,
                version_doc["v"],
            )
            return {
                "added": added_count,
                "updated": updated_count,
                "total_movies": total_movies,
                "total_scares": total_scares,
                "version": version_doc["v"],
            }
        else:
            logger.info("Pipeline complete: No changes detected (data is up to date).")
            return {
                "added": 0,
                "updated": 0,
                "total_movies": len(catalog_by_id),
                "total_scares": sum(m.get("scare_count", 0) for m in catalog_by_id.values()),
                "version": existing_version.get("v", 1),
            }

    finally:
        await scraper.close()
        await tmdb_client.close()
        await subtitles_fetcher.close()


def main() -> int:
    """CLI entrypoint."""
    parser = argparse.ArgumentParser(description="SpookCue Jump Scare Scraper and Phonetic Indexer")
    parser.add_argument("--data-dir", type=Path, default=Path("data"), help="Target data directory")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of movies to scrape")
    parser.add_argument("--force", action="store_true", help="Force re-scrape existing movies")
    parser.add_argument("--delay", type=float, default=2.0, help="Polite delay between requests in seconds")

    args = parser.parse_args()
    asyncio.run(
        run_pipeline(
            data_dir=args.data_dir,
            limit=args.limit,
            force=args.force,
            delay_seconds=args.delay,
        )
    )
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
