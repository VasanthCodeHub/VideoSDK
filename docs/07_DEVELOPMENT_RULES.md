# 07 — Development Rules

> Guardrails so multiple devs (and AI agents) never conflict. **Follow every
> rule; they are mandatory, not suggestions.**

## Rule 1 — Module boundaries are sacred

- `:app` (host UI) never contains signaling logic.
- `:meshcall` SDK never contains app-specific UI (no `com.example` imports).
- WebRTC engine code stays in `internal/webrtc`; signaling in `internal/signaling`;
  orchestration in `internal/mesh`.

## Rule 2 — Android code never edits signaling logic

- The Node.js broker is the only owner of room/presence/relay behavior.
- Android only consumes `05_MESSAGE_PROTOCOL.md` events.

## Rule 3 — Node.js never edits Android UI

- The broker must never send UI-specific payloads. All UI decisions live in the app.

## Rule 4 — Protocol changes require updating MESSAGE_PROTOCOL.md

- Before writing code that adds/renames/removes a Socket.IO event, update
  `05_MESSAGE_PROTOCOL.md` in the **same change**.
- Update the `SignalingSchema.kt` constants alongside.

## Rule 5 — No feature without documentation

- Every merged feature must touch at least one doc:
  - new story → `06_BACKLOG.md` (marked Done)
  - behavior change → `02_ANDROID_APP.md` / `03_SIGNALING_SERVER.md` /
    `04_WEBRTC_FLOW.md` as applicable

## Rule 6 — Every PR updates the changelog

- `CHANGELOG.md` entry per PR: `Added / Changed / Fixed` + story id (VC-###).

## Rule 7 — No breaking protocol changes

- Existing events keep their names and payloads (additive fields only).
- Breaking changes require a new event name or a version bump documented in
  `05_MESSAGE_PROTOCOL.md`, with a migration note.

## Rule 8 — Maintain backward compatibility

- The app must keep working with an older broker and vice versa.
- New fields are optional; clients ignore unknown keys (already the convention).

## Rule 9 — One session, deterministic negotiation

- Keep the "lower userId sends the offer" rule (`MeshCallManager.ensureLinkTo`).
- Do not add both-side-offer races without a glare-handling plan.

## Rule 10 — SurfaceView hygiene

- Never add `elevation`, opaque backgrounds, or `clipToOutline` to
  `SurfaceViewRenderer`s (hides the video; see `02_ANDROID_APP.md` pitfalls).

## Rule 11 — Commits

- Small, single-purpose commits. Feature branches named
  `feature/VC-###-short-name`.
- No secrets/keys in the repo (broker URL config stays a constant/extras).

## Rule 12 — AI agent handoff

- When an agent touches protocol/UI/engine files, it must cite the relevant doc
  in its summary and obey Rules 4-8.
