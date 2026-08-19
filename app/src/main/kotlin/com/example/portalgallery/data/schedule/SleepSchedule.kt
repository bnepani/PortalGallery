package com.example.portalgallery.data.schedule

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Quiet-hours window for the frame.
 *
 * Pure and Android-free so the wrap-around cases are unit tested. Windows that cross
 * midnight (22:00 → 07:00) are the normal case for a photo frame and are the easiest
 * thing to get wrong here.
 *
 * Times are wall-clock in the device's local zone. That is deliberate: if the Portal is
 * set to Pacific, "midnight to 7am" means midnight to 7am Pacific, and it follows DST
 * without any special handling.
 */
data class SleepSchedule(
    val enabled: Boolean,
    val start: LocalTime,
    val end: LocalTime,
) {
    companion object {
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val DISABLED = SleepSchedule(false, LocalTime.MIDNIGHT, LocalTime.of(7, 0))

        /** Parses "HH:mm". Returns null on anything unparseable. */
        fun parseTime(value: String?): LocalTime? = value?.let {
            runCatching { LocalTime.parse(it.trim(), FMT) }.getOrNull()
        }

        fun format(time: LocalTime): String = time.format(FMT)
    }

    /**
     * A window where start == end is treated as "never sleep" rather than "always
     * sleep". Always-asleep is never what someone means, and it would present as a
     * dead frame.
     */
    fun isAsleepAt(now: LocalTime): Boolean {
        if (!enabled || start == end) return false
        return if (start < end) {
            // Same-day window, e.g. 01:00 -> 06:00
            now >= start && now < end
        } else {
            // Crosses midnight, e.g. 22:00 -> 07:00
            now >= start || now < end
        }
    }

    /**
     * The next moment the frame should wake, at or after [from].
     *
     * Needed because a Handler cannot be relied on across screen-off, and because
     * FLAG_KEEP_SCREEN_ON cannot switch a dark panel back on — waking requires an
     * AlarmManager alarm scheduled at this instant.
     */
    fun nextWake(from: LocalDateTime): LocalDateTime {
        val todayEnd = from.toLocalDate().atTime(end)
        return if (todayEnd.isAfter(from)) todayEnd else todayEnd.plusDays(1)
    }

    fun describe(): String =
        if (!enabled) "sleep disabled"
        else "asleep ${format(start)}–${format(end)} local"
}
