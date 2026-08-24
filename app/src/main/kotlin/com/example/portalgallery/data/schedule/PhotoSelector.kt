package com.example.portalgallery.data.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Chooses which items are in rotation, from two independent filters.
 *
 * Pure and Android-free, because the interesting behaviour is in what happens when a
 * filter matches nothing — and that is precisely what is hard to reproduce on a device
 * and easy to get wrong.
 *
 * **Why this relaxes rather than filters.** Both filters can legitimately empty the
 * library:
 *
 *  - Orientation: a landscape frame drops every portrait photo, and the reference album
 *    is 56% portrait.
 *  - Weekday: a trip album shot over one weekend contributes nothing at all from Monday
 *    to Friday.
 *
 * Together they are worse than either alone — roughly 1/7 of 44% — so a strict
 * intersection would routinely produce a handful of photos and sometimes none. A frame
 * showing nothing is the one outcome this whole design exists to prevent, so filters are
 * dropped in order of preference until something remains.
 */
object PhotoSelector {

    /** Which filters actually survived, so the UI can explain what is on screen. */
    enum class Applied {
        /** Both filters held. */
        WEEKDAY_AND_ORIENTATION,

        /** Weekday matched nothing today; orientation still applied. */
        ORIENTATION_ONLY,

        /** Neither could be satisfied — showing everything rather than nothing. */
        NONE,
    }

    data class Selection<T>(val items: List<T>, val applied: Applied) {
        val relaxed: Boolean get() = applied != Applied.WEEKDAY_AND_ORIENTATION
    }

    /**
     * True when [captureMs] falls on [today] in [zone].
     *
     * Device-local, deliberately: "photos from a Monday" should mean the Monday the
     * household experienced. A photo taken on a Monday morning in Tokyo may be a Sunday
     * evening locally, and for a family frame the local reading is the intended one.
     *
     * Items with no timestamp — an index written before capture times were stored —
     * count as matching, so an upgrade cannot empty the frame before the next sync
     * backfills them.
     */
    fun matchesWeekday(captureMs: Long?, today: DayOfWeek, zone: ZoneId): Boolean {
        if (captureMs == null || captureMs <= 0L) return true
        return Instant.ofEpochMilli(captureMs).atZone(zone).dayOfWeek == today
    }

    fun <T> select(
        library: List<T>,
        wantPortrait: Boolean,
        weekdayFilterEnabled: Boolean,
        today: DayOfWeek,
        zone: ZoneId,
        isPortrait: (T) -> Boolean,
        captureMs: (T) -> Long?,
    ): Selection<T> {
        if (library.isEmpty()) return Selection(emptyList(), Applied.NONE)

        val byOrientation = library.filter { isPortrait(it) == wantPortrait }

        if (weekdayFilterEnabled) {
            val both = byOrientation.filter { matchesWeekday(captureMs(it), today, zone) }
            if (both.isNotEmpty()) return Selection(both, Applied.WEEKDAY_AND_ORIENTATION)
        }

        if (byOrientation.isNotEmpty()) return Selection(byOrientation, Applied.ORIENTATION_ONLY)

        // Orientation matched nothing either. Showing an ill-fitting photo beats showing
        // a black panel.
        return Selection(library, Applied.NONE)
    }

    /** How many items would match today, ignoring orientation. For the settings readout. */
    fun <T> countForToday(
        library: List<T>,
        today: DayOfWeek,
        zone: ZoneId,
        captureMs: (T) -> Long?,
    ): Int = library.count { matchesWeekday(captureMs(it), today, zone) }
}
