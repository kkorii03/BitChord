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
}
