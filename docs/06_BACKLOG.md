# 06 — Backlog (Jira Stories)

> Every feature as a Jira story with priority, acceptance criteria, and
> dependencies. Current state is in `00_PROJECT_OVERVIEW.md`.

## Story list

### VC-001 — Camera Switch (front/back)
- **Priority:** High
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Button toggles front/back camera during a call
  - ✓ No reconnect or call drop on switch
  - ✓ Capture restarts at the new facing
  - ✓ Facing state resets to front after leaving a room

### VC-002 — Per-Peer Mute / Camera-Off Badges
- **Priority:** High
- **Dependency:** none (peer-state already relayed)
- **Acceptance criteria:**
  - ✓ Remote tile shows mic-off / camera-off badge driven by `peer-state`
  - ✓ Badges update live; local state broadcasts on toggle
  - ✓ Peer list state survives roster rebuilds

### VC-003 — Participant List
- **Priority:** Medium
- **Dependency:** VC-002
- **Acceptance criteria:**
  - ✓ Panel lists every participant (name + mic/camera state)
  - ✓ Updates on join/leave/toggle in real time
  - ✓ Excludes self; shows participant count

### VC-004 — Connection Status Indicator
- **Priority:** Medium
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Per-peer ICE state surfaced in UI (connecting/connected/disconnected/failed)
  - ✓ Toast/banner on peer disconnect or reconnect
  - ✓ Broker unreachable shows distinct error (not silent)

### VC-005 — Call Timer
- **Priority:** Low
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Timer starts when the call screen appears
  - ✓ Stops and resets on end call
  - ✓ Format hh:mm:ss; visible without overlapping video

### VC-006 — STUN/TURN Configuration
- **Priority:** High
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ SDK accepts an ICE server list (MediaConfig / API)
  - ✓ Calls work between different networks (validated 2-network test)
  - ✓ Existing LAN-only behavior unchanged when no servers configured

### VC-007 — In-Call Text Chat
- **Priority:** Medium
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Chat panel opens from a FAB; messages delivered in real time
  - ✓ Routing via WebRTC DataChannel (fallback: signaling relay)
  - ✓ Unread indicator; chat survives peer reconnect

### VC-008 — Video Quality Presets
- **Priority:** Low
- **Dependency:** VC-001 (capture restart path)
- **Acceptance criteria:**
  - ✓ Presets (360p/480p/720p) selectable during call
  - ✓ Capture restarts at chosen resolution without dropping the call
  - ✓ Bitrate cap follows preset

### VC-009 — Landscape + Responsive Tile Grid
- **Priority:** Medium
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Call screen usable in landscape (controls repositioned)
  - ✓ Remote tiles scale to available space (grid up to N)
  - ✓ No SurfaceView z-order regressions (see 02 pitfalls)

### VC-010 — Deep-Link Invitations
- **Priority:** Medium
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ `videocall://join/<code>` opens Join Room prefilled
  - ✓ Share sheet can send the room link
  - ✓ Invalid/missing code shows validation message

### VC-011 — Copy Room Code During Call
- **Priority:** Low
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Tapping the room-code badge copies it to clipboard
  - ✓ Toast confirmation

### VC-012 — Foreground Service / Background Call
- **Priority:** High
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Call continues when app is backgrounded / screen locked
  - ✓ Mic+audio keep working; camera may pause in background
  - ✓ Foreground notification with ongoing-call actions
  - ✓ Android 14+ foreground service permission handled

### VC-013 — Call Recording
- **Priority:** Low
- **Dependency:** VC-012
- **Acceptance criteria:**
  - ✓ Start/stop recording from controls
  - ✓ Captures mic + remote audio (+ local video for preview)
  - ✓ Saved to app media folder; filename includes room id + timestamp

### VC-014 — Actionable Error Surfacing
- **Priority:** High
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Distinct dialogs/toasts: server unreachable, camera in use, mic denied
  - ✓ Retry action for server failures
  - ✓ No crash on camera unavailability at join

### VC-015 — Authentication / User Profiles
- **Priority:** Low
- **Dependency:** none
- **Acceptance criteria:**
  - ✓ Display name editable in lobby (persisted)
  - ✓ (Optional) sign-in backend; stable userId across sessions

## Definition of Ready / Done

- **Ready:** story has priority + acceptance criteria + no unknown protocol changes.
- **Done:** code merged, `08_TESTING.md` checklist item verified, protocol docs
  updated (if applicable), changelog updated.
