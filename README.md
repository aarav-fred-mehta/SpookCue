# SpookCue

> **Offline horror-movie jump-scare alerter.** Listens to distant movie audio → extracts acoustic landmark fingerprints on-device → matches against horror film timelines (1,185 films, ~9,700+ jump scares) → fires haptic/visual alerts before the scare — all while the phone is locked in a pocket.

---

## Repository Structure

```
SpookCue/
├── .github/
│   └── workflows/
│       ├── ci.yml                   # CI: Runs unit tests for Scraper and Android
│       ├── daily_scrape.yml         # Daily data scrape running every day at 00:00 UTC
│       └── release_apk.yml          # Builds signed release APK on version tag (v*)
├── scraper/                         # Python data pipeline
│   ├── pyproject.toml
│   ├── src/spookcue_scraper/        # Scraper, TMDB metadata fetcher
│   └── tests/                       # Scraper unit tests
├── data/                            # Committed output (served via CDN & GitHub Pages)
│   ├── version.json                 # { "v": 42, "updated": "2026-10-20", ... }
│   ├── catalog.json                 # All horror movies (metadata & scare counts)
│   ├── movies/                      # Per-movie scare timelines
│   └── sync/                        # Acoustic landmark hash sync assets
├── app/                             # Android Kotlin project (Jetpack Compose)
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/                  # Bundled catalog + initial movie JSONs & sync data
│   │   ├── java/dev/spookcue/       # Application code & services
│   │   └── res/                     # Dark crimson theme resources
│   └── src/test/                    # Android unit tests
└── PLAN.md                          # Full multi-phase implementation roadmap
```

---

## Getting Started

### 1. Python Scraper Setup

Requirements: Python 3.11+

```bash
cd scraper
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
source .venv/bin/activate

pip install -e ".[dev]"
pytest tests
```

To run the pipeline locally:
```bash
python -m spookcue_scraper.main
```

### 2. Android App Setup

Requirements:
- Android Studio Ladybug or later
- JDK 17 or JDK 21
- Android SDK 35 (minSdk 26)

Build debug APK:
```bash
./gradlew assembleDebug
```

Run unit tests:
```bash
./gradlew testDebugUnitTest
```

---

## CI/CD & Configuration

### Required GitHub Secrets

To enable daily scraping and signed APK releases, configure the following secrets under **Settings → Secrets and variables → Actions**:

| Secret | Description |
|---|---|
| `TMDB_API_KEY` | TMDB API v3 key or v4 Read Access Token for metadata and poster paths |
| `KEYSTORE_BASE64` | Base64-encoded release `.jks` keystore (`base64 -w 0 release-key.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias in the release keystore |
| `KEY_PASSWORD` | Private key password |

---

## Data Sources & API Attribution

- **TMDB**: *This product uses the TMDB API but is not endorsed or certified by TMDB.* Metadata requests follow TMDB rate limits, HTTP 429 `Retry-After` handling, `/configuration` checks, and the 6-month cache retention cap.
- **WhenJumpScare & Where's The Jump?**: Jump scare timestamps and descriptions are collected politely with per-host `robots.txt` and `Crawl-delay` compliance.

### GitHub Pages CDN Fallback

The Android client downloads delta updates from jsDelivr by default, with automatic failover to GitHub Pages:

1. Navigate to repository **Settings → Pages**.
2. Under **Build and deployment → Source**, choose **Deploy from a branch**.
3. Select **Branch:** `main` and **Folder:** `/data`.
4. Save to deploy `data/version.json` and `data/catalog.json` at `https://<user>.github.io/<repo>/data/`.

---

## Architecture Highlights

1. **Acoustic Landmark Fingerprinting**: Robust peak constellation hashing (16 kHz, 1024 FFT, 512 hop, 4 frequency bands) extracts invariant acoustic spectral landmarks from music, Foley, sound effects, and screams.
2. **Offset Histogram Voting & Trajectory Consensus**: Offset histogram voting with stop-word frequency pruning and forward trajectory agreement locks movie playback within 2–3 short sampling windows.
3. **Pure Kotlin Audio Engine**: 100% lightweight Kotlin engine with zero native C++ binaries, zero ONNX runtime, and zero heavy neural models — slashing app bundle size by ~450+ MB.
4. **Pocket Mode**: Operates in the background with a partial wake-lock, pulsing distinct vibration waveforms before major scares without turning the screen on.
