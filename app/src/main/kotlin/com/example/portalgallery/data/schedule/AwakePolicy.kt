package com.example.portalgallery.data.schedule

/**
 * Decides whether the frame should be awake, from three independent inputs.
 *
 * Kept pure and Android-free because the combinations are where this gets subtle: three
 * inputs with different precedence, and the wrong answer means either a frame that
 * never wakes or one that burns all night.
 *
 * Precedence, highest first:
 *
 *  1. **Manual override** — an explicit "sleep now"/"wake now". Cleared at the next
 *     scheduled boundary by the caller, so it cannot persist indefinitely.
 *  2. **Presence** — when detection is actually running, it governs completely, in both
 *     directions. Someone in view wakes the frame even during quiet hours; an empty room
 *     sleeps it even at midday.
 *  3. **Schedule** — the fallback whenever presence is unavailable.
 *
 * Note what (2) implies: while presence detection works, the quiet-hours schedule has no
 * effect. That is intended. The schedule exists for when the camera cannot answer —
 * privacy shutter closed, permission denied, detection switched off, hardware busy.
 * Treating "camera unavailable" as "nobody is here" would put the frame to sleep
 * permanently, which is the failure this precedence exists to prevent.
 */
object AwakePolicy {

    enum class Presence {
        /** Someone seen within the absence timeout. */
        PRESENT,

        /** Detection is running and has seen nobody for longer than the timeout. */
        ABSENT,

        /** Detection cannot run: no camera, shutter closed, no permission, or disabled. */
        UNAVAILABLE,
    }

    /**
     * @param scheduleAsleep whether quiet hours currently apply
     * @param presence current detection state
     * @param manualOverride true = forced asleep, false = forced awake, null = neither
     */
    fun shouldBeAwake(
        scheduleAsleep: Boolean,
        presence: Presence,
        manualOverride: Boolean?,
    ): Boolean = when {
        manualOverride != null -> !manualOverride
        presence == Presence.UNAVAILABLE -> !scheduleAsleep
        else -> presence == Presence.PRESENT
    }

    /** Human-readable reason, for the settings readout and logs. */
    fun explain(
        scheduleAsleep: Boolean,
        presence: Presence,
        manualOverride: Boolean?,
    ): String = when {
        manualOverride == true -> "asleep: manual override"
        manualOverride == false -> "awake: manual override"
        presence == Presence.UNAVAILABLE && scheduleAsleep -> "asleep: quiet hours (no camera)"
        presence == Presence.UNAVAILABLE -> "awake: schedule (no camera)"
        presence == Presence.PRESENT -> "awake: someone in view"
        else -> "asleep: room empty"
    }
}
