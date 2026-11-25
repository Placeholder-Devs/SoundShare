<div align="center">

# SoundShare

**Real-time, high-fidelity audio streaming between Android devices over local Wi-Fi.**

*Capture system audio from any app, stream it losslessly to one or many receivers, chat while you listen, and control playback remotely — up to Hi-Res 192 kHz / 24-bit.*

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-31%20·%20Android%2012-brightgreen?style=for-the-badge)](https://developer.android.com/about/versions/12)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue?style=for-the-badge)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Java-orange?style=for-the-badge&logo=openjdk&logoColor=white)](#)
[![Gradle](https://img.shields.io/badge/Gradle-8.13-02303A?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org)

</div>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#network-protocol">Protocol</a> •
  <a href="#quality-presets">Presets</a> •
  <a href="#getting-started">Getting started</a> •
  <a href="#project-structure">Structure</a> •
  <a href="#knowledge-graph">Knowledge graph</a>
</p>


### Screenshots

<p align="center">
  <img src="docs/screenshots/stream.png" width="23%" alt="Stream Mode" />
  <img src="docs/screenshots/receive.png" width="23%" alt="Receive Mode" />
  <img src="docs/screenshots/duplex.png" width="23%" alt="Duplex Mode" />
  <img src="docs/screenshots/FileShare.png" width="23%" alt="Fileshare" />
</p>

---

## Features

| Feature | Description |
|---|---|
| **System-wide audio capture** | Grabs audio from any app via the MediaProjection API. |
| **Multi-receiver streaming** | One host broadcasts to many listeners over local TCP/IP Wi-Fi. |
| **Hi-Res Audio** | Supports up to 192 kHz / 24-bit for audiophile-grade listening. |
| **FLAC lossless** | Bit-perfect audio with efficient bandwidth usage. |
| **Duplex mode** | Full bidirectional audio between two devices. |
| **Live chat & metadata** | Two-way chat plus track metadata broadcast. |
| **Transport controls** | Pause, resume, and skip from any receiver. |
| **Deep performance tuning** | Adjustable buffers and quality presets for any network environment. |

---

## Architecture

A single-Activity application utilizing a multi-Fragment design with a `BottomNavigationView` (4 tabs). All fragments communicate with `MainActivity` via callback interfaces.

| Tab | Mode | Description |
|---|---|---|
| **Stream** | Host / sender | Captures system audio and broadcasts it. |
| **Receive** | Client / receiver | Decodes and plays incoming streams. |
| **Duplex** | Bidirectional | Simultaneous send and receive between two devices. |
| **Settings** | Tuning | Buffers, presets, and performance options. |

### Data Flow

```mermaid
flowchart LR
    A["System audio (MediaProjection)"] --> B["FLAC encoder"]
    B -- "audio · TCP 5000" --> C["FLAC decoder"]
    C --> D["Playback"]
    B <-. "controls / ping · TCP 5001" .-> C
    B <-. "chat / metadata · TCP 5002" .-> C
```

---

## Network Protocol

SoundShare uses a custom binary protocol (v2) with a 28-byte header and the magic string `SSHD`. Traffic is spread over eight ports:

| Port | Proto | Purpose |
|:---:|:---:|---|
| `5000` | TCP | Main audio data stream |
| `5001` | TCP | Ping / latency measurement and transport control (pause / resume / skip) |
| `5002` | TCP | Chat and metadata broadcast |
| `5003` | TCP | Duplex audio stream |
| `5004` | TCP | Duplex chat stream |
| `5005` | TCP | File Share data transfer |
| `5006` | TCP | File Share sync-control (synchronized playback) |
| `5007` | UDP | File Share host discovery |

The Linux client in `c/` speaks the same protocol, so the header layout, command bytes and ports must stay byte-identical.

---

## Quality Presets

Seven one-tap presets are available, ranging from battery-saver modes to high-resolution audio:

| Preset | Sample Rate | Channels | Bit Depth |
|---|:---:|:---:|:---:|
| Ultra Low | 44.1 kHz | Mono | 16-bit |
| **Balanced** *(default)* | 48 kHz | Stereo | 16-bit |
| Hi-Res Ultra | 192 kHz | Stereo | 24-bit |

---

## Getting Started

### Prerequisites

- Android Studio (or Android SDK command-line tools)
- JDK 11 or higher
- Android SDK with API 36

### Build and Run

```bash
# Build the application
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK

# Install on a connected device
./gradlew installDebug

# Run tests
./gradlew test                   # Local unit tests
./gradlew connectedAndroidTest   # Instrumented tests
```

*Note: Host and receiver devices must be connected to the same local Wi-Fi network (or one phone's hotspot).*

### Start / Stop

| Action | How |
|---|---|
| Start streaming (host) | Open **Stream** → pick a quality preset → **Start Streaming** → accept the screen-capture prompt. The host IP is shown on screen. |
| Start receiving | Open **Receive** → enter the host IP (or tap **Scan**) → **Start Receiving**. |
| Duplex | Open **Duplex** → one device taps **Host**, the other taps **Join** with the host IP. |
| Stop | Tap the same button again (it becomes a stop button while a session runs), tap **Stop Session** in Duplex, or use the stop action in the media notification. The foreground service shuts down when its session ends. |
| Stop the app's dev build | `adb shell am force-stop com.example.soundshare` |
| Uninstall | `./gradlew uninstallDebug` |

### Quick reference

| Service | Address |
|---|---|
| Audio / ping / chat | `<host-ip>:5000` / `:5001` / `:5002` (TCP) |
| Duplex audio / chat | `<host-ip>:5003` / `:5004` (TCP) |
| File Share data / sync | `<host-ip>:5005` / `:5006` (TCP) |
| File Share discovery | broadcast `:5007` (UDP) |

### Troubleshooting / device compatibility

If streaming works on some phones but not others, see [REPORT.md](REPORT.md) for the root causes and fixes and [DEVICE_MATRIX.md](DEVICE_MATRIX.md) for per-OEM and per-Android-version test steps. Common fixes:

- Exempt SoundShare from battery optimization (the app shows a one-time hint with maker-specific steps for Xiaomi, Oppo, Realme, OnePlus, Vivo, Huawei, Honor and Samsung).
- Keep both devices on the same Wi-Fi band and subnet. Turn off VPNs and "Wi-Fi + mobile data" switching.
- If a Hi-Res preset fails, the host now falls back to a supported format automatically. Check the status line to see what was actually used.

---

## Project Structure

```text
SoundShare/
├── app/                  # Android app module (Java)
│   └── src/              # Activities, fragments, services, FLAC codec
├── gradle/               # Wrapper and version catalog (libs.versions.toml)
├── graphify-out/         # Generated architecture knowledge graph
├── build.gradle.kts      # Root build script (Kotlin DSL)
├── settings.gradle.kts   # Module configuration
└── gradle.properties     # Gradle properties
```

---

## Knowledge Graph

This project has a graphify Obsidian vault for architecture reference:

- **Vault:** `~/vaults/soundshare_v1/`
- **Graph:** `/home/abinav/AndroidStudioProjects/soundshare_v1/graphify-out/graph.json`
- **Report:** `/home/abinav/AndroidStudioProjects/soundshare_v1/graphify-out/GRAPH_REPORT.md`
- **Communities:** MainActivity Core, AudioConfig Presets, StreamFragment UI, ReceiveFragment UI, DuplexFragment UI, FileShareFragment UI, SettingsFragment, AudioCaptureService, AudioReceiveService, MediaMetadataHelper, FLAC Codec, HiResAudioHelper, AudioPipelineUtils, NetUtils, PipelineLog, BatteryOptHelper, LyricsHelper, Chat, FileShare queue/notifications, C client (GUI, sync server/client, network, audio player, transfer, stream receiver), Build Config, Tests (49 communities, rebuilt 2026-11-23)

To rebuild the vault after code changes:

```bash
graphify /home/abinav/AndroidStudioProjects/soundshare_v1 --obsidian --obsidian-dir ~/vaults/soundshare_v1
```

---

<div align="center">

**Java · Android 12+ · Gradle 8.13 (Kotlin DSL) · AGP 8.13.2 · TCP/IP · FLAC**

</div>
