package dev.meshcall.sdk.internal.media

import dev.meshcall.sdk.api.IceServerConfig
import dev.meshcall.sdk.api.MeshCallConfig

/**
 * Media acquisition + transport settings for one meeting session.
 *
 * Internal mirror of the public [MeshCallConfig]; the engine reads it once when creating
 * the local sources and again whenever a toggle re-describes the session.
 */
internal data class MediaConfig(
    val cameraFacing: CameraFacing = CameraFacing.FRONT,
    val frameRate: Int = 30,
    val videoWidth: Int = 640,
    val videoHeight: Int = 480,
    val initialMicOn: Boolean = true,
    val initialCameraOn: Boolean = true,
    val maxVideoKbps: Int = 500,
    val iceServers: List<IceServerConfig> = MeshCallConfig.DEFAULT_ICE_SERVERS,
) {
    enum class CameraFacing { FRONT, BACK }

    companion object {
        fun from(config: MeshCallConfig) = MediaConfig(
            cameraFacing = if (config.useFrontCamera) CameraFacing.FRONT else CameraFacing.BACK,
            frameRate = config.frameRate,
            videoWidth = config.videoWidth,
            videoHeight = config.videoHeight,
            initialMicOn = config.startWithMicOn,
            initialCameraOn = config.startWithCameraOn,
            maxVideoKbps = config.maxVideoKbps,
            iceServers = config.iceServers,
        )
    }
}
