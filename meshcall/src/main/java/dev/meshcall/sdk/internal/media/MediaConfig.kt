package dev.meshcall.sdk.internal.media

import org.webrtc.CameraVideoCapturer
import org.webrtc.VideoSource

/**
 * Media acquisition + transport settings for one call session.
 *
 * Plain data holder; the mesh engine reads it once when creating the local track
 * sources and again when a media toggle re-describes the session.
 * Defaults reflect a conservative baseline instead of a bandwidth-hungry maximum so
 * a (mesh, Wi-Fi-only) room of ~4 stays usable on older phones.
 */
data class MediaConfig(
    val cameraFacing: CameraFacing = CameraFacing.FRONT,
    val frameRate: Int = 30,
    val videoWidth: Int = 640,
    val videoHeight: Int = 480,
    val audioEchoCancellation: Boolean = true,
    val audioNoiseSuppression: Boolean = true,
    val audioAutoGainControl: Boolean = true,
    val initialMicOn: Boolean = true,
    val initialCameraOn: Boolean = true,
    /**
     * Per-link video ceiling in kbps applied to every local SDP before it is sent.
     * A mesh uploads N-1 video streams, so capping each one keeps a 4-5 person room
     * within mobile Wi-Fi uplink instead of letting an encoder burst free. Raise it
     * only when every peer is on a fast backhaul.
     */
    val maxVideoKbps: Int = 500,
) {
    enum class CameraFacing { FRONT, BACK }
}
