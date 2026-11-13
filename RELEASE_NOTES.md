# SNDCPY v1.0.0 — Official Release

> Production-ready screen mirroring for Android — secure, low-latency, multi-platform.

## What's New

### Exactly 3 Pairing Methods (selectable per-client)

| Method | Platforms | How it works |
|--------|-----------|-------------|
| **Code** | All platforms | Enter a 6-digit pairing code when connecting |
| **Bluetooth** | All platforms | Mutual confirmation — both sides display and confirm the same 6-digit code |
| **USB** | Desktop only (macOS, Linux, Windows) | No code needed — plug in via USB cable |

> **iOS**: USB method is filtered out. Code and Bluetooth are available.

### Security Hardening

- **Approval-before-pairing-code flow** on the Android server: an incoming pairing request is rejected until the server user explicitly taps "Allow". The pairing code is never shown before approval.
- **Dependency pins** across both Android modules override vulnerable transitive versions: Netty 4.1.115, protobuf 3.25.5, Guava 33.3.1, jose4j 0.9.6, commons-io 2.16.1, xerces 2.12.2, JDOM 2.0.7.1. (All CVEs previously reported by Dependabot.)
- Wire protocol unchanged: ECDH P-256 + HKDF-SHA256 → AES-256-GCM per-session encryption.

### Theme Selector

- macOS / Linux clients: Light / Dark / System theme picker (remembered between sessions; switching system theme after selection still applies).
- Android server: follows system `Material3.DayNight` theme.
- iOS client: dark mode preferred (`preferredColorScheme(.dark)`).

### Android Server

- **Launcher icon** restored (`roundIcon` + `ic_launcher`).
- **Self-update from APK**: File Picker → install locally-picked APK without ADB.
- **Approval-before-pairing** dialog surfaces when an unknown device tries to connect.
- Minimum SDK 26 (Android 8.0), target SDK 34.

### Android Client

- 3-method pairing selector (Code / Bluetooth / USB buttons with visual highlight).
- Auto-discovery over LAN UDP beacon (`port 27184`) and mDNS (`_sndcpy._tcp.`).
- Touch surface with pointer events + keyboard passthrough.

### macOS Client

- PyInstaller `.app` bundle (`SNDCPY.app`) built for Apple Silicon.
- Light/Dark/System theme support with remembered selection.
- Qt6 font warnings eliminated (`Monaco` on macOS, `DejaVu Sans Mono` on Linux).

## Assets

| Asset | Platform | Notes |
|-------|----------|-------|
| `SNDCPY-Server-v1.0.0.apk` | Android (ARM) | Server + self-update |
| `SNDCPY-Client-v1.0.0.apk` | Android (ARM) | LAN discovery + 3-method pairing |
| `SNDCPY-macos-v1.0.0-arm64.zip` | macOS (Apple Silicon) | Standalone `.app` bundle |
| `SNDCPY-v1.0.0-arm64.dmg` | macOS (Apple Silicon) | Disk image installer |
| `SNDCPY-v1.0.0-arm64.pkg` | macOS (Apple Silicon) | Installer package |

> **Note:** Intel macOS builds, Linux `.deb/.rpm/AppImage`, and Windows `.exe`/MSI require their respective build machines and are not included in this local release.

## Installation

### Android Server

```bash
adb install SNDCPY-Server-v1.0.0.apk
# Open app → Allow pairing request → Start screen share → note the IP
```

### Android Client

```bash
adb install SNDCPY-Client-v1.0.0.apk
# Enter the server IP and tap Connect
```

### macOS

```bash
unzip SNDCPY-macos-v1.0.0-arm64.zip -d ~/Desktop
open ~/Desktop/SNDCPY.app --args --host 192.168.1.42
```

Or install from the DMG / PKG:

```bash
sudo installer -pkg SNDCPY-v1.0.0-arm64.pkg -target /
# or: open SNDCPY-v1.0.0-arm64.dmg and drag into Applications
```

## Quick API Reference

```
TCP :27183
  HELLO SNDCPY <cKey> <cNonce>
    ├─ paired  → SECURE <sKey> <sNonce>
    └─ new     → PAIRING_REQUIRED … → APPROVED → PAIR <code> → PAIRED
  ── ECDH + HKDF → AES-256-GCM session ──
encrypted frames [u32 len][12B IV][ciphertext]
  plaintext envelope [u8 channel][i64 pts µs][payload]
        0x01 control  0x02 video-H264  0x03 audio-AAC  0x04 telemetry
```

Common commands (sent as control envelopes):

```
TAP x y           SWIPE x1 y1 x2 y2 duration_ms
SCROLL UP|DOWN    HOME   BACK   RECENTS
KEY:<keycode>     SET_BITRATE bps
SET_FPS fps       GET_CONFIG   PING
```

## Known Issues

- **Intel macOS**: no prebuilt binary provided (build machine required).
- **Linux / Windows**: Python CLI clients available; GUI deb/rpm/AppImage/.exe builders need their respective CI machines.
- **Audio forwarding**: requires Android 10+ (API 29+); skipped gracefully on older devices.
- **Xiaomi devices**: enable "USB debugging (Security Settings)" under Developer options, then reboot.
- **iOS client**: unsigned device IPA requires Xcode; simulator builds work immediately.

## Links

- **Repository**: https://github.com/TheRealRexo/SNDCPY
- **Issues**: https://github.com/TheRealRexo/SNDCPY/issues
- **Protocol spec**: PROTOCOL.md

---

*SNDCPY — "sound copy" — Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)*
