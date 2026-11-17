"""Migration script: Migrate SpookCue dataset from phonetic dialogue to acoustic landmark sync."""

from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "data"
ASSETS_DIR = ROOT_DIR / "app" / "src" / "main" / "assets"

def migrate_movie_file(filepath: Path) -> bool:
    try:
        data = json.loads(filepath.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"Error reading {filepath}: {e}")
        return False

    changed = False

    # Remove phonetic_index if present
    if "phonetic_index" in data:
        del data["phonetic_index"]
        changed = True

    # Set sync_type to audio_hash
    if data.get("sync_type") != "audio_hash":
        data["sync_type"] = "audio_hash"
        changed = True

    # Clean scares
    scares = data.get("scares", [])
    for s in scares:
        if "anchor_phonetic" in s:
            del s["anchor_phonetic"]
            changed = True
        if "offset_from_anchor_ms" in s:
            del s["offset_from_anchor_ms"]
            changed = True

    if changed:
        filepath.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")
    return changed

def main():
    print("=== Phase C: SpookCue Data Migration ===")

    # 1. Inspect and clean data/sync/
    sync_dir = DATA_DIR / "sync"
    kept_hashes = 0
    deleted_phonetic = 0

    if sync_dir.exists():
        for sync_file in list(sync_dir.glob("*.json")):
            try:
                sync_data = json.loads(sync_file.read_text(encoding="utf-8"))
                if sync_data.get("type") == "audio_hash":
                    kept_hashes += 1
                else:
                    sync_file.unlink()
                    deleted_phonetic += 1
            except Exception as e:
                print(f"Error processing sync file {sync_file}: {e}")

    print(f"Sync files: Kept {kept_hashes} audio_hash files, deleted {deleted_phonetic} phonetic_index files.")

    # Get set of TMDB IDs that have verified acoustic sync
    acoustic_tmdb_ids = {int(f.stem) for f in sync_dir.glob("*.json")}
    print(f"Total movies with acoustic sync hashes: {len(acoustic_tmdb_ids)}")

    # 2. Migrate movies in data/movies/
    data_movies_dir = DATA_DIR / "movies"
    migrated_data_movies = 0
    if data_movies_dir.exists():
        for mf in data_movies_dir.glob("*.json"):
            if migrate_movie_file(mf):
                migrated_data_movies += 1
    print(f"Cleaned {migrated_data_movies} movie JSON files in data/movies/")

    # 3. Migrate movies in app/src/main/assets/movies/
    assets_movies_dir = ASSETS_DIR / "movies"
    migrated_assets_movies = 0
    if assets_movies_dir.exists():
        for mf in assets_movies_dir.glob("*.json"):
            if migrate_movie_file(mf):
                migrated_assets_movies += 1
    print(f"Cleaned {migrated_assets_movies} movie JSON files in app/src/main/assets/movies/")

    # 4. Clean assets/sync/
    assets_sync_dir = ASSETS_DIR / "sync"
    if assets_sync_dir.exists():
        for sf in list(assets_sync_dir.glob("*.json")):
            try:
                sdata = json.loads(sf.read_text(encoding="utf-8"))
                if sdata.get("type") != "audio_hash":
                    sf.unlink()
                    print(f"Deleted obsolete asset sync file {sf.name}")
            except Exception:
                pass

    # 5. Migrate catalog.json
    def migrate_catalog(catalog_path: Path):
        if not catalog_path.exists():
            return
        cat = json.loads(catalog_path.read_text(encoding="utf-8"))
        updated = []
        for item in cat:
            tid = item.get("tmdb_id")
            has_acoustic = tid in acoustic_tmdb_ids
            item["has_acoustic_sync"] = has_acoustic
            item["sync_type"] = "audio_hash"
            if "has_subtitles" in item:
                del item["has_subtitles"]
            updated.append(item)
        catalog_path.write_text(json.dumps(updated, indent=2, sort_keys=True), encoding="utf-8")
        print(f"Updated {catalog_path}: {len(updated)} movies ({sum(1 for x in updated if x['has_acoustic_sync'])} acoustic sync)")

    migrate_catalog(DATA_DIR / "catalog.json")
    migrate_catalog(ASSETS_DIR / "catalog.json")

    # 6. Update version.json
    def update_version(version_path: Path):
        if not version_path.exists():
            return
        vdata = json.loads(version_path.read_text(encoding="utf-8"))
        vdata["v"] = vdata.get("v", 1) + 1
        vdata["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        version_path.write_text(json.dumps(vdata, indent=2, sort_keys=True), encoding="utf-8")
        print(f"Updated {version_path} to v{vdata['v']}")

    update_version(DATA_DIR / "version.json")
    update_version(ASSETS_DIR / "version.json")

    print("=== Migration Completed Successfully! ===")

if __name__ == "__main__":
    main()
