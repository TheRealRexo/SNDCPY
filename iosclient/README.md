# SNDCPY iOS Client (v1.0.0)

iOS client for SNDCPY — connects to the SNDCPY server on port 27183 and sends remote input commands.

> **Status:** 1.0.0 — official release. Built with Xcode 16.4, SwiftUI interface.

## Build

```bash
cd iosclient
xcodebuild -sdk iphoneos -configuration Release build
# Or open iosclient/SNDCPYClient.xcodeproj in Xcode and run
```

Requires Xcode 16.4+, iOS 16.0+.

## Use

1. Start the server on another Android device and note its IP.
2. Open the iOS client app, select a **pairing method**, and enter the IP.
3. Tap **Connect**. First connect will show pairing method selector:
   - **Code**: 6-digit code entered via `QInputDialog` alert
   - **Bluetooth**: mutual confirmation with matching 6-digit codes
   - **USB**: filtered out (desktop only feature)
4. Send commands via the touch interface or hardware keyboard.

## Pairing Methods (v1.0.0)

Three pairing methods are supported:

| Method | Platforms | Description |
|--------|-----------|-------------|
| **Code** | iOS, macOS, Android, Linux, Windows | 6-digit code entered via alert (`QInputDialog`) |
| **Bluetooth** | iOS, macOS, Android, Linux, Windows | Mutual confirmation — both sides must have matching codes |
| **USB** | macOS, Linux, Windows only | USB cable connection — no code needed (iOS filtered out) |

> **iOS**: USB method is filtered out. Code and Bluetooth methods are available.

## Light/Dark Theme

- **iOS client**: `preferredColorScheme(.dark)` — default dark mode (Liquid Glass styling)
- **macOS client**: QPalette-based automatic light/dark switching (Liquid Glass style)

## Known Issues

- iOS build requires Apple Developer account for device deployment (simulator works without)
- USB method available on desktop only (macOS, Linux, Windows); filtered out on iOS

## Links

- **Repository**: https://github.com/TheRealRexo/SNDCPY
- **Issues**: https://github.com/TheRealRexo/SNDCPY/issues

---*

*SNDCPY — "sound copy" — Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)*
