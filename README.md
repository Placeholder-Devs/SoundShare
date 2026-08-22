# SoundShare

Real-time, high-fidelity audio streaming over local WiFi between Android devices. Captures system audio via MediaProjection API and streams to one or more receivers over TCP/IP, with support for Hi-Res Audio (up to 192kHz/24-bit), FLAC lossless compression, bidirectional chat, and transport controls.

## Knowledge Graph

This project has a graphify Obsidian vault for architecture reference:

- **Vault:** `~/vaults/soundshare_v1/`
- **Graph:** `/home/abinav/AndroidStudioProjects/soundshare_v1/graphify-out/graph.json`
- **Report:** `/home/abinav/AndroidStudioProjects/soundshare_v1/graphify-out/GRAPH_REPORT.md`
- **Communities:** MainActivity Core, StreamFragment UI, ReceiveFragment UI, DuplexFragment UI, AudioCaptureService, AudioReceiveService, FLAC Codec, HiResAudioHelper, MediaMetadataHelper, SettingsFragment, ChatAdapter, ChatMessage Model, ChatNotificationHelper, CLAUDE.md Documentation, Icon Density Variants, Build Config (Root/App/Settings), Tests

To rebuild the vault after code changes:
```bash
graphify /home/abinav/AndroidStudioProjects/soundshare_v1 --obsidian --obsidian-dir ~/vaults/soundshare_v1
```

## Tech Stack

- **Language**: Java
- **Min SDK**: 31 (Android 12) / **Target SDK**: 36
- **Build System**: Gradle 8.13 with Kotlin DSL, Version Catalog
- **AGP**: 8.13.2

## Build / Run / Test

```bash
# Build
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK

# Install on connected device
./gradlew installDebug

# Tests
./gradlew test                   # Local unit tests
./gradlew connectedAndroidTest   # Instrumented tests
```

**Prerequisites**: Android Studio or Android SDK, JDK 11+, Android SDK with API 36.

## Architecture

Single-Activity, multi-Fragment with `BottomNavigationView` (4 tabs):

- **StreamFragment** — host/sender mode
- **ReceiveFragment** — client/receiver mode
- **DuplexFragment** — bidirectional mode
- **SettingsFragment** — buffer/performance tuning (SharedPreferences)

All fragments communicate with `MainActivity` via callback interfaces.

### Network Protocol

Five TCP ports with custom binary protocol v2 (28-byte header, magic "SSHD"):

| Port | Purpose |
|------|---------|
| 5000 | Main audio data stream |
| 5001 | Ping/latency + control (pause/resume/skip) |
| 5002 | Chat + metadata broadcast |
| 5003 | Duplex audio |
| 5004 | Duplex chat |

### Quality Presets

7 presets from 44.1kHz Mono 16-bit (Ultra Low) to 192kHz Stereo 24-bit (Hi-Res Ultra). Default: 48kHz Stereo 16-bit (Balanced).

## Web UI

A responsive web recreation of the SoundShare mobile UI is available in `web-ui/`. Built with vanilla HTML/CSS/JS — no build step required.

**Features:**
- Playful OLED theme (dark/light mode toggle)
- 4-tab navigation: Stream, Receive, Duplex, Settings
- Animated transitions, responsive layout (375px–1440px)
- Simulated live stats, chat with auto-replies, transport controls
- Full accessibility: ARIA roles, keyboard navigation, reduced motion support

**Open locally:**
```bash
# Option 1: Open directly in browser
open web-ui/index.html          # macOS
xdg-open web-ui/index.html      # Linux
start web-ui/index.html         # Windows

# Option 2: Serve via Python
cd web-ui && python3 -m http.server 8765
# Then visit http://localhost:8765
```

**Files:** 23 files across `index.html`, `css/` (11 files), and `js/` (11 files).
