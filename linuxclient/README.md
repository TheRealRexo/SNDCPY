# SNDCPY Linux Client (v1.0.0)

Cross-platform Python client for SNDCPY — connects to the server on port 27183 and handles remote input.

> **Status:** 1.0.0 — official release. Built with Python 3.10+ and `cryptography` library.

## Build

```bash
cd linuxclient
pip install -r requirements.txt
python3 sndcpy_client.py --help
```

Packages: `cryptography`, `PyQt6` (for GUI clients).

## Usage

```bash
python3 sndcpy_client.py --host 192.168.1.42
```

## Pairing Methods (v1.0.0)

Three pairing methods are supported:

| Method | Description |
|--------|-------------|
| **Code** | 6-digit code entered via terminal input after `PAIRING_REQUIRED` |
| **Bluetooth** | Mutual confirmation — both sides must have matching 6-digit codes |
| **USB** | Desktop only — no code; connect via USB cable |

## Light/Dark Themes

- The Python client uses QPalette for light/dark theme switching (macOS build only).
- CLI mode has no theme.

## Known Issues

- USB method available on desktop (macOS, Linux, Windows) only
- Audio forwarding requires Android API 29+ on the server device

## Links

- **Repository**: https://github.com/TheRealRexo/SNDCPY
- **Issues**: https://github.com/TheRealRexo/SNDCPY/releases

---*

*SNDCPY — "sound copy" — Inspired by [scrcpy](https://github.com/Genymobile/scrcpy)*
