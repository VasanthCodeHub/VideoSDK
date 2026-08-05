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
   ▼
MainActivity  (call screen)
   │  permission check (CAMERA, RECORD_AUDIO)
   ▼
MeshCall.join(brokerUrl, roomId, displayName)
   ▼
MeshCallManager
   ├── MeshWebRtcEngine.prepareLocalMedia()   → camera/mic tracks
   └── SocketIOSignalingClient.connect()       → join-room
   ▼
MeshCallRoomView.bind(call)
   ├── seedSlots()        → init renderers already in the layout
   ├── bindLocalPreview() → local track → preview renderer
   └── collect peers + mediaEvents → remote tiles + streams
   ▼
PeerConnection per peer → ICE → remote stream → remote renderer
```

## Activities

### LobbyActivity (launcher)
- **Create Room**: generates a 6-char code (alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789`,
  no ambiguous characters), shows a modal with Copy / Share / **Start Call** (icon
  buttons). Share uses the Android share sheet.
- **Join Room**: validates non-empty code (uppercased), starts the call.
- **Signaling server field**: broker URL, default `ws://10.0.2.2:3000`.
- Passes extras to MainActivity: `broker`, `room`, `name` (default = device model).

### MainActivity (in-call)
- Runtime permission flow (camera + mic) via `RequestMultiplePermissions` launcher.
- Binds the room code badge (`txtRoomCode`).
- Wires mic / camera / end-call FABs → `MeshCall.toggleMic/toggleCamera/leave`.
- Collects `MeshCall.errors` → toasts.
- `onDestroy` → `roomView.release()` + `call.dispose()`.

## UI (layouts)

| Layout | Contents |
|--------|----------|
| `activity_lobby.xml` | Title, subtitle, Create Room button, Room ID field, Join Room button, server field |
| `dialog_room_code.xml` | Room code (monospace, selectable), Copy / Share / Start icon buttons |
| `activity_main.xml` | `call_container` (video), room-code badge, control FABs |
| drawables | `ic_mic`, `ic_camera`, `ic_call_end`, `ic_copy`, `ic_share`, `ic_play`, `rounded_surface`, `bg_icon_circle` |

**Rule:** no gradients / decorative overlays in the call layout — the video surface
must never be covered (SurfaceView compositing issues).

## ViewModels / state management

No ViewModels currently — `MainActivity` is a thin host. State flows (reactive):

- `MeshCall.state: Flow<MeshCallState>` (IDLE / CONNECTED)
- `MeshCall.peers: Flow<List<MeshRoomPeer>>` (roster, excluding self)
- `MeshCall.errors: Flow<String>`
- `MeshCallRoomView.renderersReady: StateFlow<Boolean>`
- `MeshCallManager.mediaEvents: SharedFlow<MediaEvent>` (remote streams)

Threading: all SDK orchestration confined to `Dispatchers.Main.immediate`; WebRTC
callbacks (binder threads) funnel into the manager via flows.

## CallManager (`MeshCallManager`)

- Owns one `MeshWebRtcEngine` + one `SignalingClient` per session.
- `join(roomId, config)`: leaves previous session, prepares media, connects socket,
  routes events.
- `ensureLinkTo(peerId)`: creates a `PeerConnection`; **the peer with the lower
  userId sends the offer** (deterministic polite/impolite split).
- Handles `Offer/Answer/IceCandidate/RoomSnapshot/PeerJoined/PeerLeft/PeerState`.
- `publishPeers()`: rebuilds the public roster from connections + media state.
- Toggles: `toggleMic` / `toggleCamera` → engine + broadcast `peer-state`.

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
- Preview is mirrored; `setZOrderMediaOverlay(true)` for the local tile.

## Audio

- Always-on mic track (`googEchoCancellation`, `googAutoGainControl`,
  `googNoiseSuppression`).
- No speaker routing control yet (roadmap).

## Renderer

- `MeshVideoRenderer` = thin wrapper over `SurfaceViewRenderer`.
- All renderers initialized against the engine's **shared EGL context**
  (`eglBase.eglBaseContext`).
- Local preview renderer lives in XML; remote renderers are added/removed
  dynamically by `MeshCallRoomView` (match_parent tiles).
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
