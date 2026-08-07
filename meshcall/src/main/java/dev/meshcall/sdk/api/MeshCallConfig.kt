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
 * Defaults target a 4-5 person mesh on mobile Wi-Fi: 720p capture, with the encoder's
 * bitrate *and* resolution re-derived from the live link count, because a mesh
 * participant uploads its own stream once per peer.
 */
data class MeshCallConfig(
    /**
     * Capture ceiling. This is the *most* the camera will ever be encoded at — the SDK
     * steps down from here as the meeting grows (see [uplinkBudgetKbps]); it never steps
     * above.
     */
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val frameRate: Int = 30,
    /**
     * Per-link video ceiling in kbps, enforced on every outgoing sender via RTP encoding
     * parameters (and mirrored as `b=TIAS` in the SDP). This is the *one-to-one* quality:
     * with a single peer to feed, that link gets the whole ceiling.
     *
     * A ceiling, not a target — congestion control decides the actual send rate underneath
     * it. Raising this does not make video better on a link that cannot carry it; it only
     * gives the encoder room to overshoot, take loss, and oscillate. Raise it only if you
     * know every participant has uplink to spare. Set 0 to remove the cap entirely.
     */
    val maxVideoKbps: Int = 1000,
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
     * and to a legibility floor at the bottom. At the default of 3000 the split only bites
     * from the fourth link on (five participants) — smaller meetings each get the full
     * [maxVideoKbps] and are unaffected. Set 0 to disable the split entirely.
     */
    val uplinkBudgetKbps: Int = 3000,
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
