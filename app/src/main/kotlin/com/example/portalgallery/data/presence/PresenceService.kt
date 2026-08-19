package com.example.portalgallery.data.presence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.portalgallery.R

/**
 * Holds the app in the foreground so presence detection survives the screen going off.
 *
 * **Why this has to exist.** Android 9 disconnects the camera from any app whose process
 * has no foreground activity and no foreground service. When the frame sleeps, the panel
 * powers down, the Activity is stopped, the process drops to the background, and the
 * system revokes camera access — so nothing can notice someone walking in, and the frame
 * can never wake itself. Sleeping worked; waking could not.
 *
 * The service deliberately does nothing else. It does not own the camera or the
 * detector; those stay in the Activity's process, and process-level foreground state is
 * what the restriction actually keys on. A do-nothing service is far less invasive than
 * moving the whole detection pipeline across a service boundary.
 *
 * The notification is unavoidable — that is the deal Android makes for foreground
 * status. It is set to minimum importance so it sits silently in the shade rather than
 * appearing on the frame.
 */
class PresenceService : Service() {

    companion object {
        private const val TAG = "PortalGallery"
        private const val CHANNEL_ID = "presence"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, PresenceService::class.java))
            }.onFailure { Log.e(TAG, "could not start presence service: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PresenceService::class.java))
            }.onFailure { Log.e(TAG, "could not stop presence service: ${it.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "presence service foregrounded — camera will survive screen-off")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the process is killed, bring the service back, which restores
        // camera access for whatever is left of the app.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "presence service stopped")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.presence_channel_name),
            // MIN, not LOW: no sound, no badge, collapsed in the shade. This is
            // bookkeeping the user did not ask for, so it should be as quiet as the
            // platform permits.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.presence_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.presence_notification_title))
            .setContentText(getString(R.string.presence_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
}
