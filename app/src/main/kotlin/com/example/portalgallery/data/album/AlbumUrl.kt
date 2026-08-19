package com.example.portalgallery.data.album

/**
 * Validation for Google Photos album URLs.
 *
 * Pure and Android-free so it is unit testable. That matters: the first version of this
 * logic lived inline in AlbumSync and rejected a valid album because the page body
 * contained the string "ServiceLogin" — which every public share page does, since
 * anonymous visitors are shown a "Sign in" button. Content sniffing was the wrong
 * signal; the redirect target is the right one.
 */
object AlbumUrl {

    /** Problem with a configured URL, or null if it looks usable. */
    fun problem(url: String): String? = when {
        url.isBlank() ->
            "no album URL configured"

        url.contains("photos.google.com/album/") ->
            "that is your private album URL, which requires signing in. Open the album " +
                "in Google Photos > Share > Create link, and use the photos.app.goo.gl " +
                "link that produces."

        !url.contains("photos.app.goo.gl/") && !url.contains("photos.google.com/share/") ->
            "not a Google Photos share link: $url"

        else -> null
    }

    /**
     * True when a fetch ended up at Google's sign-in flow, meaning the album is not
     * publicly shared.
     *
     * Checks only where the request *landed*. A valid share page mentions sign-in in
     * its markup and must not be rejected for it.
     */
    fun isSignInRedirect(finalUrl: String): Boolean =
        finalUrl.startsWith("https://accounts.google.com/") ||
            finalUrl.startsWith("http://accounts.google.com/")
}
