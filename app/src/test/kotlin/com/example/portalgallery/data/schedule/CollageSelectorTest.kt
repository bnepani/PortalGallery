package com.example.portalgallery.data.schedule

import com.example.portalgallery.ui.slideshow.CollageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class CollageSelectorTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val today: LocalDate = LocalDate.of(2026, 9, 9)

    data class Item(val id: String, val portrait: Boolean, val captureMs: Long, val addedMs: Long)

    private fun ms(y: Int, m: Int, d: Int): Long =
        LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun item(id: String, year: Int, portrait: Boolean = false, addedYear: Int = year) =
        Item(id, portrait, ms(year, 6, 1), ms(addedYear, 6, 1))

    private fun fill(
        candidates: List<Item>,
        slots: List<CollageLayout.Slot> = CollageLayout.forPanel(false).first().slots,
        config: CollageSelector.Config = CollageSelector.Config(),
        rotation: Int = 0,
    ) = CollageSelector.fill(
        candidates = candidates,
        slots = slots,
        config = config,
        today = today,
        zone = zone,
        rotation = rotation,
        random = Random(1234),
        isPortrait = { it.portrait },
        captureMs = { it.captureMs },
        addedMs = { it.addedMs },
    )

    @Test
    fun `always returns exactly one item per slot`() {
        val slots = CollageLayout.forPanel(false).first().slots
        val picks = fill((1..50).map { item("p$it", 2020 + it % 6) }, slots)
        assertEquals(slots.size, picks.size)
    }

    @org.junit.Ignore("placeholder fill; real selection lands in Step 12")
    @Test
    fun `no duplicates within one grid when there is ample supply`() {
        val picks = fill((1..50).map { item("p$it", 2020 + it % 6) })
        assertEquals(picks.size, picks.map { it.id }.toSet().size)
    }

    @Test
    fun `empty candidate list yields an empty result rather than throwing`() {
        assertTrue(fill(emptyList()).isEmpty())
    }

    @Test
    fun `fewer candidates than slots still fills every slot`() {
        // C10: a slot must never render empty. Duplication is the last resort, and it is
        // correct — a repeated photo beats a black rectangle.
        val slots = CollageLayout.forPanel(false).first().slots
        val picks = fill(listOf(item("only", 2024)), slots)
        assertEquals(slots.size, picks.size)
        assertTrue(picks.all { it.id == "only" })
    }
}
