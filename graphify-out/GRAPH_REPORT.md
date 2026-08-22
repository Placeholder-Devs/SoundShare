# Graph Report - /home/abinav/AndroidStudioProjects/soundshare_v1  (2026-04-12)

## Corpus Check
- Corpus is ~44,301 words - fits in a single context window. You may not need a graph.

## Summary
- 613 nodes · 1258 edges · 24 communities detected
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 21 edges (avg confidence: 0.89)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_MainActivity Core|MainActivity Core]]
- [[_COMMUNITY_MainActivity v2 Backup|MainActivity v2 Backup]]
- [[_COMMUNITY_MainActivity v4 Backup|MainActivity v4 Backup]]
- [[_COMMUNITY_MainActivity v3 Backup|MainActivity v3 Backup]]
- [[_COMMUNITY_MainActivity v1 Backup|MainActivity v1 Backup]]
- [[_COMMUNITY_StreamFragment UI|StreamFragment UI]]
- [[_COMMUNITY_ReceiveFragment UI|ReceiveFragment UI]]
- [[_COMMUNITY_DuplexFragment UI|DuplexFragment UI]]
- [[_COMMUNITY_AudioCaptureService|AudioCaptureService]]
- [[_COMMUNITY_FLAC Codec|FLAC Codec]]
- [[_COMMUNITY_MediaMetadataHelper|MediaMetadataHelper]]
- [[_COMMUNITY_AudioReceiveService|AudioReceiveService]]
- [[_COMMUNITY_SettingsFragment|SettingsFragment]]
- [[_COMMUNITY_ChatAdapter|ChatAdapter]]
- [[_COMMUNITY_CLAUDE.md Documentation|CLAUDE.md Documentation]]
- [[_COMMUNITY_Icon Density Variants|Icon Density Variants]]
- [[_COMMUNITY_HiResAudioHelper|HiResAudioHelper]]
- [[_COMMUNITY_ChatNotificationHelper|ChatNotificationHelper]]
- [[_COMMUNITY_ChatMessage Model|ChatMessage Model]]
- [[_COMMUNITY_Instrumented Tests|Instrumented Tests]]
- [[_COMMUNITY_Unit Tests|Unit Tests]]
- [[_COMMUNITY_Root Build Config|Root Build Config]]
- [[_COMMUNITY_Gradle Settings|Gradle Settings]]
- [[_COMMUNITY_App Build Config|App Build Config]]

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 103 edges
2. `MainActivity` - 62 edges
3. `MainActivity` - 59 edges
4. `MainActivity` - 56 edges
5. `MainActivity` - 32 edges
6. `StreamFragment` - 23 edges
7. `ReceiveFragment` - 22 edges
8. `DuplexFragment` - 19 edges
9. `AudioCaptureService` - 14 edges
10. `AudioReceiveService` - 12 edges

## Surprising Connections (you probably didn't know these)
- `Round Adaptive App Icon` --conceptually_related_to--> `SoundShare Android Application`  [EXTRACTED]
  app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp → CLAUDE.md
- `Legacy Round App Icon` --conceptually_related_to--> `SoundShare Android Application`  [EXTRACTED]
  app/src/main/res/mipmap-xxhdpi/icon_round.webp → CLAUDE.md
- `Adaptive Icon Foreground Layer` --conceptually_related_to--> `SoundShare Android Application`  [EXTRACTED]
  app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.webp → CLAUDE.md
- `Legacy Icon Foreground` --conceptually_related_to--> `SoundShare Android Application`  [EXTRACTED]
  app/src/main/res/mipmap-xxhdpi/icon_foreground.webp → CLAUDE.md
- `Play Store Listing Icon` --conceptually_related_to--> `SoundShare Android Application`  [EXTRACTED]
  app/src/main/res/drawable/iconplaystore.png → CLAUDE.md

## Communities

### Community 0 - "MainActivity Core"
Cohesion: 0.04
Nodes (6): AudioConfig, ChatClient, ClientConnection, MainActivity, onReceive(), run()

### Community 1 - "MainActivity v2 Backup"
Cohesion: 0.06
Nodes (4): AudioConfig, ChatClient, ClientConnection, MainActivity

### Community 2 - "MainActivity v4 Backup"
Cohesion: 0.07
Nodes (4): AudioConfig, ChatClient, ClientConnection, MainActivity

### Community 3 - "MainActivity v3 Backup"
Cohesion: 0.07
Nodes (4): AudioConfig, ChatClient, ClientConnection, MainActivity

### Community 4 - "MainActivity v1 Backup"
Cohesion: 0.11
Nodes (3): AudioConfig, ClientConnection, MainActivity

### Community 5 - "StreamFragment UI"
Cohesion: 0.1
Nodes (2): StreamCallback, StreamFragment

### Community 6 - "ReceiveFragment UI"
Cohesion: 0.1
Nodes (2): ReceiveCallback, ReceiveFragment

### Community 7 - "DuplexFragment UI"
Cohesion: 0.1
Nodes (2): DuplexCallback, DuplexFragment

### Community 8 - "AudioCaptureService"
Cohesion: 0.21
Nodes (1): AudioCaptureService

### Community 9 - "FLAC Codec"
Cohesion: 0.21
Nodes (3): Decoder, Encoder, FlacCodec

### Community 10 - "MediaMetadataHelper"
Cohesion: 0.21
Nodes (3): MediaMetadataHelper, NLService, NowPlayingInfo

### Community 11 - "AudioReceiveService"
Cohesion: 0.26
Nodes (1): AudioReceiveService

### Community 12 - "SettingsFragment"
Cohesion: 0.17
Nodes (2): SettingsFragment, SimpleSeekListener

### Community 13 - "ChatAdapter"
Cohesion: 0.18
Nodes (2): ChatAdapter, ChatViewHolder

### Community 14 - "CLAUDE.md Documentation"
Cohesion: 0.26
Nodes (12): Single-Activity Multi-Fragment Architecture, Hi-Res Audio Engine (FLAC/MediaCodec), CLAUDE.md - Project Guidance Document, Round Adaptive App Icon, Square Adaptive App Icon, Adaptive Icon Foreground Layer, Legacy Icon Foreground, Legacy Round App Icon (+4 more)

### Community 15 - "Icon Density Variants"
Cohesion: 0.36
Nodes (12): HDPI Density Variant Set, MDPI Density Variant Set, XHDPI Density Variant Set, XXHDPI Density Variant Set, XXXHDPI Density Variant Set, Black Background Theme, Purple-to-Blue Gradient Color Scheme, SoundShare App Icon Foreground - Audio Cable with Signal Waves (+4 more)

### Community 16 - "HiResAudioHelper"
Cohesion: 0.27
Nodes (2): Capabilities, HiResAudioHelper

### Community 17 - "ChatNotificationHelper"
Cohesion: 0.28
Nodes (2): ChatEntry, ChatNotificationHelper

### Community 18 - "ChatMessage Model"
Cohesion: 0.25
Nodes (1): ChatMessage

### Community 19 - "Instrumented Tests"
Cohesion: 0.67
Nodes (1): ExampleInstrumentedTest

### Community 20 - "Unit Tests"
Cohesion: 0.67
Nodes (1): ExampleUnitTest

### Community 21 - "Root Build Config"
Cohesion: 1.0
Nodes (0): 

### Community 22 - "Gradle Settings"
Cohesion: 1.0
Nodes (0): 

### Community 23 - "App Build Config"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **2 isolated node(s):** `NLService`, `Single-Activity Multi-Fragment Architecture`
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Root Build Config`** (1 nodes): `build.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Gradle Settings`** (1 nodes): `settings.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `App Build Config`** (1 nodes): `build.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What connects `NLService`, `Single-Activity Multi-Fragment Architecture` to the rest of the system?**
  _2 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity Core` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `MainActivity v2 Backup` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
- **Should `MainActivity v4 Backup` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `MainActivity v3 Backup` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `MainActivity v1 Backup` be split into smaller, more focused modules?**
  _Cohesion score 0.11 - nodes in this community are weakly interconnected._
- **Should `StreamFragment UI` be split into smaller, more focused modules?**
  _Cohesion score 0.1 - nodes in this community are weakly interconnected._