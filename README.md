# MeshCall SDK (VideoCall)

A small Android WebRTC **mesh-call** SDK plus a demo app. Every participant sends their
own camera + microphone to every other participant directly over peer-to-peer WebRTC —
there is no media server and no re-broadcasting, so a room of 4–5 people works without
infrastructure beyond a lightweight signaling relay.

```
:meshcall      → the SDK library (WebRTC engine + socket.io signaling + mesh manager + view kit)
:app           → demo Android application that hosts one room
docs/signaling_server_spec.md → wire contract + reference Node.js broker
```

## Why a mesh works for 4–5 people

In a full mesh each peer has one `PeerConnection` per participant and uploads only its
**own** captured stream (never relays what it receives — see
`MeshWebRtcEngine.preparePeerConnection`). Negotiation for each link is
glare-free by making the peer with the lexicographically smaller user id the polite
offering side (`MeshCallManager.ensureLinkTo`). Media cost therefore scales as
**N-1 uplinks × one video stream**, which is affordable on Wi-Fi at 4–5 participants.

To keep a 4–5 person room from saturating a mobile uplink, each local SDP is capped at
[`MediaConfig.maxVideoKbps`](meshcall/src/main/java/dev/meshcall/sdk/internal/media/MediaConfig.kt)
(default 500 kbps per link) by `MeshWebRtcEngine.applyBitrateCap`. Raise it only when all
participants are on a fast wired backhaul.

## Modules

### `:meshcall` — the SDK

Public surface (`dev.meshcall.sdk.api` / `.ui`):

| Type | Purpose |
|------|---------|
| `MeshCall` | Create a session: `join(brokerUrl, roomId, displayName)`, `errors`, `peers`, `toggleMic()`, `toggleCamera()`, `leave()`, `dispose()`. |
| `LocalIdentityProvider.userId` | Opaque per-user identifier. In production derive it from your auth backend; the demo sets a throwaway value. |
| `MeshCallRoomView` | Binds a `ViewGroup` container to a joined `MeshCall`, renders the local preview + one tile per remote peer, and exposes `bind`/`unbind`/`release`. |
| `MeshVideoRenderer` | `SurfaceViewRenderer` subclass inflateable from XML or code. |

Internals under `dev.meshcall.sdk.internal` — not part of the supported API:

- `webrtc.MeshWebRtcEngine` — one shared `PeerConnectionFactory` + EGL context, local
  capture, N peer connections, offer/answer/ICE, per-link bitrate cap.
- `signaling.SocketIOSignalingClient` — socket.io transport for presence, roster, and the
  SDP/ICE relay described in `docs/signaling_server_spec.md`.
- `mesh.MeshCallManager` — single-threaded coordinator that normalizes signaling + media
  into `RoomState`, `peers`, and `mediaEvents` flows.
- `media.MediaConfig` — capture/transport tuning (resolution, frame rate, default bitrate cap).

### `:app` — demo

`com.example.videocall.MainActivity` requests camera + microphone, then joins a room and
binds `MeshCallRoomView` to the layout. Mic / camera / end-call buttons are wired to the
session. The sample app is independent of the OneCash `KaramActivity` flow.

## Try it

1. Run a signaling broker (see `docs/signaling_server_spec.md`); the default demo points
   at `ws://10.0.2.2:3000` (host loopback from the emulator).
2. Build + install `:app`, granting camera + mic.
3. Start a second instance (emulator, device, or `adb reverse`) and both join the same room id.
4. On an emulator, use `adb reverse tcp:3000 tcp:3000` if the broker runs on host port 3000,
   and launch with `-e broker ws://127.0.0.1:3000`.

## Notes for going to production

- **Signaling relay** — see `docs/signaling_server_spec.md`. The schema is plain
  JSON over socket.io; room presence and SDP/ICE relay are implemented server-side.
- **Mesh ceiling** — 4–5 works; beyond ~6, deploy an SFU instead so each peer uploads
  once and the server fans out.
- **Identity** — set `LocalIdentityProvider.userId` from your session before `join`.
- **Cleartext** — `ws://` origins require `usesCleartextTraffic` (`:app` demo sets it);
  use `wss://` in production.
"# VideoSDK" 
