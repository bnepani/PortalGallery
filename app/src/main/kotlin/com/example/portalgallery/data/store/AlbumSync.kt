package com.example.portalgallery.data.store

import android.graphics.BitmapFactory
import android.util.Log
import com.example.portalgallery.data.album.AlbumList
import com.example.portalgallery.data.album.AlbumUrl
import com.example.portalgallery.data.album.SharedAlbumParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refreshes the on-disk library from a Google Photos public share link.
 *
 * The governing rule: **a failed sync must never reduce what is on disk.** Every exit
 * path other than a fully validated success leaves the existing photos and index
 * untouched, so the frame keeps showing the last good set indefinitely.
 */
class AlbumSync(private val store: PhotoStore) {

    companion object {
        private const val TAG = "PortalGallery"

        /** Below this, a "photo" is the 384x512 thumbnail you get from a bare URL. */
        private const val MIN_PLAUSIBLE_EDGE = 800

        /** A parse returning less than this fraction of the previous set is suspect. */
        private const val SHRINK_ALARM = 0.5

        private const val CONCURRENCY = 4

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
            val total: Int,
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
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val existing = store.load()

        if (albumUrls.isEmpty()) {
            return@withContext Result.Failure("no album configured")
        }

        // Each album is fetched independently: one unreachable album must not stop the
        // others from refreshing.
        val fetches = albumUrls.map { url -> fetchAlbum(url) }
        val outcomes = fetches.map { it.first }

        if (outcomes.none { it.ok }) {
            return@withContext Result.Failure(
                "all ${outcomes.size} album(s) failed — " +
                    outcomes.joinToString("; ") { "${AlbumList.shortName(it.url)}: ${it.error}" }
            )
        }
        outcomes.filter { !it.ok }.forEach {
            Log.e(TAG, "album ${AlbumList.shortName(it.url)} failed: ${it.error} — keeping its photos")
        }

        // Union across albums. Ids are globally unique, so an item that appears in two
        // albums is stored once rather than twice.
        val wanted = LinkedHashMap<String, SharedAlbumParser.Photo>()
        fetches.filter { it.first.ok }.forEach { (_, photos) ->
            photos.forEach { photo ->
                wanted[photo.id ?: photo.baseUrl.substringAfterLast("/pw/")] = photo
            }
        }

        // Count gate, applied to the union. Only meaningful when every album was read —
        // a partial sync legitimately sees fewer items than are on disk.
        val allOk = outcomes.all { it.ok }
        if (allOk && existing.isNotEmpty() && wanted.size < existing.size * SHRINK_ALARM) {
            return@withContext Result.Failure(
                "suspicious shrink: ${wanted.size} vs ${existing.size} — keeping existing"
            )
        }
        val missing = wanted.filterKeys { !store.hasPhoto(it) }
        Log.i(TAG, "sync: ${wanted.size} in album, ${missing.size} to download")

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

        // Index only what is actually on disk. A photo that failed to download simply
        // is not listed; it will be retried on the next sync.
        val present = wanted.filterKeys { store.hasPhoto(it) }

        // **The multi-album hazard.** `wanted` only contains items from albums that were
        // read successfully. If one of five albums is unreachable, indexing and pruning
        // against `wanted` alone would drop its photos from the frame and then delete
        // them from disk — losing a third of the library to one failed HTTP request, and
        // silently, because every other album still works.
        //
        // So when any album failed, everything already on disk is carried forward
        // untouched. Nothing is deleted until every album has been read and we actually
        // know what belongs.
        val carried = if (allOk) {
            emptyList()
        } else {
            existing.filter { it.id !in present.keys }
                .also { Log.i(TAG, "carrying forward ${it.size} items from unread album(s)") }
        }

        if (present.isEmpty() && carried.isEmpty()) {
            return@withContext Result.Failure("nothing on disk after sync — keeping existing")
        }

        val entries = present.map { (id, p) ->
            PhotoStore.Entry(id, p.width ?: targetW, p.height ?: targetH, p.isVideo, p.captureMs ?: 0L)
        } + carried.map {
            PhotoStore.Entry(it.id, it.width, it.height, it.isVideo, it.captureMs)
        }
        store.saveIndex(entries)

        val pruned = if (allOk) store.prune(entries.map { it.id }.toSet()) else 0

        Result.Success(
            albums = outcomes,
            total = entries.size,
            added = missing.size - failures.get(),
            pruned = pruned,
            bytes = store.totalBytes(),
        )
    }

    /**
     * Fetches and parses one album. Never throws — a failure is reported as an outcome
     * so the other albums still sync and this album's photos are preserved.
     */
    private fun fetchAlbum(url: String): Pair<AlbumOutcome, List<SharedAlbumParser.Photo>> {
        fun fail(reason: String) =
            AlbumOutcome(url, null, 0, reason) to emptyList<SharedAlbumParser.Photo>()

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
        Log.i(TAG, "album ${AlbumList.shortName(url)}: ${parsed.photos.size} items" +
            if (parsed.isCompleteAlbum) "" else " (truncated at the page limit)")

        return AlbumOutcome(
            url = url,
            title = parsed.albumTitle,
            items = parsed.photos.size,
            error = null,
            degraded = degraded,
            truncated = !parsed.isCompleteAlbum,
        ) to parsed.photos
    }

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
