# Graph Report - /home/abinav/AndroidStudioProjects/soundshare_v1  (2026-11-23)

## Corpus Check
- 137 files · ~214,433 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 930 nodes · 1666 edges · 49 communities detected
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 21 edges (avg confidence: 0.89)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_MainActivity Core|MainActivity Core]]
- [[_COMMUNITY_C Client GUI|C Client GUI]]
- [[_COMMUNITY_FileShareFragment UI|FileShareFragment UI]]
- [[_COMMUNITY_C Sync Server|C Sync Server]]
- [[_COMMUNITY_DuplexFragment UI|DuplexFragment UI]]
- [[_COMMUNITY_StreamFragment UI|StreamFragment UI]]
- [[_COMMUNITY_ReceiveFragment UI|ReceiveFragment UI]]
- [[_COMMUNITY_AudioPipelineUtils Tests|AudioPipelineUtils Tests]]
- [[_COMMUNITY_MainActivity Core (2)|MainActivity Core (2)]]
- [[_COMMUNITY_SettingsFragment|SettingsFragment]]
- [[_COMMUNITY_AudioReceiveService|AudioReceiveService]]
- [[_COMMUNITY_File Queue Adapter|File Queue Adapter]]
- [[_COMMUNITY_LyricsHelper|LyricsHelper]]
- [[_COMMUNITY_C Network Utils|C Network Utils]]
- [[_COMMUNITY_AudioCaptureService|AudioCaptureService]]
- [[_COMMUNITY_MediaMetadataHelper|MediaMetadataHelper]]
- [[_COMMUNITY_File Queue Item|File Queue Item]]
- [[_COMMUNITY_AudioPipelineUtils|AudioPipelineUtils]]
- [[_COMMUNITY_NetUtils Tests|NetUtils Tests]]
- [[_COMMUNITY_C Audio Player|C Audio Player]]
- [[_COMMUNITY_FLAC Codec|FLAC Codec]]
- [[_COMMUNITY_Chat Adapter|Chat Adapter]]
- [[_COMMUNITY_NetUtils|NetUtils]]
- [[_COMMUNITY_Project Docs & Icons|Project Docs & Icons]]
- [[_COMMUNITY_Icon Density Variants|Icon Density Variants]]
- [[_COMMUNITY_FileShare Notifications|FileShare Notifications]]
- [[_COMMUNITY_HiResAudioHelper|HiResAudioHelper]]
- [[_COMMUNITY_PipelineLog|PipelineLog]]
- [[_COMMUNITY_BatteryOptHelper|BatteryOptHelper]]
- [[_COMMUNITY_Synced Lyrics Adapter|Synced Lyrics Adapter]]
- [[_COMMUNITY_Chat Notifications|Chat Notifications]]
- [[_COMMUNITY_Chat Message Model|Chat Message Model]]
- [[_COMMUNITY_PipelineLog Tests|PipelineLog Tests]]
- [[_COMMUNITY_C Sync Client|C Sync Client]]
- [[_COMMUNITY_AudioTrack Instrumented Test|AudioTrack Instrumented Test]]
- [[_COMMUNITY_C Song Transfer|C Song Transfer]]
- [[_COMMUNITY_C Stream Receiver|C Stream Receiver]]
- [[_COMMUNITY_C Time Utils|C Time Utils]]
- [[_COMMUNITY_C Client Entry|C Client Entry]]
- [[_COMMUNITY_HiRes Instrumented Test|HiRes Instrumented Test]]
- [[_COMMUNITY_Example Instrumented Test|Example Instrumented Test]]
- [[_COMMUNITY_Example Unit Test|Example Unit Test]]
- [[_COMMUNITY_Root Build Config|Root Build Config]]
- [[_COMMUNITY_Gradle Settings|Gradle Settings]]
- [[_COMMUNITY_App Build Config|App Build Config]]
- [[_COMMUNITY_C Time Header|C Time Header]]
- [[_COMMUNITY_C Song Transfer Header|C Song Transfer Header]]
- [[_COMMUNITY_C Audio Player Header|C Audio Player Header]]
- [[_COMMUNITY_C Stream Receiver Header|C Stream Receiver Header]]

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 202 edges
2. `AudioPipelineUtilsTest` - 34 edges
3. `StreamFragment` - 30 edges
4. `ReceiveFragment` - 29 edges
5. `DuplexFragment` - 27 edges
6. `FileShareFragment` - 27 edges
7. `AudioReceiveService` - 26 edges
8. `SettingsFragment` - 23 edges
9. `AudioCaptureService` - 20 edges
10. `log_msg()` - 19 edges

## Surprising Connections (you probably didn't know these)
- `SoundShare Android Application` --conceptually_related_to--> `Round Adaptive App Icon`  [EXTRACTED]
  CLAUDE.md → app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp
- `SoundShare Android Application` --conceptually_related_to--> `Legacy Round App Icon`  [EXTRACTED]
  CLAUDE.md → app/src/main/res/mipmap-xxhdpi/icon_round.webp
- `SoundShare Android Application` --conceptually_related_to--> `Adaptive Icon Foreground Layer`  [EXTRACTED]
  CLAUDE.md → app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.webp
- `SoundShare Android Application` --conceptually_related_to--> `Legacy Icon Foreground`  [EXTRACTED]
  CLAUDE.md → app/src/main/res/mipmap-xxhdpi/icon_foreground.webp
- `SoundShare Android Application` --conceptually_related_to--> `Play Store Listing Icon`  [EXTRACTED]
  CLAUDE.md → app/src/main/res/drawable/iconplaystore.png

## Communities

### Community 0 - "MainActivity Core"
Cohesion: 0.03
Nodes (2): MainActivity, onReceive()

### Community 1 - "C Client GUI"
Cohesion: 0.07
Nodes (33): apply_client_synced_start_time(), apply_sync_time_nudge(), build_client_view(), build_server_view(), client_meta_worker(), client_song_dl_thread(), client_sync_worker(), log_msg() (+25 more)

### Community 2 - "FileShareFragment UI"
Cohesion: 0.06
Nodes (2): FileShareCallback, FileShareFragment

### Community 3 - "C Sync Server"
Cohesion: 0.13
Nodes (34): add_sync_peer(), broadcast_file_ready(), broadcast_queue_add(), broadcast_sync_cmd(), broadcast_track_change(), host_receive_song_worker(), log_status(), notify_clients_changed() (+26 more)

### Community 4 - "DuplexFragment UI"
Cohesion: 0.07
Nodes (2): DuplexCallback, DuplexFragment

### Community 5 - "StreamFragment UI"
Cohesion: 0.08
Nodes (2): StreamCallback, StreamFragment

### Community 6 - "ReceiveFragment UI"
Cohesion: 0.08
Nodes (2): ReceiveCallback, ReceiveFragment

### Community 7 - "AudioPipelineUtils Tests"
Cohesion: 0.09
Nodes (1): AudioPipelineUtilsTest

### Community 8 - "MainActivity Core (2)"
Cohesion: 0.09
Nodes (7): AudioConfig, ChatClient, ClientConnection, FileSharePeer, ReceiveSession, run(), StreamSession

### Community 9 - "SettingsFragment"
Cohesion: 0.09
Nodes (3): OnSettingsChangedListener, SettingsFragment, SimpleSeekListener

### Community 10 - "AudioReceiveService"
Cohesion: 0.15
Nodes (1): AudioReceiveService

### Community 11 - "File Queue Adapter"
Cohesion: 0.1
Nodes (4): FileQueueAdapter, OnQueueItemListener, OnStartDragListener, ViewHolder

### Community 12 - "LyricsHelper"
Cohesion: 0.18
Nodes (4): LyricLine, LyricsCallback, LyricsHelper, ParsedLyrics

### Community 13 - "C Network Utils"
Cohesion: 0.15
Nodes (16): discovery_server_worker(), net_connect_tcp(), net_get_local_ip(), net_read_exact(), net_set_tcp_nodelay(), net_write_all(), recv_string(), recv_u16() (+8 more)

### Community 14 - "AudioCaptureService"
Cohesion: 0.17
Nodes (1): AudioCaptureService

### Community 15 - "MediaMetadataHelper"
Cohesion: 0.19
Nodes (3): MediaMetadataHelper, NLService, NowPlayingInfo

### Community 16 - "File Queue Item"
Cohesion: 0.11
Nodes (1): FileQueueItem

### Community 17 - "AudioPipelineUtils"
Cohesion: 0.16
Nodes (1): AudioPipelineUtils

### Community 18 - "NetUtils Tests"
Cohesion: 0.19
Nodes (1): NetUtilsTest

### Community 19 - "C Audio Player"
Cohesion: 0.14
Nodes (2): audio_player_init(), audio_player_load()

### Community 20 - "FLAC Codec"
Cohesion: 0.21
Nodes (3): Decoder, Encoder, FlacCodec

### Community 21 - "Chat Adapter"
Cohesion: 0.18
Nodes (2): ChatAdapter, ChatViewHolder

### Community 22 - "NetUtils"
Cohesion: 0.23
Nodes (2): Candidate, NetUtils

### Community 23 - "Project Docs & Icons"
Cohesion: 0.26
Nodes (12): Single-Activity Multi-Fragment Architecture, Hi-Res Audio Engine (FLAC/MediaCodec), CLAUDE.md - Project Guidance Document, Round Adaptive App Icon, Square Adaptive App Icon, Adaptive Icon Foreground Layer, Legacy Icon Foreground, Legacy Round App Icon (+4 more)

### Community 24 - "Icon Density Variants"
Cohesion: 0.36
Nodes (12): HDPI Density Variant Set, MDPI Density Variant Set, XHDPI Density Variant Set, XXHDPI Density Variant Set, XXXHDPI Density Variant Set, Black Background Theme, Purple-to-Blue Gradient Color Scheme, SoundShare App Icon Foreground - Audio Cable with Signal Waves (+4 more)

### Community 25 - "FileShare Notifications"
Cohesion: 0.29
Nodes (1): FileShareNotificationHelper

### Community 26 - "HiResAudioHelper"
Cohesion: 0.27
Nodes (2): Capabilities, HiResAudioHelper

### Community 27 - "PipelineLog"
Cohesion: 0.4
Nodes (1): PipelineLog

### Community 28 - "BatteryOptHelper"
Cohesion: 0.31
Nodes (1): BatteryOptHelper

### Community 29 - "Synced Lyrics Adapter"
Cohesion: 0.22
Nodes (2): LyricViewHolder, SyncedLyricsAdapter

### Community 30 - "Chat Notifications"
Cohesion: 0.28
Nodes (2): ChatEntry, ChatNotificationHelper

### Community 31 - "Chat Message Model"
Cohesion: 0.25
Nodes (1): ChatMessage

### Community 32 - "PipelineLog Tests"
Cohesion: 0.25
Nodes (1): PipelineLogTest

### Community 33 - "C Sync Client"
Cohesion: 0.38
Nodes (3): apply_synced_start_time(), calibrate_clock(), run_client()

### Community 34 - "AudioTrack Instrumented Test"
Cohesion: 0.6
Nodes (1): AudioTrackFormatInstrumentedTest

### Community 35 - "C Song Transfer"
Cohesion: 0.5
Nodes (2): get_received_songs_dir(), song_transfer_receive()

### Community 36 - "C Stream Receiver"
Cohesion: 0.4
Nodes (0): 

### Community 37 - "C Time Utils"
Cohesion: 0.4
Nodes (0): 

### Community 38 - "C Client Entry"
Cohesion: 0.67
Nodes (2): main(), print_usage()

### Community 39 - "HiRes Instrumented Test"
Cohesion: 0.67
Nodes (1): HiResAudioHelperInstrumentedTest

### Community 40 - "Example Instrumented Test"
Cohesion: 0.67
Nodes (1): ExampleInstrumentedTest

### Community 41 - "Example Unit Test"
Cohesion: 0.67
Nodes (1): ExampleUnitTest

### Community 42 - "Root Build Config"
Cohesion: 1.0
Nodes (0): 

### Community 43 - "Gradle Settings"
Cohesion: 1.0
Nodes (0): 

### Community 44 - "App Build Config"
Cohesion: 1.0
Nodes (0): 

### Community 45 - "C Time Header"
Cohesion: 1.0
Nodes (0): 

### Community 46 - "C Song Transfer Header"
Cohesion: 1.0
Nodes (0): 

### Community 47 - "C Audio Player Header"
Cohesion: 1.0
Nodes (0): 

### Community 48 - "C Stream Receiver Header"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **4 isolated node(s):** `NLService`, `StreamSession`, `ReceiveSession`, `Single-Activity Multi-Fragment Architecture`
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Root Build Config`** (1 nodes): `build.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Gradle Settings`** (1 nodes): `settings.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `App Build Config`** (1 nodes): `build.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `C Time Header`** (1 nodes): `soundshare_time.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `C Song Transfer Header`** (1 nodes): `song_transfer.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `C Audio Player Header`** (1 nodes): `audio_player.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `C Stream Receiver Header`** (1 nodes): `stream_receiver.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity Core` to `MainActivity Core (2)`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._
- **What connects `NLService`, `StreamSession`, `ReceiveSession` to the rest of the system?**
  _4 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity Core` be split into smaller, more focused modules?**
  _Cohesion score 0.03 - nodes in this community are weakly interconnected._
- **Should `C Client GUI` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `FileShareFragment UI` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
- **Should `C Sync Server` be split into smaller, more focused modules?**
  _Cohesion score 0.13 - nodes in this community are weakly interconnected._
- **Should `DuplexFragment UI` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._