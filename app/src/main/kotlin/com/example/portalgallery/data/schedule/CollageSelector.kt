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
 *     the full-screen path discards every portrait photo — 56% of the committed trip
 *     fixture, 33% of the live family album measured 2026-09-09, so a large slice either
 *     way; a portrait slot is the only way those photographs reach the screen. Filling
 *     one with a
 *     landscape photo — letterboxed into a tall box, or cropped to a strip — hands back
 *     exactly the ground the tags were added to win, so orientation is worth more than
 *     either preference above it.
 *
 * If dropping all three still leaves no unused photo, one already on screen is repeated.
 * Duplication is the terminal fallback and it is the right one: a repeated photograph
 * reads as a design choice, a black tile reads as a broken frame.
 *
 * **One slot is exempt from that ordering.** With recency on and something newly arrived,
 * [reservedSlot] holds a tile back for the new photos, and that tile will take a recent
 * photo of the wrong shape ahead of a well-shaped older one — the single place orientation
 * is dropped in front of a preference rather than behind it. A stated exception rather than
 * an oversight: see [reservedSlot] for why the guarantee has to be structural.
 */
object CollageSelector {

    data class Config(
        val eraMix: Boolean = true,
        val onThisDay: Boolean = false,
        val recency: Boolean = true,
    )

    /** Photos added within this window are "new" for recency purposes. */
    private const val RECENT_WINDOW_MS = 21L * 24 * 60 * 60 * 1000

    /**
     * How much more likely an anniversary photo is to win a draw than an ordinary one.
     *
     * A multiplier rather than a filter, because on-this-day has to relax like everything
     * else: most days match nothing, and 25x nothing is still nothing, so the draw simply
     * carries on. It is deliberately large. Anniversaries are a thin slice of any archive
     * — ten photos in a two-hundred-photo library is generous — and at 25x that slice
     * takes 250 of 450 weight, so it wins a little over half the tiles it competes for.
     * A gentler multiplier would leave the feature invisible on the day it is meant for.
     */
    private const val ON_THIS_DAY_BOOST = 25.0

    /**
     * Extra weight a brand-new photo carries, decaying linearly to none over
     * [RECENT_WINDOW_MS]. Modest on purpose: the visibility guarantee is the reserved slot,
     * which is structural, and this only tilts the ordinary slots so a batch that arrived
     * yesterday keeps a little momentum after the reservation has moved on to the next one.
     */
    private const val RECENCY_BOOST = 4.0

    /** Bucket key for photos whose capture time is missing. Cannot collide with a real year. */
    const val UNKNOWN_YEAR = -1

    /**
     * Slot share per year bucket, damped by sqrt of bucket size.
     *
     * **This is a product choice, and the numbers behind it are now measured rather than
     * assumed.** An earlier version of this comment argued from a guess — that a family
     * album accumulates, so the newest year dominates and old years need protecting from
     * proportional allocation. Crawling the real album showed the opposite:
     *
     * | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
     * |------|------|------|------|------|------|------|------|
     * | 3657 | 3843 | 3280 | 2916 | 1297 | 3189 | 1134 |  658 |
     *
     * 2019 is the second-largest year at 18% of the archive; 2026 is the smallest at 3.3%.
     * People photograph less over time here, not more.
     *
     * The mechanism survives the correction because it was never really about which end
     * was heavy — sqrt sits between proportional and uniform whichever way the archive
     * leans, pulling every bucket toward the middle. On these numbers it lifts 2026 from a
     * 3.3% proportional share to 6.7% and trims 2019 from 18% to 16%, so the newest photos
     * get roughly twice the screen time raw proportion would give them and the oldest are
     * barely touched. That is the outcome worth having; it just arrives from the opposite
     * direction to the one first written down.
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
     * to outlast the thinnest bucket's wait, and that is what fixes the size. Across the
     * reference 20,000-photo archive the roots sum to about 375, so a bucket holding a
     * single photo has a quota of 0.016 seats per six-slot grid — and it is seated at
     * rotation 12. Note that this is far sooner than the 1/quota ≈ 63 grids the quota
     * alone suggests: 1/quota is how long a bucket would wait if it had to out-credit
     * buckets sitting at zero, but the ledger charges a full seat to every bucket that
     * wins one, so the field it has to beat is drawn down below zero and meets the thin
     * bucket's slowly accruing credit part-way. At fifty times that archive the quota
     * falls to 0.0023 and the wait stretches to 61 grids, so 512 leaves ample headroom.
     */
    private const val ROTATION_CYCLE = 512

    /**
     * The year bucket each slot should draw from, rotating so no era is squeezed out.
     *
     * Apportionment is largest-remainder: a bucket's quota is its weight times [slotCount],
     * it is seated floor(quota) times outright, and the seats left over go to the largest
     * fractional parts. Eight eras across six slots pushes most quotas below one — though
     * not every one: sqrt damping still leaves a dominant recent year above the 1/6 that
     * buys a seat outright — so nearly every seat is a leftover seat, and a stateless
     * pass then ranks the same eras first in every grid, leaving two years permanently
     * invisible. That is the exact failure this function exists to prevent, so rotating
     * the finished list is not enough either: the buckets that were ranked last stay at
     * zero seats however the list is turned.
     *
     * The fix is to carry the remainders across grids. A bucket that loses a leftover seat
     * keeps the fraction it was owed and starts the next grid ahead of the buckets that
     * won, so the winners rotate on their own and the long-run share still tracks the
     * weights. [rotation] chooses how many grids of that ledger to replay; grid 0 is a
     * plain largest-remainder pass.
     *
     * Ties break towards the older year — `>` keeps the incumbent, and `years` is sorted
     * ascending. That is not a one-off. Under uniform weights, the case this function was
     * written for, the credits move in lockstep and exact ties recur in every grid: eight
     * equal eras over three slots tie all eight at grid 0, six of them at 0.125 by grid 2.
     * So the tie-break is not a seed the ledger then overwrites, it is the rule driving a
     * permanent round robin through the years in ascending order, which is precisely the
     * even spread wanted here. The credit ledger takes over only as the weights separate.
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

    /**
     * How fresh a photo is, 1.0 the moment it lands and 0.0 once [RECENT_WINDOW_MS] has
     * passed. Measured from the end of [today] rather than its start so a photo added this
     * afternoon is not given a negative age by a clock the frame reads only to day
     * precision; a genuinely future [addedMs] — a device whose clock ran ahead before NTP
     * corrected it — clamps to 1.0 rather than wrapping.
     *
     * An [addedMs] of 0 means the index predates the column and cannot be dated, so it
     * scores 0: unknown is treated as old, which is the safe direction. Guessing "new"
     * would hand every unsynced photo the reserved slot below.
     */
    private fun freshness(addedMs: Long, nowMs: Long): Double {
        if (addedMs <= 0L) return 0.0
        return ((RECENT_WINDOW_MS - (nowMs - addedMs)).toDouble() / RECENT_WINDOW_MS)
            .coerceIn(0.0, 1.0)
    }

    /**
     * Which slot, if any, is held back for photos added in the last [RECENT_WINDOW_MS].
     * -1 when the reservation is off or nothing qualifies.
     *
     * **Why a slot is reserved at all.** Era mix and recency pull against each other, and
     * era mix wins on volume unless it is stopped structurally. Six tiles swapping every
     * 20s is 1,080 tile renders an hour. Split evenly across six eras — near enough for
     * this estimate; sqrt damping tilts it towards the recent ones — the newest bucket
     * draws 180 of them, and against the ~6,000 photos a recent year holds in the
     * reference archive that is one appearance per specific new photo every ~33 hours.
     * The frame today — 300 photos, one at a time, 8s each, so 450 renders an hour —
     * shows a given photo every ~40 minutes. Era mix on by default would therefore make a
     * new photo roughly 50x less visible than it is now, and "photos the family adds
     * actually show up" is the requirement the frame exists for. Reserving a slot makes
     * the guarantee structural instead of probabilistic: the recent pool gets one tile in
     * every grid — 180 renders an hour — however the eras are apportioned.
     *
     * **Why the position rotates.** Slot 0 is the obvious pick and the wrong one. A fixed
     * position means new arrivals are always top-left, which is the same defect [allocate]
     * shuffles its seats to avoid — one category pinned to one position for the life of
     * the frame, read by anyone watching as a fixed spatial gradient rather than as a
     * grid. Keying the reservation off [rotation] walks it across the grid instead, and
     * consecutive rotations visit every position before repeating. floorMod for the reason
     * given on [ROTATION_CYCLE]: the counter wraps negative eventually.
     */
    private fun reservedSlot(recentCount: Int, slotCount: Int, rotation: Int): Int =
        if (recentCount == 0) -1 else Math.floorMod(rotation, slotCount)

    /**
     * The order [fill] visits its unreserved slots in: most-constrained-first, ranked by
     * the size of each slot's stage-(a) pool — its era bucket narrowed to unused candidates
     * of its own orientation.
     *
     * **This buys era fidelity and nothing else.** It cannot reduce the number of
     * ill-fitting tiles, and the KDoc should not be read as claiming otherwise: stage (b)
     * relaxes era while holding the shape, so the count of slots that get a well-shaped
     * photo is min(supply of that shape, slots wanting it) whatever the order. What order
     * decides is *which* slot gets the scarce photo, and therefore whether the era target
     * [allocate] worked to produce survives or is thrown away.
     *
     * Two slots' stage-(a) pools are either identical (same bucket, same shape) or disjoint,
     * so slots that can be served strictly never compete with one another. The only slot
     * that can spoil another's era target is one whose own stage (a) is empty and which
     * therefore reaches across the whole library at stage (b).
     *
     * **Which is why an empty pool sorts last, not first.** Ranking it first — the reading
     * of "fewest eligible candidates" that the plain count gives — is not merely unhelpful,
     * it deterministically produces the worst available order in exactly the case the rule
     * exists to fix, and so does worse than not sorting at all. A slot with nothing strictly
     * eligible has already lost its era target however early it runs; running it first only
     * lets it take a photo another slot could still have used strictly. See the
     * `a slot that can match its era strictly is ordered ahead of one that cannot` test,
     * which is the one that pins this; the black-box `most constrained slot is filled first`
     * cannot distinguish any of these orderings.
     *
     * sortedBy is stable, so equally constrained slots keep their natural order and the grid
     * stays reproducible.
     */
    internal fun fillOrder(
        open: List<Int>,
        slots: List<CollageLayout.Slot>,
        bucketOf: (Int) -> List<Int>,
        isPortraitAt: (Int) -> Boolean,
        isUsed: (Int) -> Boolean,
    ): List<Int> = open.sortedBy { s ->
        val want = slots[s].wantPortrait
        val strict = bucketOf(s).count { !isUsed(it) && isPortraitAt(it) == want }
        if (strict == 0) Int.MAX_VALUE else strict
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

        // Indices, not items, throughout: the pools double as the draw pools and "already
        // used in this grid" stays a question about positions, not about whether two
        // candidates happen to be equal.
        val all = candidates.indices.toList()
        val nowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val fresh = DoubleArray(candidates.size) { freshness(addedMs(candidates[it]), nowMs) }

        val recent = if (config.recency) all.filter { fresh[it] > 0.0 } else emptyList()
        val reserved = reservedSlot(recent.size, slots.size, rotation)

        // The reserved slot is excluded from era stratification: it is held for the recent
        // pool, so apportioning a year bucket to it would only be overridden.
        val open = slots.indices.filter { it != reserved }
        val pools: Map<Int, List<Int>> =
            if (config.eraMix) bucketByYear(all, zone) { captureMs(candidates[it]) } else emptyMap()
        val targets =
            if (pools.isEmpty()) {
                emptyList()
            } else {
                val weights = bucketWeights(pools.map { (year, idx) -> year to idx.size })
                allocate(weights, open.size, rotation)
            }
        val target = HashMap<Int, List<Int>>(open.size)
        open.forEachIndexed { k, s -> targets.getOrNull(k)?.let { target[s] = pools.getValue(it) } }

        val weight = DoubleArray(candidates.size) { i ->
            var w = 1.0
            if (config.onThisDay && isAnniversary(captureMs(candidates[i]), today, zone)) {
                w *= ON_THIS_DAY_BOOST
            }
            if (config.recency) w *= 1.0 + RECENCY_BOOST * fresh[i]
            w
        }

        val used = BooleanArray(candidates.size)
        val chosen = IntArray(slots.size) { -1 }

        fun draw(pool: List<Int>): Int {
            // Every weight is >= 1.0, so the total is always positive and the walk always
            // lands; `last()` catches the float rounding case where it lands a hair short.
            var r = random.nextDouble() * pool.sumOf { weight[it] }
            for (i in pool) {
                r -= weight[i]
                if (r <= 0.0) return i
            }
            return pool.last()
        }

        fun free(pool: List<Int>): List<Int> = pool.filter { !used[it] }
        fun fits(pool: List<Int>, want: Boolean): List<Int> =
            pool.filter { isPortrait(candidates[it]) == want }

        if (reserved >= 0) {
            // Prefer a recent photo shaped for the slot, but take one of the wrong shape
            // over giving the reservation up: the point is that the new photo is seen.
            // Nothing is used yet and `recent` is non-empty whenever reserved >= 0, so the
            // elvis here is the orientation relaxation, not an emptiness guard.
            val shaped = fits(recent, slots[reserved].wantPortrait)
            val idx = draw(if (shaped.isEmpty()) recent else shaped)
            used[idx] = true
            chosen[reserved] = idx
        }

        // Counted once here, after the reservation has taken its photo, rather than
        // recomputed as the grid fills. See [fillOrder] for what the order is worth.
        val order = fillOrder(
            open = open,
            slots = slots,
            bucketOf = { target[it] ?: all },
            isPortraitAt = { isPortrait(candidates[it]) },
            isUsed = { used[it] },
        )

        for (s in order) {
            val want = slots[s].wantPortrait
            // The slot's era bucket, or the whole library when era mix is off — which
            // makes stages (a)/(b) and (c)/(d) coincide, exactly as they should when
            // there is no era target to relax.
            val bucket = target[s] ?: all
            val pool = free(fits(bucket, want)).nonEmpty()  // a. era + shape
                ?: free(fits(all, want)).nonEmpty()         // b. shape, any era
                ?: free(bucket).nonEmpty()                  // c. era, any shape
                ?: free(all).nonEmpty()                     // d. anything unused
                ?: all                                      // e. repeat: the C10 floor
            val idx = draw(pool)
            used[idx] = true
            chosen[s] = idx
        }

        // Slot order, not selection order: the caller maps this straight onto its tiles.
        return chosen.map { candidates[it] }
    }

    /**
     * True when [captureMs] falls on today's month and day, in any year including this one.
     * Read in [zone] for the same reason PhotoSelector.matchesWeekday is: "this day" means
     * the day the household lived, not the one UTC was having.
     */
    private fun isAnniversary(captureMs: Long, today: LocalDate, zone: ZoneId): Boolean {
        if (captureMs <= 0L) return false
        val d = Instant.ofEpochMilli(captureMs).atZone(zone).toLocalDate()
        return d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth
    }

    /**
     * Null for an empty pool, so the relax cascade can chain on elvis. Elvis is lazy, which
     * is the point: a stage is only filtered for if every stricter one came back empty.
     */
    private fun List<Int>.nonEmpty(): List<Int>? = takeIf { it.isNotEmpty() }
}
