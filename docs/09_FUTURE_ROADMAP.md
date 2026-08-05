# 09 — Future Roadmap

> Phased plan. Stories map to `06_BACKLOG.md` (VC-###).

## Phase 1 — Basic Video Call ✅ DONE

- Lobby: Create Room / Join Room with shareable code
- Full-mesh WebRTC audio+video (same LAN)
- Remote tiles, mic/camera toggles, end call
- Reference Node.js broker, protocol docs

---

## Phase 2 — Call UX (current target)

- **VC-001** Camera Switch (front/back) — ✅
- **VC-002** Per-peer mute/camera-off badges — ✅
- **VC-003** Participant list — ✅
- **VC-004** Connection status indicator — ◐ (per-peer dots + broker banner; disconnect toast pending)
- **VC-009** Landscape + responsive tile grid — ✅
- **VC-007** In-call text chat — pending

> Dev note: Phase 2 UI is verified offline via **demo mode** (`MeshCall.joinDemo`,
> mock signaling client with up to 10 simulated peers) until the Node.js broker is
> available. See `02_ANDROID_APP.md` → Demo mode.

Exit criteria: two phones + one tablet in a room, all media + chat stable in
portrait and landscape.

---

## Phase 3 — Reach & persistence

- **VC-006** STUN/TURN servers (calls across different networks / internet)
- **VC-012** Foreground service / background call
- **VC-013** Call recording
- **VC-010** Deep-link invitations & room links

Exit criteria: calls work between a phone (mobile data) and a phone (Wi-Fi);
call survives screen lock.

---

## Phase 4 — Intelligence & accessibility

- **VC-015** Authentication / user profiles
- Closed captions (speech-to-text on audio track)
- Noise cancellation / echo suppression improvements
- Screen share (VideoTrack from display capture)
- AI features: smart mute, speaker detection, transcription

Exit criteria: captions + screen share stable in 4-person mesh; transcripts
exportable.

---

## Sequencing notes

- Phase 2 items are independent of Phase 3; start in parallel teams.
- Text chat (VC-007) can reuse the signaling relay first, migrate to
  DataChannel later (no protocol break if the event is additive).
- VC-006 is a prerequisite for any "works over the internet" marketing claim.
- Revisit mesh scalability if rooms exceed ~6 people → consider SFU/MCU
  (architecture change, new doc).