# 04 — WebRTC Flow

> Learning/reference document. This is the full story of how two phones end up
> showing each other's video — from room creation to hanging up.

## Concepts (the glossary you'll need)

| Term | What it is |
|------|-----------|
| **SDP** | Session Description Protocol — a text description of what a phone can send/receive (codecs, media lines, formats). Exchanged as **offer** and **answer**. |
| **ICE** | Interactive Connectivity Establishment — the process of discovering the best network path between two phones. Candidates are addresses (host = LAN IP). |
| **PeerConnection** | The WebRTC object that represents the secure channel to one other phone. One per remote peer (mesh). |
| **VideoTrack** | A video source (local camera or remote). Can be enabled/disabled. |
| **AudioTrack** | An audio source (mic or remote). |
| **MediaStream** | A group of tracks (e.g. one remote peer's audio+video). Received via `onAddStream`. |
| **EglBase** | Shared OpenGL ES context; required for hardware video decoding/rendering. |

## End-to-end flow

```
1. Create Room (Android A) ──► generates code "ABC123"
2. Join Room (Android B)   ──► sends join-room {room:"ABC123"}
3. Broker: peer-joined + room-members (roster) to both
4. A and B create a PeerConnection for each other
5. Offer: the peer with the LOWER userId sends SDP offer
6. Answer: the other replies with SDP answer
7. ICE Candidates: both exchange gathered candidates
8. ICE Connected: a viable path is found
9. Media Starts: local tracks added → remote streams render
10. Peer Left: socket drops → peer-left → renderer removed
```

## Step-by-step (with code locations)

### 1–3. Room setup
- `LobbyActivity` → extras → `MainActivity.startCall()` → `MeshCall.join(...)`.
- `MeshCallManager.join()`: `MeshWebRtcEngine.prepareLocalMedia()` starts the
  camera/mic; `SocketIOSignalingClient.connect(roomId)` emits `join-room`
  (`rejoinAndSync` also fires on every reconnect).
- Broker replies `room-members` → `SignalEvent.RoomSnapshot` →
  `MeshCallManager.onSignalEvent` reconciles the roster and calls
  `ensureLinkTo(peerId)` for every other user.

### 4. PeerConnection creation
- `MeshWebRtcEngine.preparePeerConnection(peerId, ...)`:
  - `PeerConnection.RTCConfiguration(emptyList())` — no STUN/TURN yet
  - UNIFIED_PLAN SDP semantics, continual gathering, ICE pool 4
  - attaches local `AudioTrack` + `VideoTrack` via `pc.addTrack(...)`
  - observer: `onIceCandidate`, `onAddStream` (→ `remoteStreams` flow),
    `onIceConnectionChange` (→ status/error)

### 5–6. Offer / Answer (polite-impolite)
- `MeshCallManager.ensureLinkTo`: `if (userId < peerId)` we are **polite** →
  `engine.createOffer(holder)`; otherwise we wait for the remote offer.
- `createOffer`: `pc.createOffer` → `setLocalDescription` → on success send
  `offer` (with `applyBitrateCap`: strips `b=AS:` lines, appends
  `b=AS:500`) via signaling.
- `handleOffer`: `setRemoteDescription` → `pc.createAnswer` → `setLocalDescription`
  → send `answer`.

### 7. ICE candidates
- `onIceCandidate` → `ice-candidate` message (sdp, sdpMLineIndex, sdpMid).
- Inbound → `engine.addIceCandidate`.
- Same-LAN phones connect via **host candidates** (no STUN needed on a flat
  network). Different networks won't connect yet (Phase 3 → STUN/TURN).

### 8. ICE Connected
- `onIceConnectionChange` → CONNECTED/COMPLETED/DISCONNECTED/FAILED.
- FAILED currently disposes the holder.

### 9. Media rendering
- Remote: `onAddStream` → `RemoteStreamChanged(peerId, stream)` →
  `MeshCallRoomView` → `syncPeers` (roster) creates a full-screen renderer;
  `bindPeerStream` attaches `stream.videoTracks[0]` as a sink.
- Local: `bindLocalPreview` mirrors the preview and sinks the local track.
- All renderers share `eglBase.eglBaseContext` (hardware decode path).

### 10. Peer left / teardown
- `peer-left` or roster reconciliation → `holder.dispose()` → renderer removed.
- End call: `leave()` → disconnect socket, dispose connections + engine +
  renderers (`MainActivity.onDestroy`).

## Common failure modes (diagnose with logcat)

| Symptom | Likely cause |
|---------|--------------|
| Broker never connects | `ws://` cleartext blocked / wrong server IP |
| Roster empty | Broker not running / firewall / wrong room id |
| No ICE Connected | Different networks (no STUN), or firewall blocking UDP |
| Local preview black | Layout covering the SurfaceView (see 02, pitfalls) |
| Remote tile empty | Signaling ok but media path blocked (same as ICE) |
