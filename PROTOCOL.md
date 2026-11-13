# SNDCPY Wire Protocol & Client Reference (v2)

This document is the single source of truth for every client implementation
(Android, iOS, Linux, Windows). All values are compatible with the shipped
Android server.

## Transport

- TCP, default port `27183`.
- `TCP_NODELAY` recommended on the client.

## Phase 1 — Plain-text handshake

Client sends one line:

```
HELLO SNDCPY <clientPublicKeyB64> <clientNonceB64>
```

- `<clientPublicKeyB64>` — X.509 `SubjectPublicKeyInfo` of an ECDH **P-256**
  key, base64 (standard, no line wrapping).
- `<clientNonceB64>` — 16 random bytes base64.

Server replies with one of:

```
SECURE <serverPublicKeyB64> <serverNonceB64>
PAIRING_REQUIRED <serverPublicKeyB64> <serverNonceB64>
ERROR <message>
```

When `PAIRING_REQUIRED`, the client shows a pairing method selector with 3 options:

### Pairing Methods (v1.0.1)

Three pairing methods are available, selectable from the client connection UI:

| Method | Description |
|--------|-------------|
| **Code** | 6-digit code entered via alert dialog. Client shows `QInputDialog` "Enter the 6-digit pairing code:" after receiving `PAIRING_REQUIRED`. The entered code is sent as `PAIR <code>`. |
| **Bluetooth** | Mutual confirmation — both sides must have matching 6-digit codes. After `PAIRING_REQUIRED`, client shows a confirm code field; sending the code completes pairing when the server also confirms. |
| **USB** | Desktop only (macOS, Linux, Windows). No code exchange; pairing is completed by connecting the devices via USB cable. iOS filters out this method. |

### After Pairing Selection

- **Code method**: Client shows alert `Enter the 6-digit pairing code:` → user enters code → sends `PAIR <code>` → server replies `PAIRED` or `REJECTED`
- **Bluetooth method**: Client shows confirm code field → user enters 6-digit code → sends `PAIR <code>` → both sides must confirm → server replies `PAIRED`
- **USB method**: No code exchange; pairing completes when USB connection is established; server replies `PAIRED`

Server replies `PAIRED` or `REJECTED`. (`PAIRED` proceeds; `REJECTED` closes.)

## Phase 2 — Session key derivation

Both sides compute the shared secret with **ECDH P-256**, then derive a 32-byte
AES-256-GCM key using **HKDF-SHA256** (RFC 5869):

```
PRK = HMAC-SHA256(key = salt, data = sharedSecret)
      where salt = clientNonce || serverNonce
OKM = HKDF-Expand(PRK, info = "SNDCPY-SESSION-KEY-v1", length = 32)
```

> **Canonical wire format**: X963 = `0x04||x||y` (65 bytes) base64; salt order is
> `clientNonce + serverNonce`; info = `SNDCPY-SESSION-KEY-v1`.

## Phase 3 — Encrypted frames

After the handshake all traffic uses length-prefixed AES-256-GCM frames:

```
[4-byte big-endian length] [12-byte IV] [ciphertext + 16-byte GCM tag]
```

- IV is random per frame.
- Plaintext is the **envelope** below.

## Envelope (inside each decrypted frame)

```
[1-byte channel] [8-byte big-endian PTS in µs] [payload bytes]
```

Channels:

| Code | Name                | Payload                         |
|------|---------------------|---------------------------------|
| 0x01 | CHANNEL_CONTROL     | UTF-8 command (see commands)    |
| 0x02 | CHANNEL_VIDEO_H264  | Raw H.264 Annex-B access unit   |
| 0x03 | CHANNEL_AUDIO_AAC   | AAC-LC access unit              |
| 0x04 | CHANNEL_TELEMETRY   | JSON string                     |

## Control commands (client → server, UTF-8)

### Multi-touch (v2 — REQUIRED for live touch)

```
POINTER_DOWN <pointerId> <x> <y>
POINTER_MOVE <pointerId> <x> <y>
POINTER_UP   <pointerId>
```

- `x`, `y` are **floating point** decimals in the *server's* screen pixel
  coordinates (0..screenWidth/Height). Clients must scale touch coordinates
  from their viewport to the server's resolution, or send exact integer pixels.
- `pointerId` is a client-assigned integer per concurrent finger (0, 1, 2, …).
- The server tracks each pointer and dispatches combined multi-stroke gestures,
  so **multi-touch works** (pinch, rotate, continuous drag).

### Legacy single gestures (still supported)

```
TAP <x> <y>
LONG_PRESS <x> <y>
SWIPE <x1> <y1> <x2> <y2> [durationMs]
SCROLL <UP|DOWN|LEFT|RIGHT> [<x> <y>]
HOME
BACK
RECENTS
KEY:<android-keycode>
```

### Text / keyboard passthrough

```
TEXT:<base64(utf8-chars)>
```

- The payload is the characters typed by the client's soft keyboard (including
  newlines/backspaces where supported).
- `` (0x08) in the decoded text is treated as a backspace by the server.
- The server inserts the text into the currently focused editable field on the
  Android device. On Android clients the app's own `EditText` stays focused with
  the platform keyboard visible; the client forwards the entered characters.
- Special keys: send `HOME`, `BACK`, `RECENTS`, or `KEY:<keycode>` as commands.

## Server replies

Server echoes control frames on the control channel (UTF-8) either as a relayed
message or `ERR rejected: <command>` on failure.

## Coordinate scaling

The server streams H.264 whose nominal size is whatever `Size` it configured.
Clients should:

1. Determine server resolution from the video format / aspect when possible.
2. Otherwise assume `width=1280, height=720` (the server default) and scale
   touch points proportionally: `serverX = touchX / viewW * serverW`.

## Building clients

- Android: MediaCodec for H.264; see `androidclient/`.
- iOS: CryptoKit for ECDH/HKDF/AES-GCM; AVSampleBufferDisplayLayer for H.264.
- Linux/Windows: `cryptography` (PyCryptodome compatible) — `pip install cryptography`.
