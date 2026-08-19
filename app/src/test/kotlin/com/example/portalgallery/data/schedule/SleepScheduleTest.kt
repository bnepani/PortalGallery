package com.example.portalgallery.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class SleepScheduleTest {

    private fun at(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private fun schedule(start: LocalTime, end: LocalTime) =
        SleepSchedule(enabled = true, start = start, end = end)

    // --- same-day window ---------------------------------------------------

    @Test
    fun `same-day window covers times inside it`() {
        val s = schedule(at(0), at(7))     // midnight -> 7am, the requested default
        assertTrue(s.isAsleepAt(at(0)))    // inclusive start
        assertTrue(s.isAsleepAt(at(3, 30)))
        assertTrue(s.isAsleepAt(at(6, 59)))
    }

    @Test
    fun `same-day window excludes times outside it`() {
        val s = schedule(at(0), at(7))
        assertFalse("end is exclusive — 07:00 is awake", s.isAsleepAt(at(7)))
        assertFalse(s.isAsleepAt(at(12)))
        assertFalse(s.isAsleepAt(at(23, 59)))
    }

    // --- window crossing midnight ------------------------------------------

    @Test
    fun `window crossing midnight covers the evening side`() {
        val s = schedule(at(22), at(7))
        assertTrue(s.isAsleepAt(at(22)))
        assertTrue(s.isAsleepAt(at(23, 59)))
    }

    @Test
    fun `window crossing midnight covers the morning side`() {
        val s = schedule(at(22), at(7))
        assertTrue(s.isAsleepAt(at(0)))
        assertTrue(s.isAsleepAt(at(6, 59)))
    }

    @Test
    fun `window crossing midnight leaves the day awake`() {
        val s = schedule(at(22), at(7))
        assertFalse(s.isAsleepAt(at(7)))
        assertFalse(s.isAsleepAt(at(12)))
        assertFalse(s.isAsleepAt(at(21, 59)))
    }

    // --- degenerate cases ---------------------------------------------------

    /** Always-asleep is never the intent, and would look exactly like a broken frame. */
    @Test
    fun `equal start and end means never sleep`() {
        val s = schedule(at(3), at(3))
        assertFalse(s.isAsleepAt(at(3)))
        assertFalse(s.isAsleepAt(at(15)))
    }

    @Test
    fun `disabled schedule never sleeps`() {
        val s = SleepSchedule(enabled = false, start = at(0), end = at(23, 59))
        assertFalse(s.isAsleepAt(at(2)))
    }

    // --- next wake ----------------------------------------------------------
    // These drive the AlarmManager alarm. Getting them wrong means the frame sleeps
    // and never comes back — the exact failure this logic was added to fix.

    @Test
    fun `next wake is later today when the wake time has not passed`() {
        val s = schedule(at(0), at(7))
        assertEquals(
            LocalDateTime.of(2026, 8, 18, 7, 0),
            s.nextWake(LocalDateTime.of(2026, 8, 18, 2, 30)),
        )
    }

    @Test
    fun `next wake rolls to tomorrow once today's wake time has passed`() {
        val s = schedule(at(0), at(7))
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 7, 0),
            s.nextWake(LocalDateTime.of(2026, 8, 18, 23, 30)),
        )
    }

    @Test
    fun `next wake from a window crossing midnight lands the same night`() {
        val s = schedule(at(22), at(7))
        // 23:30 Tuesday -> 07:00 Wednesday
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 7, 0),
            s.nextWake(LocalDateTime.of(2026, 8, 18, 23, 30)),
        )
        // 01:00 Wednesday -> 07:00 the same Wednesday
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 7, 0),
            s.nextWake(LocalDateTime.of(2026, 8, 19, 1, 0)),
        )
    }

    @Test
    fun `next wake exactly at the boundary rolls forward a day`() {
        val s = schedule(at(0), at(7))
        // Not "now" — otherwise an alarm set for this instant could fire immediately
        // and re-trigger in a loop.
        assertEquals(
            LocalDateTime.of(2026, 8, 19, 7, 0),
            s.nextWake(LocalDateTime.of(2026, 8, 18, 7, 0)),
        )
    }

    // --- parsing ------------------------------------------------------------

    @Test
    fun `parses 24-hour times`() {
        assertEquals(at(0), SleepSchedule.parseTime("00:00"))
        assertEquals(at(7), SleepSchedule.parseTime("07:00"))
        assertEquals(at(22, 30), SleepSchedule.parseTime("22:30"))
        assertEquals(at(7), SleepSchedule.parseTime("  07:00  "))
    }

    @Test
    fun `rejects unparseable times rather than guessing`() {
        assertNull(SleepSchedule.parseTime(null))
        assertNull(SleepSchedule.parseTime(""))
        assertNull(SleepSchedule.parseTime("7am"))
        assertNull(SleepSchedule.parseTime("25:00"))
        assertNull(SleepSchedule.parseTime("7:00 PM"))
    }
}
