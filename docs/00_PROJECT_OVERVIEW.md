# 00 — Project Overview

> Single source of truth for *what* VideoCall is. Architecture, protocol, and testing
> details live in their own documents (see the index below).

## Project goal

A peer-to-peer video call app for Android that lets users connect **without any
central media server**:

- **Create Room** → generates a unique Room ID the user can share
- **Join Room** → anyone with the Room ID joins the same live call
- Audio + video flows **directly between phones** (WebRTC mesh), with only a tiny
  signaling broker relaying connection metadata

## Supported Android versions

| Item | Value |
|------|-------|
| minSdk | 24 (Android 7.0) |
| targetSdk | 36 |
| compileSdk | 36 (minor API level 1) |
| Architecture | arm64 (standard APK) |

## Tech stack

| Layer | Technology |
|-------|------------|
| App | Kotlin, AndroidX, Material 3, coroutines |
| Modules | `:app` (host/demo) + `:meshcall` (reusable SDK) |
| WebRTC | `io.github.webrtc-sdk:android:144.7559.09` |
| Signaling | Socket.IO client (`io.socket:socket.io-client:2.1.2`) |
| Broker | Node.js + Socket.IO v4 (reference impl in `03_SIGNALING_SERVER.md`) |
| Build | Gradle (AGP 9.2.1), Kotlin DSL |

## Current status

- **Working end-to-end on real devices** (same Wi-Fi): room creation/join, mesh
  signaling, local preview, remote video tiles, mic/camera toggles, end call.
- Known limitation: no STUN/TURN → media only flows on the same LAN (roadmap Phase 3).

## Features completed

- Lobby: Create Room (auto-generated 6-char code) with Copy / Share / Start modal
- Lobby: Join Room with shared code; configurable signaling server field; **Demo mode
  switch** (offline: drives the in-call UI with a simulated 6-peer room, no broker needed)
- In-call screen: room code badge (tap to copy), call timer, signaling connection
  banner, participants panel (names + live mic/camera state + count)
- **Responsive remote tile grid** (1–9 peers): non-overlapping cells, name chips,
  mic/camera-off badges, per-peer connection dots, avatar placeholders for
  camera-off peers; reflows on rotation/resize
- Front/back **camera switch** control
- Local camera preview (top-right, mirrored), mic / camera / end-call FAB controls
- Full-mesh WebRTC session (each phone ↔ every other phone, N-1 uplinks)
- Per-link video bitrate cap (500 kbps) for multi-participant mesh usability
- Runtime camera + mic permission flow
- Signaling: join/leave/reconnect, roster snapshot, SDP/ICE relay, peer media state
- Single dark Material 3 theme; cleartext `ws://` enabled for dev

## Features not yet implemented

- In-call text chat (WebRTC DataChannel or signaling relay)
- STUN/TURN support (calls across different networks)
- Video quality presets, per-peer disconnect toast
- Foreground service / background call, recording
- Incoming-call/deep-link invitations, authentication

All of the above are tracked as Jira-style stories in `06_BACKLOG.md`.

## Document index

| File | Responsibility |
|------|----------------|
| 00_PROJECT_OVERVIEW.md | Introduction (this file) |
| 01_ARCHITECTURE.md | Whole system: Android ↔ Node.js ↔ WebRTC |
| 02_ANDROID_APP.md | Android app internals only |
| 03_SIGNALING_SERVER.md | Node.js broker: rooms, lifecycle, deployment |
| 04_WEBRTC_FLOW.md | Learning/reference: offer/answer/ICE/media flow |
| 05_MESSAGE_PROTOCOL.md | Every Socket.IO event, payload, and direction |
| 06_BACKLOG.md | Jira stories with priority + acceptance criteria |
| 07_DEVELOPMENT_RULES.md | Rules so multi-dev / AI agents don't conflict |
| 08_TESTING.md | Test checklist + environments |
| 09_FUTURE_ROADMAP.md | Phased plan |

> Note: `docs/signaling_server_spec.md` (reference server code) and
> `docs/jira_stories.md` (earlier backlog draft) are superseded by this structure.
> Keep them until 03/06 absorb their content.
