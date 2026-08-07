package dev.meshcall.sdk.internal.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.meshcall.sdk.R
import dev.meshcall.sdk.internal.util.MeshLog

/**
 * Foreground service that exists so screen capture is legal.
 *
 * Android 10 requires a foreground service to be running before `getMediaProjection()` is
 * called, and Android 14 enforces that it declare the `mediaProjection` type — without this
 * the projection request throws instead of returning a capturer.
 *
 * It carries no logic of its own: the meeting keeps running in [dev.meshcall.sdk.api.MeshCall]
 * either way, and this only exists for the lifetime of a share.
 *
 * Started through [start], which reports back once the service is genuinely in the
 * foreground — that ordering is the whole point, so callers must not race it.
 */
internal class MeshScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.meshcall_screen_share_title))
            .setContentText(getString(R.string.meshcall_screen_share_text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // The platform call rather than ServiceCompat: the typed overload is what Android 10+
        // requires for a projection, and going direct keeps this off any androidx version.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Only now is it safe to ask for a projection.
        val ready = pendingReady
        pendingReady = null
        ready?.invoke()

        // START_NOT_STICKY: a restarted service would hold a notification with no projection
        // behind it, since the consent token does not survive process death.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.meshcall_screen_share_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "meshcall_screen_share"
        private const val NOTIFICATION_ID = 4711

        /**
         * Invoked once the service is in the foreground. Held statically because a Service
         * cannot be handed a callback through an Intent, and the alternative — binding —
         * would add a second async hop to a path that is already ordering-sensitive.
         */
        @Volatile
        private var pendingReady: (() -> Unit)? = null

        /**
         * Start the service and run [onReady] once it is foregrounded. Screen capture must
         * not be requested before that callback fires.
         */
        fun start(context: Context, onReady: () -> Unit) {
            pendingReady = onReady
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MeshScreenShareService::class.java),
                )
            } catch (e: Exception) {
                // Throws when the app is in the background on Android 12+. The share simply
                // does not start; leaving the callback pending would strand it forever.
                pendingReady = null
                MeshLog.e(TAG, "could not start the screen share service", e)
                throw e
            }
        }

        fun stop(context: Context) {
            pendingReady = null
            context.stopService(Intent(context, MeshScreenShareService::class.java))
        }

        private const val TAG = "ScreenShare"
    }
}
