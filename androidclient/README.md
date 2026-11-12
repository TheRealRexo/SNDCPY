# SNDCPY Android Client

Android client for SNDCPY — connects to the SNDCPY server on port 27183 and sends remote input commands.

> **Status:** 1.0.0 — official release. Progression `0.1.0-beta-ANDROIDCLIENT` → `1.0.0-beta-ANDROIDCLIENT` validated the wire protocol.

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
2. Open the client app, enter the IP, tap **Connect**.
3. First connect: server shows `PAIRING_REQUIRED` — send `PAIR <6-digit-code>`.
4. Send commands:

```
TAP 500 800
SWIPE 100 600 900 600 200
HOME
BACK
```

See `../androidserver/README.md` for the full command grammar.
