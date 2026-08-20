package com.example.portalgallery.data.presence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.portalgallery.R
import com.example.portalgallery.prefs.AppPreferences

/**
 * Owns presence detection, and brings the frame back to the foreground when someone
 * arrives.
 *
 * **Why the service owns this.** Two separate failures made an Activity-owned detector
 * unworkable on Portal:
 *
 *  1. Android 9 disconnects the camera from any process with no foreground activity and
 *     no foreground service. Once the panel powers off, the Activity stops and the
 *     system revokes camera access — so the frame could detect absence but never
 *     presence.
 *  2. Even with the camera alive, cycling the panel hands the foreground to Portal's
 *     launcher. The Activity kept running and kept waking the screen, but the user saw
 *     the Portal home screen: presence woke the *device* and not the *gallery*.
 *
 * So this service holds the process foreground, owns the detector so detection survives
 * the Activity being destroyed, and relaunches the frame when presence returns.
 *
 * The notification is the price Android charges for foreground status. Minimum
 * importance, so it stays silent and collapsed in the shade.
 */
class PresenceService : Service() {

    companion object {
        private const val TAG = "PortalGallery"
        private const val CHANNEL_ID = "presence"
        private const val NOTIFICATION_ID = 1

        /** Matches the frame's own presence tick; fast enough that walking up to the
         *  frame feels immediate rather than laggy. */
        private const val TICK_MS = 3_000L

        /** Don't hammer startActivity while someone stands in front of the frame. */
        private const val RELAUNCH_COOLDOWN_MS = 10_000L

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

    private lateinit var prefs: AppPreferences
    private lateinit var detector: PresenceDetector
    private val handler = Handler(Looper.getMainLooper())
    private var lastRelaunchMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            runCatching { evaluate() }
                .onFailure { Log.w(TAG, "presence tick failed: ${it.message}") }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        detector = PresenceDetector.get(this)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "presence service foregrounded — camera will survive screen-off")

        detector.start()
        handler.post(tick)
    }

    /**
     * If someone is in view and the frame is not on screen, put it back on screen.
     *
     * This is the fix for "presence woke the Portal but not the gallery". It covers both
     * shapes of the problem: an Activity that is alive but behind Portal's launcher
     * (reordered to front) and an Activity that no longer exists (launched fresh).
     */
    private fun evaluate() {
        if (!prefs.presenceEnabled) return
        if (detector.status != PresenceDetector.Status.RUNNING) {
            detector.recoverIfStopped()
            return
        }

        val idleMs = System.currentTimeMillis() - detector.referenceMs
        val present = idleMs < prefs.absenceTimeoutMinutes * 60_000L
        if (!present) return

        // Process-wide, not slideshow-specific: otherwise opening settings reads as
        // "the app is gone" and this would relaunch the slideshow over the top of it.
        // getRunningTasks is restricted and unreliable, so the activities report in.
        if (com.example.portalgallery.ui.AppForeground.isVisible) return

        val now = System.currentTimeMillis()
        if (now - lastRelaunchMs < RELAUNCH_COOLDOWN_MS) return
        lastRelaunchMs = now

        Log.i(TAG, "someone in view but the frame is not on screen — bringing it forward")

        // Power the panel first. Bringing an Activity forward does not turn a dark
        // screen on, and the two together are what "wake on presence" actually means.
        com.example.portalgallery.data.schedule.WakeAlarm.powerScreenOn(this)

        val launch = Intent().apply {
            setClassName(
                this@PresenceService,
                "com.example.portalgallery.ui.slideshow.SlideshowActivity",
            )
            // API 28 predates the Android 10 background-activity-start restriction, so
            // a service may do this. REORDER_TO_FRONT handles the common case where the
            // Activity is alive behind the launcher; NEW_TASK covers a cold start.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        runCatching { startActivity(launch) }
            .onFailure { Log.e(TAG, "could not bring the frame forward: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the process is killed, bring the service back, which restores
        // both camera access and the relaunch watchdog.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        detector.stop()
        Log.i(TAG, "presence service stopped")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.presence_channel_name),
            // MIN: no sound, no badge, collapsed. This is bookkeeping the user did not
            // ask for, so it should be as quiet as the platform permits.
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
