# Signaling server spec — MeshCall

The mesh never routes media; it only needs a small **relay** that brokers presence and
forwards SDP/ICE packets and peer-state between room members. This document is the
wire contract the SDK client (`SocketIOSignalingClient`) expects, plus a copy-paste
Node.js broker that satisfies it.

## Transport

- **Protocol**: [socket.io](https://socket.io) (v4 to match `io.socketio-client`).
- **Namespace**: default `/` (the client connects with no custom namespace).
- **Endpoint**: the URL passed as `brokerUrl` to `MeshCall.join(...)`. For dev only
  `ws://` works on a cleartext-enabled app; use `wss://` in production.
- **Payloads**: every event payload is a single JSON **object** (never an array) and is
  decoded client-side with `JSONObject` semantics.

## Events

### Client → server

| Event | Payload | Notes |
|-------|---------|-------|
| `join-room` | `{"room","userId","userName"}` | Sent on connect and on each reconnect. The server should add the socket to the room, broadcast presence, and (re)reply with a roster. |
| `offer` | `{"sdp":{"type","sdp"}, "to"}` → relays to `to` as `offer` with `from` added | See relay rules. |
| `answer` | `{"sdp":{"type","sdp"}, "to"}` | Same relay pattern. |
| `ice-candidate` | `{"candidate":{"candidate","sdpMLineIndex","sdpMid"}, "to"}` | Same relay pattern. |
| `peer-state` | `{"state":{"micEnabled","cameraEnabled"}, "to"}` | `to` optional; when absent, treat as broadcast to the whole room. |

### Server → client

| Event | Payload | Notes |
|-------|---------|-------|
| `peer-joined` | `{"userId","userName","room"}` | Broadcast to existing members when a new peer joins. |
| `peer-left` | `{"peerId"}` | Broadcast when a peer disconnects. |
| `offer` / `answer` / `ice-candidate` / `peer-state` | `{"from", ...payload}` | `from` is the sender's `userId`; `sdp`/`candidate`/`state` as above. |
| `room-members` | `{"room","peers":[{"id","userName"}]}` | The roster snapshot the client uses to reconcile the mesh. Sent on join and on any (re)connect. |
| `error` | `{"error":"message"}` | Any fatal signal error worth surfacing. |

## Server responsibilities (summary)

1. Track `userId` per socket (from `join-room`). Ignore events from sockets that never joined.
2. Address SDP/ICE/peer-state exactly one socket: look up `to` in the room.
3. Re-emit presence for a socket on every socket.io reconnect (the client re-sends
   `join-room`; respond with `room-members` containing the current roster including the re-joiner).
4. On `disconnect`, broadcast `peer-left` to the room and remove the socket from its room.

## Reference implementation (Node.js, Socket.IO v4)

```js
const { Server } = require("socket.io");

const io = new Server(3000, { cors: { origin: "*" } });

// userId -> Set<socket id>. A user may hold several tabs, so keep a set.
const socketsByUser = new Map();
// userId -> display name (last known), so the roster can be rebuilt even if a
// user's sockets are currently offline.
const namesByUser = new Map();

const roomPeers = (rid) => {
  const seen = new Set();
  const out = [];
  for (const [uid, sids] of socketsByUser) {
    if (seen.has(uid)) continue;
    if (![...sids].some((sid) => io.sockets.sockets.get(sid)?.rooms?.has(rid))) continue;
    seen.add(uid);
    out.push({ id: uid, userName: namesByUser.get(uid) ?? uid });
  }
  return out;
};

io.on("connection", (socket) => {
  let userId = null;
  let room = null;

  socket.on("join-room", ({ room: rid, userId: uid, userName }) => {
    userId = uid;
    room = rid;
    socket.join(rid);
    if (!socketsByUser.has(uid)) socketsByUser.set(uid, new Set());
    socketsByUser.get(uid).add(socket.id);
    namesByUser.set(uid, userName);
    socket.data.userId = uid;

    // Broadcast presence + answer with a roster of current members.
    socket.to(rid).emit("peer-joined", { userId: uid, userName, room: rid });
    socket.emit("room-members", { room: rid, peers: roomPeers(rid) });
  });

  // Relay helper: forward {type, data} to the addressed user (or broadcast the room).
  const relay = (type) => (data) => {
    const from = userId;
    const to = data?.to ?? null;
    delete data?.to;
    if (to == null) {
      socket.to(room).emit(type, { from, ...data });
    } else {
      for (const sid of socketsByUser.get(to) ?? []) {
        io.to(sid).emit(type, { from, ...data });
      }
    }
  };

  socket.on("offer", relay("offer"));
  socket.on("answer", relay("answer"));
  socket.on("ice-candidate", relay("ice-candidate"));
  socket.on("peer-state", relay("peer-state"));

  socket.on("disconnect", () => {
    if (userId && room) {
      socketsByUser.get(userId)?.delete(socket.id);
      socket.to(room).emit("peer-left", { peerId: userId });
    }
  });
});
```

`npm install socket.io` and run with `node broker.js`. The demo app defaults the broker URL
to `ws://10.0.2.2:3000`; from a physical device use your machine's LAN IP, and from the
emulator with the host on port 3000 you can also `adb reverse tcp:3000 tcp:3000` and point
the app at `ws://127.0.0.1:3000`.
