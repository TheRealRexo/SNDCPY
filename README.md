> [!WARNING]
> **This GitHub repo (<https://github.com/TheRealRexo/SNDCPY>) is the only official
> source for SNDCPY. Do not download releases from random websites.**

# SNDCPY (v1.0.0)

<img src="androidserver/app/src/main/res/mipmap-hdpi/ic_launcher.png" width="128" height="128" alt="SNDCPY" align="right" />

_pronounced "**s**ou**nd** **c**o**py**"_

SNDCPY mirrors Android devices (video and audio) over Wi-Fi and allows control with a keyboard and mouse. Inspired by [scrcpy](https://github.com/Genymobile/scrcpy), it is built from scratch for low-latency, production-grade remote control on a local network.

[![Android Server](https://img.shields.io/badge/Android%20Server-1.0.0-brightgreen?style=for-the-badge&logo=android)](androidserver/)&nbsp;
[![Android Client](https://img.shields.io/badge/Android%20Client-1.0.0-blue?style=for-the-badge&logo=android)](androidclient/)&nbsp;
[![iOS Client](https://img.shields.io/badge/iOS%20Client-1.0.0-black?style=for-the-badge&logo=apple)](iosclient/)&nbsp;
[![Linux Client](https://img.shields.io/badge/Linux%20Client-1.0.0-orange?style=for-the-badge&logo=linux)](linuxclient/)&nbsp;
[![Windows Client](https://img.shields.io/badge/Windows%20Client-1.0.0-blue?style=for-the-badge&logo=windows)](windowsclient/)&nbsp;

It focuses on:

 - **lightness**: native where it matters, Python where it’s pragmatic
 - **performance**: 30~60 fps H.264, 128 kbps AAC, ~35–70 ms latency on a good LAN
 - **quality**: 720p by default, up to 1080p with `SET_BITRATE`
 - **low startup**: ~1 s to first frame after pairing
 - **non-intrusiveness**: nothing left installed on the controlled device after the server stops
 - **security**: ECDH + AES-GCM per-session encryption with one-time pairing codes

Its features include:
 - [audio forwarding](androidserver/README.md#streaming-pipeline) (Android 10+)
 - [screen recording](androidserver/README.md) via the H.264 stream
 - [mDNS discovery](androidserver/README.md#networking-model) (`_sndcpy._tcp.`)
 - [telemetry](androidserver/README.md) (fps, heap, uptime every 5 s)
 - [configurable quality](androidserver/README.md#streaming-pipeline) (`SET_BITRATE`, `SET_FPS`)
 - physical keyboard/mouse simulation via AccessibilityService
 - and more…

## Quick Start

### 1. Start the server (Android device)

```bash
# Build
cd androidserver
./gradlew assembleDebug

# Install (minSdk 26)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app → **Issue pairing code** → **Start screen share** → note the IP.

### 2. Connect a client

**Android**

```bash
cd androidclient
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# Enter the server IP in the app and tap Connect
```

**iOS / macOS**

```swift
// Swift Package
let client = SNDCPYClient(host: "192.168.1.42")
client.connect()
// or: open iosclient/Package.swift in Xcode and Run
```

**Linux**

```bash
cd linuxclient
pip install -r requirements.txt
python3 sndcpy_client.py --host 192.168.1.42
```

**Windows**

```powershell
cd windowsclient
py -3 sndcpy_client.py --host 192.168.1.42
```

First connect will ask for `PAIR <code>` — enter the 6-digit code shown on the Android device.

## Prerequisites

- Android device API 26+ (Android 8.0) for the server
- Audio forwarding needs API 29+ (Android 10+)
- Enable **Developer options → USB debugging** if you need `adb`; Wi-Fi control itself needs no USB after install
- Clients and server must be on the same Wi-Fi/LAN (port **27183**)

On some devices (notably Xiaomi) you may need to enable **USB debugging (Security Settings)** to allow input injection. Reboot after toggling.

## Get the App

- [Android Server](androidserver/) — APK from Releases or `assembleDebug`
- [Android Client](androidclient/) — APK from Releases or `assembleDebug`
- [iOS Client](iosclient/) — Swift Package, build in Xcode
- [Linux Client](linuxclient/) — `sndcpy_client.py`
- [Windows Client](windowsclient/) — `sndcpy_client.py`

Prebuilt `1.0.0` artifacts are on the [Releases](https://github.com/TheRealRexo/SNDCPY/releases) page.

## Must-Know Tips

- Lowering resolution greatly helps performance: send `SET_BITRATE 2000000` before starting capture
- `HOME` / `BACK` / `RECENTS` are global actions — reliable even when the foreground app blocks input
- `PING` → `PONG` keeps a session alive through idle timeouts
- If input stops working, re-enable the Accessibility service via **Enable remote input** in the server app

## Usage Examples

There are many options — see [androidserver/README.md](androidserver/README.md) for the full wire spec.

**Mirror at 720p, 30 fps (default) and send a tap:**

```bash
# linux / windows / mac (python)
python3 sndcpy_client.py --host 192.168.1.42
> TAP 500 800
```

**Swipe and scroll:**

```
> SWIPE 100 600 900 600 200
> SCROLL DOWN
> SCROLL UP 500 400
```

**Tune quality on the fly (applies on next capture start):**

```
> SET_BITRATE 4000000
> SET_FPS 60
> GET_CONFIG
```

**Pair a new client (first time only):**

```
>> HELLO SNDCPY
<< PAIRING_REQUIRED <serverKey> <nonce>
> PAIR 123456
<< PAIRED
```

## How It Works (v2 Wire Protocol)

```
TCP :27183
  HELLO SNDCPY <clientPub> <clientNonce>
    ├─ paired  → SECURE <serverPub> <serverNonce>
    └─ new     → PAIRING_REQUIRED … → PAIR <code> → PAIRED
  ── ECDH + HKDF → AES-256-GCM session ──
  encrypted frames [u32 len][12B IV][ciphertext]
    └─ plaintext envelope [u8 channel][i64 pts][payload]
         0x01 control  0x02 video-H264  0x03 audio-AAC  0x04 telemetry
```

See [androidserver/README.md](androidserver/README.md) for channel details.

## Repository Structure

```
SNDCPY/
├── androidserver/   → Android server (Kotlin, MediaProjection, MediaCodec)
├── androidclient/   → Android client (Kotlin, NW + handshake)
├── iosclient/       → iOS / macOS client (Swift, Network.framework)
├── linuxclient/     → Linux client (Python, sockets)
├── windowsclient/   → Windows client (Python, sockets)
├── README.md        → this file
└── LICENSE
```

Each folder is an independent build:

| Component | Build |
|-----------|-------|
| `androidserver` | `cd androidserver && ./gradlew assembleDebug` |
| `androidclient` | `cd androidclient && ./gradlew assembleDebug` |
| `iosclient` | `open iosclient/Package.swift` in Xcode or `swift build` |
| `linuxclient` | `pip install -r linuxclient/requirements.txt && python3 linuxclient/sndcpy_client.py --help` |
| `windowsclient` | `py -3 windowsclient/sndcpy_client.py --help` |

## Version Roadmap

Every component followed `0.1.0-beta` → `1.0.0-beta` to validate the wire protocol before the 1.0.0 official release.

**Server** `androidserver` — the reference implementation:

- `0.1.0-beta` Clean skeleton, deterministic Gradle, CI
- `0.2.0-beta` TCP server, discovery prototype, sessions
- `0.3.0-beta` Video prototype, basic input
- `0.4.0-beta` First APK in Releases
- `0.5.0-beta` **Security**: ECDH P-256, pairing codes, AES-GCM
- `0.6.0-beta` **Video**: MediaProjection → H.264, `StreamingBus`
- `0.7.0-beta` **Input**: AccessibilityService gestures
- `0.8.0-beta` **Discovery**: mDNS NSD + telemetry
- `0.9.0-beta` **Audio**: playback capture → AAC-LC
- `1.0.0-beta` **Polish**: back-pressure, `SET_BITRATE/FPS`, `RuntimeConfig`

**Clients** — each tracked the same betas so they all speak v2 at 1.0.0:

- `androidclient` `0.1.0-beta-ANDROIDCLIENT` → `1.0.0-beta-ANDROIDCLIENT`
- `iosclient` `0.1.0-beta-IOSCLIENT` → `1.0.0-beta-IOSCLIENT`
- `linuxclient` `0.1.0-beta-LINUXCLIENT` → `1.0.0-beta-LINUXCLIENT`
- `windowsclient` `0.1.0-beta-WINDOWSCLIENT` → `1.0.0-beta-WINDOWSCLIENT`

At **1.0.0**, all five speak the same encrypted, envelope-multiplexed protocol. Mix any client with any server 1.0.0 build.

## Resources

- [Server details](androidserver/README.md)
- [Android client](androidclient/README.md)
- [iOS client](iosclient/README.md)
- [Linux client](linuxclient/README.md)
- [Windows client](windowsclient/README.md)
- [Releases](https://github.com/TheRealRexo/SNDCPY/releases)
- Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)

## Contact

Open an [issue](https://github.com/TheRealRexo/SNDCPY/issues) for bugs or feature requests.

## License

```
Copyright 2024 TheRealRexo

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
```
