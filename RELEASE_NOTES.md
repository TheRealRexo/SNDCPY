# SNDCPY 1.0.0 — Official Release

> The first production-ready release of SNDCPY with exactly 3 pairing methods, light/dark themes, Android server icon, and APK self-update — all built locally.

## What's New

This release introduces **exactly 3 pairing methods** across all platforms, plus numerous quality-of-life improvements:

### Pairing Methods (v1.0.0)

SNDCPY now supports exactly **3 pairing methods**, selectable from the client UI:

| Method | Platforms | Description |
|--------|-----------|-------------|
| **Code** | All platforms (Android, iOS, macOS, Linux, Windows) | 6-digit code entered via alert dialog when connecting (reverted from inline text box) |
| **Bluetooth** | All platforms | Mutual confirmation — both sides must have matching 6-digit codes (like Bluetooth pairing) |
| **USB** | Desktop only (macOS, Linux, Windows) | USB cable connection — no code needed, just connect via cable |

> **iOS**: USB pairing method is filtered out (desktop only). Bluetooth and Code methods are available.

### Bug Fixes

- **Crypto bug fixes**: ECPublicKey NameError resolved (EllipticCurvePublicKey used correctly); salt order fixed to `clientNonce + serverNonce` (canonical X963 + HKDF-SHA256); `_read_loop` moved to background thread to avoid blocking GUI
- **Android server**: `PAIR PROMPT` branch reordered so prompt pairing is reachable (was previously swallowed by first `PAIR` branch)
- **Android server icon**: Added `android:icon="@mipmap/ic_launcher"` to manifest; icons copied to all mipmap density directories from `assets/sndcpy-icon-*.png`
- **iOS Liquid Glass GUI**: Rewritten `ContentView.swift` with 3-method selector, alert-based code entry, mutual confirmation for Bluetooth, USB filtered out, and glass-style visual design

### New Features

- **Light/dark themes**: macOS QPalette theme switching; Android Material3.DayNight; iOS dark mode preferred
- **APK self-update mechanism**: File picker → install from locally downloaded APKs (both server and client)
- **Android client 3-method UI**: Complete rewrite of `ClientActivity.kt` and `activity_client.xml` with methodCodeBtn, methodBluetoothBtn, methodUsbBtn selector

### Components

| Component | Version | Artifact |
|-----------|---------|----------|
| **Android Server** | 1.0.0 | `SNDCPY-Server-1.0.0.apk` |
| **Android Client** | 1.0.0 | `SNDCPY-Client-1.0.0.apk` |
| **iOS Client** | 1.0.0 | `SNDCPY-iOS-1.0.0.ipa` |
| **Linux Client** | 1.0.0 | `sndcpy-client_1.0.0_all.deb`, `sndcpy-client-1.0.0-1.noarch.rpm`, `SNDCPY-1.0.0-x86_64.AppImage` |
| **Windows Client** | 1.0.0 | `SNDCPY-1.0.0-portable.zip`, `SNDCPY-1.0.0-setup.exe`, `SNDCPY-1.0.0.exe` |

## Key Features

### Server (Android)

- **Video**: MediaProjection → H.264 @ 720p/30fps/4Mbps (configurable)
- **Audio**: Playback capture → AAC-LC 128kbps (Android 10+)
- **Input**: AccessibilityService gestures (tap, swipe, scroll, HOME/BACK/RECENTS, keycodes)
- **Security**: ECDH P-256 + HKDF → AES-256-GCM, one-time pairing codes (3 methods)
- **Discovery**: mDNS/Bonjour `_sndcpy._tcp.` + NSD
- **Telemetry**: 5s JSON snapshots (fps, heap, sessions, uptime)

### Clients

All clients speak **wire protocol v2**:

- `HELLO SNDCPY` handshake → ECDH + AES-256-GCM encryption
- Binary envelope multiplexing: control (0x01), video (0x02), audio (0x03), telemetry (0x04)
- Commands: `TAP`, `SWIPE`, `SCROLL`, `HOME`, `BACK`, `RECENTS`, `KEY:`, `SET_BITRATE`, `SET_FPS`, `GET_CONFIG`, `PING`

### Android Client

- Kotlin + NWConnection equivalent
- **3-method pairing selector** (Code/Bluetooth/USB)
- Pairing code entry UI (alert for Code method)
- Real-time server message log
- Bluetooth mutual confirmation flow

### iOS / macOS Client

- Swift + Network.framework (NWConnection)
- **Liquid Glass styling** with 3-method pairing selector
- Code method: `QInputDialog` alert entry
- Bluetooth method: mutual confirm code entry
- USB method: filtered out on iOS

### Linux Client

- Python 3.10+ reference implementation
- .deb (Debian/Ubuntu), .rpm (Fedora/RHEL), .AppImage (universal)
- Interactive command shell

### Windows Client

- Portable zip (no install)
- Standalone .exe via PyInstaller

## Wire Protocol (v2)

```
TCP :27183
  HELLO SNDCPY <clientPubKey> <clientNonce>
    ├─ paired  → SECURE <serverPubKey> <serverNonce>
    └─ new     → PAIRING_REQUIRED … → PAIR <code> → PAIRED
  ── ECDH + HKDF → AES-256-GCM session ──
encrypted frames [u32 len][12B IV][ciphertext]
  plaintext envelope [u8 channel][i64 pts µs][payload]
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
SNDCPY-1.0.0-setup.exe

# Standalone exe
SNDCPY-1.0.0.exe --host 192.168.1.42
```

## Known Issues

- iOS build requires Apple Developer account for device deployment (simulator works without)
- Windows MSI requires Inno Setup on build machine (handled in CI)
- Linux .deb/.rpm require `dpkg-dev`/`rpmbuild` (handled in CI)
- Audio on Android requires API 29+ (gracefully skipped on older)
- USB pairing method available on desktop (macOS, Linux, Windows) only; filtered out on iOS

## Links

- **Repository**: https://github.com/TheRealRexo/SNDCPY
- **Issues**: https://github.com/TheRealRexo/SNDCPY/issues
- **Documentation**: androidserver/README.md

---

*SNDCPY — "sound copy" — Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)*
