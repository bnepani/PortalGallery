package com.example.portalgallery.data.schedule

import com.example.portalgallery.data.schedule.AwakePolicy.Presence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwakePolicyTest {

    private fun awake(
        scheduleAsleep: Boolean,
        presence: Presence,
        override: Boolean? = null,
    ) = AwakePolicy.shouldBeAwake(scheduleAsleep, presence, override)

    // --- presence governs when it is available ------------------------------

    @Test
    fun `someone in view wakes the frame during the day`() {
        assertTrue(awake(scheduleAsleep = false, presence = Presence.PRESENT))
    }

    /** The whole point of choosing presence-overrides-schedule. */
    @Test
    fun `someone in view wakes the frame during quiet hours`() {
        assertTrue(awake(scheduleAsleep = true, presence = Presence.PRESENT))
    }

    @Test
    fun `an empty room sleeps the frame during the day`() {
        assertFalse(awake(scheduleAsleep = false, presence = Presence.ABSENT))
    }

    @Test
    fun `an empty room stays asleep during quiet hours`() {
        assertFalse(awake(scheduleAsleep = true, presence = Presence.ABSENT))
    }

    // --- the fallback that stops the frame dying ----------------------------

    /**
     * The critical case. If a closed privacy shutter or a denied permission were treated
     * as "nobody is here", the frame would sleep and never wake — there would be no
     * presence to bring it back. Unavailable must fall through to the schedule instead.
     */
    @Test
    fun `camera unavailable falls back to the schedule, not to absence`() {
        assertTrue("daytime with no camera should stay awake",
            awake(scheduleAsleep = false, presence = Presence.UNAVAILABLE))
        assertFalse("quiet hours with no camera should sleep",
            awake(scheduleAsleep = true, presence = Presence.UNAVAILABLE))
    }

    // --- manual override outranks everything --------------------------------

    @Test
    fun `manual sleep wins even with someone in view`() {
        assertFalse(awake(scheduleAsleep = false, presence = Presence.PRESENT, override = true))
    }

    @Test
    fun `manual wake wins over an empty room in quiet hours`() {
        assertTrue(awake(scheduleAsleep = true, presence = Presence.ABSENT, override = false))
    }

    // --- explanations, which surface in settings and logs -------------------

    @Test
    fun `explain distinguishes the reasons`() {
        assertTrue(
            AwakePolicy.explain(false, Presence.PRESENT, null).contains("someone in view")
        )
        assertTrue(
            AwakePolicy.explain(false, Presence.ABSENT, null).contains("room empty")
        )
        assertTrue(
            AwakePolicy.explain(true, Presence.UNAVAILABLE, null).contains("no camera")
        )
        assertTrue(
            AwakePolicy.explain(false, Presence.PRESENT, true).contains("manual")
        )
    }
}
