package com.example.portalgallery.ui.slideshow

/**
 * Collage templates: where the tiles go, and what shape of photo each one wants.
 *
 * Geometry is **fractional** (0..1) rather than pixels, so a template is resolution- and
 * orientation-independent and can be unit tested with no device. The renderer multiplies
 * by the panel size.
 *
 * Templates are data, deliberately. Procedural packing produces slivers and is hard to
 * assert about; a fixed set can be checked exhaustively — see CollageLayoutTest.
 *
 * [Slot.wantPortrait] is the mechanism that recovers the 56% of the library the
 * whole-screen orientation filter discards: a landscape panel has no use for a portrait
 * photo, but a 640x1080 slot has.
 */
object CollageLayout {

    /** The renderer allocates a fixed view pool of this many slots. No template may exceed it. */
    const val MAX_SLOTS = 6

    data class Slot(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** Which orientation of photo this slot is shaped for. */
        val wantPortrait: Boolean,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** True when this slot is taller than it is wide once projected onto a real panel. */
        fun isPortraitOn(panelW: Int, panelH: Int): Boolean =
            height * panelH > width * panelW
    }

    data class Template(val name: String, val slots: List<Slot>)
}
