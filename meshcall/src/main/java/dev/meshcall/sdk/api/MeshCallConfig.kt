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
 * Defaults target a 4-5 person mesh on mobile Wi-Fi: 720p capture with a per-link
 * bitrate ceiling, because a mesh participant uploads its stream once per peer.
 */
data class MeshCallConfig(
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val frameRate: Int = 30,
    /**
     * Per-link video ceiling in kbps, enforced on every outgoing sender via RTP encoding
     * parameters (and mirrored as `b=TIAS` in the SDP). A mesh uploads N-1 video streams,
     * so capping each one keeps a 4-5 person meeting inside a mobile uplink instead of
     * letting the encoder burst freely. Raise only when every participant is on fast
     * wired backhaul; set 0 to disable the cap.
     */
    val maxVideoKbps: Int = 1000,
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
