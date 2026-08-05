# 05 — Message Protocol

> **The contract between Android and the Node.js broker.** Every event, payload,
> and direction. Any change here **must** update this document (see
> `07_DEVELOPMENT_RULES.md`).

## Conventions

- Transport: Socket.IO v4, namespace `/`, JSON **objects** (never arrays).
- Every server→client payload carries `from` = sender's `userId`.
- Relay rules:
  - payload contains `to` → deliver to that user's sockets only
  - no `to` → broadcast to the room
- Payload keys are snake_case strings; client parses with `JSONObject`.

## Client → Server

### `join-room`
**Purpose:** announce presence, join a room, request roster. Sent on every
connect *and* every reconnect.
```json
{ "room": "ABC123", "userId": "alice-1700000000000", "userName": "Pixel 7" }
```
**Response:** `room-members` (+ `peer-joined` broadcast to others).

### `offer`
**Purpose:** deliver an SDP offer to one peer.
```json
{ "to": "bob-…", "sdp": { "type": "offer", "sdp": "v=0\r\n..." } }
```
Relayed to `to` as `offer` with `from` added.

### `answer`
**Purpose:** deliver an SDP answer to one peer.
```json
{ "to": "alice-…", "sdp": { "type": "answer", "sdp": "v=0\r\n..." } }
```

### `ice-candidate`
**Purpose:** deliver one ICE candidate to one peer.
```json
{
  "to": "bob-…",
  "candidate": { "candidate": "candidate:1 1 udp ...", "sdpMLineIndex": 0, "sdpMid": "0" }
}
```

### `peer-state`
**Purpose:** broadcast media state (mic/camera) to the room.
```json
{ "state": { "micEnabled": true, "cameraEnabled": false } }
```
`to` optional; absent = broadcast.

## Server → Client

### `room-members`
**Purpose:** roster snapshot after join/rejoin.
```json
{ "room": "ABC123", "peers": [ { "id": "bob-…", "userName": "Pixel 7a" } ] }
```

### `peer-joined`
**Purpose:** a new member entered the room.
```json
{ "userId": "bob-…", "userName": "Pixel 7a", "room": "ABC123" }
```

### `peer-left`
**Purpose:** a member disconnected.
```json
{ "peerId": "bob-…" }
```

### `offer` / `answer` / `ice-candidate` / `peer-state` (relayed)
**Purpose:** forwarded from another member; `from` = sender id.
```json
{ "from": "bob-…", "sdp": { ... } }
{ "from": "bob-…", "candidate": { ... } }
{ "from": "bob-…", "state": { "micEnabled": true, "cameraEnabled": false } }
```

### `error`
**Purpose:** fatal signaling error worth surfacing.
```json
{ "error": "message" }
```

## Transport-level events (Socket.IO built-ins)

| Event | Direction | Meaning |
|-------|-----------|---------|
| `connect` | client↔server | Handshake complete → client must re-emit `join-room` |
| `disconnect` | client↔server | Socket closed → broker emits `peer-left` |

## Event matrix (quick reference)

| Event | Direction | Addressed? | Effect |
|-------|-----------|-----------|--------|
| `join-room` | C→S | room | Presence + roster request |
| `room-members` | S→C | self | Roster snapshot |
| `peer-joined` | S→C | room broadcast | New member |
| `peer-left` | S→C | room broadcast | Member gone |
| `offer` | C↔S | to | SDP offer relay |
| `answer` | C↔S | to | SDP answer relay |
| `ice-candidate` | C↔S | to | ICE candidate relay |
| `peer-state` | C↔S | room / to | Mic/camera status |
| `error` | S→C | self | Error message |

## SDK mapping (Android)

| Protocol event | Kotlin type (`SignalEvent`) |
|----------------|------------------------------|
| `room-members` | `RoomSnapshot(peers, room)` |
| `peer-joined` | `PeerJoined(id, userName, room)` |
| `peer-left` | `PeerLeft(id)` |
| `offer` | `Offer(from, SdpPayload)` |
| `answer` | `Answer(from, SdpPayload)` |
| `ice-candidate` | `IceCandidate(from, IceCandidatePayload)` |
| `peer-state` | `PeerState(from, PeerStatePayload)` |
| `error` | `ErrorReceived(message)` |
| socket disconnect | `SignalingDisconnected` |

Constants: `SignalingSchema.kt` (`TYPE_*`, `KEY_*`).

## Versioning rule

- **No breaking changes** to existing events without a new event name
  (e.g. `join-room-v2`) or a protocol version bump documented here.
- Additive fields are allowed (clients ignore unknown keys).
