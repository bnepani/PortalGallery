package com.example.portalgallery.data.schedule

import com.example.portalgallery.data.schedule.PhotoSelector.Applied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class PhotoSelectorTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    private data class Item(val portrait: Boolean, val captureMs: Long)

    /** 2026-08-24 is a Monday. */
    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val monday = at(2026, 8, 24)
    private val tuesday = at(2026, 8, 25)
    private val saturday = at(2026, 8, 22)

    private fun select(
        library: List<Item>,
        wantPortrait: Boolean = false,
        weekday: Boolean = true,
        today: DayOfWeek = DayOfWeek.MONDAY,
    ) = PhotoSelector.select(
        library, wantPortrait, weekday, today, zone,
        isPortrait = { it.portrait },
        captureMs = { it.captureMs },
    )

    // --- weekday matching ----------------------------------------------------

    @Test
    fun `matches only the same day of week`() {
        assertTrue(PhotoSelector.matchesWeekday(monday, DayOfWeek.MONDAY, zone))
        assertFalse(PhotoSelector.matchesWeekday(tuesday, DayOfWeek.MONDAY, zone))
        assertTrue(PhotoSelector.matchesWeekday(saturday, DayOfWeek.SATURDAY, zone))
    }

    /**
     * An index written before capture times were stored has none. Treating those as
     * non-matching would empty the frame the moment someone upgrades, before the next
     * sync backfills the timestamps.
     */
    @Test
    fun `items with no timestamp always match`() {
        assertTrue(PhotoSelector.matchesWeekday(null, DayOfWeek.MONDAY, zone))
        assertTrue(PhotoSelector.matchesWeekday(0L, DayOfWeek.FRIDAY, zone))
    }

    // --- both filters hold ---------------------------------------------------

    @Test
    fun `applies weekday and orientation together when both match`() {
        val library = listOf(
            Item(portrait = false, captureMs = monday),
            Item(portrait = false, captureMs = tuesday),
            Item(portrait = true, captureMs = monday),
        )
        val s = select(library, wantPortrait = false)
        assertEquals(Applied.WEEKDAY_AND_ORIENTATION, s.applied)
        assertEquals(1, s.items.size)
        assertFalse(s.relaxed)
    }

    // --- relaxation ----------------------------------------------------------

    /**
     * The Arizona case: a trip album shot entirely over one weekend. On a Monday a
     * strict filter yields nothing, and the frame would go blank.
     */
    @Test
    fun `drops the weekday filter when nothing was taken today`() {
        val library = listOf(
            Item(portrait = false, captureMs = saturday),
            Item(portrait = false, captureMs = saturday),
        )
        val s = select(library, wantPortrait = false, today = DayOfWeek.MONDAY)
        assertEquals(Applied.ORIENTATION_ONLY, s.applied)
        assertEquals(2, s.items.size)
        assertTrue(s.relaxed)
    }

    @Test
    fun `drops both filters when orientation also matches nothing`() {
        val library = listOf(
            Item(portrait = true, captureMs = saturday),
            Item(portrait = true, captureMs = saturday),
        )
        val s = select(library, wantPortrait = false, today = DayOfWeek.MONDAY)
        assertEquals(Applied.NONE, s.applied)
        assertEquals(2, s.items.size)
    }

    /** The invariant the whole design rests on. */
    @Test
    fun `never returns empty for a non-empty library`() {
        val library = listOf(Item(portrait = true, captureMs = saturday))
        DayOfWeek.values().forEach { day ->
            listOf(true, false).forEach { orientation ->
                val s = select(library, wantPortrait = orientation, today = day)
                assertTrue("empty for $day / portrait=$orientation", s.items.isNotEmpty())
            }
        }
    }

    @Test
    fun `disabled weekday filter leaves orientation alone`() {
        val library = listOf(
            Item(portrait = false, captureMs = tuesday),
            Item(portrait = true, captureMs = tuesday),
        )
        val s = select(library, wantPortrait = false, weekday = false)
        assertEquals(Applied.ORIENTATION_ONLY, s.applied)
        assertEquals(1, s.items.size)
    }

    @Test
    fun `empty library stays empty`() {
        assertTrue(select(emptyList()).items.isEmpty())
    }

    @Test
    fun `countForToday ignores orientation`() {
        val library = listOf(
            Item(portrait = true, captureMs = monday),
            Item(portrait = false, captureMs = monday),
            Item(portrait = false, captureMs = tuesday),
        )
        assertEquals(2, PhotoSelector.countForToday(library, DayOfWeek.MONDAY, zone) { it.captureMs })
    }
}
