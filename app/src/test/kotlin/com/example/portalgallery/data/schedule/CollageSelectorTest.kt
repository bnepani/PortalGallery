package com.example.portalgallery.data.schedule

import com.example.portalgallery.ui.slideshow.CollageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `buckets are damped, not uniform`() {
        val w = CollageSelector.bucketWeights(listOf(2019 to 10, 2026 to 1000))
        assertTrue("2026 must outweigh 2019", w.getValue(2026) > w.getValue(2019))
        val ratio = w.getValue(2026) / w.getValue(2019)
        assertTrue("expected ~10x, got $ratio", ratio in 8.0..12.0)
    }

    @Test
    fun `bucket weights are shares and so sum to one`() {
        val w = CollageSelector.bucketWeights(listOf(2019 to 400, 2024 to 3000, 2026 to 6000))
        assertEquals(1.0, w.values.sum(), 1e-9)
    }

    @Test
    fun `empty buckets weigh zero rather than dividing by zero`() {
        // Reachable: bucketByYear never yields an empty list, but bucketWeights is also
        // fed counts filtered down by orientation, and a year can lose every photo there.
        val w = CollageSelector.bucketWeights(listOf(2019 to 0, 2026 to 0))
        assertEquals(setOf(2019, 2026), w.keys)
        assertTrue(w.values.all { it == 0.0 })
    }

    @Test
    fun `bucketByYear groups items by their capture year`() {
        val items = listOf(item("a", 2019), item("b", 2024), item("c", 2019))
        val buckets = CollageSelector.bucketByYear(items, zone) { it.captureMs }
        assertEquals(setOf(2019, 2024), buckets.keys)
        assertEquals(listOf("a", "c"), buckets.getValue(2019).map { it.id })
        assertEquals(listOf("b"), buckets.getValue(2024).map { it.id })
    }

    @Test
    fun `the year is read in the device zone, not UTC`() {
        // 16:30 on New Year's Eve in Los Angeles is already 2020 in UTC. The household
        // lived it as 2019, and PhotoSelector reads capture dates the same local way.
        val nye = LocalDateTime.of(2019, 12, 31, 16, 30).atZone(zone).toInstant().toEpochMilli()
        val buckets = CollageSelector.bucketByYear(
            listOf(Item("nye", false, nye, nye)),
            zone,
        ) { it.captureMs }
        assertEquals(setOf(2019), buckets.keys)
    }

    @Test
    fun `undated photos share one bucket instead of inventing a 1970 era`() {
        // An index written before capture times were stored reports 0. Feeding that to
        // Instant would mint a 1970 bucket, and sqrt damping would then hand a phantom
        // era a real share of the screen.
        val items = listOf(
            Item("zero", false, 0L, ms(2026, 1, 1)),
            Item("negative", false, -1L, ms(2026, 1, 1)),
            item("dated", 2024),
        )
        val buckets = CollageSelector.bucketByYear(items, zone) { it.captureMs }
        assertEquals(setOf(CollageSelector.UNKNOWN_YEAR, 2024), buckets.keys)
        assertFalse("1970 is not an era", buckets.containsKey(1970))
        assertEquals(
            listOf("zero", "negative"),
            buckets.getValue(CollageSelector.UNKNOWN_YEAR).map { it.id },
        )
    }
}
