package dev.meshcall.sdk.ui

import android.content.Context
import android.util.AttributeSet
import org.webrtc.SurfaceViewRenderer

/**
 * Thin wrapper over a WebRTC [SurfaceViewRenderer] that the host layout places in
 * XML. The room view (see [MeshCallRoomView]) binds a local or remote [VideoTrack]
 * to one of these instances.
 *
 * The EGL base context used for drawing is shared via the SDK's internal engine; a
 * consumer never touches EGL directly. Add an instance to a layout with:
 *
 * ```
 * <dev.meshcall.sdk.ui.MeshVideoRenderer
 *     android:id="@+id/local_preview"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:scaleType="centerCrop" />
 * ```
 *
 * ComponentId: sdk.ui.meshvideorenderer
 */
class MeshVideoRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceViewRenderer(context, attrs)
