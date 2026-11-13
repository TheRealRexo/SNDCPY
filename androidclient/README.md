# SNDCPY Android Client (v1.0.1)

Android client for SNDCPY — connects to the SNDCPY server on port 27183 and sends remote input commands.

> **Status:** 1.0.1 — official release. Progression `0.1.0-beta-ANDROIDCLIENT` → `1.0.1-beta-ANDROIDCLIENT` validated the wire protocol and all 3 pairing methods.

## Build

```bash
cd androidclient
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# or: build/outputs/apk/debug/androidclient-debug.apk (single-module layout)
```

Requires JDK 17+, Android SDK 34.

## Use

1. Start the server on another Android device and note its IP.
2. Open the client app, select a **pairing method**, and enter the IP.
3. Tap **Connect**. First connect will show pairing method selector:
   - **Code**: 6-digit code entered via alert dialog
   - **Bluetooth**: mutual confirmation with matching 6-digit codes
   - **USB**: (desktop only) connect via USB cable
4. Send commands:

```
TAP 500 800
SWIPE 100 600 900 600 200
HOME
BACK
```

> **Pairing methods available**: Code (all platforms), Bluetooth (mutual confirm), USB (desktop only; filtered on iOS).

See `../androidserver/README.md` for the full command grammar.

## Pairing Methods (v1.0.1)

Three pairing methods are supported:

| Method | Platforms | Description |
|--------|-----------|-------------|
| **Code** | Android, iOS, macOS, Linux, Windows | 6-digit code entered via alert dialog when connecting |
| **Bluetooth** | Android, iOS, macOS, Linux, Windows | Mutual confirmation — both sides must have matching 6-digit codes |
| **USB** | macOS, Linux, Windows only | USB cable connection — no code needed |

> **iOS**: USB method is filtered out. Code and Bluetooth methods are available.

## Light/Dark Themes

- **Android client**: Follows system theme (Material3.DayNight)
- **iOS client**: `preferredColorScheme(.dark)` — default dark mode
- **macOS client**: QPalette-based automatic light/dark switching

## Known Issues

- iOS build requires Apple Developer account for device deployment (simulator works without)
- USB pairing method available on desktop (macOS, Linux, Windows) only; filtered out on iOS
- First connect requires pairing method selection before code entry

## Links

- **Repository**: https://github.com/TheRealRexo/SNDCPY
- **Issues**: https://github.com/TheRealRexo/SNDCPY/issues

---*

*SNDCPY — "sound copy" — Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)*
