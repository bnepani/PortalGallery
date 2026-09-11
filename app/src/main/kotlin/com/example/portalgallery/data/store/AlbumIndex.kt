package com.example.portalgallery.data.store

import android.content.Context
import com.example.portalgallery.data.album.SharedAlbumParser
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * Everything the album contains, whether or not its bytes are on disk.
 *
 * The counterpart to [PhotoStore], and the separation is the point. `PhotoStore`'s index
 * answers "which files do we have"; this one answers "what is in the album" — for the
 * reference album, 19,974 items reaching back to April 2019, against a few hundred
 * actually downloaded. Curation reads this so it can draw from the whole archive; the
 * downloader reads it to decide what to fetch next.
 *
 * Takes a directory rather than a `Context` so it can be tested with a temporary folder.
 * That is deliberate rather than incidental: this class holds [Snapshot.complete], which
 * is the flag protecting the library from a truncated crawl, and a component carrying a
 * guarantee like that should not be one that only a device can exercise.
 *
 * ### Storage
 *
 * One JSON document, written whole. At ~20,000 entries it measures a few megabytes, which
 * is small enough that the alternative — a database, incremental updates, a schema — buys
 * complexity rather than speed. Loading is a background-thread operation and happens once
 * per sync, not per frame.
 */
class AlbumIndex(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "album").apply { mkdirs() })

    /**
     * One album item.
     *
     * [baseUrl] is why this type exists separately from [PhotoStore.Entry]: it is what
     * lets an item be downloaded later, and the on-disk index has never carried it —
     * `PhotoStore` only ever needed to know about files it already had.
     *
     * Every field defaults, so a document written by an older build still deserialises
     * into something usable rather than failing the whole load. The same discipline
     * `PhotoStore.Entry` follows for `isVideo` and `captureMs`.
     */
    data class Entry(
        val id: String = "",
        val baseUrl: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val captureMs: Long = 0L,
        /** When the album received it — distinct from when this frame downloaded it. */
        val addedMs: Long = 0L,
        val isVideo: Boolean = false,
        /** Which configured album URL it came from, so a removed album can be dropped. */
        val album: String = "",
    ) {
        val isPortrait: Boolean get() = height > width

        /**
         * A loadable URL bounded to [w] x [h]. Fit-inside, not a crop.
         *
         * **Never load [baseUrl] directly.** Bare, it resolves to a 384x512 thumbnail —
         * the size suffix is applied by the page's own JavaScript at render time and is
         * absent from the data. This mirrors `SharedAlbumParser.Photo.url` for the same
         * reason, and the resolution gate in AlbumSync exists because getting it wrong is
         * otherwise invisible: the item count is unchanged.
         */
        fun url(w: Int, h: Int): String = "$baseUrl=w$w-h$h-no"

        /**
         * The MP4 itself.
         *
         * `=dv` returns a video for stills too — Google synthesises one from Motion
         * Photos — so this suffix cannot be used to decide what is a video. That is what
         * [isVideo] is for, and it comes from the structured payload.
         */
        fun videoUrl(): String = "$baseUrl=dv"
    }

    /**
     * An index plus what is known about the crawl that produced it.
     *
     * @param complete whether that crawl reached the end of every album — the pager
     *   returning no further token, not merely no error. **Nothing may prune while this is
     *   false.** A crawl cut short by a rate limit, a cap or a timeout looks exactly like a
     *   small album from the inside, and acting on the difference deletes photographs.
     * @param pageCount how many pages it took. Kept so the next crawl can notice it
     *   finished suspiciously early, which is the one truncation a token check cannot
     *   catch on its own.
     */
    data class Snapshot(
        val version: Int = VERSION,
        val crawledAtMs: Long = 0L,
        val pageCount: Int = 0,
        val complete: Boolean = false,
        val items: List<Entry> = emptyList(),
        /**
         * The ids chosen to live on disk when this index was written.
         *
         * Persisted because the next re-roll needs the previous generation to implement
         * [ResidentSelector.keepIds]'s grace period — a file may only be deleted once it
         * has been unwanted across two crawls, which is what makes it safe to re-roll the
         * sample while the renderer is holding an older list of photos.
         */
        val resident: List<String> = emptyList(),
        /**
         * When [resident] was last drawn fresh, as opposed to carried forward.
         *
         * Re-rolling is expensive — a new sample means downloading most of it — so it
         * happens on a slow cadence and this is what paces it. 0 means "never rolled",
         * which an index written before this field existed also reads as, and which
         * correctly forces one roll on the next sync.
         */
        val sampleRolledAtMs: Long = 0L,
    ) {
        val size: Int get() = items.size
    }

    companion object {
        const val VERSION = 1
        private const val FILE_NAME = "album_index.json"

        /** Converts a parsed album item into an index entry. */
        fun entryOf(photo: SharedAlbumParser.Photo, album: String): Entry? {
            val id = photo.id ?: return null
            return Entry(
                id = id,
                baseUrl = photo.baseUrl,
                width = photo.width ?: 0,
                height = photo.height ?: 0,
                captureMs = photo.captureMs ?: 0L,
                addedMs = photo.addedMs ?: 0L,
                isVideo = photo.isVideo,
                album = album,
            )
        }
    }

    private val file = File(root, FILE_NAME)
    private val gson = Gson()

    val exists: Boolean get() = file.exists() && file.length() > 0

    /**
     * Reads the index, or null when there is not a usable one.
     *
     * Never throws. Null means "no index" and must be treated as "we know nothing about
     * the album", never as "the album is empty" — the two differ by the entire library.
     *
     * A document that parses but holds no items also returns null, on the same reasoning:
     * an empty index is not a fact anyone has established, it is a failed read wearing a
     * valid shape.
     */
    fun load(): Snapshot? {
        if (!exists) return null
        return try {
            val snapshot = gson.fromJson(file.readText(), Snapshot::class.java)
            when {
                snapshot == null -> null
                snapshot.items.isEmpty() -> null
                // A future build's format is not something this one can interpret. Better
                // to re-crawl than to read fields that have moved.
                snapshot.version > VERSION -> null
                else -> snapshot
            }
        } catch (e: JsonSyntaxException) {
            null
        } catch (e: RuntimeException) {
            // Gson raises assorted unchecked types on malformed input; a torn file must
            // not take the frame down.
            null
        }
    }

    /**
     * Replaces the index, write-temp-then-rename with an fsync.
     *
     * The same discipline as [PhotoStore.saveIndex] and for the same reason: a power cut
     * mid-write must leave the previous index intact rather than a truncated one. On a
     * frame that runs unattended for months, "it was interrupted once" is not a rare case.
     *
     * @throws IllegalStateException if the rename fails, so a caller cannot mistake a
     *   failed write for a successful one.
     */
    fun save(snapshot: Snapshot) {
        root.mkdirs()
        val tmp = File(root, "$FILE_NAME.tmp")
        tmp.outputStream().use { out ->
            out.write(gson.toJson(snapshot).toByteArray())
            out.flush()
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not replace the album index" }
    }

    /** Removes the index. For tests and for a forced re-crawl. */
    fun clear(): Boolean = file.delete()
}
