# MeshCall — P2P Video Meetings for Android

> **Single source of truth.** This is the only doc in the repo: overview, architecture,
> wire protocol, status tracker, backlog, and rules. Update it in the same commit as the
> code it describes.

Android video meetings with **no media server**. Every participant sends their own camera
and microphone directly to every other participant over WebRTC. A small Node.js broker
relays connection metadata only — it never sees a single video frame.

---

## 1. How many people fit?

"P2P" here means *no media server*, not *two people*. It is a **full mesh**: everyone
connects directly to everyone else. But the mesh has a real ceiling, and it is worth
knowing exactly where it is.

With N participants, each phone holds N−1 connections and **uploads its own video N−1
times** — it never relays anyone else's:

| People | Uplinks each | Upload @ 500 kbps cap | Concurrent encoders |
|--------|--------------|-----------------------|---------------------|
| 3 | 2 | ~1.0 Mbps | 2 |
| 4 | 3 | ~1.5 Mbps | 3 |
| 5 | 4 | ~2.0 Mbps | 4 |
| 6 | 5 | ~2.5 Mbps | 5 |
| 8 | 7 | ~3.5 Mbps | 7 |

Bandwidth is the lesser problem. The wall is **hardware encoders**: each PeerConnection
runs its own encoder instance, and most Android SoCs support only ~2–4 concurrent
sessions. Past that the stack falls back to software encoding — the phone heats up,
framerate collapses, the battery drains.

- **Design target: 3–4 participants.** Comfortably inside every limit above.
- **5–6** works, with noticeably warm phones.
- **7+** needs an **SFU** (each phone uploads once, the server fans out) — an architecture
  change, planned for later, not a tuning exercise.

The UI is already built for the larger case regardless: four main tiles plus an overflow
strip of avatar chips, with pinned and actively-speaking participants promoted into the
grid.

---

## 2. Modules

```
:meshcall   The SDK (dev.meshcall.sdk) — WebRTC engine, signaling, mesh, and the whole meeting UI
:app        Host app (com.example.videocall) — lobby, meeting creation, permissions
server/     Node.js signaling broker (VC-019) — separate repo: VideoSDKServer/server/
```

**The SDK owns the meeting screen.** Everything a meeting *looks* like — video grid,
meeting-code badge, call timer, connection banner, participants panel, and the mic /
camera / switch-camera / share / more / leave controls — is `MeshMeetingView`. The app
keeps only what genuinely belongs to a host: runtime permissions, identity, navigation,
and the lobby.

### Integrating is three calls

```kotlin
// activity_main.xml is just: <dev.meshcall.sdk.ui.MeshMeetingView android:id="@+id/meeting_view" ... />

val meetingView = findViewById<MeshMeetingView>(R.id.meeting_view)
meetingView.onLeave = { finish() }

val call = MeshCall(applicationContext)
call.join(brokerUrl = "wss://…", meetingId = "ABC123", displayName = "Ada")
meetingView.attach(call, "ABC123")

// onDestroy
meetingView.detach()
call.dispose()
```

`MeshMeetingView` is built from platform views only — no Material dependency — so it
inflates under any host theme.

### Public API

| Type | Purpose |
|------|---------|
| `MeshCall` | Session: `join(brokerUrl, meetingId, displayName, config)`, `joinDemo(...)`, `leave()`, `dispose()`, `toggleMic()`, `toggleCamera()`, `switchCamera()`, `setMic/setCamera` |
| `MeshCall` flows | `participants`, `speaker`, `connected`, `localMedia`, `state`, `errors` — stable across joins; safe to collect before or after `join` |
| `MeshMeetingView` | The complete meeting screen. `attach(call, meetingId, showConnectionBanner)`, `detach()`, `leaveNow()`, callbacks `onLeave` / `onShareScreen` / `onMoreOptions`, flag `confirmBeforeLeaving` |
| `MeshParticipantGrid` | Just the tile grid, for building a custom meeting screen. `bind`/`unbind`/`release`, `setPinned`, `setSpeaker`, `setLocalMediaState`, `onPinRequest` |
| `MeshVideoRenderer` | `SurfaceViewRenderer` subclass, inflatable from XML |
| `MeshCallConfig` | Resolution, frame rate, bitrate cap, initial mic/camera, **ICE servers (STUN/TURN)** |
| `IceServerConfig` | One STUN/TURN entry (`urls`, `username`, `credential`) |
| `MeshParticipant` | Roster entry: `id`, `userName`, `micEnabled`, `cameraEnabled`, `connectionState` |
| `LocalMediaState` | The SDK's authoritative mic/camera state — bind controls to this, never to a local mirror |
| `LocalIdentityProvider.userId` | Opaque per-user id. Derive from your auth backend in production |

### Internals (`dev.meshcall.sdk.internal`, not supported API)

| Package | Contents |
|---------|----------|
| `webrtc/` | `MeshWebRtcEngine` (shared factory + EGL, capture, N peer connections, offer/answer/ICE), `SdpTransform` (bitrate-cap rewriting) |
| `signaling/` | `SignalingClient`, `SocketIOSignalingClient`, `SignalEvent`, `SignalingSchema` |
| `mesh/` | `MeshMeetingManager` — normalizes signaling + media into flows |
| `media/` | `MediaConfig` — internal mirror of `MeshCallConfig` |
| `demo/` | `MockSignalingClient`, `MockMeetingData` — offline demo mode only |
| `util/` | `MeshLog` — everything logs under the `MeshCall/*` tag |

---

## 3. Architecture

### Phase A — Signaling (before media)

```
Android A                Node.js Broker                Android B
    │                          │                           │
    │  join-meeting (WebSocket)│                           │
    ├─────────────────────────►│                           │
    │                          │   peer-joined broadcast   │
    │                          ├──────────────────────────►│
    │ meeting-members (roster) │  meeting-members (roster) │
    │◄─────────────────────────┼──────────────────────────►│
    │                          │                           │
    │  offer / answer / ice-candidate  (relayed)           │
    │◄────────────────────────►│◄─────────────────────────►│
```

### Phase B — Media (after signaling)

```
Android A ══════════════════════════════════════════════► Android B
              WebRTC (SRTP audio/video, direct P2P)
```

The broker is **never in the media path**.

### Negotiation is glare-free by construction

`MeshMeetingManager.shouldOffer` — **the peer with the lexicographically lower `userId`
sends the offer**; the other waits. No rollback, no glare handling. Do not break this.

### Data flow

```
LobbyActivity ──(meetingId, broker, name)──► MainActivity
                                                  │
                              permissions → MeshCall.join()
                                                  │
                                MeshMeetingManager (orchestrator)
                                  ├── MeshWebRtcEngine       (local media + N PeerConnections)
                                  └── SocketIOSignalingClient (broker)
                                                  │
                                          MeshMeetingView
                                  ├── MeshParticipantGrid ("You" tile + one per peer + overflow)
                                  └── chrome: code badge, timer, banner, participants, controls
```

### Threading

SDK orchestration is confined to `Dispatchers.Main.immediate`. WebRTC callbacks arrive on
binder threads and funnel in through flows. Audio-level sampling runs on the WebRTC audio
thread into a `ConcurrentHashMap`. Camera `startCapture`/`stopCapture` **block**, so they
run on a dedicated single-thread executor.

---

## 4. Wire protocol (Android ↔ Node.js)

> **This is the contract the Node backend must implement.** Changes land here in the same
> commit as the code, and `SignalingSchema.kt` is updated alongside.

- Transport: **Socket.IO v4**, default namespace `/`, JSON **objects** (never arrays).
- The event name *is* the message type. There is no `{type, payload}` envelope.
- Every server→client relay carries `from` = the sender's `userId`.
- Relay rule: payload has `to` → deliver to that user's sockets only; no `to` → broadcast
  to the meeting.
- Additive fields only. Clients ignore unknown keys.

### Client → Server

| Event | Payload | Notes |
|-------|---------|-------|
| `join-meeting` | `{ meeting, userId, userName }` | Sent on **every** connect *and* reconnect. Server replies `meeting-members` and broadcasts `peer-joined`. |
| `offer` | `{ to, sdp: { type: "offer", sdp } }` | Relay to `to`, inject `from`. |
| `answer` | `{ to, sdp: { type: "answer", sdp } }` | Relay to `to`, inject `from`. |
| `ice-candidate` | `{ to, candidate: { candidate, sdpMLineIndex, sdpMid } }` | Relay to `to`, inject `from`. |
| `peer-state` | `{ state: { micEnabled, cameraEnabled } }` | `to` optional; absent = broadcast. |

### Server → Client

| Event | Payload | Notes |
|-------|---------|-------|
| `meeting-members` | `{ meeting, peers: [ { userId, userName } ] }` | Roster after join/rejoin. Peer key is **`userId`**. |
| `peer-joined` | `{ userId, userName, meeting }` | Broadcast. |
| `peer-left` | `{ peerId }` | Broadcast on disconnect. |
| `offer` / `answer` | `{ from, sdp: {...} }` | Forwarded. |
| `ice-candidate` | `{ from, candidate: {...} }` | Forwarded. |
| `peer-state` | `{ from, state: {...} }` | Forwarded. |
| `error` | `{ error }` | Fatal signaling error worth surfacing. |

### Broker responsibilities

1. Ignore events from sockets that never sent `join-meeting`.
2. Track socket → meeting and socket → userId. Multiple sockets per userId are allowed.
3. Relay `offer` / `answer` / `ice-candidate` / `peer-state`, always injecting `from`.
4. On `disconnect`: broadcast `peer-left`, drop the socket from its meeting.
5. On reconnect the client re-sends `join-meeting` — re-broadcast presence and reply with
   the roster **including the re-joiner**.
6. Never send UI-specific payloads. Never inspect SDP.

### SDK mapping

| Protocol event | Kotlin (`SignalEvent`) |
|----------------|------------------------|
| `meeting-members` | `MeetingSnapshot(peers, meetingId)` |
| `peer-joined` / `peer-left` | `PeerJoined` / `PeerLeft` |
| `offer` / `answer` | `Offer(from, SdpPayload)` / `Answer(from, SdpPayload)` |
| `ice-candidate` | `IceCandidate(from, IceCandidatePayload)` |
| `peer-state` | `PeerState(from, PeerStatePayload)` |
| `error` | `ErrorReceived(message)` |
| socket disconnect | `SignalingDisconnected` |

Constants: `SignalingSchema.kt` (`TYPE_*`, `KEY_*`).

---

## 5. Running it

| Item | Value |
|------|-------|
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| Build | Gradle + AGP 9.2.1, Kotlin DSL |
| WebRTC | `io.github.webrtc-sdk:android:144.7559.09` |
| Signaling | `io.socket:socket.io-client:2.1.2` |

### Offline (no broker needed)

Lobby → **Create** or **Join** → flip the **Demo mode** switch. `MockSignalingClient`
simulates a 6-peer meeting with roster churn, mute/unmute, and a rotating speaker.
Connections deliberately stay in `connecting`, which exercises the placeholder and
connection-dot UI.

### With a broker

1. Run the broker on port 3000: `cd VideoSDKServer/server && npm install && npm start`
   (separate repo, VC-019 — see its README for details and a smoke test).
2. Emulator: `adb reverse tcp:3000 tcp:3000`, use `ws://127.0.0.1:3000` (or `ws://10.0.2.2:3000`).
3. Real devices: same Wi-Fi, `ws://<PC-LAN-IP>:3000`, firewall open on TCP 3000.
4. Both devices join the same meeting code.

```bash
# real broker
adb shell am start -n com.example.videocall/.MainActivity \
  -e broker ws://10.0.2.2:3000 -e meeting DEMO01 -e name Alice

# offline demo
adb shell am start -n com.example.videocall/.MainActivity --ez demo true --ei peers 8
```

### Configuring ICE (STUN/TURN)

```kotlin
call.join(
    brokerUrl = "wss://signaling.example.com",
    meetingId = "ABC123",
    displayName = "Ada",
    config = MeshCallConfig(
        iceServers = listOf(
            IceServerConfig("stun:stun.l.google.com:19302"),
            IceServerConfig("turn:turn.example.com:3478", username = "u", credential = "p"),
        ),
    ),
)
```

Defaults to Google's public STUN. Pass `iceServers = emptyList()` for LAN-only host
candidates. **Cross-network calls need a TURN server** — STUN alone will not traverse
symmetric NAT.

---

## 6. Status tracker

### Done

| ID | Feature | Notes |
|----|---------|-------|
| VC-001 | Camera switch (front/back) | No renegotiation |
| VC-002 | Per-peer mute / camera-off badges | Driven by `peer-state`; survives roster rebuilds |
| VC-003 | Participant list | Panel with live state and count, excludes self |
| VC-005 | Meeting timer | `hh:mm:ss` next to the code badge |
| VC-006 | **STUN/TURN configuration** | `MeshCallConfig.iceServers`; Google STUN default |
| VC-009 | Responsive tile grid + landscape | Non-overlapping cells, reflows on resize |
| VC-011 | Copy meeting code in-meeting | Tap the badge |
| VC-016 | Active-speaker detection | RMS from remote audio tracks; speaker promoted into the grid |
| VC-017 | Pin a participant | Tap a tile or overflow chip |
| VC-018 | Overflow strip | Participants beyond 4 main slots become avatar chips |
| VC-020 | **Meeting UI moved into the SDK** | `MeshMeetingView` owns the screen; the app is lobby + permissions |
| VC-019 | **Node.js signaling broker** | Implements §4 exactly; in-memory, single-process, Socket.IO v4. See `VideoSDKServer/server/` (separate repo) |

### Partial

| ID | Feature | Remaining |
|----|---------|-----------|
| VC-004 | Connection status indicator | Per-peer ICE dots + broker banner ship; **peer disconnect/reconnect toast** pending |

### Not started

| ID | Feature | Priority | Notes |
|----|---------|----------|-------|
| VC-012 | Foreground service / background meeting | High | Survive screen lock; Android 14+ FGS type `camera\|microphone` |
| VC-014 | Actionable error surfacing | High | Distinct dialogs: server unreachable (with retry), camera in use, mic denied |
| VC-007 | In-meeting text chat | Medium | Signaling relay first, DataChannel later (additive event) |
| VC-010 | Deep-link invitations | Medium | `videocall://join/<code>` prefills Join |
| VC-008 | Video quality presets | Low | 360p / 480p / 720p without dropping the meeting |
| VC-013 | Recording | Low | Depends on VC-012 |
| VC-015 | Auth / user profiles | Low | Persisted display name; stable `userId` across sessions |
| VC-021 | SFU migration | Later | Only if meetings need to exceed ~6 participants (§1) |

### Known limitations

- Cross-network calling needs a **TURN** server you supply.
- Mesh ceiling ~5–6 participants; design target is 3–4 (§1).
- No speaker/earpiece audio routing control.
- `ws://` cleartext is enabled for dev. Use `wss://` in production and drop
  `usesCleartextTraffic`.
- No automated tests. Verification is demo mode plus the manual checklist in §8.

---

## 7. Roadmap

| Phase | Contents | Exit criteria |
|-------|----------|---------------|
| 1 ✅ | Lobby, full-mesh WebRTC, tiles, toggles, leave | Two phones on one LAN hold a meeting |
| 2 ✅ | Meeting UX: camera switch, badges, participant list, grid, pin, active speaker | 8-peer demo stable in portrait + landscape |
| 3 ✅ | SDK owns the meeting UI; STUN/TURN; correctness pass | App integrates in three calls |
| 4 ▶ | Node.js broker (VC-019) ✅, foreground service, error surfacing | Two real phones meet over the internet through the broker + TURN |
| 5 | Text chat, deep links, quality presets, recording | Chat stable in a 4-person mesh |
| 6 | Auth, captions, screen share, SFU if needed | Meetings beyond 6 participants |

---

## 8. Development rules

Mandatory, not suggestions.

1. **Module boundaries.** `:app` holds no signaling or meeting-UI logic. `:meshcall` holds
   no `com.example` imports. Engine code stays in `internal/webrtc`, signaling in
   `internal/signaling`, orchestration in `internal/mesh`.
2. **New meeting UI goes in the SDK**, not the app. The app is lobby, permissions,
   navigation. If the host needs to customize, add a callback or a flag to
   `MeshMeetingView`.
3. **Protocol changes update §4 in the same commit**, along with `SignalingSchema.kt`.
4. **No breaking protocol changes.** Existing events keep their names and payloads.
   Breaking changes need a new event name (`join-meeting-v2`) or a documented version bump.
5. **Keep the deterministic offer rule** (lower `userId` offers). No both-sides-offer races
   without a glare-handling plan.
6. **SurfaceView hygiene.** Never put `elevation`, an opaque background, or `clipToOutline`
   directly on a `SurfaceViewRenderer` in XML — the background composites *above* the video
   and hides it. Tile chrome belongs in a sibling view on the window plane.
7. **The SDK stays theme-independent.** `MeshMeetingView` uses platform views only, so it
   inflates in any host app. Don't introduce a Material dependency into `:meshcall`.
8. **No secrets in the repo.** Broker URLs stay constants/extras; keystores are gitignored.
9. **Vocabulary is "meeting", never "room"** — in code, resources, protocol, and UI.
10. **Every feature updates this file** — the §6 tracker at minimum.

### Android pitfalls already paid for

1. `elevation` + opaque background + `clipToOutline` on a `SurfaceViewRenderer` hides the video.
2. `ws://` requires `android:usesCleartextTraffic="true"` on Android 9+.
3. `findViewById<Button>` on a `FloatingActionButton` throws `ClassCastException` at runtime.
4. `videoCapturer.stopCapture()` **blocks** — never call it on the main thread.
5. With `UNIFIED_PLAN`, `onAddStream` is a legacy shim. Handle `onTrack`/`onAddTrack` too,
   and dedupe, or remote video is unreliable across WebRTC revisions.
6. `b=AS:` must sit **inside** a media section, directly after its `c=` line. Appending it
   to the end of the SDP is silently ignored — the bitrate cap simply never applies.
7. `addIceCandidate` before `setRemoteDescription` is rejected. Candidates and SDP race
   over the same socket, so inbound candidates must be buffered until the remote
   description lands, or ICE never completes.
8. A `SurfaceViewRenderer` accumulates sinks: calling `addSink` on every roster update
   delivers each frame N times. Track the bound track and remove before re-adding.
9. Creating the camera track lazily on first toggle means it is missing from every
   already-negotiated PeerConnection. Create it up front, even when starting camera-off,
   and toggle with `setEnabled` + start/stopCapture.
10. Putting a background on a scroll container that wraps a toggled child leaves an empty
    floating pill when the child is `GONE`. Style the child instead.

---

## 9. Testing

### Functional checklist

**Meeting flow** — create generates a 6-char code · code dialog Copy/Share/Start work ·
join with the code connects both phones · empty code shows validation · broker unreachable
surfaces the banner (not silence) · custom server URL accepted.

**In meeting** — permission prompt on first launch · denial shows a toast and exits
cleanly · local "You" tile visible · offer/answer exchanged · ICE reaches CONNECTED ·
remote video visible · remote audio audible · mic toggle updates the remote badge · camera
toggle likewise · switch camera does not drop the meeting · tap a tile to pin · active
speaker gets the ring and is promoted · 5th+ participant appears in the overflow strip ·
leave removes the tile remotely · leaving frees resources with no leak.

**Robustness** — peer joins mid-meeting · peer leaves mid-meeting · network drop
reconnects and resumes · re-joining the same meeting is a no-op · ICE failure re-links
(bounded to 3 attempts) · 10× rapid create→leave leaks no sockets.

### Environments

| Environment | Setup |
|-------------|-------|
| Demo mode | No broker. Exercises all UI paths including 8-peer overflow. |
| Emulator | `ws://10.0.2.2:3000` or `adb reverse tcp:3000 tcp:3000`. Virtual camera only. |
| LAN (primary) | Two real phones + broker PC on one Wi-Fi. Verify both create/join orders, and 3 phones in one meeting. |
| Cross-network (final gate) | One phone on mobile data, one on Wi-Fi, TURN configured. |

### Logcat

Everything the SDK logs is tagged `MeshCall/*` — filter on `MeshCall` to see the whole
session, or narrow to `MeshCall/Mesh`, `MeshCall/Engine`, `MeshCall/Signaling`,
`MeshCall/Grid`, `MeshCall/MeetingView`. Set `MeshLog.verbose = false` to silence
everything below WARN.

Also useful: `IceConnectionState`, `webrtc/CameraStatistics`, `SurfaceEglRenderer`.

### Bug report template

```
Environment: two real phones (models/OS) / emulator / demo mode
Meeting code:
Steps:
Expected:
Actual:
Logcat (MeshCall/* excerpt):
```
