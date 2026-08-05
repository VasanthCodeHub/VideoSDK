# VideoCall — Jira Stories

## Current State (already implemented)

**Tech stack:** Android (Kotlin, Material 3, min SDK 24 / target 36) + WebRTC mesh SDK (`io.github.webrtc-sdk:android:144.7559.09`) + Socket.IO signaling + Node.js reference broker.

| Story | Description | Status |
|-------|-------------|--------|
| Lobby: Create Room | User taps **Create Room**; app generates a unique 6-char Room ID (unambiguous alphabet) and shows a modal with the code + Copy / Share / Start Call icon buttons | Done |
| Lobby: Join Room | User enters a shared Room ID and joins the existing room | Done |
| Signaling server field | User configures the broker URL (default `ws://10.0.2.2:3000`, LAN IP for real phones) | Done |
| Room code in call | Room ID shown top-left of the in-call screen | Done |
| WebRTC mesh call | Full mesh peer-to-peer audio/video; each phone connects to every other phone in the room (N-1 uplinks) | Done |
| Local camera preview | Front-camera 640x480@30fps preview (top-right, mirrored) | Done |
| Remote video tiles | Renderers are added dynamically per peer as they join/leave | Done |
| Mic toggle | FAB control to mute/unmute; state broadcast to peers | Done |
| Camera toggle | FAB control to disable/enable camera; state broadcast to peers | Done |
| End call | Ends session, disposes WebRTC engine + socket, returns to lobby | Done |
| Permissions | Runtime camera + microphone request flow | Done |
| Signaling broker | Reference Socket.IO v4 Node.js broker (presence, room roster, SDP/ICE relay, reconnect handling) | Done (docs/signaling_server_spec.md) |
| Dark theme | Single Material 3 dark palette, custom in-call controls | Done |
| Media config | Per-link video cap 500 kbps (`MediaConfig.maxVideoKbps`) to keep mesh rooms usable | Done |

## Backlog (suggested future stories)

**1. Camera switch (front/back)**
> As a user, I want to switch between my front and back camera during a call, so that I can show my surroundings.
- AC: New button toggles facing; capture restarts without dropping the call; state resets on rejoin.

**2. Mute / camera-off badges per peer**
> As a user, I want to see which participants have muted mic or disabled camera, so that I know why someone is silent or dark.
- AC: Badge shown on each remote tile based on `peer-state` events; updates in real time.

**3. Connection status indicator**
> As a user, I want to know the ICE/connection state of each peer, so that I can understand if a participant is offline.
- AC: Per-peer status ("connecting / connected / disconnected") driven by `IceStateChanged`; toast or badge on disconnect.

**4. Call timer**
> As a user, I want to see how long the call has lasted, so that I can track the call duration.
- AC: Timer starts at join, stops on end call; shown in the in-call UI.

**5. STUN/TURN server configuration**
> As a user, I want to call people on other networks (not just the same Wi-Fi), so that calls work over the internet.
- AC: `MediaConfig`/SDK accepts ICE server list; validated in a two-network test.

**6. Text chat during the call**
> As a user, I want to send text messages during a call, so that I can share links/details without interrupting audio.
- AC: Chat panel toggled from a FAB; messages routed via WebRTC DataChannel (or signaling); received messages shown live.

**7. Participant list**
> As a user, I want to see who is currently in the room, so that I know the roster at a glance.
- AC: Panel lists names with mic/camera status; updates on join/leave.

**8. Video quality selector**
> As a user, I want to adjust video quality/bitrate, so that I can save data on weak connections.
- AC: Per-session quality presets (e.g. 360p/480p/720p) that restart capture at the chosen resolution.

**9. Landscape support + responsive tile grid**
> As a user, I want to use the app in landscape, so that I can make calls on a large screen.
- AC: Call layout adapts to landscape; remote tiles stay visible; controls reposition correctly.

**10. Incoming-call experience (presence-based)**
> As a user, I want to join a room as a guest without creating a room first, so that invitees can enter from a shared link.
- AC: Deep link (`videocall://join/<room>`) opens Join Room prefilled; room link shareable via Android share sheet.

**11. Copy room code from the call screen**
> As a user, I want to copy the room code during a call, so that I can invite more people mid-call.
- AC: Tapping the "Room: XXXX" badge copies the code and shows a toast.

**12. Foreground service / background call**
> As a user, I want the call to keep running when I switch apps or lock the screen, so that I don't drop the call.
- AC: Foreground service keeps WebRTC alive; audio continues in background; permission handled on Android 14+.

**13. Reconnection UX**
> As a user, I want to see when the connection drops and reconnects, so that I don't get confused by a frozen screen.
- AC: Banner/toast on signaling disconnect and on ICE failure; auto-retry with backoff already present in socket client.

**14. Call recording**
> As a user, I want to record the call, so that I can review it later.
- AC: Start/stop recording from controls; saved to app media folder; mic + remote audio captured.

**15. Error surfacing improvements**
> As a user, I want actionable messages when something fails (no camera, unreachable server), so that I can fix the issue myself.
- AC: Distinct toasts/dialogs for: server unreachable, camera in use, microphone denied; retry button for server failures.

## Notes / Known limitations
- Media uses **host-only ICE candidates** (no STUN/TURN) → calls work only on the same LAN for now (see Backlog #5).
- Full mesh topology is practical up to ~4-5 participants on mobile Wi-Fi (per-link 500 kbps cap).
- `ws://` cleartext is enabled app-wide for dev (`android:usesCleartextTraffic="true"`); switch to `wss://` + HTTPS for production.
