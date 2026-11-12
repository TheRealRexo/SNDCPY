# SNDCPY iOS Client

iOS client for SNDCPY — mirrors and controls an Android device running the SNDCPY server. Built with Swift + Network.framework (NWConnection).

> **Status:** 1.0.0 — official release. Validated through `0.1.0-beta` → `1.0.0-beta` progression against the Android server's wire protocol v2.

## Requirements

- iOS 16+ / macOS 13+ (Network.framework)
- Xcode 15+ / Swift 5.9+
- An Android device on the same Wi-Fi running `androidserver` (port 27183)

## Build & Run

```bash
# Open in Xcode
open Package.swift

# Or build via CLI
swift build
```

In the sample app, set `host` to the IP shown in the Android server app, then `connect()`:

```swift
let client = SNDCPYClient(host: "192.168.1.42", port: 27183)
client.onMessage = { print("<< \($0)") }
client.onStateChange = { print("state: \($0)") }
client.connect()
client.sendCommand("TAP 500 800")
```

## Pairing

First connect triggers `PAIRING_REQUIRED` on the server. Call:

```swift
client.send("PAIR 123456") // code shown on Android device
```

The server persists the fingerprint; subsequent connects go straight to `SECURE`.

## Commands

```
TAP x y
SWIPE x1 y1 x2 y2 [durMs]
SCROLL UP|DOWN|LEFT|RIGHT [x y]
HOME | BACK | RECENTS
KEY:<code>
SET_BITRATE <bps> | SET_FPS <fps> | GET_CONFIG
PING
```

See `../androidserver/README.md` for the full wire spec and envelope format.
