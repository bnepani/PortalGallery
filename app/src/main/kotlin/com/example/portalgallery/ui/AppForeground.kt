package com.example.portalgallery.ui

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether any of this app's screens is currently on display.
 *
 * [PresenceService][com.example.portalgallery.data.presence.PresenceService] relaunches
 * the frame when someone is in view and the app is not visible. That check has to be
 * process-wide rather than per-Activity: tracking only the slideshow would mean opening
 * settings looks like "the app is gone", and the service would relaunch the slideshow on
 * top of whoever is using it — which, since presence is detected precisely when someone
 * is standing at the frame, would be every time.
 *
 * The grace window covers the handoff between two of our own activities. During a
 * transition the outgoing `onPause` runs before the incoming `onResume`, so a bare
 * counter briefly reads zero; without the grace, that gap is enough for a service tick
 * to fire a spurious relaunch.
 */
object AppForeground {

    private const val HANDOFF_GRACE_MS = 3_000L

    private val resumedCount = AtomicInteger(0)

    @Volatile
    private var lastVisibleMs = 0L

    fun onActivityResumed() {
        resumedCount.incrementAndGet()
        lastVisibleMs = System.currentTimeMillis()
    }

    fun onActivityPaused() {
        if (resumedCount.decrementAndGet() < 0) resumedCount.set(0)
        lastVisibleMs = System.currentTimeMillis()
    }

    val isVisible: Boolean
        get() = resumedCount.get() > 0 ||
            System.currentTimeMillis() - lastVisibleMs < HANDOFF_GRACE_MS
}
