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
times** — it never relays anyone else's. A fixed per-link cap therefore multiplies: five
people at 1 Mbps each is 4 Mbps off one phone, well past a normal uplink, and congestion
control then claws it back unevenly until every tile goes soft at once.

So the cap is not fixed. The SDK splits an **uplink budget** (3 Mbps by default) across the
live links and steps capture resolution down with it, keeping bits-per-pixel roughly
constant — a smaller, sharp picture instead of a mushy 720p one. It retunes the instant
somebody joins or leaves:

| People | Uplinks each | Per link | Encoded at | Upload | Concurrent encoders |
|--------|--------------|----------|------------|--------|---------------------|
| 2 | 1 | 1000 kbps | 720p | ~1.0 Mbps | 1 |
| 3 | 2 | 1000 kbps | 720p | ~2.0 Mbps | 2 |
| 4 | 3 | 1000 kbps | 720p | ~3.0 Mbps | 3 |
| 5 | 4 | 750 kbps | 540p | ~3.0 Mbps | 4 |
| 6 | 5 | 600 kbps | 540p | ~3.0 Mbps | 5 |
| 8 | 7 | 450 kbps (floor) | 480p | ~3.2 Mbps | 7 |

At the default budget the split only bites from the **fourth link on** — two, three and four
participants each get the full 1000 kbps ceiling at 720p, exactly as they did before the
ladder existed. That restraint is deliberate: see pitfall 14.

Every number in that table is a **ceiling, never a target**. Congestion control measures the
link and picks the send rate underneath it, and frame rate is left entirely to libwebrtc's
own degradation logic. Nothing here seeds or overrides the bandwidth estimate.

Bandwidth is the lesser problem. The wall is **hardware encoders**: each PeerConnection
runs its own encoder instance, and most Android SoCs support only ~2–4 concurrent
sessions. Past that the stack falls back to software encoding — the phone heats up,
framerate collapses, the battery drains.

- **Design target: 3–4 participants.** Comfortably inside every limit above.
- **5–6** works, with noticeably warm phones.
- **7+** needs an **SFU** (each phone uploads once, the server fans out) — an architecture
  change, planned for later, not a tuning exercise.

The UI is already built for the larger case regardless: the grid pairs tiles per row and
gives a leftover odd tile the full row width, shrinking every row as people join, so a
5–6 person meeting still shows everybody. Only past nine tiles does the tail move to an
overflow strip of avatar chips, with pinned and actively-speaking participants promoted
back into the grid.

---

## 2. Modules

```
:meshcall   The SDK (dev.meshcall.sdk) — WebRTC engine, signaling, mesh, and the whole meeting UI
:app        Host app (com.example.videocall) — name entry, lobby, meeting creation, permissions
server/     Node.js signaling broker (VC-019) — separate repo: VideoSDKServer/server/
```

**The SDK owns the meeting screen.** Everything a meeting *looks* like — video grid,
meeting-code badge, call timer, connection banner, participants panel, and the mic /
camera / audio-output / more / leave controls — is `MeshMeetingView`. The app
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
| `MeshCall` | Session: `join(brokerUrl, meetingId, displayName, config, createIfMissing)`, `leave()`, `dispose()`, `toggleMic()`, `toggleCamera()`, `switchCamera()`, `setMic/setCamera` |
| `MeshCall` flows | `participants`, `speaker`, `connected`, `localMedia`, `state`, `errors`, `meetingNotFound` — stable across joins; safe to collect before or after `join` |
| `MeshMeetingDirectory` | `isLive(brokerUrl, code)` / `status(brokerUrl, codes)` — is that meeting joinable? Returns **null** when the broker is unreachable, which is not the same answer as "no" |
| Private meetings | `join(..., isPrivate = true)` opens one; `admission` says whether you're in, waiting or refused; `joinRequests` + `admitParticipant`/`declineParticipant` are the host's door. `MeshMeetingView` renders both sides |
| `MeshMeetingView` | The complete meeting screen. `attach(call, meetingId, showConnectionBanner)`, `detach()`, `leaveNow()`, callbacks `onLeave` / `onShareScreen` / `onMoreOptions`, flag `confirmBeforeLeaving` |
| `MeshParticipantGrid` | Just the tile grid, for building a custom meeting screen. `bind`/`unbind`/`release`, `setPinned`, `setSpeaker`, `setLocalMediaState`, `onPinRequest` |
| `MeshVideoRenderer` | `SurfaceViewRenderer` subclass, inflatable from XML |
| `MeshCallConfig` | Capture ceiling (resolution, frame rate), per-link `maxVideoKbps` + total `uplinkBudgetKbps`, initial mic/camera, **ICE servers (STUN/TURN)** |
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
NameEntryActivity ─(display name → SharedPreferences)─► LobbyActivity
                                                  │
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
- **A meeting exists only while at least one participant is in it.** The broker keeps no
  meeting records; a code is live iff somebody is connected to it.

### Client → Server

| Event | Payload | Notes |
|-------|---------|-------|
| `join-meeting` | `{ meeting, userId, userName, create, private }` | Sent on **every** connect *and* reconnect. Server replies `meeting-members` and broadcasts `peer-joined`, or refuses with `meeting-not-found`, or parks the socket with `awaiting-approval`. `private` is honored **only** on the join that creates the meeting. |
| `admit-decision` | `{ userId, admit }` | The host's verdict on one knock. Obeyed only from the host's own sockets. |
| `check-meetings` | `{ meetings: [ code ] }` → **ack** `{ meetings: [ { meeting, participants, private } ] }` | Liveness lookup, answered over the Socket.IO ack. Allowed *before* `join-meeting` — the lobby asks precisely because it is in no meeting. Capped at 20 codes per call. |
| `offer` | `{ to, sdp: { type: "offer", sdp } }` | Relay to `to`, inject `from`. |
| `answer` | `{ to, sdp: { type: "answer", sdp } }` | Relay to `to`, inject `from`. |
| `ice-candidate` | `{ to, candidate: { candidate, sdpMLineIndex, sdpMid } }` | Relay to `to`, inject `from`. |
| `peer-state` | `{ state: { micEnabled, cameraEnabled } }` | `to` optional; absent = broadcast. |

### Server → Client

| Event | Payload | Notes |
|-------|---------|-------|
| `meeting-members` | `{ meeting, peers: [ { userId, userName } ] }` | Roster after join/rejoin. Peer key is **`userId`**. |
| `meeting-not-found` | `{ meeting }` | Join refused: no such live meeting, and `create` was not set. Terminal — the client disconnects and leaves the screen. |
| `awaiting-approval` | `{ meeting }` | Private meeting: you are in the waiting room. The socket **stays connected** — it *is* the pending request, and dropping it withdraws the knock. |
| `join-denied` | `{ meeting }` | The host said no. Terminal. |
| `knock` | `{ userId, userName, meeting }` | To the host only. Re-sent to whoever inherits the host role, so a queued request is never orphaned. |
| `knock-withdrawn` | `{ userId }` | To the host only: that person gave up or dropped. |
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
7. **Never create a meeting implicitly.** `join-meeting` for a meeting nobody is in is
   refused with `meeting-not-found` unless `create: true`. The client sets `create` when
   the user *started* the meeting, and on any reconnect after it was already accepted —
   a participant left alone must be able to come back to a meeting that emptied while
   their socket was down.
8. `check-meetings` answers a participant count per requested code (0 = not live) and
   nothing else. It never reveals who is in a meeting, and never lists meetings the
   caller did not name.
9. **Private meetings are the broker's job, never the client's.** A flag the joining app
   could ignore would be no gate at all. On a private meeting, a `userId` that has not
   been admitted goes into the meeting's pending list — never into the roster — gets
   `awaiting-approval`, and its knock is sent to the host. Nothing it emits is relayed,
   because it never became a member.
10. **Admission is keyed by `userId`, not socket**, so a reconnect never sends an
    established participant back to the waiting room. `admit-decision` is honored only
    from the current host's sockets, and the host role passes to the longest-standing
    remaining member when the host leaves — along with the queue of people still waiting.

### SDK mapping

| Protocol event | Kotlin (`SignalEvent`) |
|----------------|------------------------|
| `meeting-members` | `MeetingSnapshot(peers, meetingId)` |
| `meeting-not-found` | `MeetingNotFound(meetingId)` → `MeshCall.meetingNotFound` |
| `check-meetings` ack | `MeetingLookupClient` → `MeshMeetingDirectory.status/isLive` |
| `awaiting-approval` / `join-denied` | `AwaitingApproval` / `JoinDenied` → `MeshCall.admission` |
| `knock` / `knock-withdrawn` | `Knock` / `KnockWithdrawn` → `MeshCall.joinRequests` |
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

### With a broker

1. Run the broker on port 3000: `cd VideoSDKServer/server && npm install && npm start`
   (separate repo, VC-019 — see its README for details and a smoke test).
2. Emulator: `adb reverse tcp:3000 tcp:3000`, use `ws://127.0.0.1:3000` (or `ws://10.0.2.2:3000`).
3. Real devices: same Wi-Fi, `ws://<PC-LAN-IP>:3000`, firewall open on TCP 3000.
4. Both devices join the same meeting code.

```bash
adb shell am start -n com.example.videocall/.MainActivity \
  -e broker ws://10.0.2.2:3000 -e meeting DEMO01 -e name Alice
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
| VC-009 | Responsive tile grid + landscape | Non-overlapping cells, reflows on resize; rows of pairs, odd tile takes the full row |
| VC-011 | Copy meeting code in-meeting | Tap the badge |
| VC-016 | Active-speaker detection | RMS from remote audio tracks; speaker promoted into the grid |
| VC-024 | **Adaptive encoder tuning** | Per-link bitrate ceiling + capture resolution re-derived from the live link count on every join/leave (§1). Ceilings only — congestion control keeps full authority over the send rate |
| VC-017 | Pin a participant | Tap a tile or overflow chip |
| VC-018 | Overflow strip | Participants beyond 9 grid tiles become avatar chips |
| VC-020 | **Meeting UI moved into the SDK** | `MeshMeetingView` owns the screen; the app is lobby + permissions |
| VC-019 | **Node.js signaling broker** | Implements §4 exactly; in-memory, single-process, Socket.IO v4. See `VideoSDKServer/server/` (separate repo) |
| VC-022 | **Audio output routing** | Speaker / Bluetooth / wired / earpiece behind one control-bar button; `AudioRouteController` owns mode, focus and device changes. No Bluetooth permission needed |
| VC-027 | **Private meetings** | Switch at creation time. Every later joiner knocks: broker parks them out of the roster, host gets an admit/decline card at the top of the meeting, joiner sees a waiting room. Broker-enforced, keyed by `userId`, host role and pending queue survive the host leaving (§4) |
| VC-025 | **Meeting codes are validated** | A meeting exists only while somebody is in it: `check-meetings` gates the join dialog, `join-meeting` without `create` is refused with `meeting-not-found`, and Recent Meetings offers Rejoin only for codes the broker confirms are live. Needs the matching broker handlers (§4) |
| VC-026 | **Display name** | Asked once on first launch, stored in `SharedPreferences`, sent as `userName` instead of `Build.MODEL`. Editable from the lobby's name chip |
| VC-023 | **Screen share** | Replaces the camera on the existing sender via `setTrack` — no renegotiation, no new signaling. Foreground service (`mediaProjection`) + host-supplied consent Intent. Remote tiles crop it; see Partial |

### Partial

| ID | Feature | Remaining |
|----|---------|-----------|
| VC-004 | Connection status indicator | Per-peer ICE dots + broker banner ship; **peer disconnect/reconnect toast** pending |
| VC-023 | Screen share | Works end to end, but **receivers do not know a tile is a screen**: their renderer stays on `SCALE_ASPECT_FILL` and crops it. Fix needs a `sharing` flag on `peer-state` (broker change, separate repo) so remote tiles can switch to `SCALE_ASPECT_FIT` — the sharer's own tile already does |

### Not started

| ID | Feature | Priority | Notes |
|----|---------|----------|-------|
| VC-012 | Foreground service / background meeting | High | Survive screen lock; Android 14+ FGS type `camera\|microphone` |
| VC-014 | Actionable error surfacing | High | Distinct dialogs: server unreachable (with retry), camera in use, mic denied |
| VC-007 | In-meeting text chat | Medium | Signaling relay first, DataChannel later (additive event) |
| VC-010 | Deep-link invitations | Medium | `videocall://join/<code>` prefills Join |
| VC-008 | Video quality presets | Low | The SDK already steps 720p → 540p → 480p automatically with the link count (§1); what is missing is a *user-facing* override for people who want to force one |
| VC-013 | Recording | Low | Depends on VC-012 |
| VC-015 | Auth / user profiles | Low | Persisted display name; stable `userId` across sessions |
| VC-021 | SFU migration | Later | Only if meetings need to exceed ~6 participants (§1) |

### Known limitations

- Cross-network calling needs a **TURN** server you supply.
- Mesh ceiling ~5–6 participants; design target is 3–4 (§1).
- No speaker/earpiece audio routing control.
- `ws://` cleartext is enabled for dev. Use `wss://` in production and drop
  `usesCleartextTraffic`.
- No automated tests. Verification is the manual checklist in §9.

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
   directly on a `SurfaceViewRenderer`, in XML or in code — the background composites *above*
   the video and hides it. Tile chrome belongs in a sibling view on the window plane. Rounded
   video corners are *masked*, not clipped: `TileFrameDrawable` paints the corner slivers over
   the video from the topmost child. An underlay surface cannot be shaped any other way.
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
7. MediaProjection needs a foreground service of type `mediaProjection` **already running**
   before the projection is requested (Android 10+, enforced from 14). Requesting it first
   throws — hence `MeshScreenShareService.start(context) { ...capture... }` starting capture
   from the ready callback rather than inline.
8. `RtpSender.setTrack` swaps a same-kind track with **no renegotiation**. That is what makes
   screen share cheap here; adding a second video track instead would need a new transceiver,
   a fresh offer/answer to every peer, and a signaling change.
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
11. §4 payloads nest: `{ to, sdp: { type, sdp } }`, not `{ to, type, sdp }`. Emitting them
    flat put a raw SDP **string** where the receiver's `optJSONObject("sdp")` expected an
    object, so `?: return` silently discarded every offer, answer, ICE candidate, and
    peer-state. Meetings connected, exchanged nothing, and logged no error. Parsing a
    required field must **log on failure** — a silent `return` here is indistinguishable
    from a network problem and sends you hunting ICE, TURN, and camera code for hours.
12. Matching video senders by **track identity** (`sender.track() === localVideoTrack`) instead
    of by kind silently skips the screen-share track, because sharing swaps a different track
    into the same sender. The bitrate cap then never applied while sharing — the one time the
    stream is largest. Match on `kind() == "video"`.
13. A per-link bitrate cap multiplies in a mesh. Capping each of N−1 senders at 1 Mbps asks the
    uplink for N−1 Mbps; congestion control then backs every stream off unevenly and the whole
    grid goes soft at once. Divide a total budget instead, and move capture resolution with it —
    see §1.
14. **Never seed the bandwidth estimate.** `PeerConnection.setBitrate(min, current, max)` does
    not hint at a starting point — passing `current` *resets* the estimate, overriding what
    congestion control measured. Seeding it (and raising the ceiling to match) made the encoder
    demand more than the uplink could carry before it had measured anything: loss, a hard
    back-off, a probe back up, repeat. Video was sharp or blocky depending on which phase of
    the oscillation you caught, and 1:1 calls — where the ceiling was raised most — suffered
    worst. Symptom to recognise: *"sometimes okay, sometimes worse"* rather than a steady
    quality level. Set ceilings; let BWE find the rate. Raising a ceiling never adds quality a
    link cannot carry — it only adds room to overshoot.

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
speaker gets the ring · 3rd and 5th participants each take a full-width row and the grid
shrinks to fit · leave removes the tile remotely · leaving frees resources with no leak.

**Video quality** — `MeshCall/Engine` logs a `video tier for N link(s)` line on every join
and leave · 2–4 participants stay at `1280x720 ≤1000kbps` · the tier climbs back up when
peers drop · quality holds *steady* rather than alternating sharp and blocky (see pitfall
14) · a shared screen stays legible at 5 people.

**Robustness** — peer joins mid-meeting · peer leaves mid-meeting · network drop
reconnects and resumes · re-joining the same meeting is a no-op · ICE failure re-links
(bounded to 3 attempts) · 10× rapid create→leave leaks no sockets.

### Environments

| Environment | Setup |
|-------------|-------|
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
Environment: two real phones (models/OS) / emulator
Meeting code:
Steps:
Expected:
Actual:
Logcat (MeshCall/* excerpt):
```
