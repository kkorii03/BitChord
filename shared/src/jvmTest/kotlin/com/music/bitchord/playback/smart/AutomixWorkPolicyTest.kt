package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomixWorkPolicyTest {

    @Test
    fun `only the immediate next track is selected`() {
        val target = AutomixWorkPolicy.nextAnalysisTarget(
            nextId = "next",
            nextAnalysed = false,
            automixEnabled = true,
        )
        assertEquals(AnalysisTarget("next", AnalysisWindow.HEAD), target)
    }

    @Test
    fun `an analysed next track is not decoded again`() {
        assertNull(
            AutomixWorkPolicy.nextAnalysisTarget(
                nextId = "next",
                nextAnalysed = true,
                automixEnabled = true,
            ),
        )
    }

    @Test
    fun `automix off selects no analysis at all`() {
        assertNull(AutomixWorkPolicy.nextAnalysisTarget("next", false, automixEnabled = false))
        assertNull(AutomixWorkPolicy.outgoingAnalysisTarget("current", false, automixEnabled = false))
    }

    @Test
    fun `outgoing tail is only selected when the current track is unmeasured`() {
        assertEquals(
            AnalysisTarget("current", AnalysisWindow.TAIL),
            AutomixWorkPolicy.outgoingAnalysisTarget("current", false, automixEnabled = true),
        )
        assertNull(AutomixWorkPolicy.outgoingAnalysisTarget("current", true, automixEnabled = true))
    }

    @Test
    fun `standby starts fifteen seconds before the transition`() {
        val lead = AutomixWorkPolicy.STANDBY_LEAD_MS
        assertFalse(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead + 1))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead - 1))
    }

    @Test
    fun `a tempo ramp is added to the standby lead`() {
        val lead = AutomixWorkPolicy.STANDBY_LEAD_MS
        assertFalse(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead + 500, rampMs = 400))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead + 400, rampMs = 400))
    }

    @Test
    fun `no transition means no standby lead`() {
        assertEquals(0L, AutomixWorkPolicy.standbyLeadMs(transitionPossible = false))
        assertEquals(
            AutomixWorkPolicy.STANDBY_LEAD_MS,
            AutomixWorkPolicy.standbyLeadMs(transitionPossible = true),
        )
    }

    @Test
    fun `a null next id has nothing to analyse`() {
        assertNull(AutomixWorkPolicy.nextAnalysisTarget(null, false, automixEnabled = true))
    }

    @Test
    fun `a blank next id is treated as no next track`() {
        assertNull(AutomixWorkPolicy.nextAnalysisTarget("", false, automixEnabled = true))
        assertNull(AutomixWorkPolicy.nextAnalysisTarget("   ", false, automixEnabled = true))
        assertNull(AutomixWorkPolicy.nextAnalysisTarget("\t\n", false, automixEnabled = true))
    }

    @Test
    fun `a blank current id has no outgoing tail`() {
        assertNull(AutomixWorkPolicy.outgoingAnalysisTarget(null, false, automixEnabled = true))
        assertNull(AutomixWorkPolicy.outgoingAnalysisTarget("", false, automixEnabled = true))
        assertNull(AutomixWorkPolicy.outgoingAnalysisTarget("  ", false, automixEnabled = true))
    }

    @Test
    fun `the two sides of a transition ask for opposite windows`() {
        assertEquals(
            AnalysisWindow.HEAD,
            AutomixWorkPolicy.nextAnalysisTarget("next", false, true)?.window,
        )
        assertEquals(
            AnalysisWindow.TAIL,
            AutomixWorkPolicy.outgoingAnalysisTarget("current", false, true)?.window,
        )
    }

    @Test
    fun `a transition at or past its start arms immediately`() {
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = 0L))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = -1L))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = Long.MIN_VALUE))
    }

    @Test
    fun `a negative ramp never moves the arm point later`() {
        val lead = AutomixWorkPolicy.STANDBY_LEAD_MS
        assertFalse(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead + 1, rampMs = -500L))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = lead, rampMs = -500L))
    }

    @Test
    fun `a custom lead of zero arms only at the transition point`() {
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = 0L, leadMs = 0L))
        assertFalse(AutomixWorkPolicy.shouldArmStandby(remainingMs = 1L, leadMs = 0L))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = 1L, rampMs = 1L, leadMs = 0L))
    }

    @Test
    fun `a custom lead is the whole deadline when no ramp is running`() {
        assertFalse(AutomixWorkPolicy.shouldArmStandby(remainingMs = 501L, leadMs = 500L))
        assertTrue(AutomixWorkPolicy.shouldArmStandby(remainingMs = 500L, leadMs = 500L))
    }

    @Test
    fun `the analysis window and standby lead keep their battery budgets`() {
        assertEquals(45.0, AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS)
        assertEquals(15_000L, AutomixWorkPolicy.STANDBY_LEAD_MS)
    }
}
