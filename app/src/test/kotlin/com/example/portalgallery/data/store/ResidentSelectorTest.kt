package com.example.portalgallery.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class ResidentSelectorTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun ms(y: Int, m: Int = 6, d: Int = 1) =
        LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entry(id: String, year: Int) =
        AlbumIndex.Entry(id = id, baseUrl = "https://x/pw/$id", width = 4000, height = 3000,
            captureMs = ms(year), addedMs = ms(year))

    /** Roughly the shape of the real album: 2019-2020 heaviest, 2026 lightest. */
    private fun realisticArchive(): List<AlbumIndex.Entry> {
        val byYear = mapOf(2019 to 3657, 2020 to 3843, 2021 to 3280, 2022 to 2916,
                           2023 to 1297, 2024 to 3189, 2025 to 1134, 2026 to 658)
        return byYear.flatMap { (y, n) -> (1..n).map { entry("y$y-$it", y) } }
    }

    private fun choose(index: List<AlbumIndex.Entry>, target: Int, pin: Int = 300, seed: Int = 7) =
        ResidentSelector.choose(index, target, pin, zone, Random(seed))

    @Test
    fun `returns exactly the target size`() {
        assertEquals(1500, choose(realisticArchive(), 1500).size)
    }

    @Test
    fun `never returns duplicates`() {
        val out = choose(realisticArchive(), 1500)
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test
    fun `an index smaller than the target is taken whole`() {
        val small = (1..50).map { entry("s$it", 2024) }
        assertEquals(50, choose(small, 1500).size)
    }

    @Test
    fun `degenerate inputs do not throw`() {
        assertTrue(choose(emptyList(), 1500).isEmpty())
        assertTrue(ResidentSelector.choose(realisticArchive(), 0, 0, zone, Random(1)).isEmpty())
        assertTrue(ResidentSelector.choose(realisticArchive(), -5, 300, zone, Random(1)).isEmpty())
    }

    @Test
    fun `the newest photos are always resident`() {
        // The guarantee the frame depends on: whatever the era stratification prefers, a
        // photo added this week is on the device. Without it the collage's reserved
        // recency slot would have nothing to draw.
        val archive = realisticArchive()
        val newest = archive.sortedByDescending { it.captureMs }.take(300).map { it.id }.toSet()
        val resident = choose(archive, 1500, pin = 300).map { it.id }.toSet()
        assertTrue("all 300 newest must be resident", resident.containsAll(newest))
    }

    @Test
    fun `every era is represented`() {
        val resident = choose(realisticArchive(), 1500)
        val years = resident.mapNotNull {
            java.time.Instant.ofEpochMilli(it.captureMs).atZone(zone).year
        }.toSet()
        assertEquals("all 8 years must appear", (2019..2026).toSet(), years)
    }

    @Test
    fun `no era is starved even though the archive is lopsided`() {
        // 2026 is the smallest year in the real album at 3.3% of it. Proportional sampling
        // would give it ~50 of 1500; sqrt damping should give it meaningfully more.
        val resident = choose(realisticArchive(), 1500)
        val counts = resident.groupingBy {
            java.time.Instant.ofEpochMilli(it.captureMs).atZone(zone).year
        }.eachCount()
        assertTrue("2026 was starved: ${counts[2026]}", (counts[2026] ?: 0) >= 60)
        assertTrue("2019 dominated: ${counts[2019]}", (counts[2019] ?: 0) <= 500)
    }

    @Test
    fun `download order leads with the newest`() {
        // The frame is usable long before the download finishes, so what arrives first
        // decides what the wall shows for the first several minutes.
        val out = choose(realisticArchive(), 1500)
        val firstTen = out.take(10).map {
            java.time.Instant.ofEpochMilli(it.captureMs).atZone(zone).year
        }
        assertTrue("expected recent years at the head, got $firstTen", firstTen.all { it >= 2025 })
    }

    @Test
    fun `re-rolling with a different seed changes the sample but keeps the pinned newest`() {
        val archive = realisticArchive()
        val a = choose(archive, 1500, seed = 1).map { it.id }.toSet()
        val b = choose(archive, 1500, seed = 2).map { it.id }.toSet()
        assertTrue("a re-roll should move a meaningful share of the sample",
            (a subtract b).size > 100)

        val newest = archive.sortedByDescending { it.captureMs }.take(300).map { it.id }.toSet()
        assertTrue(a.containsAll(newest))
        assertTrue(b.containsAll(newest))
    }

    @Test
    fun `the same seed gives the same sample`() {
        assertEquals(
            choose(realisticArchive(), 1500, seed = 42).map { it.id },
            choose(realisticArchive(), 1500, seed = 42).map { it.id },
        )
    }

    @Test
    fun `undated photos are bucketed rather than dropped`() {
        // captureMs 0 means unknown, not 1970. They must still be eligible.
        val archive = realisticArchive() + (1..500).map {
            AlbumIndex.Entry(id = "undated$it", baseUrl = "https://x", width = 4000, height = 3000)
        }
        val resident = choose(archive, 1500)
        assertTrue("undated photos must be reachable",
            resident.any { it.id.startsWith("undated") })
    }

    // --- the two-generation grace --------------------------------------------

    @Test
    fun `keepIds spans this generation and the last`() {
        // A file dropped from the sample can still be on screen: the renderer holds a list
        // handed to it earlier, and the re-roll happens on another thread. Keeping the
        // previous generation is what makes that safe without a handshake.
        val current = listOf(AlbumIndex.Entry(id = "a"), AlbumIndex.Entry(id = "b"))
        val keep = ResidentSelector.keepIds(current, listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), keep)
    }

    @Test
    fun `keepIds handles a first run with no previous generation`() {
        assertEquals(setOf("a"), ResidentSelector.keepIds(listOf(AlbumIndex.Entry(id = "a")), emptyList()))
    }
}
