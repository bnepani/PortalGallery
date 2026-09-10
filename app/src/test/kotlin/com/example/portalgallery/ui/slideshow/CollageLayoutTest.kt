package com.example.portalgallery.ui.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageLayoutTest {

    @Test
    fun `slot reports its fractional size`() {
        val s = CollageLayout.Slot(0f, 0f, 0.5f, 1f, wantPortrait = true)
        assertEquals(0.5f, s.width, 1e-6f)
        assertEquals(1f, s.height, 1e-6f)
    }

    @Test
    fun `slot knows whether it is portrait shaped on a given panel`() {
        // A half-width, full-height slot on a 1920x1080 panel is 960x1080 — portrait.
        val s = CollageLayout.Slot(0f, 0f, 0.5f, 1f, wantPortrait = true)
        assertTrue(s.isPortraitOn(1920, 1080))
    }

    @Test
    fun `landscape set is non-empty and within the view pool`() {
        val set = CollageLayout.forPanel(portrait = false)
        assertTrue("expected several templates", set.size >= 4)
        set.forEach {
            assertTrue("${it.name} exceeds MAX_SLOTS", it.slots.size <= CollageLayout.MAX_SLOTS)
            assertTrue("${it.name} has no slots", it.slots.isNotEmpty())
        }
    }

    @Test
    fun `at least one landscape template is all portrait slots`() {
        // This is the template that recovers the 56% portrait library on a landscape panel.
        val set = CollageLayout.forPanel(portrait = false)
        assertTrue(set.any { t -> t.slots.all { it.wantPortrait } })
    }

    private fun allTemplates() =
        CollageLayout.forPanel(portrait = false) + CollageLayout.forPanel(portrait = true)

    @Test
    fun `no slot is degenerate or out of bounds`() {
        allTemplates().forEach { t ->
            t.slots.forEachIndexed { i, s ->
                assertTrue("${t.name}[$i] width <= 0", s.width > 0f)
                assertTrue("${t.name}[$i] height <= 0", s.height > 0f)
                assertTrue("${t.name}[$i] out of bounds", s.left >= -1e-6f && s.top >= -1e-6f)
                assertTrue("${t.name}[$i] out of bounds", s.right <= 1f + 1e-6f && s.bottom <= 1f + 1e-6f)
                // A sliver is a rendering bug, not a design choice.
                assertTrue("${t.name}[$i] is a sliver", s.width >= 0.15f && s.height >= 0.15f)
            }
        }
    }

    @Test
    fun `slots tile the panel without overlapping`() {
        allTemplates().forEach { t ->
            val area = t.slots.sumOf { (it.width * it.height).toDouble() }
            assertEquals("${t.name} does not cover the panel", 1.0, area, 1e-4)

            for (i in t.slots.indices) {
                for (j in i + 1 until t.slots.size) {
                    val a = t.slots[i]
                    val b = t.slots[j]
                    val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                    val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                    assertTrue(
                        "${t.name}: slots $i and $j overlap",
                        overlapW <= 1e-6f || overlapH <= 1e-6f,
                    )
                }
            }
        }
    }

    @Test
    fun `slot orientation tags match their actual shape on the target panel`() {
        CollageLayout.forPanel(portrait = false).forEach { t ->
            t.slots.forEachIndexed { i, s ->
                assertEquals(
                    "${t.name}[$i] tag disagrees with its geometry on 1920x1080",
                    s.wantPortrait, s.isPortraitOn(1920, 1080),
                )
            }
        }
    }

    @Test
    fun `template names are unique`() {
        val names = allTemplates().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
