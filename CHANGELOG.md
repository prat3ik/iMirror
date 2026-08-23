# Changelog

All notable changes to iMirror will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [1.0.1] - 2026-08-23

### Fixed

**"TV shows on iPhone but not on Mac" discovery failure**
- Root cause: some routers (observed on an Airtel/Broadcom unit with IGMP
  snooping) silently drop multicast packets *sent by* wireless clients. The
  system mDNS daemon's multicast answers never reached a browsing Mac. iOS
  still discovered the TV because a freshly opened Screen Mirroring menu sends
  a QU ("unicast response requested") query and the daemon's unicast reply gets
  through; macOS's permanent background `_airplay._tcp` browse sends only QM
  queries, whose multicast answers the router ate.

### Added

- `UnicastMdnsResponder` — a compatibility mDNS responder that joins
  224.0.0.251:5353, parses discovery queries itself (including name
  compression), and answers every query about iMirror's services via **unicast**
  straight back to the querier, regardless of the QU/QM bit (RFC 6762 §5.4
  requires clients to accept this). Unicast is immune to the router's multicast
  forwarding problem. Runs alongside the `NsdManager` registration, follows
  collision renames, serves SRV/TXT/A additionals in the same packet so a
  sender can resolve and connect from a single response, and handles legacy
  (non-5353) queriers per RFC 6762 §6.7 (ID echo, repeated question, ≤10 s TTLs)
- The responder ignores queries originating from **any** address this device
  holds, not just the primary one. The system daemon probes each name before
  registering and treats any answer as a conflict, so on a device with a second
  active interface (an LG CreateBoard on Android 13 runs `wlan0` alongside a
  WiFi-Direct `p2p-wlan0-1`, and the daemon probes on both) iMirror answered its
  own probe and registered as "<name> (2)" while the responder still advertised
  the original name — one device, two entries in the sender's AirPlay menu
- `WifiManager.MulticastLock` held while advertising — many Android TV WiFi
  chipsets filter inbound multicast in firmware unless an app holds this lock,
  which would break discovery in the other direction (queries never heard)
- 11 unit tests for the responder's DNS wire format (parsing with compression
  pointers, response layout, TTL rules, case-insensitive matching, malformed
  packet rejection, own-address filtering)
- README Troubleshooting section covering the iPhone-works/Mac-doesn't symptom

### Changed

- AirPlay and RAOP TXT records are now built by shared helpers
  (`MdnsService.airPlayTxtRecords`/`raopTxtRecords`) so both discovery paths
  advertise identical capabilities

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/iMirror/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->
