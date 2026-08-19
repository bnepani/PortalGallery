package com.example.portalgallery.data.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Golden-file tests against a real (scrubbed) shared-album page.
 *
 * When Google changes the page structure these fail. That is the point: refresh the
 * fixture with tools/scrub_fixture.py and the diff shows exactly what moved.
 */
class SharedAlbumParserTest {

    private lateinit var fixture: String

    @Before
    fun load() {
        fixture = javaClass.getResourceAsStream("/shared_album_fixture.html")
            ?.bufferedReader()?.readText()
            ?: fail("fixture missing — run tools/scrub_fixture.py").let { return }
    }

    @Test
    fun `structured tier extracts exactly 300 album photos`() {
        val result = SharedAlbumParser.parse(fixture)
        // 300, not 302: the raw regex count includes the og:image social card and the
        // ds:0 cover hero, neither of which is an album photo.
        assertEquals(300, result.photos.size)
        assertEquals(SharedAlbumParser.Tier.STRUCTURED, result.tier)
    }

    @Test
    fun `structured tier yields dimensions and timestamps`() {
        val photos = SharedAlbumParser.parse(fixture).photos
        assertTrue("all photos should carry dimensions",
            photos.all { it.width != null && it.height != null })
        assertTrue("all photos should carry a capture timestamp",
            photos.all { it.captureMs != null })
        assertTrue("ids should be unique", photos.mapNotNull { it.id }.toSet().size == 300)
    }

    /**
     * Regression test for the highest-severity parsing bug found in review: the bare
     * URL resolves to a 384x512 thumbnail, because the size suffix is applied by the
     * page's JavaScript and is absent from the markup. Loading baseUrl directly would
     * silently render postage stamps on a 1920x1200 panel.
     */
    @Test
    fun `url always applies a size suffix`() {
        val photo = SharedAlbumParser.parse(fixture).photos.first()
        val url = photo.url(1920, 1200)
        assertTrue("size suffix missing: $url", url.endsWith("=w1920-h1200-no"))
        assertTrue("should build on the bare base url", url.startsWith(photo.baseUrl))
    }

    @Test
    fun `continuation token signals the album is truncated`() {
        val result = SharedAlbumParser.parse(fixture)
        assertNotNull("this fixture is a 300-photo prefix of a larger album",
            result.continuationToken)
        assertTrue(!result.isCompleteAlbum)
    }

    /**
     * Documents the finding that refuted the original design: the page returns the
     * OLDEST photos in ascending capture order, not a newest-first window. If this
     * ever starts failing, Google changed the ordering and the auto-refresh
     * constraint should be re-evaluated.
     */
    @Test
    fun `page returns oldest-first ascending order`() {
        val capture = SharedAlbumParser.parse(fixture).photos.mapNotNull { it.captureMs }
        val inversions = capture.zipWithNext().count { (a, b) -> a > b }
        assertTrue("expected ascending order, got $inversions/${capture.size - 1} inversions",
            inversions < capture.size * 0.1)
    }

    @Test
    fun `falls back to regex tier when the structured payload is unreachable`() {
        val broken = fixture.replace("key: 'ds:1'", "key: 'ds:XX'")
        val result = SharedAlbumParser.parse(broken)
        assertEquals(SharedAlbumParser.Tier.REGEX, result.tier)
        // Degraded: URLs only, no dimensions. Still filters the two non-photo assets.
        assertEquals(300, result.photos.size)
        assertTrue(result.photos.all { it.width == null })
    }

    /**
     * The parser must never report "zero photos" as a success. Callers rely on an
     * exception to mean "keep the previous set" — a frame going blank because a parse
     * returned an empty list is the failure mode this whole design exists to avoid.
     */
    @Test
    fun `throws rather than returning empty when nothing is extractable`() {
        try {
            SharedAlbumParser.parse("<html><body>nothing here</body></html>")
            fail("expected ParseException, got a result")
        } catch (expected: SharedAlbumParser.ParseException) {
            // correct
        }
    }

    @Test
    fun `album title is extracted`() {
        assertEquals("Test Album", SharedAlbumParser.parse(fixture).albumTitle)
    }

    /**
     * The orientation filter shows only items matching the frame's physical
     * orientation, so it depends entirely on these dimensions being parsed correctly.
     * If this split ever changes without the fixture changing, dimension parsing has
     * silently broken and the frame would be filtering on garbage.
     */
    @Test
    fun `orientation split is parsed correctly`() {
        val items = SharedAlbumParser.parse(fixture).photos
        val portrait = items.count { it.isPortrait }
        val landscape = items.size - portrait
        println("orientation: $portrait portrait / $landscape landscape")
        assertEquals(168, portrait)
        assertEquals(132, landscape)
    }

    /**
     * Videos are identified by a key in the entry's trailing metadata, because nothing
     * else in the page marks them: no MIME type, no "video" string, and `=dv` returns an
     * MP4 for stills too since Google synthesises video from Motion Photos.
     *
     * Pinning the count matters more here than elsewhere. If Google reshapes this, every
     * clip silently becomes a photo and plays as a frozen frame — exactly the bug this
     * feature fixes, returning with no error to announce it.
     */
    @Test
    fun `videos are detected by their metadata key`() {
        val items = SharedAlbumParser.parse(fixture).photos
        val videos = items.filter { it.isVideo }
        assertEquals("fixture should contain 7 videos", 7, videos.size)
        assertEquals("and 293 stills", 293, items.size - videos.size)
    }

    /**
     * Regression test for a heuristic that was wrong.
     *
     * Detection first used the media sub-array length — videos have 10 elements, stills
     * 12. That matched perfectly on one album and failed on this one, where 13 entries
     * are short but only 7 are video; the other 6 are ordinary 4:3 stills and a
     * panorama. Those would have been downloaded as MP4 and played as clips.
     *
     * So: a short sub-array is necessary but not sufficient, and this asserts the gap
     * really exists in the fixture — otherwise the test would pass under the old
     * broken rule too.
     */
    @Test
    fun `short sub-array alone would over-detect videos`() {
        val items = SharedAlbumParser.parse(fixture).photos
        // Anything not 4:3-ish and not video-shaped, plus plain 4:3 stills, are in the
        // short-array group; the point is simply that it is bigger than the true count.
        val videos = items.count { it.isVideo }
        assertTrue("fixture must contain non-video items that the old rule caught", videos < 13)
    }

    @Test
    fun `every detected video has a video aspect ratio and a duration`() {
        val items = SharedAlbumParser.parse(fixture).photos
        fun isWidescreen(w: Int, h: Int) = w * 9 == h * 16 || h * 9 == w * 16

        val videos = items.filter { it.isVideo }
        assertTrue(
            "every detected video should be 16:9 or 9:16",
            videos.all { isWidescreen(it.width!!, it.height!!) },
        )
        assertTrue(
            "duration should be parsed from the same metadata",
            videos.all { (it.durationMs ?: 0) > 0 },
        )
        assertEquals(
            "no still should be 16:9 — that would mean misdetection",
            0,
            items.filter { !it.isVideo }.count { isWidescreen(it.width!!, it.height!!) },
        )
    }

    @Test
    fun `video url requests the mp4, not a poster frame`() {
        val video = SharedAlbumParser.parse(fixture).photos.first { it.isVideo }
        assertTrue(video.videoUrl().endsWith("=dv"))
        // The still URL is still available — it is what the frame showed before, and
        // remains the fallback when video download is disabled.
        assertTrue(video.url(1920, 1200).endsWith("=w1920-h1200-no"))
    }

    @Test
    fun `regex tier excludes the og-image social card`() {
        val broken = fixture.replace("key: 'ds:1'", "key: 'ds:XX'")
        val urls = SharedAlbumParser.parse(broken).photos.map { it.baseUrl }.toSet()
        val ogImage = Regex("""<meta property="og:image" content="[^"]*?(lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+)""")
            .find(fixture)?.groupValues?.get(1)
        assertNotNull("fixture should contain an og:image", ogImage)
        assertTrue("og:image must not be treated as an album photo", "https://$ogImage" !in urls)
    }
}
