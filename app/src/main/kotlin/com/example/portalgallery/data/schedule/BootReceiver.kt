package com.example.portalgallery.data.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the frame back after a reboot.
 *
 * Two problems this solves. Obviously, a Portal that restarts should return to showing
 * photos rather than sitting on its home screen. Less obviously: **AlarmManager alarms
 * do not survive a reboot.** Without this, a restart during quiet hours would discard
 * the pending wake alarm and leave the frame dark indefinitely.
 *
 * Starting an Activity from a receiver is permitted here because Portal+ runs API 28,
 * which predates the Android 10 background-activity-start restriction. On API 29+ this
 * would be silently dropped and a DreamService or HOME-activity approach would be
 * needed instead.
 *
 * Launching the slideshow is all that is required: its own startup path evaluates the
 * schedule and re-arms the alarm if it is currently quiet hours.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        Log.i("PortalGallery", "boot completed — restoring the frame")

        val launch = Intent().apply {
            setClassName(context, "com.example.portalgallery.ui.slideshow.SlideshowActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.e("PortalGallery", "could not start frame after boot: ${it.message}") }
    }
}
