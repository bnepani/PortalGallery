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
        // Filled in over Steps 9-12.
        return slots.map { candidates[random.nextInt(candidates.size)] }
    }
}
