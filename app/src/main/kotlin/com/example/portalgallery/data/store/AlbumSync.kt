package com.example.portalgallery.data.store

import android.graphics.BitmapFactory
import android.util.Log
import com.example.portalgallery.data.album.AlbumList
import com.example.portalgallery.data.album.AlbumPager
import com.example.portalgallery.data.album.AlbumUrl
import com.example.portalgallery.data.album.SharedAlbumParser
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Refreshes the on-disk library from a Google Photos public share link.
 *
 * The governing rule: **a failed sync must never reduce what is on disk.** Every exit
 * path other than a fully validated success leaves the existing photos and index
 * untouched, so the frame keeps showing the last good set indefinitely.
 *
 * Since Phase 2 this runs in three stages, and the separation matters:
 *
 *  1. **Crawl** every album to its end via [AlbumPager], building a complete picture of
 *     what the album holds — ~20,000 items, against the 300 a single page returns.
 *  2. **Judge** the crawl with [CrawlGuard], which decides whether it can be believed and
 *     whether it has earned the right to delete anything.
 *  3. **Download** the sample [ResidentSelector] chooses, and only then index and prune.
 *
 * Knowing about a photo and holding its bytes are now different things, which is what
 * lets the frame draw on seven years of album while storing a few hundred megabytes.
 */
class AlbumSync(
    private val store: PhotoStore,
    private val index: AlbumIndex,
) {

    companion object {
        private const val TAG = "PortalGallery"

        /** Below this, a "photo" is the 384x512 thumbnail you get from a bare URL. */
        private const val MIN_PLAUSIBLE_EDGE = 800

        private const val CONCURRENCY = 4

        /**
         * Hard ceiling on pages per album.
         *
         * The reference album needs 67. This exists so a server that hands back a fresh
         * token forever cannot spin the crawl indefinitely — which is not hypothetical:
         * putting the continuation token in the wrong argument slot produced exactly that,
         * HTTP 200 and a new token on every call, while returning the same 300 photos.
         * Hitting this cap means the crawl is incomplete, so it cannot prune.
         */
        private const val MAX_PAGES = 400

        /** Pause between pages. Politeness, not a requirement — 67 pages at 1/s is ~70s. */
        private const val PAGE_DELAY_MS = 1_000L

        /** Attempts per page before a crawl gives up and reports itself incomplete. */
        private const val PAGE_RETRIES = 3

        /**
         * How many of the newest photos are always kept on disk.
         *
         * Roughly what the frame held before Phase 2, so the upgrade cannot take away
         * photographs that were already there, and the collage's reserved recency slot
         * always has something recent to draw.
         */
        const val PIN_NEWEST = 300

        /** Default sample size. ~1,500 x 301 KB measured mean is about 450 MB. */
        const val DEFAULT_RESIDENT_TARGET = 1_500

        private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Per-album outcome, so the settings screen can say which album failed and why. */
    data class AlbumOutcome(
        val url: String,
        val title: String?,
        val items: Int,
        val error: String?,
        val degraded: Boolean = false,
        val truncated: Boolean = false,
    ) {
        val ok: Boolean get() = error == null
    }

    sealed class Result {
        data class Success(
            val albums: List<AlbumOutcome>,
            /** Items on disk and displayable. */
            val total: Int,
            /** Items the album is known to hold, most of them not downloaded. */
            val indexed: Int,
            val added: Int,
            val pruned: Int,
            val bytes: Long,
        ) : Result() {
            val degraded: Boolean get() = albums.any { it.degraded }
            val partial: Boolean get() = albums.any { !it.ok }
        }

        data class Failure(val reason: String) : Result()
    }

    suspend fun sync(
        albumUrls: List<String>,
        targetW: Int,
        targetH: Int,
        includeVideos: Boolean = true,
        residentTarget: Int = DEFAULT_RESIDENT_TARGET,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val existing = store.load()
        val previous = index.load()

        if (albumUrls.isEmpty()) {
            return@withContext Result.Failure("no album configured")
        }

        // Each album is crawled independently: one unreachable album must not stop the
        // others from refreshing.
        val crawls = albumUrls.map { url -> crawlAlbum(url) }
        val outcomes = crawls.map { it.outcome }

        outcomes.filter { !it.ok }.forEach {
            Log.e(TAG, "album ${AlbumList.shortName(it.url)} failed: ${it.error} — keeping its photos")
        }

        // Union across albums. Ids are globally unique, so an item that appears in two
        // albums is stored once rather than twice.
        val union = LinkedHashMap<String, AlbumIndex.Entry>()
        crawls.filter { it.outcome.ok }.forEach { crawl ->
            crawl.entries.forEach { union[it.id] = it }
        }

        // Everything about whether this crawl may be believed, and whether it has earned
        // the right to delete anything, lives in CrawlGuard — pure, and tested against the
        // exact scenario three design reviews named as the likeliest way this fails.
        val verdict = CrawlGuard.evaluate(
            previous = previous,
            outcomes = crawls.map { it.guard },
            newSize = union.size,
        )
        val mayPrune = when (verdict) {
            is CrawlGuard.Verdict.Reject -> {
                Log.e(TAG, "crawl rejected: ${verdict.reason}")
                return@withContext Result.Failure(verdict.reason)
            }
            is CrawlGuard.Verdict.Accept -> {
                Log.i(TAG, "crawl accepted (prune=${verdict.mayPrune}): ${verdict.reason}")
                verdict.mayPrune
            }
        }

        // An incomplete crawl is merged rather than discarded — a deliberate departure
        // from §5.4, which said discard. Add-only merging protects exactly as well, since
        // the guard has already forbidden pruning, and it is strictly more useful: an
        // album that reliably times out at page 40 would otherwise never contribute a new
        // photograph again. Previous entries the crawl did not reach are carried forward.
        val merged = LinkedHashMap<String, AlbumIndex.Entry>()
        if (!mayPrune) previous?.items?.forEach { merged[it.id] = it }
        merged.putAll(union)
        if (!mayPrune && previous != null) {
            Log.i(TAG, "carried ${merged.size - union.size} item(s) forward from the previous index")
        }

        // Which of those to actually hold bytes for. Videos are excluded when disabled
        // before sampling rather than after, so turning video off does not silently spend
        // part of the sample on items that will never be downloaded.
        val eligible = merged.values.filter { includeVideos || !it.isVideo }
        val resident = ResidentSelector.choose(
            index = eligible,
            target = residentTarget,
            pinNewest = PIN_NEWEST,
            zone = ZoneId.systemDefault(),
            random = Random.Default,
        )
        val wanted = LinkedHashMap<String, AlbumIndex.Entry>()
        resident.forEach { wanted[it.id] = it }

        val missing = wanted.filterKeys { !store.hasPhoto(it) }
        Log.i(TAG, "sync: ${merged.size} in album, ${resident.size} resident, " +
            "${missing.size} to download")

        // Mutated from CONCURRENCY coroutines at once. `failures++` on a plain Int is a
        // read-modify-write, so counts would be lost and `added` would over-report.
        val done = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val resolutionChecked = AtomicBoolean(false)
        val resolutionGateFailed = AtomicBoolean(false)

        /** What this pass put on disk, so an abort can take it back off again. */
        val writtenThisPass = ConcurrentLinkedQueue<Pair<String, Boolean>>()

        coroutineScope {
            missing.entries.chunked(CONCURRENCY).forEach { chunk ->
                chunk.map { (id, photo) ->
                    async {
                        // Videos come down as MP4 via =dv, at original quality — the
                        // size suffix that bounds photos does not apply, so a single
                        // clip can be tens of megabytes. Skipped entirely when video is
                        // disabled, since that is the difference between a ~90MB
                        // library and one several times larger.
                        if (photo.isVideo && !includeVideos) {
                            onProgress(done.incrementAndGet(), missing.size)
                            return@async
                        }

                        val url = if (photo.isVideo) photo.videoUrl()
                        else photo.url(targetW, targetH)

                        val bytes = runCatching { download(url) }.getOrNull()
                        if (bytes == null) {
                            failures.incrementAndGet()
                        } else {
                            // Resolution gate: catches the case where a URL silently
                            // resolves to a thumbnail. A count check cannot see this,
                            // because the count is unchanged. Stills only — the gate
                            // decodes a bitmap, which an MP4 is not. The compareAndSet
                            // makes that exactly one still per sync, deliberately: a
                            // degraded URL degrades every item alike, so one sample
                            // settles it, and the gate is a tripwire rather than a
                            // per-item validator.
                            if (!photo.isVideo && resolutionChecked.compareAndSet(false, true)) {
                                if (!isPlausiblePhoto(bytes)) {
                                    Log.e(TAG, "resolution gate FAILED — got a thumbnail, aborting sync")
                                    resolutionGateFailed.set(true)
                                    // Progress still ticks on the way out, as it does on
                                    // every other exit from this coroutine. Skipping it
                                    // left the first-sync counter frozen one short of
                                    // its total while the gate tore the pass down.
                                    onProgress(done.incrementAndGet(), missing.size)
                                    return@async
                                }
                            }
                            // A write can fail on a full disk or a failed rename. Unguarded,
                            // that exception escapes async -> coroutineScope -> sync() and
                            // lands in refreshLoop()'s bare lifecycleScope.launch, which has
                            // no handler — so an ENOSPC would crash the frame rather than
                            // skipping one photo. Counted as a failure and retried next sync,
                            // exactly like a failed download.
                            val stored = runCatching { store.writePhoto(id, bytes, photo.isVideo) }
                            if (stored.isFailure) {
                                Log.e(TAG, "could not write $id: ${stored.exceptionOrNull()?.message}")
                                failures.incrementAndGet()
                            } else {
                                writtenThisPass.add(id to photo.isVideo)
                            }
                        }
                        onProgress(done.incrementAndGet(), missing.size)
                    }
                }.awaitAll()

                if (resolutionGateFailed.get()) return@coroutineScope
            }
        }

        if (resolutionGateFailed.get()) {
            // Delete this pass's writes before returning. Siblings of the coroutine that
            // failed the gate had already written their bytes — thumbnails, by definition,
            // since they came from the same degraded URLs. Left on disk they are excluded
            // from `missing` on every later sync by hasPhoto(), so the gate never re-examines
            // them, and once nothing is left to download the sync succeeds and indexes them
            // as full-resolution photos.
            // An explicit loop, not `count { store.deletePhoto(...) }`. `count` reads as a
            // pure query, which would hide the deletion — the whole of the fix — in the one
            // position a reader has no reason to open. The concrete hazard is a later
            // "simplification" to writtenThisPass.size: it compiles, the log still reads
            // correctly, every test still passes, and the cleanup silently stops happening.
            var removed = 0
            for ((id, isVideo) in writtenThisPass) {
                if (store.deletePhoto(id, isVideo)) removed++
            }
            Log.w(TAG, "gate aborted — removed $removed of ${writtenThisPass.size} file(s) " +
                "written before the verdict")
            return@withContext Result.Failure("resolution gate failed — refusing to index thumbnails")
        }

        // Prune BEFORE indexing, not after. Indexing first would list files that the
        // prune then deletes, leaving the store index describing a library that no longer
        // exists. PhotoStore.load() filters missing files so it would self-heal rather
        // than crash — but a sync that reports a total it has just invalidated is the kind
        // of small dishonesty that makes a later bug hard to read.
        //
        // Two generations of grace. A file leaves disk only once it has been outside the
        // sample across two crawls — the renderer is holding a list handed to it earlier,
        // on another thread, and this is what makes re-rolling safe without a handshake.
        val keep = ResidentSelector.keepIds(resident, previous?.resident ?: emptyList())
        val pruned = if (mayPrune) store.prune(keep) else 0
        if (!mayPrune) {
            Log.i(TAG, "not pruning: the crawl did not read every album to its end")
        }

        // Index only what is actually on disk, re-checked after the prune. A photo that
        // failed to download simply is not listed; it will be retried on the next sync.
        val present = wanted.filterKeys { store.hasPhoto(it) }

        // **The multi-album hazard, restated for a sampled library.** `wanted` holds only
        // the sample drawn from albums that were read. Anything still on disk but outside
        // it — an unread album's photos, or a photo the previous generation's grace is
        // protecting — must stay in the index, because "not in this sample" and "not in
        // the album" are entirely different claims and only one justifies deletion.
        val carried = existing.filter { it.id !in present.keys && store.hasPhoto(it.id) }
        if (carried.isNotEmpty()) {
            Log.i(TAG, "carrying forward ${carried.size} item(s) already on disk")
        }

        if (present.isEmpty() && carried.isEmpty()) {
            return@withContext Result.Failure("nothing on disk after sync — keeping existing")
        }

        val entries = present.map { (id, p) ->
            PhotoStore.Entry(
                id,
                p.width.takeIf { it > 0 } ?: targetW,
                p.height.takeIf { it > 0 } ?: targetH,
                p.isVideo,
                p.captureMs,
            )
        } + carried.map {
            PhotoStore.Entry(it.id, it.width, it.height, it.isVideo, it.captureMs)
        }
        store.saveIndex(entries)

        // The index is written last, and only after the bytes and the store index are
        // settled. Its `resident` list is what the next sync's grace period reads, so
        // recording it before a failure could strand files with no generation to protect
        // them.
        index.save(
            AlbumIndex.Snapshot(
                crawledAtMs = System.currentTimeMillis(),
                pageCount = crawls.sumOf { it.guard.pages },
                complete = mayPrune,
                items = merged.values.toList(),
                resident = resident.map { it.id },
            )
        )

        Result.Success(
            albums = outcomes,
            total = entries.size,
            indexed = merged.size,
            added = missing.size - failures.get(),
            pruned = pruned,
            bytes = store.totalBytes(),
        )
    }

    /** One album's crawl: what it found, and what the guard needs to judge it. */
    private data class Crawl(
        val outcome: AlbumOutcome,
        val guard: CrawlGuard.AlbumOutcome,
        val entries: List<AlbumIndex.Entry>,
    )

    /**
     * Reads one album to its end. Never throws — a failure is reported as an outcome so
     * the other albums still sync and this album's photos are preserved.
     *
     * Page 1 comes from the share page's own embedded payload; the rest come from the
     * `snAcKc` RPC that page declares. The reference album is 67 pages.
     *
     * **Completeness is only claimed when the pager runs out of tokens.** A crawl that
     * gives up after retries, hits [MAX_PAGES], or cannot build a request is incomplete —
     * and by [CrawlGuard]'s rule an incomplete crawl can add photos but never delete one.
     * Everything it did read is still returned and still useful.
     */
    private suspend fun crawlAlbum(url: String): Crawl {
        fun fail(reason: String) = Crawl(
            AlbumOutcome(url, null, 0, reason),
            CrawlGuard.AlbumOutcome(AlbumList.shortName(url), 0, 0, complete = false, error = reason),
            emptyList(),
        )

        AlbumUrl.problem(url)?.let { return fail(it) }

        val fetched = runCatching { fetch(url) }.getOrElse {
            return fail("fetch failed: ${it.message}")
        }

        // Judge by where the request landed, not by page content: a valid share page
        // shows anonymous visitors a "Sign in" button, so its markup mentions sign-in.
        if (AlbumUrl.isSignInRedirect(fetched.finalUrl)) {
            return fail(
                "not publicly shared — Google redirected to sign-in. " +
                    "Use Share > Create link."
            )
        }

        val parsed = runCatching { SharedAlbumParser.parse(fetched.body) }.getOrElse {
            return fail("parse failed: ${it.message}")
        }

        val degraded = parsed.tier == SharedAlbumParser.Tier.REGEX
        if (degraded) {
            // Not fatal, but it means the structured payload moved. Surface it loudly:
            // silent degradation is the failure mode this whole design guards against.
            Log.w(TAG, "PARSER DEGRADED to regex tier for ${AlbumList.shortName(url)}")
        }

        val short = AlbumList.shortName(url)
        val items = LinkedHashMap<String, AlbumIndex.Entry>()
        parsed.photos.forEach { p -> AlbumIndex.entryOf(p, url)?.let { items[it.id] = it } }

        var pages = 1
        var complete = parsed.isCompleteAlbum
        var token = parsed.continuationToken

        // The regex tier yields no ids and no token, so there is nothing to paginate from
        // and nothing that could be keyed on disk. Degraded means degraded.
        val endpoint = if (token == null || degraded) null else {
            AlbumPager.parseEndpoint(fetched.body, pathOf(fetched.finalUrl))
        }
        if (token != null && endpoint == null) {
            Log.w(TAG, "$short: page 1 has a continuation token but no usable RPC endpoint " +
                "— stopping at ${items.size} items")
        }

        while (endpoint != null && token != null && pages < MAX_PAGES) {
            val page = fetchPageWithRetries(endpoint, token, short, pages + 1)
            if (page == null) {
                Log.w(TAG, "$short: giving up after page $pages — crawl is incomplete, " +
                    "nothing will be pruned")
                complete = false
                break
            }
            pages++
            val before = items.size
            page.photos.forEach { p -> AlbumIndex.entryOf(p, url)?.let { items[it.id] = it } }

            // A page that adds nothing new means the cursor is not advancing, whatever the
            // server says about there being more. Pages are disjoint by construction, so
            // zero new ids is never legitimate progress.
            //
            // This is a second line of defence behind the token type check in
            // SharedAlbumParser. The first device run read an end-of-album marker as a
            // cursor and spent 333 extra requests re-fetching before MAX_PAGES stopped it;
            // this catches the same shape on the very next page instead, whatever causes
            // it next time.
            if (items.size == before) {
                Log.w(TAG, "$short: page $pages added no new items — the cursor is not " +
                    "advancing, stopping at ${items.size}")
                complete = false
                break
            }

            token = page.nextToken
            complete = page.complete
            if (token == null) break
            delay(PAGE_DELAY_MS)
        }

        if (pages >= MAX_PAGES && token != null) {
            Log.e(TAG, "$short: hit the $MAX_PAGES-page ceiling with a token still pending. " +
                "Either the album is enormous or the server is handing back a token that " +
                "never advances — treating as incomplete.")
            complete = false
        }

        Log.i(TAG, "$short: ${items.size} items across $pages page(s)" +
            if (complete) "" else " (INCOMPLETE)")

        return Crawl(
            outcome = AlbumOutcome(
                url = url,
                title = parsed.albumTitle,
                items = items.size,
                error = null,
                degraded = degraded,
                truncated = !complete,
            ),
            guard = CrawlGuard.AlbumOutcome(short, items.size, pages, complete),
            entries = items.values.toList(),
        )
    }

    /**
     * One page, with backoff.
     *
     * Retrying immediately after a rate limit is how a crawl turns backpressure into more
     * pressure, so the wait grows and the whole crawl gives up rather than hammering. A
     * null return is not fatal: the caller keeps what it has and marks itself incomplete,
     * which costs the ability to prune and nothing else.
     */
    private suspend fun fetchPageWithRetries(
        endpoint: AlbumPager.Endpoint,
        token: String,
        short: String,
        pageNumber: Int,
    ): AlbumPager.Page? {
        var wait = PAGE_DELAY_MS
        repeat(PAGE_RETRIES) { attempt ->
            val page = runCatching { fetchPage(endpoint, token) }.getOrElse { e ->
                Log.w(TAG, "$short page $pageNumber attempt ${attempt + 1}: ${e.message}")
                null
            }
            if (page != null) return page
            delay(wait)
            wait *= 4
        }
        return null
    }

    private fun fetchPage(endpoint: AlbumPager.Endpoint, token: String): AlbumPager.Page {
        val body = AlbumPager.buildRequestBody(endpoint, token)
            .toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType())
        val request = Request.Builder()
            .url(AlbumPager.buildUrl(endpoint))
            .header("User-Agent", UA)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val text = response.body?.string() ?: error("empty body")
            return AlbumPager.parseResponse(text)
        }
    }

    private fun pathOf(url: String): String =
        runCatching { java.net.URI(url).path ?: "/" }.getOrDefault("/")

    private data class Fetched(val finalUrl: String, val body: String)

    private fun fetch(url: String): Fetched {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("empty body")
            // OkHttp follows redirects, so request.url is where we actually ended up.
            return Fetched(response.request.url.toString(), body)
        }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.bytes() ?: error("empty body")
        }
    }

    private fun isPlausiblePhoto(bytes: ByteArray): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val edge = maxOf(opts.outWidth, opts.outHeight)
        if (edge < MIN_PLAUSIBLE_EDGE) {
            Log.e(TAG, "got ${opts.outWidth}x${opts.outHeight} — looks like a thumbnail")
            return false
        }
        return true
    }
}
