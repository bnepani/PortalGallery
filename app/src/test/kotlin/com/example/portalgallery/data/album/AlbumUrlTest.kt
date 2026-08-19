package com.example.portalgallery.data.album

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumUrlTest {

    @Test
    fun `accepts a short share link`() {
        assertNull(AlbumUrl.problem("https://photos.app.goo.gl/EXAMPLElink01"))
    }

    @Test
    fun `accepts an expanded share url`() {
        assertNull(AlbumUrl.problem("https://photos.google.com/share/AF1QipOZtM8nhO-f5IhP90EH"))
    }

    @Test
    fun `accepts an expanded share url carrying a key token`() {
        assertNull(AlbumUrl.problem("https://photos.google.com/share/AF1Qip123?key=abc123"))
    }

    @Test
    fun `rejects the private owner-side album url`() {
        val problem = AlbumUrl.problem("https://photos.google.com/album/AF1QipOLXwcPxA3Gsd6u")
        assertNotNull(problem)
        assertTrue("should explain how to fix it", problem!!.contains("Share > Create link"))
    }

    @Test
    fun `rejects blank and unrelated urls`() {
        assertNotNull(AlbumUrl.problem(""))
        assertNotNull(AlbumUrl.problem("https://example.com/photos"))
    }

    /**
     * Regression test. The first version of this check searched the page body for
     * "ServiceLogin" and rejected a valid album, because every public share page shows
     * anonymous visitors a "Sign in" button and therefore contains that string. Only
     * the redirect destination is a reliable signal.
     */
    @Test
    fun `sign-in detection keys on the final url, not page content`() {
        assertTrue(
            AlbumUrl.isSignInRedirect(
                "https://accounts.google.com/v3/signin/identifier?continue=https://photos.google.com/album/AF1Qip"
            )
        )
        assertFalse(
            "a valid share page must not be rejected",
            AlbumUrl.isSignInRedirect("https://photos.google.com/share/AF1QipOZtM8nhO-f5IhP90EH")
        )
    }
}
