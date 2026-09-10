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

    /**
     * Templates for a landscape panel. Pixel sizes in the comments are for the Portal+'s
     * 1920x1080, which is the only panel this frame actually runs on; the fractions hold
     * for any landscape ratio.
     */
    private val LANDSCAPE = listOf(
        // Three 640x1080 columns. The whole reason collage mode exists: three photos per
        // pass drawn from the 56% the full-screen path throws away.
        Template(
            "thirds-portrait",
            (0 until 3).map { i -> Slot(i / 3f, 0f, (i + 1) / 3f, 1f, wantPortrait = true) },
        ),
        // Six 640x540 cells — the densest layout the view pool allows.
        Template(
            "grid-3x2",
            (0 until 2).flatMap { row ->
                (0 until 3).map { col ->
                    Slot(col / 3f, row / 2f, (col + 1) / 3f, (row + 1) / 2f, wantPortrait = false)
                }
            },
        ),
        // A 960x1080 hero beside four 480x540 cells. Every slot is portrait, including the
        // small ones: half of a 16:9 panel is already 8:9 — past square — and quartering
        // that half preserves the aspect exactly, so the cells inherit it. They are small
        // enough to read as thumbnails and get tagged landscape by eye, which would fill
        // them with photos that letterbox.
        Template(
            "hero-left",
            listOf(Slot(0f, 0f, 0.5f, 1f, wantPortrait = true)) +
                (0 until 2).flatMap { row ->
                    (0 until 2).map { col ->
                        Slot(
                            0.5f + col * 0.25f, row * 0.5f,
                            0.5f + (col + 1) * 0.25f, (row + 1) * 0.5f,
                            wantPortrait = true,
                        )
                    }
                },
        ),
        // Two 576x1080 portrait bookends around a stack of two 768x540 landscapes.
        Template(
            "portrait-pair-centre-stack",
            listOf(
                Slot(0f, 0f, 0.3f, 1f, wantPortrait = true),
                Slot(0.3f, 0f, 0.7f, 0.5f, wantPortrait = false),
                Slot(0.3f, 0.5f, 0.7f, 1f, wantPortrait = false),
                Slot(0.7f, 0f, 1f, 1f, wantPortrait = true),
            ),
        ),
        // A 1920x648 banner over three 640x432 cells. Every slot is landscape, which keeps
        // the 44% that the full-screen path already serves in rotation too.
        Template(
            "banner-over-thirds",
            listOf(Slot(0f, 0f, 1f, 0.6f, wantPortrait = false)) +
                (0 until 3).map { i ->
                    Slot(i / 3f, 0.6f, (i + 1) / 3f, 1f, wantPortrait = false)
                },
        ),
    )

    /**
     * The same ideas transposed for a portrait panel (1080x1920). This is a live path, not
     * insurance: the manifest leaves screenOrientation deliberately unspecified so the frame
     * follows the Portal's physical mounting, and a portrait-mounted Portal is a supported
     * install. On one, this is the only set that ever runs.
     */
    private val PORTRAIT = listOf(
        // Six 540x640 cells — portrait-shaped, so this is the portrait panel's equivalent
        // of thirds-portrait: the layout that soaks up the majority orientation.
        Template(
            "grid-2x3",
            (0 until 3).flatMap { row ->
                (0 until 2).map { col ->
                    Slot(col / 2f, row / 3f, (col + 1) / 2f, (row + 1) / 3f, wantPortrait = true)
                }
            },
        ),
        // Three 1080x640 bands, the counterpart to thirds-portrait.
        Template(
            "thirds-landscape",
            (0 until 3).map { i -> Slot(0f, i / 3f, 1f, (i + 1) / 3f, wantPortrait = false) },
        ),
        // A 1080x960 hero above four 540x480 cells — hero-left mirrored, and mirrored in
        // the tagging trap too: on a 9:16 panel these cells land just short of square, so
        // every slot is landscape.
        Template(
            "hero-top",
            listOf(Slot(0f, 0f, 1f, 0.5f, wantPortrait = false)) +
                (0 until 2).flatMap { row ->
                    (0 until 2).map { col ->
                        Slot(
                            col * 0.5f, 0.5f + row * 0.25f,
                            (col + 1) * 0.5f, 0.5f + (row + 1) * 0.25f,
                            wantPortrait = false,
                        )
                    }
                },
        ),
        // Two 1080x960 halves. The quietest template in either set; it exists so the
        // rotation has somewhere to breathe between the dense ones.
        Template(
            "halves",
            listOf(
                Slot(0f, 0f, 1f, 0.5f, wantPortrait = false),
                Slot(0f, 0.5f, 1f, 1f, wantPortrait = false),
            ),
        ),
    )

    fun forPanel(portrait: Boolean): List<Template> = if (portrait) PORTRAIT else LANDSCAPE

    /**
     * Template for the given rotation counter. Pure: the renderer swaps templates only
     * behind a hero interlude, so this must not depend on wall-clock time.
     *
     * floorMod rather than %, because the counter is a plain Int on a frame that runs for
     * months — it will eventually wrap negative, and % would then index out of bounds.
     */
    fun templateAt(portrait: Boolean, index: Int): Template {
        val set = forPanel(portrait)
        return set[Math.floorMod(index, set.size)]
    }
}
