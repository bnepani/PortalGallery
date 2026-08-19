package com.example.portalgallery.data.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wakes the frame at the end of quiet hours.
 *
 * A Handler cannot do this job, for two independent reasons:
 *
 *  1. Once the panel powers down the Activity is paused, and any tick tied to its
 *     lifecycle stops with it. That was the original bug: the frame slept correctly and
 *     then had nothing left running to wake it.
 *  2. Even a surviving tick could not help, because FLAG_KEEP_SCREEN_ON only *prevents*
 *     a screen from sleeping. Nothing in the window flags turns a dark panel back on.
 *
 * So waking needs an AlarmManager alarm plus a wake lock that explicitly powers the
 * display. Exact alarms need no special permission on API 28.
 */
object WakeAlarm {

    private const val TAG = "PortalGallery"
    private const val REQUEST_CODE = 4711
    const val ACTION_WAKE = "com.example.portalgallery.ACTION_WAKE"

    /** Fire just after the boundary, not exactly on it — see [BOUNDARY_BUFFER_MS]. */
    private const val BOUNDARY_BUFFER_MS = 5_000L

    fun schedule(context: Context, at: LocalDateTime) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // If the alarm landed exactly on the wake instant, a fraction of a second of
        // clock skew could have isAsleepAt() still return true. The frame would go
        // straight back to sleep with the alarm already spent, and never wake at all.
        val triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() +
            BOUNDARY_BUFFER_MS

        // setExactAndAllowWhileIdle survives Doze. Doze is unreachable on a mains-powered
        // Portal anyway, but this costs nothing and removes a dependency on that staying true.
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
        Log.i(TAG, "wake alarm set for $at")
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(pendingIntent(context))
        Log.i(TAG, "wake alarm cancelled")
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, Receiver::class.java).setAction(ACTION_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Physically powers the display on.
     *
     * Needed by every wake path, not only the alarm. Presence detection wakes the frame
     * from the Activity itself, and FLAG_KEEP_SCREEN_ON cannot turn on a panel that is
     * already off — it only stops one from switching off. Any code path that wakes a
     * possibly-dark frame has to come through here.
     *
     * SCREEN_BRIGHT_WAKE_LOCK is deprecated but remains the mechanism that actually
     * powers a display on, and it works on API 28. Held only long enough for the
     * Activity to take over via KEEP_SCREEN_ON. Safe to call when already awake.
     */
    @Suppress("DEPRECATION")
    fun powerScreenOn(context: Context, holdMs: Long = 10_000L) {
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "PortalGallery:wake",
            )
            lock.acquire(holdMs)
            if (lock.isHeld) lock.release()
        }.onFailure { Log.e(TAG, "could not power the screen on: ${it.message}") }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_WAKE) return
            Log.i(TAG, "wake alarm fired")

            powerScreenOn(context, holdMs = 30_000L)

            // API 28 predates the Android 10 background-activity-start restriction, so
            // this is permitted. It covers the case where the process was killed while
            // asleep — otherwise the screen would light up on the Portal home screen.
            // No "wake" command extra: by the time this fires the schedule itself says
            // awake, so evaluateSleepState() handles it. Forcing a manual override here
            // would only add state that then has to be cleared.
            val launch = Intent().apply {
                setClassName(context, "com.example.portalgallery.ui.slideshow.SlideshowActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { context.startActivity(launch) }
                .onFailure { Log.e(TAG, "could not bring the frame forward: ${it.message}") }
        }
    }
}
