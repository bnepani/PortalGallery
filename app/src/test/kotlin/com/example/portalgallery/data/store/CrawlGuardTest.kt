package com.example.portalgallery.data.store

import com.example.portalgallery.data.store.CrawlGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11: a silently truncated crawl must never delete photographs.
 *
 * The scenario all three design reviews independently named as the most likely way this
 * project fails has its own test below — `the review's doomsday scenario is refused`.
 * If only one test in this file survives a refactor, it should be that one.
 */
class CrawlGuardTest {

    private fun ok(items: Int, pages: Int = 1, complete: Boolean = true, url: String = "A") =
        CrawlGuard.AlbumOutcome(url, items, pages, complete)

    private fun failed(url: String = "A") =
        CrawlGuard.AlbumOutcome(url, 0, 0, false, error = "HTTP 503")

    private fun previous(size: Int, pages: Int = 67) = AlbumIndex.Snapshot(
        pageCount = pages,
        complete = true,
        items = (1..size).map { AlbumIndex.Entry(id = "id$it") },
    )

    private fun accept(v: Verdict): Verdict.Accept {
        assertTrue("expected Accept, got $v", v is Verdict.Accept)
        return v as Verdict.Accept
    }

    private fun reject(v: Verdict): Verdict.Reject {
        assertTrue("expected Reject, got $v", v is Verdict.Reject)
        return v as Verdict.Reject
    }

    // --- the failure this class exists for -----------------------------------

    @Test
    fun `the review's doomsday scenario is refused`() {
        // A soft cap returns a clean, token-less page 40 of 67. HTTP 200 throughout, no
        // error anywhere, 12,000 items against 20,000 — which sails past a half-sized
        // threshold. Under the old rules prune() deletes 8,000 photos silently.
        val v = CrawlGuard.evaluate(
            previous = previous(20_000, pages = 67),
            outcomes = listOf(ok(items = 12_000, pages = 40, complete = true)),
            newSize = 12_000,
        )
        // 12,000 is below 98% of 20,000, so it never even reaches the page check.
        val r = reject(v)
        assertTrue(r.reason.contains("suspicious shrink"))
        assertTrue(r.reason.contains("12000"))
    }

    @Test
    fun `a token-less crawl that is only page-short still cannot prune`() {
        // The same cap, caught by the page count rather than the item count: the album
        // genuinely grew, so the size check passes, but 40 pages against 67 says the crawl
        // stopped early and its ending cannot be trusted.
        val v = CrawlGuard.evaluate(
            previous = previous(20_000, pages = 67),
            outcomes = listOf(ok(items = 20_100, pages = 40, complete = true)),
            newSize = 20_100,
        )
        val a = accept(v)
        assertFalse("a page-short crawl must not delete anything", a.mayPrune)
        assertTrue(a.reason.contains("truncated"))
    }

    @Test
    fun `an incomplete crawl may add but never remove`() {
        val a = accept(CrawlGuard.evaluate(
            previous = previous(20_000),
            outcomes = listOf(ok(items = 19_000, pages = 64, complete = false)),
            newSize = 19_000,
        ))
        assertFalse(a.mayPrune)
        assertTrue(a.reason.contains("truncated"))
    }

    // --- multi-album ---------------------------------------------------------

    @Test
    fun `one unreadable album out of several blocks pruning`() {
        // Pruning against a union missing an entire album would delete every photo that
        // album contributed — the hazard the download path already guards, restated here.
        val a = accept(CrawlGuard.evaluate(
            previous = previous(20_000),
            outcomes = listOf(ok(items = 15_000, pages = 50), failed("B")),
            newSize = 15_000,
        ))
        assertFalse(a.mayPrune)
        assertTrue(a.reason.contains("unreadable"))
    }

    @Test
    fun `all albums failing is rejected outright`() {
        val r = reject(CrawlGuard.evaluate(
            previous = previous(20_000),
            outcomes = listOf(failed("A"), failed("B")),
            newSize = 0,
        ))
        assertTrue(r.reason.contains("all 2 album(s) failed"))
    }

    @Test
    fun `every album readable and complete permits pruning`() {
        val a = accept(CrawlGuard.evaluate(
            previous = previous(20_000, pages = 67),
            outcomes = listOf(ok(items = 12_000, pages = 40, url = "A"),
                              ok(items = 8_100, pages = 28, url = "B")),
            newSize = 20_100,
        ))
        assertTrue(a.mayPrune)
    }

    // --- the shrink alarm ----------------------------------------------------

    @Test
    fun `the threshold is 98 percent, not 50`() {
        // 0.5 was calibrated for a bimodal 300-or-nothing outcome. Pagination makes
        // failure continuous, so a half-sized threshold accepts every crawl that reached
        // page 34 of 67.
        val justUnder = CrawlGuard.evaluate(previous(20_000), listOf(ok(19_500, 66)), 19_500)
        reject(justUnder)

        val justOver = CrawlGuard.evaluate(previous(20_000), listOf(ok(19_700, 66)), 19_700)
        assertTrue(accept(justOver).mayPrune)
    }

    @Test
    fun `a handful of deletions is normal and still prunes`() {
        // Photos do leave shared albums. The alarm must not fire on ordinary churn.
        val a = accept(CrawlGuard.evaluate(previous(20_000, 67), listOf(ok(19_950, 67)), 19_950))
        assertTrue(a.mayPrune)
    }

    @Test
    fun `growth is never suspicious`() {
        val a = accept(CrawlGuard.evaluate(previous(20_000, 67), listOf(ok(25_000, 84)), 25_000))
        assertTrue(a.mayPrune)
    }

    // --- degenerate inputs ---------------------------------------------------

    @Test
    fun `reading zero items is a failure, not an empty album`() {
        // Indistinguishable from a parser break, and one of the two readings deletes
        // everything. Believe the other one.
        val r = reject(CrawlGuard.evaluate(previous(20_000), listOf(ok(0, 1)), 0))
        assertTrue(r.reason.contains("read 0 items"))
    }

    @Test
    fun `no albums configured is rejected`() {
        reject(CrawlGuard.evaluate(previous(20_000), emptyList(), 0))
    }

    @Test
    fun `a first complete crawl may prune with no previous index to compare`() {
        val a = accept(CrawlGuard.evaluate(null, listOf(ok(19_974, 67)), 19_974))
        assertTrue(a.mayPrune)
        assertTrue(a.reason.contains("first complete crawl"))
    }

    @Test
    fun `a first crawl that is incomplete may not prune`() {
        val a = accept(CrawlGuard.evaluate(null, listOf(ok(5_000, 17, complete = false)), 5_000))
        assertFalse(a.mayPrune)
    }

    @Test
    fun `a previous index with no page count does not trip the page check`() {
        // An index written before pageCount was recorded must not be read as "0 pages" and
        // make every later crawl look enormous by comparison.
        val prior = AlbumIndex.Snapshot(pageCount = 0, complete = true,
            items = (1..20_000).map { AlbumIndex.Entry(id = "id$it") })
        val a = accept(CrawlGuard.evaluate(prior, listOf(ok(20_000, 67)), 20_000))
        assertTrue(a.mayPrune)
    }

    @Test
    fun `every verdict carries a reason worth logging`() {
        val verdicts = listOf(
            CrawlGuard.evaluate(null, emptyList(), 0),
            CrawlGuard.evaluate(null, listOf(failed()), 0),
            CrawlGuard.evaluate(null, listOf(ok(0, 1)), 0),
            CrawlGuard.evaluate(null, listOf(ok(100, 1)), 100),
            CrawlGuard.evaluate(previous(20_000), listOf(ok(1, 1)), 1),
            CrawlGuard.evaluate(previous(20_000, 67), listOf(ok(20_000, 10)), 20_000),
            CrawlGuard.evaluate(previous(100), listOf(ok(100, 1, complete = false)), 100),
        )
        verdicts.forEach { v ->
            val reason = when (v) {
                is Verdict.Accept -> v.reason
                is Verdict.Reject -> v.reason
            }
            assertTrue("a bare verdict is undiagnosable months later: $v", reason.length > 12)
        }
        assertEquals(7, verdicts.size)
    }
}
