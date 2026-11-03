import json
import os
import re
from metaphone import doublemetaphone

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")
ASSETS_DIR = os.path.join(BASE_DIR, "app", "src", "main", "assets")

STOPWORDS = {"oh", "ah", "uh", "um", "whoa", "hey", "yeah", "hmm", "huh", "okay", "ok"}

def tokenize_and_encode(text):
    clean = re.sub(r"\([^)]*\)|\[[^\]]*\]|\*[^<*]*\*", " ", text).upper()
    words = [w for w in re.findall(r"[A-Z0-9']+", clean) if w.lower() not in STOPWORDS]
    tokens = []
    for w in words:
        p, s = doublemetaphone(w)
        code = p or s
        if code:
            tokens.append(code)
    return tokens

def parse_srt(srt_path):
    with open(srt_path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()

    blocks = re.split(r"\n\s*\n", content.strip())
    entries = []
    for b in blocks:
        lines = [l.strip() for l in b.splitlines() if l.strip()]
        if len(lines) < 2:
            continue
        # Find timestamp line
        ts_line_idx = -1
        for idx, line in enumerate(lines):
            if "-->" in line:
                ts_line_idx = idx
                break
        if ts_line_idx == -1:
            continue

        ts_match = re.match(r"(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})", lines[ts_line_idx])
        if not ts_match:
            continue

        h, m, s, ms = map(int, ts_match.groups()[:4])
        start_ms = (h * 3600 + m * 60 + s) * 1000 + ms

        text = " ".join(lines[ts_line_idx + 1:])
        tokens = tokenize_and_encode(text)
        if len(tokens) >= 2:
            entries.append({"s": start_ms, "p": tokens})

    return entries

def main():
    # 1. Annabelle (242224)
    srt_file = os.path.join(BASE_DIR, "Subtitle test", "Annabelle.2014.BDRip.x264-SPARKS.srt")
    annabelle_phonetics = parse_srt(srt_file) if os.path.exists(srt_file) else []
    print(f"Annabelle phonetic entries: {len(annabelle_phonetics)}")

    annabelle_movie = {
        "tmdb_id": 242224,
        "title": "Annabelle",
        "release_year": 2014,
        "runtime_minutes": 99,
        "sync_type": "subtitles",
        "scares": [
            {
                "id": "scare_01",
                "resolved_time_ms": 756000,
                "type": "minor",
                "description": "Mia is startled by the sewing machine suddenly turning on by itself."
            },
            {
                "id": "scare_02",
                "resolved_time_ms": 1345000,
                "type": "major",
                "description": "The demon lunges at Mia from the basement stairs."
            },
            {
                "id": "scare_03",
                "resolved_time_ms": 2580000,
                "type": "major",
                "description": "The child running toward the door abruptly transforms into the adult Annabelle Higgins."
            },
            {
                "id": "scare_04",
                "resolved_time_ms": 3810000,
                "type": "minor",
                "description": "The record player needle violently scratches across the vinyl."
            },
            {
                "id": "scare_05",
                "resolved_time_ms": 4720000,
                "type": "major",
                "description": "Father Perez is thrown across the church steps by an unseen force."
            }
        ]
    }

    annabelle_sync = {
        "tmdb_id": 242224,
        "type": "subtitles",
        "phonetic_index": annabelle_phonetics
    }

    # 2. A Quiet Place (447332) - Audio Hash
    # Simulated reference landmark hashes
    sample_hashes = []
    # Generate anchor landmark hashes every ~1000ms
    for t in range(5000, 5400000, 1000):
        h = ((t // 1000 * 37) & 0x1FF) | (((t // 1000 * 73) & 0x1FF) << 9) | ((12 & 0x3F) << 18)
        sample_hashes.append({"h": int(h), "t": int(t)})

    quiet_place_movie = {
        "tmdb_id": 447332,
        "title": "A Quiet Place",
        "release_year": 2018,
        "runtime_minutes": 90,
        "sync_type": "audio_hash",
        "scares": [
            {
                "id": "scare_01",
                "resolved_time_ms": 510000,
                "type": "major",
                "description": "The creature snatches Beau from the bridge after the space shuttle toy sounds."
            },
            {
                "id": "scare_02",
                "resolved_time_ms": 2240000,
                "type": "major",
                "description": "Evelyn accidentally steps on an upturned nail on the cellar stairs."
            },
            {
                "id": "scare_03",
                "resolved_time_ms": 3310000,
                "type": "major",
                "description": "A creature claws through the flooded cellar wall directly behind Evelyn."
            },
            {
                "id": "scare_04",
                "resolved_time_ms": 4620000,
                "type": "major",
                "description": "Lee screams to save his children as the creature strikes the truck."
            }
        ]
    }

    quiet_place_sync = {
        "tmdb_id": 447332,
        "type": "audio_hash",
        "audio_hashes": sample_hashes
    }

    # 3. Catalog
    catalog = [
        {
            "tmdb_id": 242224,
            "title": "Annabelle",
            "release_year": 2014,
            "runtime_minutes": 99,
            "scare_count": len(annabelle_movie["scares"]),
            "poster_path": "/yLsuJm94ox5gacw7BwWwtqK0m17.jpg",
            "has_subtitles": True,
            "sync_type": "subtitles",
            "tags": ["HOT"]
        },
        {
            "tmdb_id": 447332,
            "title": "A Quiet Place",
            "release_year": 2018,
            "runtime_minutes": 90,
            "scare_count": len(quiet_place_movie["scares"]),
            "poster_path": "/nAU74GmpUk7t5iklEp3bufwDq4n.jpg",
            "has_subtitles": False,
            "sync_type": "audio_hash",
            "tags": ["HOT"]
        }
    ]

    version = {
        "v": 1,
        "updated": "2026-11-03T13:17:00Z",
        "movie_count": len(catalog),
        "scare_count": sum(m["scare_count"] for m in catalog)
    }

    # Write to data/ and assets/
    for base in [DATA_DIR, ASSETS_DIR]:
        os.makedirs(os.path.join(base, "movies"), exist_ok=True)
        os.makedirs(os.path.join(base, "sync"), exist_ok=True)

        with open(os.path.join(base, "catalog.json"), "w", encoding="utf-8") as f:
            json.dump(catalog, f, indent=2)

        with open(os.path.join(base, "version.json"), "w", encoding="utf-8") as f:
            json.dump(version, f, indent=2)

        with open(os.path.join(base, "movies", "242224.json"), "w", encoding="utf-8") as f:
            json.dump(annabelle_movie, f, indent=2)

        with open(os.path.join(base, "sync", "242224.json"), "w", encoding="utf-8") as f:
            json.dump(annabelle_sync, f, separators=(",", ":"))

        with open(os.path.join(base, "movies", "447332.json"), "w", encoding="utf-8") as f:
            json.dump(quiet_place_movie, f, indent=2)

        with open(os.path.join(base, "sync", "447332.json"), "w", encoding="utf-8") as f:
            json.dump(quiet_place_sync, f, separators=(",", ":"))

    print("Successfully initialized decoupled movies, sync payloads, and catalog!")

if __name__ == "__main__":
    main()
