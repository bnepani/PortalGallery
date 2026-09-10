package com.example.portalgallery.data.album

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the pagination envelope, against scrubbed captures of real responses.
 *
 * The two tests that matter most here are not the ones that check parsing works. They are
 * `token goes in slot 1` and `consecutive pages hold different photos` — between them they
 * pin the two things that were actually wrong when this RPC was first executed against the
 * live album, both of which returned HTTP 200 and well-formed data while being silently
 * useless.
 */
class AlbumPagerTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test fixture: $name"
        }.bufferedReader().use { it.readText() }

    private val page2 by lazy { fixture("rpc_page2_fixture.txt") }
    private val page3 by lazy { fixture("rpc_page3_fixture.txt") }

    private val endpoint = AlbumPager.Endpoint(
        albumId = "AF1QipTESTALBUMIDTESTALBUMID",
        shareKey = "TESTSHAREKEY0123456789",
        sourcePath = "/share/AF1QipTESTALBUMIDTESTALBUMID",
        sid = "-831142912606585437",
        bl = "boq_photosuiserver_20260908.06_p1",
        pathPrefix = "/_/PhotosUi",
    )

    // --- the request ---------------------------------------------------------

    @Test
    fun `token goes in slot 1, not slot 2`() {
        // The page's own tuple is [albumId, null, null, shareKey]. Putting the token in
        // slot 2 — the reading the design originally took — returns HTTP 200, 300 valid
        // entries and a fresh token every call, so a crawl loops forever on page 1.
        // Measured against the live album: 5 pages, 1500 entries, 300 unique.
        val body = AlbumPager.buildRequestBody(endpoint, "THE_TOKEN")
        val freq = body.removePrefix("f.req=")
        val decoded = java.net.URLDecoder.decode(freq, "UTF-8")

        val outer = JsonParser.parseString(decoded).asJsonArray
        val call = outer[0].asJsonArray[0].asJsonArray
        assertEquals("snAcKc", call[0].asString)

        val args = JsonParser.parseString(call[1].asString).asJsonArray
        assertEquals("albumId belongs in slot 0", endpoint.albumId, args[0].asString)
        assertEquals("THE TOKEN BELONGS IN SLOT 1", "THE_TOKEN", args[1].asString)
        assertTrue("slot 2 must stay null", args[2].isJsonNull)
        assertEquals("shareKey belongs in slot 3", endpoint.shareKey, args[3].asString)
    }

    @Test
    fun `request body is form-encoded and names the rpc`() {
        val body = AlbumPager.buildRequestBody(endpoint, "tok")
        assertTrue(body.startsWith("f.req="))
        assertFalse("must be percent-encoded, not raw JSON", body.contains("[["))
        assertTrue(AlbumPager.buildUrl(endpoint).contains("rpcids=snAcKc"))
    }

    @Test
    fun `url carries the session and build label the page supplied`() {
        val url = AlbumPager.buildUrl(endpoint)
        assertTrue(url.startsWith("https://photos.google.com/_/PhotosUi/data/batchexecute"))
        assertTrue(url.contains("f.sid=-831142912606585437"))
        // bl contains a dot, which must survive encoding as itself.
        assertTrue(url.contains("bl=boq_photosuiserver_20260908.06_p1"))
    }

    // --- the response --------------------------------------------------------

    @Test
    fun `parses a real response into photos and a next token`() {
        val page = AlbumPager.parseResponse(page2)
        assertEquals(300, page.photos.size)
        assertNotNull(page.nextToken)
        assertEquals(226, page.nextToken!!.length)
        assertFalse("a token means more pages remain", page.complete)
    }

    @Test
    fun `decoded entries carry the fields the index needs`() {
        val photos = AlbumPager.parseResponse(page2).photos
        assertTrue("every entry needs an id", photos.all { !it.id.isNullOrBlank() })
        assertTrue("every entry needs a url", photos.all { it.baseUrl.startsWith("https://") })
        assertTrue("dimensions drive the orientation tags", photos.all { (it.width ?: 0) > 0 })
        assertTrue("capture time drives era mix", photos.count { (it.captureMs ?: 0) > 0 } > 250)
    }

    @Test
    fun `consecutive pages hold different photos`() {
        // The regression test for the slot bug. Parsing alone cannot catch it: the wrong
        // slot produces a response that parses perfectly and is simply page 1 again.
        val a = AlbumPager.parseResponse(page2).photos.mapNotNull { it.id }.toSet()
        val b = AlbumPager.parseResponse(page3).photos.mapNotNull { it.id }.toSet()
        assertEquals(300, a.size)
        assertEquals(300, b.size)
        assertTrue("page 2 and page 3 must not overlap", (a intersect b).isEmpty())
    }

    @Test
    fun `length prefixes are ignored, not trusted`() {
        // The counts are byte lengths against text and are off by one against the JSON
        // they introduce, so a parser that slices by them fails. Corrupting them must
        // therefore change nothing.
        val mangled = page2.replace(Regex("(?m)^\\d+$"), "999999")
        val page = AlbumPager.parseResponse(mangled)
        assertEquals(300, page.photos.size)
    }

    @Test
    fun `a response with no payload for our rpc throws rather than reporting an empty album`() {
        // "Read nothing" and "the album is empty" must never be confused: the second
        // would let a caller prune the entire library.
        val other = ")]}'\n\n26\n[[\"wrb.fr\",\"XXXXXX\",\"[]\"]]\n"
        try {
            AlbumPager.parseResponse(other)
            throw AssertionError("expected PagerException")
        } catch (e: AlbumPager.PagerException) {
            assertTrue(e.message!!.contains("snAcKc"))
        }
    }

    @Test
    fun `garbage does not parse as a successful empty page`() {
        listOf("", ")]}'\n\n", "not json at all", "{}").forEach { junk ->
            try {
                AlbumPager.parseResponse(junk)
                throw AssertionError("expected PagerException for '${junk.take(12)}'")
            } catch (e: AlbumPager.PagerException) {
                // expected
            }
        }
    }

    // --- endpoint extraction -------------------------------------------------

    @Test
    fun `endpoint is lifted from the share page`() {
        val html = fixture("shared_album_fixture.html")
        val ep = AlbumPager.parseEndpoint(html, "/share/TESTPATH")
        assertNotNull("the fixture declares snAcKc; extraction must succeed", ep)
        assertTrue(ep!!.albumId.startsWith("AF1Qip"))
        assertTrue(ep.shareKey.isNotBlank())
        assertEquals("/share/TESTPATH", ep.sourcePath)
        assertTrue(ep.sid.isNotBlank())
        assertTrue(ep.bl.isNotBlank())
        assertEquals("/_/PhotosUi", ep.pathPrefix)
    }

    @Test
    fun `a page without the rpc declaration yields no endpoint`() {
        assertNull(AlbumPager.parseEndpoint("<html>nothing here</html>", "/share/x"))
    }
}
