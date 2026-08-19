package com.example.portalgallery.data.store

import android.graphics.BitmapFactory
import android.util.Log
import com.example.portalgallery.data.album.AlbumUrl
import com.example.portalgallery.data.album.SharedAlbumParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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

    sealed class Result {
        data class Success(
            val total: Int,
            val added: Int,
            val pruned: Int,
            val bytes: Long,
            val degraded: Boolean,
            val albumTitle: String?,
            val isCompleteAlbum: Boolean,
        ) : Result()

        data class Failure(val reason: String) : Result()
    }

    suspend fun sync(
        albumUrl: String,
        targetW: Int,
        targetH: Int,
        includeVideos: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val existing = store.load()

        AlbumUrl.problem(albumUrl)?.let { return@withContext Result.Failure(it) }

        val fetched = runCatching { fetch(albumUrl) }.getOrElse {
            return@withContext Result.Failure("fetch failed: ${it.message}")
        }

        // Judge by where the request landed, not by page content: a valid share page
        // shows anonymous visitors a "Sign in" button, so its markup mentions sign-in.
        if (AlbumUrl.isSignInRedirect(fetched.finalUrl)) {
            return@withContext Result.Failure(
                "album is not publicly shared — Google redirected to sign-in. " +
                    "Use Share > Create link to get a photos.app.goo.gl link."
            )
        }
        val html = fetched.body

        val parsed = runCatching { SharedAlbumParser.parse(html) }.getOrElse {
            return@withContext Result.Failure("parse failed: ${it.message}")
        }

        if (parsed.tier == SharedAlbumParser.Tier.REGEX) {
            // Not fatal, but it means the structured payload moved. Surface it loudly:
            // silent degradation is the failure mode this whole design guards against.
            Log.w(TAG, "PARSER DEGRADED to regex tier — page structure changed")
        }

        // Count gate. Only meaningful when we already had photos.
        if (existing.isNotEmpty() && parsed.photos.size < existing.size * SHRINK_ALARM) {
            return@withContext Result.Failure(
                "suspicious shrink: ${parsed.photos.size} vs ${existing.size} — keeping existing"
            )
        }

        val wanted = parsed.photos.associateBy { it.id ?: it.baseUrl.substringAfterLast("/pw/") }
        val missing = wanted.filterKeys { !store.hasPhoto(it) }
        Log.i(TAG, "sync: ${wanted.size} in album, ${missing.size} to download")

        var done = 0
        var failures = 0
        var resolutionChecked = false

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
                            onProgress(++done, missing.size)
                            return@async
                        }

                        val url = if (photo.isVideo) photo.videoUrl()
                        else photo.url(targetW, targetH)

                        val bytes = runCatching { download(url) }.getOrNull()
                        if (bytes == null) {
                            failures++
                        } else {
                            // Resolution gate: catches the case where a URL silently
                            // resolves to a thumbnail. A count check cannot see this,
                            // because the count is unchanged. Stills only — the gate
                            // decodes a bitmap, which an MP4 is not.
                            if (!photo.isVideo && !resolutionChecked) {
                                resolutionChecked = true
                                if (!isPlausiblePhoto(bytes)) {
                                    Log.e(TAG, "resolution gate FAILED — got a thumbnail, aborting sync")
                                    failures = Int.MAX_VALUE
                                    return@async
                                }
                            }
                            store.writePhoto(id, bytes, photo.isVideo)
                        }
                        onProgress(++done, missing.size)
                    }
                }.awaitAll()

                if (failures == Int.MAX_VALUE) return@coroutineScope
            }
        }

        if (failures == Int.MAX_VALUE) {
            return@withContext Result.Failure("resolution gate failed — refusing to index thumbnails")
        }

        // Index only what is actually on disk. A photo that failed to download simply
        // is not listed; it will be retried on the next sync.
        val present = wanted.filterKeys { store.hasPhoto(it) }
        if (present.isEmpty()) {
            return@withContext Result.Failure("nothing on disk after sync — keeping existing")
        }

        store.saveIndex(present.map { (id, p) ->
            PhotoStore.Entry(id, p.width ?: targetW, p.height ?: targetH, p.isVideo)
        })
        val pruned = store.prune(present.keys)

        Result.Success(
            total = present.size,
            added = missing.size - failures.coerceAtMost(missing.size),
            pruned = pruned,
            bytes = store.totalBytes(),
            degraded = parsed.tier == SharedAlbumParser.Tier.REGEX,
            albumTitle = parsed.albumTitle,
            isCompleteAlbum = parsed.isCompleteAlbum,
        )
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
