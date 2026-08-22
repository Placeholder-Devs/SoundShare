# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**SoundShare** is a native Android app (Java) that streams real-time, high-fidelity audio over local WiFi between Android devices. It captures system audio via MediaProjection API and streams to one or more receivers over TCP/IP, supporting Hi-Res Audio (up to 192kHz/24-bit), FLAC lossless compression, bidirectional chat, and transport controls.

- **Language**: Java (no Kotlin source files)
- **Min SDK**: 31 (Android 12) / **Target SDK**: 36
- **Build System**: Gradle 8.13 with Kotlin DSL, Version Catalog (`gradle/libs.versions.toml`)
- **AGP**: 8.13.2

## Architecture

Single-Activity, multi-Fragment architecture. `MainActivity` is the central controller; all fragments communicate through it via callback interfaces.

```
MainActivity (~2500+ lines)
  |-- StreamFragment   (host/sender mode)
  |-- ReceiveFragment  (client/receiver mode)
  |-- DuplexFragment   (bidirectional mode)
  |-- SettingsFragment (SharedPreferences-backed config)
```

Navigation uses `BottomNavigationView`. Fragments are added once and shown/hidden to preserve state.

### Source Files

All under `app/src/main/java/com/example/soundshare/`:

| File | Purpose |
|------|---------|
| `MainActivity.java` | Central controller: networking (server/client sockets on 5 ports), audio capture/playback, protocol handling, fragment callbacks, permissions, notifications, chat routing |
| `StreamFragment.java` | Sender UI: device IP, quality selector, stats, now-playing card, chat panel |
| `ReceiveFragment.java` | Receiver UI: IP input, connect button, stats, now-playing with transport controls, chat |
| `DuplexFragment.java` | Bidirectional mode UI: host/join, combined stats, now-playing, dedicated chat |
| `SettingsFragment.java` | Buffer size, performance mode, socket buffer multiplier, timeout, custom client name (SharedPreferences) |
| `AudioCaptureService.java` | Foreground service (mediaProjection) with MediaSessionCompat notification controls |
| `AudioReceiveService.java` | Foreground service (mediaPlayback) with MediaSessionCompat notification controls |
| `FlacCodec.java` | MediaCodec wrapper for FLAC encode/decode (16/24/32-bit, various sample rates) |
| `HiResAudioHelper.java` | Runtime capability detection: probes 96kHz/192kHz/24-bit/32-bit/float/FLAC support |
| `MediaMetadataHelper.java` | MediaSessionManager + NLService for reading media metadata from other apps; transport controls |
| `ChatNotificationHelper.java` | Heads-up notifications for incoming chat (MessagingStyle), unread count tracking |
| `ChatAdapter.java` | RecyclerView adapter for chat (sent/received/system view types) |
| `ChatMessage.java` | POJO for chat messages |

### Network Protocol

Five TCP ports with custom binary protocol:

| Port | Purpose |
|------|---------|
| 5000 | Main audio data stream |
| 5001 | Ping/latency + control (pause/resume/skip) |
| 5002 | Chat + metadata broadcast |
| 5003 | Duplex audio |
| 5004 | Duplex chat |

**Protocol v2**: 28-byte header (magic `0x53534844` = "SSHD", version, sample rate, bit depth, channels, frames-per-buffer, chunk size, compression type, float flag).

**Control messages** via ping channel: `CTRL_PAUSE` (0x20), `CTRL_RESUME` (0x21), `CTRL_SKIP_FWD` (0x22), `CTRL_SKIP_BACK` (0x23).

**Chat protocol**: Byte command (`CHAT_MSG` = 0x10, `CHAT_METADATA` = 0x11) followed by length-prefixed UTF-8 fields.

### Quality Presets

7 presets: 44.1kHz Mono 16-bit (Ultra Low) through 192kHz Stereo 24-bit (Hi-Res Ultra). Default is 48kHz Stereo 16-bit (Balanced).

## Developing in This Repository

### Build / Run / Test

```bash
# Build
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (minify currently disabled)

# Install on connected device
./gradlew installDebug

# Tests (minimal coverage - boilerplate only)
./gradlew test                   # Local unit tests
./gradlew connectedAndroidTest   # Instrumented tests
```

**Prerequisites**: Android Studio or Android SDK, JDK 11+, Android SDK with API 36.

### Key Configuration Files

- `build.gradle.kts` — root build config
- `app/build.gradle.kts` — app module config
- `settings.gradle.kts` — Gradle settings
- `gradle/libs.versions.toml` — dependency version catalog
- `gradle.properties` — JVM args (`-Xmx2048m`), AndroidX, non-transitive R class
- `app/src/main/AndroidManifest.xml` — permissions, activities, services, NLService

### Important Notes

- Root-level files `main_activity_java_v1.java` through `main_activity_java_v4.java` are iteration backups of MainActivity development and are **not** part of the build. Ignore them.
- The test suite is minimal (boilerplate placeholder tests only).
- All networking uses raw TCP sockets with `DataInputStream`/`DataOutputStream`.
- Audio capture requires MediaProjection screen capture permission (Android 10+).
- Media metadata reading requires the user to grant Notification Listener Service access.
- Settings are stored in SharedPreferences; static getters on `SettingsFragment` expose them to `MainActivity`.
