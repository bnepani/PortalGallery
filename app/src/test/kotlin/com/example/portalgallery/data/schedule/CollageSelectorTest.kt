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
    fun `a freshly added photo appears in every grid when recency is on`() {
        val old = (1..500).map { item("old$it", 2020 + it % 5, addedYear = 2020 + it % 5) }
        val fresh = Item("FRESH", false, ms(2026, 9, 8), ms(2026, 9, 8))
        val slots = CollageLayout.forPanel(false).first().slots

        repeat(5) { r ->
            val picks = fill(
                old + fresh, slots,
                CollageSelector.Config(eraMix = true, recency = true), rotation = r,
            )
            assertTrue("grid $r missed the new arrival", picks.any { it.id == "FRESH" })
        }
    }

    @Test
    fun `recency off means no reserved slot`() {
        val old = (1..500).map { item("old$it", 2021) }
        val fresh = Item("FRESH", false, ms(2026, 9, 8), ms(2026, 9, 8))
        val slots = CollageLayout.forPanel(false).first().slots
        val grids = (0 until 10).map {
            fill(old + fresh, slots, CollageSelector.Config(eraMix = true, recency = false), rotation = it)
        }
        assertTrue(
            "without recency, FRESH should not be in every grid",
            grids.any { g -> g.none { it.id == "FRESH" } },
        )
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

    @Test
    fun `every bucket appears within a few rotations when buckets exceed slots`() {
        val years = 2019..2026 // 8 buckets
        val items = years.flatMap { y -> (1..20).map { item("p$y-$it", y) } }
        val slots = CollageLayout.forPanel(false).first().slots // 3 slots

        val seen = mutableSetOf<Int>()
        repeat(8) { r ->
            fill(items, slots, CollageSelector.Config(eraMix = true, recency = false), rotation = r)
                .forEach { seen.add(java.time.Instant.ofEpochMilli(it.captureMs).atZone(zone).year) }
        }
        assertEquals("every era must appear within 8 grids", years.toSet(), seen)
    }

    @Test
    fun `single bucket does not crash and fills every slot`() {
        val slots = CollageLayout.forPanel(false).first().slots
        val picks = fill(
            (1..20).map { item("p$it", 2024) }, slots,
            CollageSelector.Config(eraMix = true),
        )
        assertEquals(slots.size, picks.size)
    }

    @Test
    fun `allocate always hands out exactly one bucket per slot`() {
        val weights = CollageSelector.bucketWeights(listOf(2019 to 400, 2026 to 6000))
        (0 until 20).forEach { r ->
            assertEquals(6, CollageSelector.allocate(weights, slotCount = 6, rotation = r).size)
        }
    }

    @Test
    fun `rotation moves the leftover seats to different buckets`() {
        // Eight equal buckets over three slots: every quota is 0.375, so every seat is a
        // leftover seat. A stateless largest-remainder pass ranks the same three eras
        // first in every grid and the other five never appear at all.
        val weights = (2019..2026).associateWith { 1.0 / 8 }
        val grids = (0 until 8).map { CollageSelector.allocate(weights, slotCount = 3, rotation = it) }
        assertEquals("every bucket must win a seat", (2019..2026).toSet(), grids.flatten().toSet())
    }

    @Test
    fun `heavy buckets get proportionally more slots over many rotations`() {
        // Rotation must not flatten the weights into a round robin: 2026 is owed nine
        // times the screen time of 2019 and must still get it.
        val weights = mapOf(2019 to 0.1, 2026 to 0.9)
        val counts = (0 until 100)
            .flatMap { CollageSelector.allocate(weights, slotCount = 6, rotation = it) }
            .groupingBy { it }.eachCount()
        assertEquals(600, counts.values.sum())
        assertEquals(60.0, counts.getValue(2019).toDouble(), 6.0)
    }

    @Test
    fun `no slot position is pinned to a single era`() {
        // The apportionment pass emits seats in descending-credit order. Mapped straight
        // onto slots that pins the heaviest era to slot 0 forever — on thirds-portrait, a
        // permanently recent left column and a permanently old right one. Without the
        // per-grid permutation slots 0-4 here are 2026 in all 500 grids.
        val weights = mapOf(2019 to 0.1, 2026 to 0.9)
        val slotCount = 6
        val grids = (0 until 500).map {
            CollageSelector.allocate(weights, slotCount = slotCount, rotation = it)
        }
        (0 until slotCount).forEach { s ->
            val counts = grids.map { it[s] }.groupingBy { it }.eachCount()
            assertTrue("slot $s never showed the light era", counts.getOrDefault(2019, 0) > 0)
            val top = counts.values.max()
            assertTrue("slot $s showed one era $top/500 times", top < 475)
        }
    }

    @Test
    fun `spreading eras across positions leaves the long-run share alone`() {
        // The permutation moves seats between positions, never between buckets, so the
        // 9:1 split has to survive it intact.
        val weights = mapOf(2019 to 0.1, 2026 to 0.9)
        val counts = (0 until 500)
            .flatMap { CollageSelector.allocate(weights, slotCount = 6, rotation = it) }
            .groupingBy { it }.eachCount()
        assertEquals(3000, counts.values.sum())
        assertEquals(300.0, counts.getValue(2019).toDouble(), 30.0)
    }

    @Test
    fun `a bucket too thin to win any single grid is still seated eventually`() {
        // One photo against seven years of 2,857 — the shape of an archive with a stray
        // scanned print in it. Its quota is about 0.016 of a six-slot grid, so it can
        // never win outright; the carried remainder is what eventually seats it.
        val weights = CollageSelector.bucketWeights(
            listOf(2019 to 1) + (2020..2026).map { it to 2857 },
        )
        val seen = (0 until 128)
            .flatMap { CollageSelector.allocate(weights, slotCount = 6, rotation = it) }
            .toSet()
        assertTrue("the thinnest era must not starve", 2019 in seen)
    }

    @Test
    fun `allocate survives a wrapped rotation counter`() {
        // The counter is a plain Int on a frame that runs for months, so it goes negative.
        val weights = mapOf(2024 to 0.5, 2025 to 0.5)
        assertEquals(4, CollageSelector.allocate(weights, slotCount = 4, rotation = -7).size)
        assertEquals(4, CollageSelector.allocate(weights, slotCount = 4, rotation = Int.MIN_VALUE).size)
    }

    @Test
    fun `all-zero weights round robin rather than stacking one era`() {
        // bucketWeights returns all zeros when every bucket it is handed is empty. fill
        // cannot reach that today, but Step 12 filters the pools by orientation and a
        // year can lose every photo there, so pin the behaviour now.
        //
        // It is sane, not accidental: zero quota means no bucket ever accrues credit, so
        // the 1.0 charged for a seat is the only thing separating them and each seat goes
        // to whichever bucket has paid least. Six slots over three eras is two apiece,
        // which beats the alternative of one era taking the whole grid.
        val weights = mapOf(2019 to 0.0, 2024 to 0.0, 2026 to 0.0)
        (0 until 4).forEach { r ->
            val picks = CollageSelector.allocate(weights, slotCount = 6, rotation = r)
            assertEquals(6, picks.size)
            assertEquals(
                "rotation $r must seat every era twice, got $picks",
                mapOf(2019 to 2, 2024 to 2, 2026 to 2),
                picks.groupingBy { it }.eachCount(),
            )
        }
    }

    @Test
    fun `allocate returns nothing when there is nothing to apportion`() {
        assertTrue(CollageSelector.allocate(emptyMap(), slotCount = 6, rotation = 0).isEmpty())
        assertTrue(CollageSelector.allocate(mapOf(2024 to 1.0), slotCount = 0, rotation = 0).isEmpty())
    }
}
