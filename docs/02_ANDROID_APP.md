# 02 — Android App

> This document covers **only** the Android side. Signaling server details live in
> `03_SIGNALING_SERVER.md`, protocol in `05_MESSAGE_PROTOCOL.md`.

## Module layout

```
:app                    Host application (demo + tests)
└── src/main/java/com/example/videocall
    ├── LobbyActivity.kt
    ├── MainActivity.kt
    └── res/...

:meshcall               Reusable SDK (library)
└── src/main/java/dev/meshcall/sdk
    ├── api/            Public entry points (MeshCall, LocalIdentityProvider)
    ├── ui/             Public UI helpers (MeshCallRoomView, MeshVideoRenderer)
    └── internal/
        ├── demo/       MockSignalingClient + MockRoomData (offline demo mode only)
        ├── media/      MediaConfig
        ├── mesh/       MeshCallManager (orchestration)
        ├── signaling/  SocketIOSignalingClient, SignalingSchema, SignalEvent
        └── webrtc/     MeshWebRtcEngine
```

## Flow (happy path)

```
LobbyActivity
   │  Create Room  → show code modal (Copy/Share/Start)
   │  Join Room    → read code + server field
   │  Demo mode    → offline: MeshCall.joinDemo(room, name, N peers)
   ▼
MainActivity  (call screen)
   │  permission check (CAMERA, RECORD_AUDIO)
   ▼
MeshCall.join(brokerUrl, roomId, displayName)   // or joinDemo for offline UI work
   ▼
MeshCallManager
   ├── MeshWebRtcEngine.prepareLocalMedia()   → camera/mic tracks
   └── SignalingClient.connect()               → join-room (Socket.IO or mock)
   ▼
MeshCallRoomView.bind(call)
   ├── seedSlots()        → init renderers already in the layout
   ├── bindLocalPreview() → local track → preview renderer
   └── collect peers + mediaEvents → responsive tile grid + chrome
   ▼
PeerConnection per peer → ICE → remote stream → remote renderer
```

## Activities

### LobbyActivity (launcher)
- **Create Room**: generates a 6-char code (alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789`,
  no ambiguous characters), shows a modal with Copy / Share / **Start Call** (icon
  buttons). Share uses the Android share sheet.
- **Join Room**: validates non-empty code (uppercased), starts the call.
- **Demo mode switch**: when enabled, the call runs fully offline against
  `MockSignalingClient` (simulated 6-peer room) — used to develop/exercise the
  in-call UI before the broker is available.
- **Signaling server field**: broker URL, default `ws://10.0.2.2:3000`.
- Passes extras to MainActivity: `broker`, `room`, `name` (default = device model),
  `demo`, `peers`.

### MainActivity (in-call)
- Runtime permission flow (camera + mic) via `RequestMultiplePermissions` launcher.
- Top chrome: room-code badge (tap to copy), call timer (`hh:mm:ss`), signaling
  connection banner ("Connecting to signaling server…" while disconnected).
- Participants panel (FAB toggle): every remote peer with live mic/camera state + count.
- Wires mic / camera / **switch-camera** / end-call FABs → `MeshCall.toggleMic/
  toggleCamera/switchCamera/leave`.
- Collects `MeshCall.errors` → toasts; `MeshCall.connected` → banner; `peers` → panel.
- `onDestroy` → `roomView.release()` + `call.dispose()`.

## UI (layouts)

| Layout | Contents |
|--------|----------|
| `activity_lobby.xml` | Title, subtitle, Create Room button, Room ID field, Join Room button, server field, demo-mode switch |
| `dialog_room_code.xml` | Room code (monospace, selectable), Copy / Share / Start icon buttons |
| `activity_main.xml` | `call_container` (video grid), room-code + timer (top), connection banner, participants panel, control FABs (participants / mic / end / camera / switch-camera) |
| drawables | `ic_mic`, `ic_camera`, `ic_call_end`, `ic_copy`, `ic_share`, `ic_play`, `rounded_surface`, `bg_icon_circle` |

**Rule:** no gradients / decorative overlays in the call layout — the video surface
must never be covered (SurfaceView compositing issues).

## Demo mode (offline UI development)

- Lives in `internal/demo/`: `MockRoomData` (participant pool, timings) and
  `MockSignalingClient` (a `SignalingClient` that emits roster snapshots, staggered
  `peer-joined`, and media-state churn — no real broker involved).
- Wired via `MeshCall.joinDemo(roomId, displayName, simulatedPeers)`; the production
  path (`join`) is untouched and always uses Socket.IO.
- Peer connections against mock peers stay in "connecting" ICE state, which
  deliberately exercises the tile placeholder + connection-dot UI.

## ViewModels / state management

No ViewModels currently — `MainActivity` is a thin host. State flows (reactive):

- `MeshCall.state: Flow<MeshCallState>` (IDLE / CONNECTED)
- `MeshCall.peers: Flow<List<MeshRoomPeer>>` (roster, excluding self; each peer carries
  `micEnabled`, `cameraEnabled`, and `connectionState` = "new" / "connecting" /
  "connected" / "completed" / "disconnected" / "failed")
- `MeshCall.connected: Flow<Boolean>` (signaling transport up — drives the banner)
- `MeshCall.errors: Flow<String>`
- `MeshCallRoomView.renderersReady: StateFlow<Boolean>`
- `MeshCallManager.mediaEvents: SharedFlow<MediaEvent>` (remote streams)

Public API added for Phase 2: `MeshCall.switchCamera()`, `MeshCall.joinDemo(...)`
(offline demo), `MeshCall.connected`.

Threading: all SDK orchestration confined to `Dispatchers.Main.immediate`; WebRTC
callbacks (binder threads) funnel into the manager via flows.

## CallManager (`MeshCallManager`)

- Owns one `MeshWebRtcEngine` + one `SignalingClient` per session. The signaling
  client is injected through a factory (default = `SocketIOSignalingClient`); the
  demo path injects `MockSignalingClient`.
- `join(roomId, config)`: leaves previous session, prepares media, connects socket,
  routes events.
- `ensureLinkTo(peerId)`: creates a `PeerConnection`; **the peer with the lower
  userId sends the offer** (deterministic polite/impolite split).
- Handles `Offer/Answer/IceCandidate/RoomSnapshot/PeerJoined/PeerLeft/PeerState`.
- `publishPeers()`: rebuilds the public roster from connections + media state +
  per-peer ICE label (surfaced as `connectionState`).
- `signalingConnected`: flips true on `room-members`, false on socket drop.
- Toggles: `toggleMic` / `toggleCamera` / `switchCamera` → engine + (mic/cam)
  broadcast `peer-state`.

## Permissions

- `CAMERA`, `RECORD_AUDIO` (runtime), `INTERNET`, `ACCESS_NETWORK_STATE`,
  `MODIFY_AUDIO_SETTINGS` (normal). Declared in `:meshcall` manifest; merged into
  the app.
- Camera/mic are **required** for the call (denied → toast, no call).

## Camera

- Front-facing by default (`MediaConfig.CameraFacing.FRONT`), fallback to any
  camera, then error event.
- Capture: 640x480 @ 30 fps, `Camera2Enumerator` preferred, `SurfaceTextureHelper`
  on the shared EGL context.
- Toggle = `startCapture/stopCapture` + `VideoTrack.setEnabled`.
- `switchCamera()` = `CameraVideoCapturer.switchCamera(null)` (front ⇄ back,
  no renegotiation).
- Preview is mirrored; `setZOrderMediaOverlay(true)` for the local tile.

## Audio

- Always-on mic track (`googEchoCancellation`, `googAutoGainControl`,
  `googNoiseSuppression`).
- No speaker routing control yet (roadmap).

## Renderer / tile grid

- `MeshVideoRenderer` = thin wrapper over `SurfaceViewRenderer`.
- All renderers initialized against the engine's **shared EGL context**
  (`eglBase.eglBaseContext`).
- **Grid model** (`MeshCallRoomView`): one non-overlapping tile per remote peer,
  cells recomputed on roster change / container resize (1..9 peers → 1x1 … 3x3).
- **Z-order planes** (deliberate): local preview = media-overlay surface (floats
  above grid); remote surfaces = underlay plane; tile chrome (name chip, mic/cam
  badges, connection dot, avatar placeholder) = window plane, so it always draws
  **above** remote video.
- Placeholder (initials avatar + name) is shown while a peer has no stream or their
  camera is off; it is hidden the moment a stream binds.
- Hardware scaler enabled; local preview = first `MeshVideoRenderer` child of the
  container.

## MediaConfig (defaults)

```kotlin
cameraFacing = FRONT
frameRate    = 30
videoWidth   = 640
videoHeight  = 480
initialMicOn = true
initialCameraOn = true
maxVideoKbps = 500      // applied to every outgoing SDP (b=AS)
```

## Known Android pitfalls (learned)

1. Never put `elevation` + opaque background + `clipToOutline` on a
   `SurfaceViewRenderer` — the view's background renders **above** the video
   surface and hides it.
2. `ws://` cleartext requires `android:usesCleartextTraffic="true"` (done) —
   otherwise the broker is unreachable on Android 9+.
3. `findViewById<Button>` on a `FloatingActionButton` throws `ClassCastException`
   at runtime.
