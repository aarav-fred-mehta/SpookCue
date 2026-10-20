# SpookCue

> **Offline horror-movie jump-scare alerter.** Listens to distant movie audio → transcribes speech on-device → matches phonetic dialogue fingerprints against 1,164 horror films (~9,754 jump scares) → fires haptic/visual alerts ±2 s before the scare — all while the phone is locked in a pocket.

---

## Repository Structure

```
SpookCue/
├── .github/
│   └── workflows/
│       ├── ci.yml                   # CI: Runs unit tests for Scraper and Android
│       ├── weekly_scrape.yml        # Weekly data scrape running every Sunday 00:00 UTC
│       └── release_apk.yml          # Builds signed release APK on version tag (v*)
├── scraper/                         # Python data pipeline
│   ├── pyproject.toml
│   ├── src/spookcue_scraper/        # Scraper, TMDB fetcher, sanitizer & phonetic indexer
│   └── tests/                       # Scraper unit tests
├── data/                            # Committed output (served via CDN & GitHub Pages)
│   ├── version.json                 # { "v": 42, "updated": "2026-10-20", ... }
│   ├── catalog.json                 # All horror movies (metadata & scare counts)
│   └── movies/                      # Per-movie phonetic indexes & jump-scare anchors
├── app/                             # Android Kotlin project (Jetpack Compose)
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/                  # Bundled catalog + initial movie JSONs
│   │   ├── java/dev/spookcue/       # Application code & services
│   │   └── res/                     # Dark crimson theme resources
│   └── src/test/                    # Android unit tests
├── models/                          # ONNX models (Silero VAD + Moonshine-Tiny STT)
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

To enable weekly scraping and signed APK releases, configure the following secrets under **Settings → Secrets and variables → Actions**:

| Secret | Description |
|---|---|
| `TMDB_API_KEY` | TMDB API v3 developer token for metadata and poster paths |
| `SUBDL_API_KEY` | SubDL API token for downloading English SRT subtitle tracks |
| `KEYSTORE_BASE64` | Base64-encoded release `.jks` keystore (`base64 -w 0 release-key.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias in the release keystore |
| `KEY_PASSWORD` | Private key password |

### GitHub Pages CDN Fallback

The Android client downloads delta updates from jsDelivr by default, with automatic failover to GitHub Pages:

1. Navigate to repository **Settings → Pages**.
2. Under **Build and deployment → Source**, choose **Deploy from a branch**.
3. Select **Branch:** `main` and **Folder:** `/data`.
4. Save to deploy `data/version.json` and `data/catalog.json` at `https://<user>.github.io/<repo>/data/`.

---

## Architecture Highlights

1. **Two-Stage Acoustic Engine**: Silero VAD (voice activity detection) filters out 80%+ of audio frames (music, screams, silence) so Moonshine-Tiny / Whisper STT runs only during spoken dialogue.
2. **Double Metaphone Phonetic Fingerprinting**: Matches dialogue based on speech sounds rather than exact orthography, tolerating transcription errors caused by cinema reverb, phone fabric, or ambient noise.
3. **Dialogue-Relative Anchoring**: Jump scares are anchored to preceding dialogue lines, making synchronization immune to different movie cuts, theatrical vs. unrated editions, and framerate conversions.
4. **Pocket Mode**: Operates in the background with a partial wake-lock, pulsing distinct vibration waveforms 10s, 5s, and 2s before major scares without turning the screen on.
