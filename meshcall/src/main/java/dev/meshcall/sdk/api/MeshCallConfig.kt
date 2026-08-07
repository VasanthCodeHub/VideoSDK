package dev.meshcall.sdk.api

/**
 * One STUN or TURN server the mesh may use for connectivity.
 *
 * - **STUN** discovers your public address so two peers behind ordinary NATs can reach
 *   each other. Free, stateless, no credentials.
 * - **TURN** relays media when a direct path is impossible (symmetric NAT, restrictive
 *   corporate firewalls). It costs bandwidth and needs credentials, but without one a
 *   meaningful fraction of real-world calls simply never connect.
 */
data class IceServerConfig(
    val urls: String,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * Per-session tuning passed to [MeshCall.join].
 *
 * Defaults target a 4-5 person mesh on mobile Wi-Fi: 720p capture at 24fps, encoded at a
 * resolution *and* bitrate re-derived from the live link count, because a mesh participant
 * uploads its own stream once per peer.
 *
 * The defaults are picked to hold roughly 0.08-0.12 bits per pixel at every meeting size,
 * which is the band where realtime video stops looking smeared. Five people land on
 * 960x540@24 at 1000 kbps; smaller meetings spend the surplus on bitrate rather than on
 * resolution, because a phone-sized grid tile has no use for more pixels.
 */
data class MeshCallConfig(
    /**
     * Capture size, and the ceiling on the encoded frame. The SDK steps *down* from here as
     * the meeting grows (see [uplinkBudgetKbps]); it never steps above.
     *
     * Left at 720p on purpose even though the encoder seldom sends 720p. This is the size
     * handed to `startCapture`, and a camera can only deliver a format it actually
     * supports — ask for 960x540 and the enumerator substitutes its "closest match", which
     * on a good number of devices is 640x480: lower *and* 4:3 instead of 16:9. Capturing at
     * a universally supported 16:9 format and scaling down to the tier on the way to the
     * encoder lands on the intended size everywhere.
     */
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    /**
     * Capture and encode frame rate.
     *
     * 24 rather than 30 because bitrate buys either motion or detail, and a grid of faces
     * wants detail. Giving up the last six frames leaves roughly 25% more bits for every
     * frame that is sent — a visible sharpness gain against a barely visible loss of
     * smoothness.
     */
    val frameRate: Int = 24,
    /**
     * Per-link video ceiling in kbps, enforced on every outgoing sender via RTP encoding
     * parameters (and mirrored as `b=TIAS` in the SDP). This is the *one-to-one* quality:
     * with a single peer to feed, that link gets the whole ceiling.
     *
     * A ceiling, not a target — congestion control decides the actual send rate underneath
     * it. Raising this does not make video better on a link that cannot carry it; it only
     * gives the encoder room to overshoot, take loss, and oscillate. Raise it only if you
     * know every participant has uplink to spare. Set 0 to remove the cap entirely.
     *
     * The number that matters is bits per pixel, not kbps: sharpness is bitrate divided by
     * pixels-per-second, and realtime video needs roughly 0.08 bpp to stop looking smeared.
     * 1500 kbps is what 960x540@24 (the tier a small meeting lands on) needs to clear that
     * comfortably. An earlier default of 1000 kbps against 720p30 capture worked out to
     * 0.036 bpp — under half of what the frame needed — which made every call soft
     * regardless of how many people were in it.
     */
    val maxVideoKbps: Int = 1500,
    /**
     * Total video the device is willing to upload, in kbps, split evenly across the links.
     *
     * This is the knob that keeps a 5-person meeting sharp. A mesh sends N-1 copies of the
     * same camera, so a fixed per-link cap silently multiplies: five people at 1 Mbps each
     * is 4 Mbps off one phone, far past a normal uplink. Congestion control then claws it
     * back unevenly and every tile goes soft at once. Dividing a fixed budget instead means
     * the encoder asks for what the link can plausibly deliver, and the SDK drops capture
     * resolution to match, so the bits that do arrive land on a smaller, sharper picture
     * rather than a mushy 720p one.
     *
     * Per-link bitrate is `uplinkBudgetKbps / links`, clamped to [maxVideoKbps] at the top
     * and to a legibility floor at the bottom. At the default of 4000 the split starts
     * biting from the third link on (four participants); one- and two-link meetings get the
     * full [maxVideoKbps]. Set 0 to disable the split entirely.
     *
     * 4000 is chosen so the five-person case — the one this SDK is built for — still lands
     * on 1000 kbps per link, which is exactly what 960x540@24 needs to stay sharp. The
     * device uploads 4 Mbps total at that point: comfortable on Wi-Fi, and the honest
     * ceiling of what a mesh can ask of a phone before congestion control starts making the
     * decisions for you.
     */
    val uplinkBudgetKbps: Int = 4000,
    val startWithMicOn: Boolean = true,
    val startWithCameraOn: Boolean = true,
    val useFrontCamera: Boolean = true,
    /**
     * ICE servers used for connectivity. Defaults to public STUN, which is enough for
     * most home/mobile networks. Pass `emptyList()` for LAN-only host candidates, or add
     * a TURN entry to make cross-network calls reliable.
     */
    val iceServers: List<IceServerConfig> = DEFAULT_ICE_SERVERS,
) {
    companion object {
        /** Google's public STUN servers. Fine for development; add TURN for production. */
        val DEFAULT_ICE_SERVERS: List<IceServerConfig> = listOf(
            IceServerConfig("stun:stun.l.google.com:19302"),
            IceServerConfig("stun:stun1.l.google.com:19302"),
        )
    }
}

/** The SDK's authoritative local mic/camera state. Drives control buttons and the self tile. */
data class LocalMediaState(
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
)
