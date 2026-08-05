# 01 — Architecture

## System diagram

### Phase 1 — Signaling (before the call starts)

```
Android A                Node.js Broker                Android B
    │                          │                           │
    │   join-room (WebSocket)  │                           │
    ├─────────────────────────►│                           │
    │                          │   peer-joined broadcast   │
    │                          ├──────────────────────────►│
    │                          │   room-members (roster)   │
    │◄─────────────────────────┤                           │
    │                          │   room-members (roster)   │
    │◄─────────────────────────┤                           │
    │                          │                           │
    │   offer / answer /       │                           │
    │   ice-candidate (relay)  │                           │
    │◄────────────────────────►│◄─────────────────────────►│
```

### Phase 2 — Media (after signaling succeeds)

```
Android A ══════════════════════════════════════════════► Android B
            WebRTC (SRTP audio/video, direct P2P)
```

The Node.js broker is **never in the media path**. Once PeerConnections are
established, audio/video flows directly between phones.

## Components and responsibilities

### Android app (`:app` + `:meshcall`)

- **UI/UX**: Lobby (create/join), in-call screen (preview, remote tiles, controls)
- **Identity**: generates a unique user id per session (`name-timestamp`)
- **Media capture**: camera + microphone via WebRTC (front camera, 640x480@30)
- **Rendering**: local preview + one renderer per remote peer (EGL-shared context)
- **Mesh management**: one `PeerConnection` per remote peer; SDP/ICE lifecycle
- **State**: peers roster, mic/camera status per peer, room state (flows)
- **Protocol client**: Socket.IO events per `05_MESSAGE_PROTOCOL.md`

### Node.js signaling broker

- **Presence**: tracks which sockets are in which room
- **Roster**: answers `join-room` with the current `room-members` snapshot
- **Relay**: forwards `offer` / `answer` / `ice-candidate` / `peer-state` between
  members (addressed or broadcast)
- **Lifecycle**: `peer-joined` / `peer-left` broadcasts; reconnection handling
- **No media**: never touches audio/video data

### WebRTC (in-process, both phones)

- **PeerConnection**: the secure channel between two phones
- **SDP**: offer/answer negotiation (unified plan)
- **ICE**: host-candidate gathering/exchange (no STUN/TURN configured yet)
- **Tracks**: local `AudioTrack` + `VideoTrack` sent via `addTrack`; remote
  `MediaStream` received via `onAddStream`
- **Renderer**: `SurfaceViewRenderer` per tile, hardware scaler, mirrored preview

## Data flow (one room, two phones)

```
LobbyActivity ──(roomId, broker, name)──► MainActivity
                                              │
                                        MeshCall.join()
                                              │
                            MeshCallManager (orchestrates everything)
                              ├── MeshWebRtcEngine (local media + PCs)
                              └── SocketIOSignalingClient (broker)
                                              │
                                        MeshCallRoomView
                              ├── local preview renderer
                              └── one renderer per peer (roster-driven)
```

## Key decisions

| Decision | Rationale |
|----------|-----------|
| Full mesh (no SFU) | Zero server cost; fine up to ~4-5 people; simple to deploy |
| Per-link 500 kbps cap | N-1 uplinks in a mesh; keeps mobile Wi-Fi usable |
| Host-only ICE | Works on LAN; STUN/TURN deferred to Phase 3 |
| Socket.IO broker | Reconnect + rooms for free; trivial reference implementation |
| SDK as separate module | `:meshcall` can be published/consumed independently |
