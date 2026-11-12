# SNDCPY Linux Client

Linux desktop client for SNDCPY — mirrors and controls an Android device running the SNDCPY server.

> **Status:** 1.0.0 — official release. Built on top of the `0.1.0-beta` → `1.0.0-beta` progression that validated the wire protocol on this platform.

## Requirements

- Python 3.10+
- `pip install -r requirements.txt` (only `cryptography` for secure mode, optional)

## Quick Start

```bash
# 1. Find your Android device IP (shown in server app)
# 2. Connect — basic handshake
python3 sndcpy_client.py --host 192.168.1.42 --port 27183

# With secure pairing (first time, provide code shown on device)
python3 sndcpy_client.py --host 192.168.1.42 --pair-code 123456

# Send a command interactively after connecting
> TAP 500 800
> SWIPE 100 500 900 500 200
> HOME
> BACK
```

## Protocol

Client initiates:

```
HELLO SNDCPY <clientPubKey> <clientNonce>   // secure mode
HELLO SNDCPY                                 // legacy fallback
```

Server replies `SECURE` / `PAIRING_REQUIRED` / `WELCOME`, then encrypted length-prefixed frames.

See `../androidserver/README.md` for full wire spec.
