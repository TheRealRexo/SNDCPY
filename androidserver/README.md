# SNDCPY – Android Remote Control (v1.0.0)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Version](https://img.shields.io/badge/version-1.0.0-brightgreen.svg)](https://github.com/TheRealRexo/SNDCPY/releases)
[![Platform](https://img.shields.io/badge/platform-android-green.svg)](https://developer.android.com)
[![Twitter](https://img.shields.io/twitter/url/https/?label=%23Android%20&style=social)](https://twitter.com/intent/tweet?text=Check%20out%20SNDCPY%20%23Android%20Remote%20Control)

SNDCPY is a Wi‑Fi based Android remote control server, inspired by [scrcpy](https://github.com/Genymobile/scrcpy).

## What it does

Mirror and remotely control an Android device from another device over local Wi‑Fi.

- **Screen streaming** – H.264 encoded video via MediaProjection
- **Audio streaming** – AAC playback capture (Android 10+)
- **Remote input** – Accessibility‑service gestures (tap, swipe, scroll, global actions)
- **Secure pairing** – ECDH P‑256 identity, 3 pairing methods (Code/Bluetooth/USB), persisted fingerprints
- **Local discovery** – mDNS/Bonjour (`_sndcpy._tcp.`) via Android NSD
- **Telemetry** – JSON snapshots (fps, sessions, heap, uptime) every 5 s

## Quick start

```bash
# Build the server APK
./gradlew assembleDebug

# Install on an Android device (minSdk 26)
adb install app/build/outputs/apk/debug/app-debug.apk

# Run the server app on the device
#   • Select pairing method: Code / Bluetooth / USB
#   • For Code method: tap "Issue pairing code" → enter 6-digit code on client
#   • For Bluetooth method: mutual confirmation with matching codes
#   • For USB method: connect device via USB cable
#   • Tap "Start screen share" to begin streaming
```
