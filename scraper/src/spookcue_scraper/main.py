"""Orchestrator for the SpookCue data scraping and metadata pipeline."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import logging
from pathlib import Path
import re
from typing import Any, Dict, List, Optional

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


from spookcue_scraper.tmdb import TmdbClient
from spookcue_scraper.whenjumpscare import WhenJumpScareScraper

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("spookcue_scraper")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)

RE_TMDB_EXPLICIT = re.compile(r"(?:tmdb|themoviedb)(?:[_-]?id)?[/_=-]?(\d{3,8})\b", re.IGNORECASE)
RE_TMDB_LEAD = re.compile(r"^(\d{3,8})-[a-z0-9]")
RE_TMDB_TRAIL = re.compile(r"-(\d{5,8})$")
RE_BROKEN_TITLE_ARTIFACT = re.compile(
    r"(?<![.\-/])\b(?:S|T|D|M|Re|Ve|Ll|Ii|Iii|Iv|Vi|Vii|Viii|Llc|Vfw|1Br|M3Gan|3Dd)\b(?![.\-/])"
)
RE_TRAILING_BARE_YEAR = re.compile(r"^(.+?)\s+((?:19|20)\d{2})$")
_LEGIT_TRAILING_YEAR_TITLES = frozenset({"dracula 2000", "blade runner 2049"})
MAX_TMDB_TTL_REFRESHES_PER_RUN = 60
TMDB_METADATA_REFRESH_DAYS = 150
HOT_SCARE_THRESHOLD = 20


def _has_broken_title(title: str, tmdb_updated_at: Any = None) -> bool:
    """Detect broken contraction/Roman/acronym tokens or leaked slug years without false-positives."""
    clean = title.strip()
    if RE_BROKEN_TITLE_ARTIFACT.search(clean):
        return True
    if not tmdb_updated_at and RE_TRAILING_BARE_YEAR.match(clean):
        low = clean.lower()
        if low not in _LEGIT_TRAILING_YEAR_TITLES and ": " not in low:
            return True
    return False


def _is_newer_lastmod(candidate: Optional[str], existing: Optional[str]) -> bool:
    """Return True only when candidate ISO timestamp is strictly newer than existing."""
    if not candidate:
        return False
    if not existing:
        return True
    try:
        cand_dt = datetime.fromisoformat(str(candidate).replace("Z", "+00:00"))
        exist_dt = datetime.fromisoformat(str(existing).replace("Z", "+00:00"))
        return cand_dt > exist_dt
    except ValueError:
        return str(candidate) > str(existing)


def extract_tmdb_id_from_slug(slug_or_url: str) -> Optional[int]:
    """Extract numeric TMDB ID from slug or URL if embedded."""
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


def _is_valid_release_year(year: Any, tmdb_id: Optional[int] = None) -> bool:
    if not isinstance(year, int):
        return False
    cur_year = datetime.now(timezone.utc).year
    if year < 1888 or year > cur_year + 3:
        return False
    if tmdb_id is not None and year == tmdb_id and year not in (1984, 2001, 2012):
        return False
    return True


def _parse_tmdb_release_year(tmdb_details: Dict[str, Any]) -> Optional[int]:
    rel_date = tmdb_details.get("release_date")
    if not rel_date:
        return None
    y_str = str(rel_date).split("-")[0]
    if y_str.isdigit():
        y_val = int(y_str)
        if _is_valid_release_year(y_val):
            return y_val
    return None


def _resolve_canonical_title_and_year(
    fallback_title: str,
    fallback_year: Optional[int],
    page_data: Optional[Dict[str, Any]],
    tmdb_details: Dict[str, Any],
    tmdb_id: Optional[int] = None,
) -> tuple[str, Optional[int]]:
    """Prefer authoritative TMDB title/release_year, falling back to scraped HTML then slug."""
    tmdb_title = str(tmdb_details.get("title") or "").strip()
    page_title = str((page_data or {}).get("title") or "").strip()
    title = tmdb_title or page_title or fallback_title.strip()

    tmdb_year = _parse_tmdb_release_year(tmdb_details)
    page_year = (page_data or {}).get("year")
    if tmdb_year is not None:
        year: Optional[int] = tmdb_year
    elif _is_valid_release_year(page_year, tmdb_id):
        year = int(page_year)
    elif _is_valid_release_year(fallback_year, tmdb_id):
        year = int(fallback_year)
    else:
        year = None

    return title, year


def _is_tmdb_metadata_stale(movie_doc: Dict[str, Any], cat_entry: Dict[str, Any]) -> bool:
    if not cat_entry.get("poster_path") or not movie_doc.get("runtime_minutes"):
        return True
    updated_raw = movie_doc.get("tmdb_updated_at")
    if not updated_raw:
        return True
    try:
        updated_dt = datetime.fromisoformat(str(updated_raw).replace("Z", "+00:00"))
        return datetime.now(timezone.utc) - updated_dt > timedelta(days=TMDB_METADATA_REFRESH_DAYS)
    except ValueError:
        return True


def _normalize_catalog_for_diff(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    normalized = []
    for item in sorted(items, key=lambda m: (m.get("title", "").lower(), m.get("tmdb_id", 0))):
        copy_item = dict(item)
        copy_item.setdefault("tags", [])
        normalized.append(copy_item)
    return normalized


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
    known_tmdb_ids: Dict[str, int],
    sync_dir: Path,
    use_tmdb: bool = True,
) -> Optional[Dict[str, Any]]:
    """Scrape jump scares, optionally fetch TMDB metadata, and produce movie metadata document."""
    raw_title = item.get("title", "").strip()
    raw_year = item.get("year")
    slug = item.get("slug", "")

    if not raw_title:
        return None

    lookup_key = f"{raw_title.lower()}::{raw_year}"
    tmdb_id = item.get("tmdb_id") or known_tmdb_ids.get(lookup_key)

    if tmdb_id is None:
        tmdb_id = extract_tmdb_id_from_slug(slug) or extract_tmdb_id_from_slug(item.get("url", ""))

    movie_page_data = await scraper.parse_movie_page(item.get("url") or slug)
    raw_scares = movie_page_data.get("scares", [])
    if movie_page_data.get("fetch_failed") and not raw_scares:
        logger.warning("Skipping '%s' (%s): jump scare source unavailable", raw_title, raw_year)
        return None

    if tmdb_id is None:
        tmdb_id = movie_page_data.get("tmdb_id")

    if tmdb_id is None and use_tmdb:
        tmdb_id = await tmdb_client.search_movie(raw_title, raw_year)

    if tmdb_id is None:
        logger.warning("Could not resolve TMDB ID for '%s' (%s); skipping", raw_title, raw_year)
        return None

    runtime = movie_page_data.get("runtime_minutes") or 0

    tmdb_details: Dict[str, Any] = await tmdb_client.get_movie_details(tmdb_id) if use_tmdb else {}
    poster_path = tmdb_details.get("poster_path") or ""
    confirmed_runtime = tmdb_details.get("runtime") or runtime or 0
    title, year = _resolve_canonical_title_and_year(
        fallback_title=raw_title,
        fallback_year=raw_year,
        page_data=movie_page_data,
        tmdb_details=tmdb_details,
        tmdb_id=tmdb_id,
    )

    clean_scares = []
    for idx, s in enumerate(raw_scares, start=1):
        clean_scares.append({
            "id": s.get("id") or f"scare_{idx:02d}",
            "resolved_time_ms": s.get("resolved_time_ms") or s.get("time_ms", 0),
            "type": s.get("type", "minor"),
            "description": s.get("description", ""),
        })

    resolved_source = movie_page_data.get("source") or item.get(
        "source",
        "whenjumpscare" if "whenjumpscare.com" in item.get("url", "") else "wheresthejump",
    )
    movie_doc: Dict[str, Any] = {
        "tmdb_id": tmdb_id,
        "title": title,
        "release_year": year,
        "runtime_minutes": confirmed_runtime,
        "sync_type": "audio_hash",
        "scares": clean_scares,
        "source": resolved_source,
        "source_lastmod": item.get("lastmod") if resolved_source == "whenjumpscare" else None,
    }
    if tmdb_details:
        movie_doc["tmdb_updated_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    has_acoustic_sync = (sync_dir / f"{tmdb_id}.json").exists()

    catalog_entry = {
        "tmdb_id": tmdb_id,
        "title": title,
        "release_year": year or 0,
        "runtime_minutes": confirmed_runtime,
        "scare_count": len(clean_scares),
        "poster_path": poster_path,
        "has_acoustic_sync": has_acoustic_sync,
        "sync_type": "audio_hash",
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
    mode: str = "all",
    update_sitemap: bool = False,
) -> Dict[str, Any]:
    """Execute the metadata pipeline in 'all', 'whenjumpscare', or 'tmdb' mode."""
    if mode not in ("all", "whenjumpscare", "tmdb"):
        raise ValueError(f"Unsupported pipeline mode: {mode!r}")
    run_scrape = mode in ("all", "whenjumpscare")
    run_tmdb = mode in ("all", "tmdb")

    movies_dir = data_dir / "movies"
    movies_dir.mkdir(parents=True, exist_ok=True)
    sync_dir = data_dir / "sync"
    sync_dir.mkdir(parents=True, exist_ok=True)

    catalog_path = data_dir / "catalog.json"
    version_path = data_dir / "version.json"

    existing_catalog = load_existing_catalog(catalog_path)
    existing_version = load_existing_version(version_path)

    catalog_by_id: Dict[int, Dict[str, Any]] = {
        item["tmdb_id"]: dict(item) for item in existing_catalog if "tmdb_id" in item
    }
    known_tmdb_ids: Dict[str, int] = {
        f"{item['title'].lower()}::{item.get('release_year')}": item["tmdb_id"]
        for item in existing_catalog if "title" in item and "tmdb_id" in item
    }

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
                        "has_acoustic_sync": (sync_dir / f"{tid}.json").exists(),
                        "sync_type": "audio_hash",
                    }
                if tid and doc.get("title"):
                    known_tmdb_ids.setdefault(f"{doc['title'].lower()}::{doc.get('release_year')}", tid)
            except Exception:
                pass

    scraper = WhenJumpScareScraper(delay_seconds=delay_seconds)
    tmdb_client = TmdbClient()

    try:
        if run_tmdb and tmdb_client.has_credentials:
            await tmdb_client.get_configuration()

        if run_scrape:
            if update_sitemap:
                sitemap_cache = Path(__file__).parent / "data" / "whenjumpscare_sitemap.xml"
                movie_items = await scraper.fetch_movie_index(save_sitemap_to=sitemap_cache)
            else:
                movie_items = await scraper.fetch_movie_index()
        else:
            movie_items = [
                {
                    "title": m["title"],
                    "year": m.get("release_year"),
                    "tmdb_id": m["tmdb_id"],
                }
                for m in catalog_by_id.values()
            ]

        if not movie_items:
            if catalog_by_id:
                logger.warning("No movies found from index; using existing catalog")
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
        tmdb_ttl_refreshes = 0

        for item in movie_items:
            title = item.get("title", "").strip()
            if not title or title.lower() in ("tv shows", "tv show", "tv series", "television", "home"):
                continue
            year = item.get("year")
            slug = item.get("slug", "")
            item_url = item.get("url", "")
            item_lastmod = item.get("lastmod")
            item_source = item.get("source", "whenjumpscare" if "whenjumpscare.com" in item_url else "wheresthejump")
            lookup_key = f"{title.lower()}::{year}"

            sitemap_tmdb_id = (
                item.get("tmdb_id")
                or extract_tmdb_id_from_slug(slug)
                or extract_tmdb_id_from_slug(item_url)
            )
            legacy_tmdb_id = known_tmdb_ids.get(lookup_key)

            if sitemap_tmdb_id and legacy_tmdb_id and sitemap_tmdb_id != legacy_tmdb_id:
                old_file = movies_dir / f"{legacy_tmdb_id}.json"
                new_file = movies_dir / f"{sitemap_tmdb_id}.json"
                if old_file.exists() and not new_file.exists():
                    logger.info("Reconciling legacy TMDB ID %d -> %d for '%s'", legacy_tmdb_id, sitemap_tmdb_id, title)
                    try:
                        old_doc = load_json_bytes(old_file.read_bytes())
                        old_doc["tmdb_id"] = sitemap_tmdb_id
                        old_doc["sync_type"] = "audio_hash"
                        old_doc.pop("phonetic_index", None)
                        new_file.write_bytes(dump_json_bytes(old_doc))
                        old_file.unlink(missing_ok=True)

                        old_sync = sync_dir / f"{legacy_tmdb_id}.json"
                        new_sync = sync_dir / f"{sitemap_tmdb_id}.json"
                        if old_sync.exists() and not new_sync.exists():
                            old_sync_doc = load_json_bytes(old_sync.read_bytes())
                            old_sync_doc["tmdb_id"] = sitemap_tmdb_id
                            new_sync.write_bytes(dump_json_bytes(old_sync_doc))
                            old_sync.unlink(missing_ok=True)

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
                    from_bundled_fallback = bool(item.get("from_bundled_fallback")) or bool(
                        getattr(scraper, "last_index_from_fallback", False)
                    )
                    doc_changed = False

                    if not run_scrape:
                        needs_scare_verification = False
                    elif len(existing_scares) == 0:
                        needs_scare_verification = (item.get("jump_count", 0) > 0) or (not from_bundled_fallback)
                    elif from_bundled_fallback:
                        # Bundled offline sitemap is a static snapshot; never overwrite existing scares from it
                        needs_scare_verification = False
                    else:
                        needs_scare_verification = (
                            (item_source == "whenjumpscare" and existing_source != "whenjumpscare")
                            or _is_newer_lastmod(item_lastmod, existing_lastmod)
                        )

                    if needs_scare_verification:
                        allow_wtj_fallback = len(existing_scares) == 0 or existing_source != "whenjumpscare"
                        logger.info(
                            "Verifying jump scares from %s for '%s' (%s, TMDB %d)...",
                            item_source,
                            title,
                            year,
                            existing_tmdb_id,
                        )
                        fresh_page = await scraper.parse_movie_page(
                            item_url or slug,
                            allow_wtj_fallback=allow_wtj_fallback,
                        )
                        fresh_scares = fresh_page.get("scares", [])
                        fresh_runtime = fresh_page.get("runtime_minutes") or movie_doc.get("runtime_minutes", 0)
                        actual_source = fresh_page.get("source") or item_source

                        if fresh_scares:
                            clean_fresh_scares = []
                            for idx, s in enumerate(fresh_scares, start=1):
                                clean_fresh_scares.append({
                                    "id": s.get("id") or f"scare_{idx:02d}",
                                    "resolved_time_ms": s.get("resolved_time_ms") or s.get("time_ms", 0),
                                    "type": s.get("type", "minor"),
                                    "description": s.get("description", ""),
                                })

                            scares_changed = (
                                len(clean_fresh_scares) != len(existing_scares)
                                or any(
                                    s1.get("resolved_time_ms") != s2.get("resolved_time_ms")
                                    or s1.get("type") != s2.get("type")
                                    for s1, s2 in zip(clean_fresh_scares, existing_scares)
                                )
                            )

                            movie_doc["scares"] = clean_fresh_scares
                            movie_doc["runtime_minutes"] = fresh_runtime
                            movie_doc["source"] = actual_source
                            if actual_source == "whenjumpscare" and (
                                _is_newer_lastmod(item_lastmod, existing_lastmod) or not existing_lastmod
                            ):
                                movie_doc["source_lastmod"] = item_lastmod
                            movie_doc["sync_type"] = "audio_hash"
                            movie_doc.pop("phonetic_index", None)
                            doc_changed = True

                            cat_entry["scare_count"] = len(clean_fresh_scares)
                            cat_entry["has_acoustic_sync"] = (sync_dir / f"{existing_tmdb_id}.json").exists()
                            cat_entry["sync_type"] = "audio_hash"
                            if fresh_runtime:
                                cat_entry["runtime_minutes"] = fresh_runtime

                            if scares_changed:
                                updated_count += 1
                        elif "phonetic_index" in movie_doc or movie_doc.get("sync_type") != "audio_hash":
                            movie_doc["sync_type"] = "audio_hash"
                            movie_doc.pop("phonetic_index", None)
                            doc_changed = True

                    # Heal invalid years/titles or refresh TMDB metadata within 6-month cache limit
                    if run_tmdb:
                        cur_title = str(movie_doc.get("title") or "")
                        cur_year = movie_doc.get("release_year")
                        has_bad_year = (
                            not _is_valid_release_year(cur_year, existing_tmdb_id)
                            and not movie_doc.get("tmdb_updated_at")
                        )
                        has_broken_title = _has_broken_title(cur_title, movie_doc.get("tmdb_updated_at"))
                        needs_priority_heal = has_bad_year or has_broken_title
                        needs_new_enrichment = (
                            tmdb_client.has_credentials
                            and not movie_doc.get("tmdb_updated_at")
                            and (not cat_entry.get("poster_path") or not movie_doc.get("runtime_minutes"))
                        )
                        needs_ttl_refresh = (
                            tmdb_client.has_credentials
                            and tmdb_ttl_refreshes < MAX_TMDB_TTL_REFRESHES_PER_RUN
                            and _is_tmdb_metadata_stale(movie_doc, cat_entry)
                        )

                        if needs_priority_heal or needs_new_enrichment or needs_ttl_refresh:
                            tmdb_details: Dict[str, Any] = {}
                            if tmdb_client.has_credentials:
                                tmdb_details = await tmdb_client.get_movie_details(existing_tmdb_id)
                                if not needs_priority_heal and not needs_new_enrichment:
                                    tmdb_ttl_refreshes += 1

                            resolved_title, resolved_year = _resolve_canonical_title_and_year(
                                fallback_title=title if needs_priority_heal else (cur_title or title),
                                fallback_year=year if has_bad_year else cur_year,
                                page_data=None,
                                tmdb_details=tmdb_details,
                                tmdb_id=existing_tmdb_id,
                            )
                            meta_changed = False
                            if resolved_title and resolved_title != cur_title:
                                movie_doc["title"] = resolved_title
                                cat_entry["title"] = resolved_title
                                meta_changed = True
                            if resolved_year is not None and resolved_year != cur_year:
                                movie_doc["release_year"] = resolved_year
                                cat_entry["release_year"] = resolved_year
                                meta_changed = True
                            if tmdb_details.get("poster_path") and tmdb_details["poster_path"] != cat_entry.get("poster_path"):
                                cat_entry["poster_path"] = tmdb_details["poster_path"]
                                meta_changed = True
                            if tmdb_details.get("runtime") and tmdb_details["runtime"] != movie_doc.get("runtime_minutes"):
                                movie_doc["runtime_minutes"] = tmdb_details["runtime"]
                                cat_entry["runtime_minutes"] = tmdb_details["runtime"]
                                meta_changed = True
                            if tmdb_details:
                                movie_doc["tmdb_updated_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                                doc_changed = True
                            if meta_changed:
                                doc_changed = True
                                updated_count += 1

                    if doc_changed:
                        movie_file.write_bytes(dump_json_bytes(movie_doc))
                        catalog_by_id[existing_tmdb_id] = cat_entry

                    continue

            if not run_scrape:
                continue

            logger.info("Processing movie '%s' (%s)...", title, year)
            result = await process_movie(
                item=item,
                scraper=scraper,
                tmdb_client=tmdb_client,
                known_tmdb_ids=known_tmdb_ids,
                sync_dir=sync_dir,
                use_tmdb=run_tmdb,
            )

            if not result:
                continue

            movie_doc = result["movie_doc"]
            catalog_entry = result["catalog_entry"]
            tmdb_id = movie_doc["tmdb_id"]

            out_file = movies_dir / f"{tmdb_id}.json"
            is_new = not out_file.exists()
            movie_doc.pop("phonetic_index", None)
            movie_doc["sync_type"] = "audio_hash"
            out_file.write_bytes(dump_json_bytes(movie_doc))

            catalog_by_id[tmdb_id] = catalog_entry
            known_tmdb_ids[lookup_key] = tmdb_id

            if is_new:
                added_count += 1
            else:
                updated_count += 1

        # Dynamic Tagging from TMDB API & current release calendar
        try:
            cur_year = datetime.now(timezone.utc).year
            if run_tmdb:
                now_playing_ids = await tmdb_client.get_now_playing_movie_ids()
                popular_ids = await tmdb_client.get_popular_horror_ids()
            else:
                now_playing_ids = set()
                popular_ids = set()

            for entry in catalog_by_id.values():
                tid = entry.get("tmdb_id")
                yr = entry.get("release_year")
                scares = entry.get("scare_count", 0)
                existing_tags = entry.get("tags")
                is_valid_new_year = isinstance(yr, int) and (cur_year - 1) <= yr <= (cur_year + 1)

                if not run_tmdb:
                    if existing_tags is None:
                        entry_tags = []
                        if is_valid_new_year:
                            entry_tags.append("NEW")
                        if scares >= HOT_SCARE_THRESHOLD:
                            entry_tags.append("HOT")
                        entry["tags"] = entry_tags
                else:
                    entry_tags = []
                    if (tid in now_playing_ids) or is_valid_new_year:
                        entry_tags.append("NEW")

                    if tmdb_client.has_credentials:
                        if (tid in popular_ids) or (scares >= HOT_SCARE_THRESHOLD):
                            entry_tags.append("HOT")
                    else:
                        if ("HOT" in (existing_tags or []) and scares < HOT_SCARE_THRESHOLD) or (
                            scares >= HOT_SCARE_THRESHOLD
                        ):
                            entry_tags.append("HOT")

                    entry["tags"] = entry_tags

                entry["has_acoustic_sync"] = (sync_dir / f"{tid}.json").exists()
                entry["sync_type"] = "audio_hash"
        except Exception as exc:
            logger.debug("Dynamic tagging fallback: %s", exc)

        sorted_catalog = sorted(catalog_by_id.values(), key=lambda m: (m.get("title", "").lower()))
        catalog_changed = _normalize_catalog_for_diff(sorted_catalog) != _normalize_catalog_for_diff(existing_catalog)

        if added_count > 0 or updated_count > 0 or catalog_changed or not catalog_path.exists():
            catalog_path.write_bytes(dump_json_bytes(sorted_catalog))

            total_movies = len(sorted_catalog)
            total_scares = sum(m.get("scare_count", 0) for m in sorted_catalog)
            should_bump = added_count > 0 or updated_count > 0 or catalog_changed
            new_version_num = existing_version.get("v", 0) + (1 if should_bump else 0)

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


def main() -> int:
    """CLI entrypoint."""
    parser = argparse.ArgumentParser(description="SpookCue Jump Scare Scraper and Metadata Pipeline")
    parser.add_argument("--data-dir", type=Path, default=Path("data"), help="Target data directory")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of movies to scrape")
    parser.add_argument("--force", action="store_true", help="Force re-scrape existing movies")
    parser.add_argument("--delay", type=float, default=2.0, help="Polite delay between requests in seconds")
    parser.add_argument(
        "--mode",
        choices=["all", "whenjumpscare", "tmdb"],
        default="all",
        help="Pipeline stage to execute: 'all', 'whenjumpscare', or 'tmdb'",
    )
    parser.add_argument(
        "--update-sitemap",
        action="store_true",
        help="Update bundled whenjumpscare_sitemap.xml when live sitemap fetch succeeds",
    )

    args = parser.parse_args()
    asyncio.run(
        run_pipeline(
            data_dir=args.data_dir,
            limit=args.limit,
            force=args.force,
            delay_seconds=args.delay,
            mode=args.mode,
            update_sitemap=args.update_sitemap,
        )
    )
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
