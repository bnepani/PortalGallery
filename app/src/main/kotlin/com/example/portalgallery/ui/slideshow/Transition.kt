package com.example.portalgallery.ui.slideshow

/**
 * How one photo gives way to the next.
 *
 * Stored by [name] so the preference survives reordering of this enum; unknown or
 * missing values fall back to [CROSSFADE] rather than throwing.
 */
enum class Transition(val label: String, val description: String) {
    CROSSFADE("Crossfade", "One photo dissolves into the next"),
    SLIDE("Slide", "The next photo slides in from the right"),
    ZOOM("Zoom", "The next photo settles in from slightly enlarged"),
    CUT("Cut", "Instant change, no animation"),
    RANDOM("Random", "A different effect each time");

    companion object {
        val DEFAULT = CROSSFADE

        fun from(value: String?): Transition =
            values().firstOrNull { it.name == value } ?: DEFAULT

        /** RANDOM resolves to a concrete effect, never to itself or to CUT. */
        fun randomConcrete(): Transition =
            listOf(CROSSFADE, SLIDE, ZOOM).random()
    }
}
