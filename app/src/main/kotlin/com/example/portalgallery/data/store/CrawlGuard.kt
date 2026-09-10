package com.example.portalgallery.data.store

/**
 * Decides whether a crawl may be believed, and whether it may delete anything.
 *
 * Pure and Android-free so every branch can be tested, because this is the piece that
 * stands between a bad crawl and a deleted library. Three independent reviews of the
 * design named the same scenario as the most likely way the whole project fails:
 *
 * > The crawl works, and then quietly stops working. Some week — a soft rate limit, a
 * > shard hiccup, a server-side cap — the RPC returns a clean, token-less page 40 of 67.
 * > Every guard passes: HTTP 200, every album read, 12,000 items against 20,000 is more
 * > than half. `prune()` deletes 8,000 photos. No error is logged, no alarm fires, and the
 * > frame keeps cycling from a smaller pool. The family notices months later, if at all.
 *
 * That is not hypothetical. Executing the pagination RPC for the first time produced
 * exactly this shape from a different cause: the wrong argument slot returned HTTP 200,
 * 300 well-formed entries, and a fresh continuation token on every call — a crawl that
 * would loop forever reporting progress while collecting the same 300 photos.
 *
 * So the rule here is narrow and deliberately hard to satisfy: **nothing is deleted unless
 * every album was read all the way to the end.** Everything else — an error, a cap, a
 * short crawl, a suspicious shrink — is allowed to add photos and forbidden to remove any.
 */
object CrawlGuard {

    /**
     * Fraction of the previous index below which a *complete* crawl is disbelieved.
     *
     * 0.98, not the 0.5 the download path uses. That figure was calibrated when the only
     * outcomes were "300 items" or "nothing", so anything in between meant catastrophe.
     * Pagination makes failure continuous: a crawl can stop at any page and return any
     * fraction, and every crawl reaching page 34 of 67 would sail through a half-sized
     * threshold. Against a whole-album index a legitimate change is a fraction of a
     * percent — photos leave a shared album occasionally, not by the thousand.
     */
    const val SHRINK_ALARM = 0.98

    /**
     * Fraction of the previous page count below which a crawl is treated as truncated
     * even though it claimed to finish.
     *
     * The backstop for the failure a token check cannot see. If a server-side cap starts
     * returning a token-less page 40 where 67 were needed, completeness looks true and the
     * item count is the only other evidence — and [SHRINK_ALARM] only rejects, it does not
     * explain. Comparing pages catches the same event earlier and more specifically.
     */
    const val PAGE_SHORTFALL = 0.9

    /**
     * One album's crawl.
     *
     * @param complete the pager ran out of continuation tokens. Not "no exception was
     *   thrown" — a crawl abandoned after a 429, or cut off at a cap, is incomplete even
     *   though nothing failed loudly.
     */
    data class AlbumOutcome(
        val url: String,
        val items: Int,
        val pages: Int,
        val complete: Boolean,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    sealed class Verdict {
        /**
         * The crawl may be written to the index.
         *
         * @param mayPrune whether files absent from it may be deleted. False is the common
         *   case and the safe one; it means the index grows or holds steady and nothing is
         *   removed until a crawl earns the right.
         */
        data class Accept(val mayPrune: Boolean, val reason: String) : Verdict()

        /** The crawl is not believable. Keep the previous index untouched. */
        data class Reject(val reason: String) : Verdict()
    }

    /**
     * @param previous the last index written, or null on a first run.
     * @param outcomes one per configured album.
     * @param newSize unique items across every album that was read.
     */
    fun evaluate(
        previous: AlbumIndex.Snapshot?,
        outcomes: List<AlbumOutcome>,
        newSize: Int,
    ): Verdict {
        if (outcomes.isEmpty()) return Verdict.Reject("no albums configured")

        if (outcomes.none { it.ok }) {
            return Verdict.Reject(
                "all ${outcomes.size} album(s) failed — " +
                    outcomes.joinToString("; ") { "${it.url}: ${it.error}" }
            )
        }

        if (newSize == 0) {
            // Every album that was read returned nothing. That is a parser or transport
            // failure wearing the shape of an empty album, and the two are indistinguishable
            // from here — so believe the one that cannot destroy anything.
            return Verdict.Reject("read $newSize items from ${outcomes.count { it.ok }} album(s)")
        }

        val failed = outcomes.filterNot { it.ok }
        val truncated = outcomes.filter { it.ok && !it.complete }

        // Completeness is a property of the whole set. One unreadable album out of five
        // means the union is missing that album's photos entirely, and pruning against it
        // would delete every one of them — the multi-album hazard the download path already
        // guards, restated for the index.
        if (failed.isNotEmpty() || truncated.isNotEmpty()) {
            val why = buildString {
                if (failed.isNotEmpty()) {
                    append("${failed.size} album(s) unreadable")
                    if (truncated.isNotEmpty()) append(", ")
                }
                if (truncated.isNotEmpty()) append("${truncated.size} album(s) truncated")
            }
            return Verdict.Accept(mayPrune = false, reason = "$why — adding only, nothing removed")
        }

        // Past here every album was read to its end.

        if (previous == null) {
            return Verdict.Accept(mayPrune = true, reason = "first complete crawl, $newSize items")
        }

        if (newSize < previous.size * SHRINK_ALARM) {
            return Verdict.Reject(
                "suspicious shrink: $newSize vs ${previous.size} " +
                    "(below ${(SHRINK_ALARM * 100).toInt()}%) — keeping the previous index"
            )
        }

        // A complete crawl that took materially fewer pages than last time did not get
        // smaller, it got cut off somewhere that still handed back a clean ending.
        val pages = outcomes.sumOf { it.pages }
        if (previous.pageCount > 0 && pages < previous.pageCount * PAGE_SHORTFALL) {
            return Verdict.Accept(
                mayPrune = false,
                reason = "finished in $pages pages against ${previous.pageCount} last time — " +
                    "treating as truncated, adding only",
            )
        }

        return Verdict.Accept(
            mayPrune = true,
            reason = "complete crawl of ${outcomes.size} album(s), $newSize items in $pages pages",
        )
    }
}
