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
}
