package com.example.portalgallery.data.album

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * Extracts photo entries from a Google Photos public shared-album page.
 *
 * This is deliberately pure Kotlin with no Android dependencies so it can be unit
 * tested against a committed HTML fixture in milliseconds. The fixture test is the
 * highest-value test in this project: this parser depends on undocumented page
 * structure and will break when Google changes it.
 *
 * Two tiers, in order:
 *   1. STRUCTURED — parse the `ds:1` AF_initDataCallback payload as JSON. Richer:
 *      yields dimensions and timestamps, which let callers sort, filter videos, and
 *      enforce a resolution gate.
 *   2. REGEX — harvest photo URLs from raw HTML. A degraded-mode alarm, not an
 *      equivalent path. It loses dimensions and timestamps.
 *
 * WARNING: never load [Photo.baseUrl] directly. Bare, it resolves to a 384x512
 * thumbnail — the size suffix is applied by the page's JavaScript at render time and
 * is absent from the markup. Always go through [Photo.url]. This is why the parser
 * does not expose a ready-to-load URL string.
 */
object SharedAlbumParser {

    /** Suffixes indicating a hard-cropped social/cover asset rather than an album photo. */
    private val CROP_SUFFIXES = listOf("-p-k", "-p-k-no")

    private val PHOTO_URL = Regex("""lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+""")
    private val OG_IMAGE = Regex("""<meta property="og:image" content="([^"]*)"""")
    private val OG_TITLE = Regex("""<meta property="og:title" content="([^"]*)"""")

    enum class Tier { STRUCTURED, REGEX }

    data class Photo(
        val id: String?,
        /** Bare URL. Do not load this directly — see [url]. */
        val baseUrl: String,
        val width: Int?,
        val height: Int?,
        val captureMs: Long?,
        val addedMs: Long?,
        val isVideo: Boolean = false,
        /** Clip length in milliseconds, when known. Stills are null. */
        val durationMs: Long? = null,
    ) {
        val isPortrait: Boolean get() = (height ?: 0) > (width ?: 0)

        /** Builds a loadable URL bounded to [w] x [h]. Fit-inside, not a crop.
         *  For a video this yields its poster frame, which is what the old code
         *  displayed for every clip: a silent still. */
        fun url(w: Int, h: Int): String = "$baseUrl=w$w-h$h-no"

        /**
         * The MP4 itself.
         *
         * Note `=dv` returns a video for *stills* too — Google synthesises one from
         * Motion Photos — so this suffix cannot be used to decide what is a video.
         * That is why detection is structural; see [isVideo].
         */
        fun videoUrl(): String = "$baseUrl=dv"
    }

    data class Result(
        val photos: List<Photo>,
        val albumTitle: String?,
        val continuationToken: String?,
        val tier: Tier,
    ) {
        /** True when the page returned the entire album, so ordering cannot hide new photos. */
        val isCompleteAlbum: Boolean get() = continuationToken == null
    }

    class ParseException(message: String) : Exception(message)

    /**
     * @throws ParseException if neither tier yields photos. Callers must treat this as
     *   "keep the previous set", never as "the album is empty".
     */
    fun parse(html: String): Result {
        val title = OG_TITLE.find(html)?.groupValues?.get(1)
        structured(html, title)?.let { if (it.photos.isNotEmpty()) return it }
        regex(html, title)?.let { if (it.photos.isNotEmpty()) return it }
        throw ParseException("no photos extracted by either tier; page structure likely changed")
    }

    // --- Tier 1 -------------------------------------------------------------

    private fun structured(html: String, title: String?): Result? {
        val data = extractDsRaw(html, "ds:1")
            ?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() }
            ?: return null
        if (data.size() < 2 || !data[1].isJsonArray) return null

        val photos = data[1].asJsonArray.mapNotNull { element ->
            runCatching {
                val e = element.asJsonArray
                // [id, [url, w, h, ...], captureMs, ?, tzOffset, addedMs, ...]
                val media = e[1].asJsonArray
                val video = videoMeta(e)
                Photo(
                    id = e[0].asString,
                    baseUrl = normalizeScheme(media[0].asString),
                    width = media.getOrNullInt(1),
                    height = media.getOrNullInt(2),
                    captureMs = e.getOrNullLong(2),
                    addedMs = e.getOrNullLong(5),
                    isVideo = video != null,
                    durationMs = video?.let { runCatching { it[0].asLong }.getOrNull() },
                )
            }.getOrNull()
        }

        val token = runCatching {
            data[2].takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        return Result(photos, title, token, Tier.STRUCTURED)
    }

    /** Pulls the raw `data:` array text out of an AF_initDataCallback block by ds key. */
    private fun extractDsRaw(html: String, key: String): String? {
        val anchor = html.indexOf("key: '$key'").takeIf { it >= 0 } ?: return null
        val dataAt = html.indexOf("data:", anchor).takeIf { it >= 0 } ?: return null
        val start = html.indexOf('[', dataAt).takeIf { it >= 0 } ?: return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // --- Tier 2 -------------------------------------------------------------

    /**
     * Degraded mode. Yields URLs only — no dimensions, no timestamps — and cannot
     * distinguish album photos from page furniture beyond the filtering below.
     * A caller reaching this tier should log loudly.
     */
    private fun regex(html: String, title: String?): Result? {
        // Exclude the og:image social card and any hard-cropped asset; both are page
        // furniture, not album photos, and would otherwise inflate the count.
        val ogImage = OG_IMAGE.find(html)?.groupValues?.get(1)
            ?.let { PHOTO_URL.find(it)?.value }

        val cropped = Regex("""lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+=[A-Za-z0-9_-]*""")
            .findAll(html)
            .filter { m -> CROP_SUFFIXES.any { m.value.endsWith(it) } }
            .mapNotNull { PHOTO_URL.find(it.value)?.value }
            .toSet()

        // ds:0 carries album metadata including the cover hero, which is not an album
        // photo. Verified on the fixture: ds:0 holds exactly one URL with zero overlap
        // with the ds:1 photo array, so excluding it drops the cover and nothing else.
        val coverUrls = extractDsRaw(html, "ds:0")
            ?.let { raw -> PHOTO_URL.findAll(raw).map { it.value }.toSet() }
            ?: emptySet()

        val photos = PHOTO_URL.findAll(html)
            .map { it.value }
            .distinct()
            .filter { it != ogImage && it !in cropped && it !in coverUrls }
            .map {
                Photo(
                    id = null,
                    baseUrl = normalizeScheme(it),
                    width = null,
                    height = null,
                    captureMs = null,
                    addedMs = null,
                )
            }
            .toList()

        return Result(photos, title, continuationToken = null, tier = Tier.REGEX)
    }

    /**
     * Key in the entry's trailing metadata object that marks an item as video. Its
     * value is `[durationMs, null, width, height, …]`.
     *
     * An earlier version used a structural heuristic instead — video entries have a
     * shorter media sub-array than stills (10 elements vs 12). That looked exact on one
     * album, where all 7 short entries were videos, and was wrong: a second album has 13
     * short entries of which only 7 are video. The other 6 are ordinary 4:3 stills and a
     * panorama, which would have been downloaded as MP4 and played as clips.
     *
     * A short sub-array is necessary but not sufficient. This key is exact on both
     * albums. Still undocumented, hence the golden tests.
     */
    private const val VIDEO_META_KEY = "76647426"

    /** `[durationMs, null, width, height, …]`, or null when the item is a still. */
    private fun videoMeta(entry: JsonArray): JsonArray? = runCatching {
        entry.get(9)?.takeIf { it.isJsonObject }
            ?.asJsonObject?.get(VIDEO_META_KEY)
            ?.takeIf { it.isJsonArray }?.asJsonArray
    }.getOrNull()

    /** The page uses scheme-relative and absolute forms; the regex tier yields neither. */
    private fun normalizeScheme(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://$url"
    }

    private fun JsonArray.getOrNullInt(i: Int): Int? =
        runCatching { this[i].asInt }.getOrNull()

    private fun JsonArray.getOrNullLong(i: Int): Long? =
        runCatching { this[i].asLong.takeIf { it > 1_000_000_000_000L } }.getOrNull()
}
