package com.example.portalgallery.data.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumListTest {

    private fun link(n: Int) = "https://photos.app.goo.gl/EXAMPLElink0$n"

    @Test
    fun `empty input yields no albums`() {
        assertTrue(AlbumList.parse(null).urls.isEmpty())
        assertTrue(AlbumList.parse("").urls.isEmpty())
        assertTrue(AlbumList.parse("   ").urls.isEmpty())
    }

    @Test
    fun `accepts newline and comma separated input`() {
        assertEquals(2, AlbumList.parse("${link(1)}\n${link(2)}").urls.size)
        assertEquals(2, AlbumList.parse("${link(1)},${link(2)}").urls.size)
        assertEquals(3, AlbumList.parse("${link(1)}, ${link(2)}\n ${link(3)} ").urls.size)
    }

    @Test
    fun `order is preserved`() {
        val parsed = AlbumList.parse("${link(3)}\n${link(1)}\n${link(2)}")
        assertEquals(listOf(link(3), link(1), link(2)), parsed.urls)
    }

    @Test
    fun `duplicates are dropped and reported`() {
        val parsed = AlbumList.parse("${link(1)}\n${link(1)}\n${link(2)}")
        assertEquals(2, parsed.urls.size)
        assertTrue(parsed.rejected.any { it.second == "duplicate" })
    }

    /** Silently dropping a bad link would leave someone staring at a frame that never
     *  shows the album they thought they configured. */
    @Test
    fun `invalid links are rejected with a reason, valid ones still kept`() {
        val parsed = AlbumList.parse(
            "${link(1)}\nhttps://photos.google.com/album/AF1QipPRIVATE\n${link(2)}"
        )
        assertEquals(listOf(link(1), link(2)), parsed.urls)
        assertEquals(1, parsed.rejected.size)
        assertTrue(parsed.rejected.first().second.contains("Share > Create link"))
    }

    @Test
    fun `caps at five and reports the overflow`() {
        val raw = (1..8).joinToString("\n") { link(it) }
        val parsed = AlbumList.parse(raw)
        assertEquals(AlbumList.MAX_ALBUMS, parsed.urls.size)
        assertEquals(3, parsed.rejected.size)
        assertTrue(parsed.rejected.all { it.second.contains("limit") })
    }

    @Test
    fun `round-trips through serialise`() {
        val urls = listOf(link(1), link(2), link(3))
        assertEquals(urls, AlbumList.parse(AlbumList.serialise(urls)).urls)
    }

    @Test
    fun `shortName is a usable label`() {
        assertEquals("EXAMPLElink", AlbumList.shortName(link(1)).take(11))
        assertTrue(AlbumList.shortName("https://photos.google.com/share/AF1Qip123?key=x").isNotBlank())
    }
}
