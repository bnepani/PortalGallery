package com.example.portalgallery.data.store

import com.example.portalgallery.data.album.SharedAlbumParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The index is the only place that records whether a crawl actually finished, so most of
 * what matters here is what happens when a read goes wrong — every one of those paths has
 * to be distinguishable from "the album is small".
 */
class AlbumIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun index() = AlbumIndex(tmp.root)

    private fun entry(id: String, year: Int = 2024, portrait: Boolean = false) = AlbumIndex.Entry(
        id = id,
        baseUrl = "https://lh3.googleusercontent.com/pw/$id",
        width = if (portrait) 3000 else 4000,
        height = if (portrait) 4000 else 3000,
        captureMs = year * 31_557_600_000L,
        addedMs = year * 31_557_600_000L + 1000,
        isVideo = false,
        album = "https://photos.app.goo.gl/TEST",
    )

    // --- round trip ----------------------------------------------------------

    @Test
    fun `saves and reloads an index`() {
        val snap = AlbumIndex.Snapshot(
            crawledAtMs = 1_700_000_000_000L,
            pageCount = 67,
            complete = true,
            items = listOf(entry("a"), entry("b", portrait = true)),
        )
        index().save(snap)

        val back = index().load()
        assertNotNull(back)
        assertEquals(2, back!!.size)
        assertEquals(67, back.pageCount)
        assertEquals(1_700_000_000_000L, back.crawledAtMs)
        assertTrue(back.complete)
        assertEquals("b", back.items[1].id)
        assertTrue("orientation must survive the round trip", back.items[1].isPortrait)
        assertTrue("baseUrl is what lets an item be fetched later",
            back.items[0].baseUrl.startsWith("https://"))
    }

    @Test
    fun `the completeness flag round-trips both ways`() {
        // The whole of C11 rests on this value surviving a save and a load.
        index().save(AlbumIndex.Snapshot(complete = false, items = listOf(entry("a"))))
        assertFalse(index().load()!!.complete)

        index().save(AlbumIndex.Snapshot(complete = true, items = listOf(entry("a"))))
        assertTrue(index().load()!!.complete)
    }

    // --- the ways a read can go wrong ----------------------------------------

    @Test
    fun `no index reads as null, not as an empty album`() {
        assertNull(index().load())
        assertFalse(index().exists)
    }

    @Test
    fun `a torn file reads as null rather than throwing`() {
        index().save(AlbumIndex.Snapshot(items = listOf(entry("a"))))
        val f = File(tmp.root, "album_index.json")
        f.writeText(f.readText().substring(0, 40))   // power cut mid-write
        assertNull(index().load())
    }

    @Test
    fun `garbage reads as null`() {
        listOf("", "   ", "not json", "{", "[1,2,3]", "null").forEach { junk ->
            File(tmp.root, "album_index.json").writeText(junk)
            assertNull("input: '$junk'", index().load())
        }
    }

    @Test
    fun `an index holding no items reads as null`() {
        // A valid document with an empty list is not evidence the album is empty; it is a
        // failed read wearing a valid shape, and treating it as fact would let a caller
        // prune everything.
        index().save(AlbumIndex.Snapshot(complete = true, items = emptyList()))
        assertNull(index().load())
    }

    @Test
    fun `an index from a newer build is refused rather than misread`() {
        index().save(AlbumIndex.Snapshot(version = AlbumIndex.VERSION + 1, items = listOf(entry("a"))))
        assertNull(index().load())
    }

    @Test
    fun `a failed save leaves the previous index intact`() {
        val good = AlbumIndex.Snapshot(pageCount = 67, complete = true, items = listOf(entry("a")))
        index().save(good)
        // A directory where the temp file wants to be makes the write fail.
        File(tmp.root, "album_index.json.tmp").mkdirs()
        runCatching { index().save(AlbumIndex.Snapshot(items = listOf(entry("z")))) }
        val back = index().load()
        assertNotNull("the old index must survive a failed write", back)
        assertEquals("a", back!!.items[0].id)
        assertEquals(67, back.pageCount)
    }

    // --- conversion ----------------------------------------------------------

    @Test
    fun `converts parsed photos, and skips ones with no id`() {
        val withId = SharedAlbumParser.Photo(
            id = "abc", baseUrl = "https://x/pw/abc", width = 100, height = 200,
            captureMs = 5L, addedMs = 6L, isVideo = true,
        )
        val e = AlbumIndex.entryOf(withId, "ALBUM")
        assertNotNull(e)
        assertEquals("abc", e!!.id)
        assertEquals(200, e.height)
        assertEquals(6L, e.addedMs)
        assertTrue(e.isVideo)
        assertEquals("ALBUM", e.album)
        assertTrue(e.isPortrait)

        // The regex fallback tier yields photos with no id. They cannot be indexed —
        // there is nothing to key a downloaded file on.
        val noId = withId.copy(id = null)
        assertNull(AlbumIndex.entryOf(noId, "ALBUM"))
    }

    @Test
    fun `missing dimensions and timestamps become zero rather than failing`() {
        val sparse = SharedAlbumParser.Photo(
            id = "x", baseUrl = "https://x/pw/x", width = null, height = null,
            captureMs = null, addedMs = null,
        )
        val e = AlbumIndex.entryOf(sparse, "A")!!
        assertEquals(0, e.width)
        assertEquals(0L, e.captureMs)
        // Consistent with PhotoStore.Entry, where 0 means "unknown" and the selector
        // treats it as always matching rather than as 1970.
        assertEquals(0L, e.addedMs)
    }

    // --- scale ---------------------------------------------------------------

    @Test
    fun `handles a full-size archive`() {
        // The reference album is 19,974 items. This is the measurement behind the
        // decision to keep the index as one JSON document instead of a database.
        val items = (1..20_000).map { entry("id%05d".format(it), year = 2019 + it % 8) }
        val idx = index()

        val wrote = System.currentTimeMillis()
        idx.save(AlbumIndex.Snapshot(pageCount = 67, complete = true, items = items))
        val writeMs = System.currentTimeMillis() - wrote

        val read = System.currentTimeMillis()
        val back = idx.load()
        val readMs = System.currentTimeMillis() - read

        assertNotNull(back)
        assertEquals(20_000, back!!.size)
        assertEquals("id00001", back.items[0].id)
        assertEquals("id20000", back.items[19_999].id)

        val bytes = File(tmp.root, "album_index.json").length()
        println("index: 20,000 items, ${bytes / 1024}KB, write ${writeMs}ms, read ${readMs}ms")
        assertTrue("an index this size should stay well under 32MB, was $bytes", bytes < 32 * 1024 * 1024)
    }
}
