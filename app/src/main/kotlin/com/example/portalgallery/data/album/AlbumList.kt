package com.example.portalgallery.data.album

/**
 * Parsing and validation for the configured set of albums.
 *
 * Pure and Android-free so the edge cases are testable: duplicates, blanks, over-cap
 * input, and the mix of valid and invalid links you get when someone pastes a list.
 */
object AlbumList {

    /**
     * Hard cap. Each album contributes up to ~300 items, so five is already ~1500
     * photos and several hundred megabytes once videos are on — well beyond what a
     * frame needs, and enough that sync time starts to be noticeable.
     */
    const val MAX_ALBUMS = 5

    const val DELIMITER = "\n"

    data class Parsed(
        val urls: List<String>,
        /** Entries rejected, with the reason, so the caller can say why rather than
         *  silently dropping them. */
        val rejected: List<Pair<String, String>>,
    )

    /**
     * Accepts newline- or comma-separated input, which is what both `local.properties`
     * and an adb extra realistically look like.
     *
     * De-duplicates, drops blanks, validates each entry, and truncates at [MAX_ALBUMS] —
     * reporting anything dropped rather than discarding it quietly.
     */
    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed(emptyList(), emptyList())

        val seen = LinkedHashSet<String>()
        val rejected = mutableListOf<Pair<String, String>>()

        raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { candidate ->
                val problem = AlbumUrl.problem(candidate)
                when {
                    problem != null -> rejected += candidate to problem
                    !seen.add(candidate) -> rejected += candidate to "duplicate"
                    else -> Unit
                }
            }

        val kept = seen.take(MAX_ALBUMS)
        seen.drop(MAX_ALBUMS).forEach {
            rejected += it to "over the $MAX_ALBUMS-album limit"
        }
        return Parsed(kept, rejected)
    }

    fun serialise(urls: List<String>): String = urls.joinToString(DELIMITER)

    /** Short label for a share link, for logs and the settings readout. */
    fun shortName(url: String): String =
        url.substringAfterLast('/').substringBefore('?').take(12).ifBlank { url }
}
