# 08 — Testing

> Checklist + environments. A feature is "Done" only when its relevant items pass.

## Functional checklist (per environment)

### Signaling / room flow
- [ ] Create Room generates a 6-char code
- [ ] Room code modal shows; Copy, Share, Start work
- [ ] Join Room with the code connects both phones
- [ ] Invalid/empty code shows validation
- [ ] Broker unreachable → surfaced error (not silent)
- [ ] Server field accepts custom URL

### Call flow
- [ ] Permission prompt on first launch (camera+mic)
- [ ] Permission denied → toast, no crash
- [ ] Local preview visible (not covered by layout)
- [ ] Offer exchanged (logcat `createOffer`)
- [ ] Answer exchanged
- [ ] ICE Connected (logcat `IceConnectionState.CONNECTED`)
- [ ] Remote video visible on the second phone
- [ ] Remote audio audible
- [ ] Mic toggle syncs to remote (badge/state)
- [ ] Camera toggle syncs to remote
- [ ] Leave room → peer removed from remote's tile grid
- [ ] Room code badge visible during call
- [ ] End call frees resources (no leak/crash on exit)

### Robustness
- [ ] Peer joins mid-call
- [ ] Peer leaves mid-call (tile removed)
- [ ] Network drop → reconnect backoff re-joins, video resumes
- [ ] Second join with same room id is a no-op while already in room
- [ ] Rapid create→leave cycles (10x) don't leak sockets/connections

## Environments

### LAN testing (primary, two real phones)
- Both phones + broker PC on the **same Wi-Fi**
- `node broker.js` running; port 3000 open in firewall
- Phones use `ws://<PC-LAN-IP>:3000`
- Verify matrix: A creates / B joins; A joins / B creates; both join pre-created
  room; 3 phones in one room.

### Emulator testing (single phone)
- Use the packaged host: `ws://10.0.2.2:3000` (host loopback)
- Or `adb reverse tcp:3000 tcp:3000` + `ws://127.0.0.1:3000`
- Verify permissions, lobby, preview, and error paths (no second camera source —
  use the emulator's virtual camera).

### Real device testing (final gate)
- Two physical phones, release/minified build
- Test: create/join, toggles, leave, background/foreground, rotate (if landscape
  lands), notify that no STUN → same-Wi-Fi only (until VC-006).

## Performance testing

- **Bandwidth:** with 3 phones, per-link cap should hold at `b=AS:500`
  (verify in captured SDP; measure uplink in logcat `CameraStatistics` ~30fps).
- **CPU/memory:** watch `Render fps` / dropped frames via
  `SurfaceEglRenderer` stats (target: 0 dropped, render time < 1 ms).
- **Latency:** visual A/B check vs. a wired call; log ICE + render timestamps.
- **Heat:** 10-minute call on real hardware; check preview stays live.

## Test tooling (current)

- Unit tests: `:app` + `:meshcall` have `testImplementation(junit)` scaffolds —
  extend for manager/engine logic (coroutines-test available in `:meshcall`).
- Manual smoke: adb install + `am start` with extras (see 00/02).
- Logcat markers to watch: `webrtc/CameraStatistics`, `SurfaceEglRenderer`,
  `IceConnectionState`, socket.io errors.

## Bug report template

```
Environment: two real phones (models/OS) / emulator
Room id: X
Steps:
1.
Expected:
Actual:
Logcat (relevant excerpt):
```