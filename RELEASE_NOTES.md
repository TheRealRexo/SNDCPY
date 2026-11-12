# SNDCPY 1.0.0 — Official Release

> The first production-ready release of SNDCPY — a complete, cross-platform remote control system for Android.

## What's New

This is the **first official release** of SNDCPY. All five components (Android Server, Android Client, iOS Client, Linux Client, Windows Client) have been validated through beta progression and now share the same wire protocol v2.

## Components

| Component | Version | Artifact |
|-----------|---------|----------|
| **Android Server** | 1.0.0 | `SNDCPY-Server-1.0.0.apk` |
| **Android Client** | 1.0.0 | `SNDCPY-Client-1.0.0.apk` |
| **iOS Client** | 1.0.0 | `SNDCPY-iOS-1.0.0.ipa` |
| **Linux Client** | 1.0.0 | `sndcpy-client_1.0.0_all.deb`, `sndcpy-client-1.0.0-1.noarch.rpm`, `SNDCPY-1.0.0-x86_64.AppImage` |
| **Windows Client** | 1.0.0 | `SNDCPY-1.0.0-portable.zip`, `SNDCPY-Setup-1.0.0.exe`, `SNDCPY-1.0.0.exe` |

## Key Features

### Server (Android)
- **Video**: MediaProjection → H.264 @ 720p/30fps/4Mbps (configurable)
- **Audio**: Playback capture → AAC-LC 128kbps (Android 10+)
- **Input**: AccessibilityService gestures (tap, swipe, scroll, HOME/BACK/RECENTS, keycodes)
- **Security**: ECDH P-256 + HKDF → AES-256-GCM, one-time 6-digit pairing codes
- **Discovery**: mDNS/Bonjour `_sndcpy._tcp.` + NSD
- **Telemetry**: 5s JSON snapshots (fps, heap, sessions, uptime)

### Clients
All clients speak **wire protocol v2**:
- `HELLO SNDCPY` handshake → ECDH + AES-256-GCM encryption
- Binary envelope multiplexing: control (0x01), video (0x02), audio (0x03), telemetry (0x04)
- Commands: `TAP`, `SWIPE`, `SCROLL`, `HOME`, `BACK`, `RECENTS`, `KEY:`, `SET_BITRATE`, `SET_FPS`, `GET_CONFIG`, `PING`

### Android Client
- Kotlin + NWConnection equivalent
- Pairing code entry UI
- Real-time server message log

### iOS / macOS Client
- Swift + Network.framework (NWConnection)
- SwiftUI interface with connection state
- Pairing flow with code entry

### Linux Client
- Python 3.10+ reference implementation
- .deb (Debian/Ubuntu), .rpm (Fedora/RHEL), .AppImage (universal)
- Interactive command shell

### Windows Client
- Portable zip (no install)
- MSI installer via Inno Setup
- Standalone .exe via PyInstaller

## Wire Protocol (v2)

```
TCP :27183
  HELLO SNDCPY <clientPubKey> <clientNonce>
    ├─ paired  → SECURE <serverPubKey> <serverNonce>
    └─ new     → PAIRING_REQUIRED … → PAIR <code> → PAIRED
  ── ECDH + HKDF → AES-256-GCM session ──
  encrypted frames [u32 len][12B IV][ciphertext]
    └─ plaintext envelope [u8 channel][i64 pts µs][payload]
         0x01 control  0x02 video-H264  0x03 audio-AAC  0x04 telemetry
```

## Installation

### Android Server
```bash
adb install SNDCPY-Server-1.0.0.apk
# Open app → Issue pairing code → Start screen share
```

### Android Client
```bash
adb install SNDCPY-Client-1.0.0.apk
```

### iOS
```bash
# Build from source
open iosclient/SNDCPYClient.xcodeproj
# Or install IPA via TestFlight / Xcode
```

### Linux
```bash
# Debian/Ubuntu
sudo dpkg -i sndcpy-client_1.0.0_all.deb
# Fedora/RHEL
sudo rpm -i sndcpy-client-1.0.0-1.noarch.rpm
# Universal
chmod +x SNDCPY-1.0.0-x86_64.AppImage && ./SNDCPY-1.0.0-x86_64.AppImage --host 192.168.1.42
```

### Windows
```powershell
# Portable
unzip SNDCPY-1.0.0-portable.zip
SNDCPY.bat --host 192.168.1.42

# Installer
SNDCPY-Setup-1.0.0.exe

# Standalone exe
SNDCPY-1.0.0.exe --host 192.168.1.42
```

## Version History (Beta Progression)

Every component followed `0.1.0-beta` → `1.0.0-beta` to validate the wire protocol:

- **0.1.0-beta** Clean skeleton, deterministic Gradle, CI
- **0.2.0-beta** TCP server, discovery prototype, sessions
- **0.3.0-beta** Video prototype, basic input
- **0.4.0-beta** First APK in Releases
- **0.5.0-beta** Security: ECDH P-256, pairing codes, AES-256-GCM
- **0.6.0-beta** Video: MediaProjection → H.264, StreamingBus
- **0.7.0-beta** Input: AccessibilityService gestures
- **0.8.0-beta** Discovery: mDNS NSD + telemetry
- **0.9.0-beta** Audio: playback capture → AAC-LC
- **1.0.0-beta** Polish: back-pressure, SET_BITRATE/FPS, RuntimeConfig

## Known Issues

- iOS build requires Apple Developer account for device deployment (simulator works without)
- Windows MSI requires Inno Setup on build machine (handled in CI)
- Linux .deb/.rpm require `dpkg-dev`/`rpmbuild` (handled in CI)
- Audio on Android requires API 29+ (gracefully skipped on older)

## Links

- **Repository**: https://github.com/TheRealRexo/SNDCPY
- **Issues**: https://github.com/TheRealRexo/SNDCPY/issues
- **Documentation**: androidserver/README.md

---

*SNDCPY — "sound copy" — Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)*