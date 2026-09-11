package com.example.portalgallery.data.store

import com.example.portalgallery.data.schedule.CollageSelector
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Chooses which slice of the album actually lives on the device.
 *
 * The index knows about ~20,000 photographs. Downloading all of them was the original
 * plan and it did not survive review: the byte budget did not close, and it bought a
 * tiering-and-eviction surface whose central invariant turned out to be false. What
 * replaced it is smaller and uses only measured numbers — **a fixed sample at the size
 * the frame already downloads at**, re-rolled when a crawl completes.
 *
 * At the measured mean of 301 KB, 1,500 photographs is about 450 MB against 12.4 GB free.
 * Every resident photo is full-size, so hero interludes draw from the same pool as the
 * tiles and there is no second tier to keep in step.
 *
 * Pure and Android-free, so the two properties that matter — the newest photos are always
 * present, and every era is represented — are unit tested rather than asserted.
 */
object ResidentSelector {

    /**
     * The sample, in the order it should be downloaded.
     *
     * Ordering is not cosmetic: the frame is usable long before the download finishes, so
     * what arrives first decides what the wall shows for the first several minutes. Newest
     * first, then one photo per era in rotation, then the remainder.
     *
     * @param pinNewest how many of the most recently captured items are always resident,
     *   whatever the era stratification would otherwise choose. This is what guarantees
     *   the frame never loses the recent photographs it shows today, and what makes the
     *   collage's reserved recency slot always have something to draw.
     * @param target total sample size, including the pinned ones.
     */
    fun choose(
        index: List<AlbumIndex.Entry>,
        target: Int,
        pinNewest: Int,
        zone: ZoneId,
        random: Random,
    ): List<AlbumIndex.Entry> {
        if (index.isEmpty() || target <= 0) return emptyList()
        if (index.size <= target) return newestFirst(index)

        val chosen = LinkedHashSet<AlbumIndex.Entry>()

        // 1. The newest, unconditionally. A photo added this week must be on the device
        //    even if its year bucket is already over-represented.
        newestFirst(index).take(pinNewest.coerceAtMost(target)).forEach { chosen.add(it) }
        if (chosen.size >= target) return chosen.toList()

        // 2. The rest, stratified by era using the same sqrt damping the collage uses for
        //    slot allocation. Sharing that policy is deliberate: a sample drawn on one
        //    rule and displayed under another would quietly starve whichever eras the two
        //    disagreed about.
        val remaining = index.filterNot { it in chosen }
        val buckets = remaining.groupBy { yearOf(it, zone) }
        val weights = CollageSelector.bucketWeights(buckets.map { it.key to it.value.size })

        val slotsLeft = target - chosen.size
        val quota = buckets.keys.associateWith { year ->
            ((weights[year] ?: 0.0) * slotsLeft).toInt()
        }.toMutableMap()

        // Shuffle within a bucket so a re-roll produces a different sample rather than the
        // same prefix of a stable ordering.
        val pools = buckets.mapValues { (_, v) -> v.shuffled(random).toMutableList() }

        // Round-robin the quotas rather than draining one era at a time, so a sample cut
        // short by `target` is still spread across eras instead of ending in 2019.
        val years = buckets.keys.sorted()
        var progress = true
        while (chosen.size < target && progress) {
            progress = false
            for (y in years) {
                if (chosen.size >= target) break
                if ((quota[y] ?: 0) <= 0) continue
                val pool = pools[y] ?: continue
                if (pool.isEmpty()) continue
                chosen.add(pool.removeAt(pool.size - 1))
                quota[y] = (quota[y] ?: 1) - 1
                progress = true
            }
        }

        // Rounding leaves the quotas a little short of the target. Top up from whatever is
        // left, newest first, rather than returning a smaller sample than asked for.
        if (chosen.size < target) {
            newestFirst(pools.values.flatten())
                .asSequence()
                .filterNot { it in chosen }
                .take(target - chosen.size)
                .forEach { chosen.add(it) }
        }

        return chosen.toList()
    }

    /**
     * The sample to use when it is not yet time to re-roll.
     *
     * **Why this exists.** [choose] draws a fresh random sample every time it is called,
     * and calling it on every sync is ruinous: measured on the device, a second sync
     * replaced 1,113 of 1,500 photographs, the grace period correctly held on to the
     * previous generation, and the library went from 499 MB to 856 MB in one pass. At the
     * six-hourly refresh that is about 1.3 GB of downloads a day, forever, to show the
     * same album. The design always said "re-rolled weekly"; only the re-rolling got
     * built.
     *
     * So between re-rolls the sample is carried forward instead. Two things still change:
     * items that have left the album are dropped, and the newest [pinNewest] are pulled in
     * unconditionally — which is what lets a photograph added this morning reach the frame
     * this afternoon without waiting for the weekly roll. Anything short of [target] after
     * that is topped up from the carried set, so the sample neither shrinks nor churns.
     */
    fun carryForward(
        index: List<AlbumIndex.Entry>,
        previousResident: List<String>,
        target: Int,
        pinNewest: Int,
    ): List<AlbumIndex.Entry> {
        if (index.isEmpty() || target <= 0) return emptyList()
        val byId = index.associateBy { it.id }
        val chosen = LinkedHashSet<AlbumIndex.Entry>()

        // Newest first, so a new arrival displaces the oldest carried item rather than
        // being dropped when the sample is already full.
        newestFirst(index).take(pinNewest.coerceAtMost(target)).forEach { chosen.add(it) }

        for (id in previousResident) {
            if (chosen.size >= target) break
            byId[id]?.let { chosen.add(it) }
        }
        return chosen.toList()
    }

    /**
     * Ids that may not be deleted: this sample and the one before it.
     *
     * The two-generation grace that replaced v1's eviction scheme. The renderer holds a
     * list of photos it was handed at some earlier moment, and the sync that re-rolls the
     * sample runs on a different thread — so a file dropped from the sample can still be
     * on screen. Keeping the previous generation means nothing is deleted until it has
     * been unwanted across two complete crawls, which on a weekly re-roll is a fortnight
     * of slack. No cross-thread handshake, and trivially testable.
     */
    fun keepIds(current: List<AlbumIndex.Entry>, previous: List<String>): Set<String> =
        current.mapTo(HashSet(current.size + previous.size)) { it.id }.apply { addAll(previous) }

    private fun newestFirst(items: List<AlbumIndex.Entry>): List<AlbumIndex.Entry> =
        items.sortedByDescending { it.captureMs }

    private fun yearOf(e: AlbumIndex.Entry, zone: ZoneId): Int =
        if (e.captureMs <= 0L) CollageSelector.UNKNOWN_YEAR
        else Instant.ofEpochMilli(e.captureMs).atZone(zone).year
}
