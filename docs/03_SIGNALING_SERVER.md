# 03 — Signaling Server

> This document covers the Node.js broker **only** — no Android code here.

## Overview

The broker is a tiny Socket.IO v4 relay. It:

- Tracks which sockets are in which room (presence)
- Answers `join-room` with a roster snapshot (`room-members`)
- Forwards SDP/ICE/peer-state packets between room members
- Broadcasts `peer-joined` / `peer-left`
- Survives reconnects (client re-emits `join-room`)

It never touches media. All audio/video flows peer-to-peer.

## Tech

- Node.js + `socket.io` (v4)
- Default namespace `/`
- Plain TCP on port 3000 (dev); `wss://` behind TLS for production

## Room management

- Rooms are identified by the `room` string clients send in `join-room`.
- Multiple sockets per user id are supported (a user may hold several tabs).
- Roster = unique user ids among connected sockets in the room.

## Connection lifecycle

```
client connects (socket.io handshake)
        │
        ▼
client emits join-room {room, userId, userName}
        │
        ├──► server: socket.join(room); record userId → sockets
        ├──► broadcast peer-joined {userId, userName, room} to room
        └──► reply room-members {room, peers:[{id, userName}, ...]}
        │
        ▼
peers exchange offer/answer/ice-candidate/peer-state (relayed)
        │
        ▼
client disconnects
        │
        ├──► remove socket from tracking
        └──► broadcast peer-left {peerId} to room
```

## Responsibilities (summary)

1. Ignore events from sockets that never sent `join-room`.
2. Relay `offer` / `answer` / `ice-candidate`:
   - payload has `to` → send only to that user's sockets
   - no `to` → broadcast to the room
   - always add `from` = sender's userId
3. `peer-state`: broadcast to the room (or to `to` if present).
4. On every (re)connect: client re-sends `join-room`; server re-broadcasts presence
   and replies with the current roster including the re-joiner.
5. On `disconnect`: broadcast `peer-left` and drop the socket from its room.
6. `error` event: send fatal signaling errors to a client.

## Reference implementation

Full copy-paste broker: **`docs/signaling_server_spec.md`** (kept verbatim).

Run it:

```bash
npm install socket.io
node broker.js
```

Verify: `curl http://localhost:3000/socket.io/?EIO=4&transport=polling` returns a
Socket.IO handshake.

## Deployment notes

- **LAN dev**: run on a machine on the same Wi-Fi; phones use `ws://<LAN-IP>:3000`
  (firewall must allow inbound TCP 3000).
- **Production**: TLS terminator (nginx/caddy) → `wss://`; the Android app already
  supports it (cleartext flag can be disabled once HTTPS is in place).
- **Scaling**: one process is fine for small mesh rooms; sockets are in-memory,
  so use sticky sessions if you ever scale horizontally.

## Testing the server standalone

```bash
# terminal 1
node broker.js
# terminal 2 — join as Alice
node -e "const i=require('socket.io-client')('http://localhost:3000');i.on('connect',()=>i.emit('join-room',{room:'r1',userId:'alice',userName:'Alice'}));i.on('peer-joined',m=>console.log('joined:',m));i.on('room-members',m=>console.log('members:',m));"
# terminal 3 — join as Bob, watch Alice's terminal print peer-joined + room-members
```
