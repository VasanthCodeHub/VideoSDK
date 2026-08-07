package dev.meshcall.sdk.ui

import android.content.Context
import android.util.AttributeSet
import org.webrtc.SurfaceViewRenderer

/**
 * Thin wrapper over a WebRTC [SurfaceViewRenderer].
 *
 * [MeshParticipantGrid] binds a local or remote video track to one of these per tile, and
 * initializes it against the SDK's shared EGL context — a consumer never touches EGL
 * directly. Usually you get these for free from [MeshMeetingView]; declare one yourself
 * only when building a custom meeting screen:
 *
 * ```
 * <dev.meshcall.sdk.ui.MeshVideoRenderer
 *     android:id="@+id/local_preview"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * Never give one an `elevation`, an opaque background, or `clipToOutline`, in XML or in code
 * — the background composites above the video surface and hides it, and `clipToOutline` has
 * no effect on an underlay surface anyway. Round the corners by masking over the video from
 * a sibling view instead, the way [TileFrameDrawable] does (README §7 rule 6).
 */
class MeshVideoRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceViewRenderer(context, attrs)
