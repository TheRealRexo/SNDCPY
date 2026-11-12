# SNDCPY Windows Client

Windows desktop client for SNDCPY — mirrors and controls an Android device running the SNDCPY server.

> **Status:** 1.0.0 — official release. Built on top of the `0.1.0-beta` → `1.0.0-beta` progression that validated the wire protocol on this platform.

## Requirements

- Windows 10+ with Python 3.10+ (or use the prebuilt `.exe` from Releases)
- `pip install -r requirements.txt`

## Quick Start (Python)

```powershell
# 1. Find your Android device IP (shown in server app)
py -3 sndcpy_client.py --host 192.168.1.42 --port 27183

# First-time pairing
py -3 sndcpy_client.py --host 192.168.1.42 --pair-code 123456
```

## Quick Start (Prebuilt)

Download `sndcpy-windows-1.0.0.zip` from the [Releases](https://github.com/TheRealRexo/SNDCPY/releases) page, unzip, and run `SNDCPY.exe`.

## Commands After Connecting

```
TAP 500 800
SWIPE 100 500 900 500 200
SCROLL UP
HOME
BACK
RECENTS
KEY:4
PING
```

See `../androidserver/README.md` for full wire spec.
