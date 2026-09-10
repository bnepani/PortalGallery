package com.example.portalgallery.data.schedule

import com.example.portalgallery.ui.slideshow.CollageLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Chooses which photo lands in which collage slot.
 *
 * Pure and Android-free for the same reason as [PhotoSelector]: the behaviour worth testing
 * is what happens when a preference matches nothing, and that is the part that is hard to
 * reproduce on a wall-mounted panel and easy to get wrong.
 *
 * **A slot must never render empty.** A collage draws every tile at once, so a slot this
 * class declines to fill is a black rectangle sitting beside five photographs for the whole
 * dwell — far more conspicuous than the full-screen path repeating itself. Every preference
 * therefore relaxes rather than returning short, dropped in this order, least costly first:
 *
 *  1. **Era target** — the year bucket [allocate] assigned to the slot. Cheapest to drop,
 *     because it is a statement about the mix rather than about this photo: the balance
 *     reasserts itself on the next rotation, which is only a dwell away.
 *  2. **Recency and on-this-day** — preferences over *which* photo inside a bucket, not
 *     over which photos are eligible, so dropping one makes the draw less pointed and
 *     nothing worse.
 *  3. **Orientation** — the slot's [CollageLayout.Slot.wantPortrait] tag, dropped last,
 *     because those tags are the whole reason collage mode exists. On a landscape panel
 *     the full-screen path discards the 56% of the library that is portrait; a portrait
 *     slot is the only way those photographs reach the screen. Filling one with a
 *     landscape photo — letterboxed into a tall box, or cropped to a strip — hands back
 *     exactly the ground the tags were added to win, so orientation is worth more than
 *     either preference above it.
 *
 * If dropping all three still leaves no unused photo, one already on screen is repeated.
 * Duplication is the terminal fallback and it is the right one: a repeated photograph
 * reads as a design choice, a black tile reads as a broken frame.
 */
object CollageSelector {

    data class Config(
        val eraMix: Boolean = true,
        val onThisDay: Boolean = false,
        val recency: Boolean = true,
    )

    /** Photos added within this window are "new" for recency purposes. */
    private const val RECENT_WINDOW_MS = 21L * 24 * 60 * 60 * 1000

    /** Bucket key for photos whose capture time is missing. Cannot collide with a real year. */
    const val UNKNOWN_YEAR = -1

    /**
     * Slot share per year bucket, damped by sqrt of bucket size.
     *
     * **This is a product choice, stated.** A family archive is not uniform: 2026 may hold
     * 6,000 photos and 2019 four hundred. Strict one-slot-per-year would give 2019 16.7%
     * of screen time for 2% of the archive, making a 2019 photo recur ~15x as often as a
     * 2026 one. Raw proportional allocation goes the other way and buries the old years.
     * sqrt sits between: old years stay clearly visible, recent years still dominate.
     */
    fun bucketWeights(sizes: List<Pair<Int, Int>>): Map<Int, Double> {
        val raw = sizes.associate { (year, n) -> year to sqrt(n.toDouble()) }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: return sizes.associate { it.first to 0.0 }
        return raw.mapValues { it.value / total }
    }

    fun <T> bucketByYear(items: List<T>, zone: ZoneId, captureMs: (T) -> Long): Map<Int, List<T>> =
        items.groupBy { t ->
            val ms = captureMs(t)
            // captureMs 0 means "unknown" — the index predates timestamps. Group these
            // together rather than mapping them all to 1970 and inventing a huge bucket.
            if (ms <= 0L) UNKNOWN_YEAR
            else Instant.ofEpochMilli(ms).atZone(zone).year
        }

    /**
     * How many grids of the remainder ledger [allocate] replays before it starts over.
     *
     * The bound exists so the cost cannot grow with a counter that ticks every dwell for
     * months; resetting the ledger makes the sequence repeat rather than degrade. It has
     * to outlast the thinnest bucket's wait, and that is what fixes the size: across the
     * reference 20,000-photo archive the roots sum to roughly 400, so a bucket holding a
     * single photo has a quota near 6/400 and waits some 67 grids for its seat. 512
     * leaves headroom for an archive around fifty times that size.
     */
    private const val ROTATION_CYCLE = 512

    /**
     * The year bucket each slot should draw from, rotating so no era is squeezed out.
     *
     * Apportionment is largest-remainder: a bucket's quota is its weight times [slotCount],
     * it is seated floor(quota) times outright, and the seats left over go to the largest
     * fractional parts. Eight eras across six slots puts every quota below one, so *every*
     * seat is a leftover seat — and a stateless pass then ranks the same eras first in
     * every grid, leaving two years permanently invisible. That is the exact failure this
     * function exists to prevent, so rotating the finished list is not enough either: the
     * buckets that were ranked last stay at zero seats however the list is turned.
     *
     * The fix is to carry the remainders across grids. A bucket that loses a leftover seat
     * keeps the fraction it was owed and starts the next grid ahead of the buckets that
     * won, so the winners rotate on their own and the long-run share still tracks the
     * weights. [rotation] chooses how many grids of that ledger to replay; grid 0 is a
     * plain largest-remainder pass. Ties break towards the older year, which only decides
     * the very first grid — after that the ledger has separated everything.
     *
     * The seats leave that pass in descending-credit order, and a caller that maps them
     * straight onto its slots therefore hands slot 0 to the heaviest era in *every* grid.
     * Measured over 1,000 six-slot grids at weights 0.1/0.9, slots 0 through 4 drew the
     * heavy era 100% of the time and only slot 5 ever showed the light one. On
     * `thirds-portrait` that renders as a permanently recent left column beside a
     * permanently old right one — a fixed spatial gradient by age. Era mix exists to make
     * each grid a cross-section of the archive, not to sort it, so the finished list is
     * shuffled before it is returned. The permutation is seeded from [rotation] alone, so
     * the result stays reproducible for a given grid; and it moves seats between
     * positions, never between buckets, so the ledger's long-run share is untouched.
     *
     * floorMod rather than %, for the same reason as CollageLayout.templateAt: the counter
     * is a plain Int on a frame that runs for months and will eventually wrap negative.
     */
    internal fun allocate(weights: Map<Int, Double>, slotCount: Int, rotation: Int): List<Int> {
        if (weights.isEmpty() || slotCount <= 0) return emptyList()

        val years = weights.keys.sorted().toIntArray()
        val quota = DoubleArray(years.size) { weights.getValue(years[it]) * slotCount }
        val credit = DoubleArray(years.size)
        val picks = IntArray(slotCount)

        repeat(Math.floorMod(rotation, ROTATION_CYCLE) + 1) {
            for (i in credit.indices) credit[i] += quota[i]
            for (s in picks.indices) {
                var best = 0
                for (i in credit.indices) if (credit[i] > credit[best]) best = i
                credit[best] -= 1.0
                picks[s] = years[best]
            }
        }
        // Seeded from rotation, not from the caller's Random: the spread has to hold for
        // any caller, including tests that reuse one fixed seed across every grid.
        return picks.toList().shuffled(Random(rotation))
    }

    fun <T> fill(
        candidates: List<T>,
        slots: List<CollageLayout.Slot>,
        config: Config,
        today: LocalDate,
        zone: ZoneId,
        rotation: Int,
        random: Random,
        isPortrait: (T) -> Boolean,
        captureMs: (T) -> Long,
        addedMs: (T) -> Long,
    ): List<T> {
        if (candidates.isEmpty() || slots.isEmpty()) return emptyList()

        // Steps 11-12 add the on-this-day, recency and orientation stages. What runs today
        // is the two ends of the cascade: the era target, then the terminal repeat.
        //
        // Bucketed by index rather than by item, so the pools double as the draw pools and
        // "already used in this grid" stays a question about positions, not about whether
        // two candidates happen to be equal.
        val pools: Map<Int, List<Int>> =
            if (config.eraMix) {
                bucketByYear(candidates.indices.toList(), zone) { captureMs(candidates[it]) }
            } else {
                emptyMap()
            }
        val targets =
            if (pools.isEmpty()) {
                emptyList()
            } else {
                val weights = bucketWeights(pools.map { (year, idx) -> year to idx.size })
                allocate(weights, slots.size, rotation)
            }

        val all = candidates.indices.toList()
        val used = BooleanArray(candidates.size)

        fun draw(pool: List<Int>?, freshOnly: Boolean): Int? {
            if (pool.isNullOrEmpty()) return null
            val eligible = if (freshOnly) pool.filter { !used[it] } else pool
            return if (eligible.isEmpty()) null else eligible[random.nextInt(eligible.size)]
        }

        return slots.indices.map { s ->
            val era = targets.getOrNull(s)?.let { pools[it] }
            val idx = draw(era, freshOnly = true)
                ?: draw(all, freshOnly = true)
                // Nothing unused is left. Repeat, preferring the era the slot was owed,
                // because the alternative is a black tile.
                ?: draw(era, freshOnly = false)
                ?: all[random.nextInt(all.size)]
            used[idx] = true
            candidates[idx]
        }
    }
}
