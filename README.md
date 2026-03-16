SNDCPY – Android Remote Control Server

SNDCPY is a Wi-Fi based Android remote control server inspired by scrcpy.

This repository contains the Android server component responsible for:

- Screen streaming
- Audio streaming (planned)
- Remote input injection
- Secure pairing (planned)
- Local network discovery
- Telemetry reporting (planned)

The goal is to build a production-grade, low-latency remote control system designed with modular architecture and long-term scalability in mind.

## Project Objectives

- Low-latency video streaming
- Secure authenticated remote control
- Modular internal architecture
- Clean separation of responsibilities
- Production-ready foundation (not a prototype)
- Deterministic build system
- CI-ready repository

## System Architecture

The server is divided into clearly separated domain modules:

`app/src/main/java/com/example/remotecontrolserver/`

- `server/` → Connection lifecycle & session management
- `streaming/` → Video encoding/streaming prototype pipeline
- `input/` → Basic input command handling
- `discovery/` → Local network discovery prototype
- `runtime/` → Wiring and lifecycle orchestration
- `security/` → Pairing & encryption (**planned**)
- `telemetry/` → Device metrics & system stats (**planned**)

Each module remains isolated and communicates through defined boundaries to prevent architectural drift.

## High-Level System Flow

1. Server runtime starts on Android device.
2. Discovery prototype advertises local IPv4 endpoints.
3. TCP control server listens for client connections.
4. Session is established on incoming connection.
5. Video prototype frame loop starts.
6. Basic input commands are accepted/rejected.

## Networking Model

### Current Transport (Implemented)

- TCP sockets (`server/TcpControlServer`)

### Planned Future Transport

- QUIC
- Multiplexed framed transport

### Logical Channels

- Control → Session + handshake
- Video → prototype frame packets
- Audio → planned
- Input → remote touch / keyboard commands
- Telemetry → planned JSON system metrics

Future upgrade targets:

- Binary protocol
- Frame-based message envelope
- Back-pressure handling

## Security Model

Security is mandatory.

Implemented baseline:

- Handshake gate on control channel (`HELLO SNDCPY`)

Planned model:

- Pairing-based trust establishment
- Ephemeral session keys
- AES-GCM encrypted transport
- Device identity persistence
- No unauthenticated remote control

Future enhancements:

- Key rotation
- Mutual authentication
- Session resume tokens

## Streaming Pipeline

### 0.3.0 Prototype

Video prototype:

`VideoStreamingPrototype` emits periodic `VideoFramePacket` frames to validate lifecycle and scheduling boundaries.

### Planned full implementation

Video pipeline:

`Surface → MediaCodec (H.264) → Packetizer → Socket transport`

Audio pipeline:

`AudioRecord → AAC encoder → Packetizer → Socket transport`

## Remote Input Injection

### 0.3.0 Prototype Support

`BasicInputInjector` currently accepts:

- `TAP`
- `SWIPE`
- `SCROLL`
- `HOME`
- `BACK`
- `KEY:<keycode>`

Input injection remains restricted to authenticated sessions in planned secure versions.

## Version Roadmap Status

### 0.1.0 (Completed)

- Clean architecture skeleton
- Deterministic Gradle build
- Module separation
- CI-ready foundation

### 0.2.0 (Completed)

- Basic TCP server (`server/TcpControlServer`)
- Local discovery prototype (`discovery/LocalDiscoveryPrototype`)
- Session establishment (`server/SessionManager` + `server/Session`)

### 0.3.0 (Completed)

- Video streaming prototype (`streaming/VideoStreamingPrototype`)
- Basic input injection (`input/BasicInputInjector`)

### 0.5.0 (Planned)

- Secure pairing layer
- Encrypted session transport

### 1.0.0 (Planned)

- Production-grade low-latency remote control server

## Build Instructions

Run:

```bash
./gradlew assembleDebug
```

Requirements:

- JDK 17+
- Android SDK (compileSdk 34)

## Repository Structure

```text
SNDCPY/
├── app/
│   └── src/main/java/com/example/remotecontrolserver/
│       ├── discovery/
│       ├── input/
│       ├── runtime/
│       ├── server/
│       └── streaming/
├── gradle/
├── gradlew
└── gradlew.bat
```
