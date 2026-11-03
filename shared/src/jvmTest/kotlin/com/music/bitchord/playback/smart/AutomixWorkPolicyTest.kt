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

    @Test
    fun `only a row that is still a video needs the plain crossfade`() {
        assertTrue(
            AutomixWorkPolicy.videoPairNeedsPlainCrossfade(
                automixVideos = false,
                currentIsVideo = true,
                nextIsVideo = false,
            ),
        )
        assertTrue(
            AutomixWorkPolicy.videoPairNeedsPlainCrossfade(false, false, true),
        )
        assertFalse(
            AutomixWorkPolicy.videoPairNeedsPlainCrossfade(true, true, true),
        )
        // A song version converted from a video is an ordinary audio row.
        assertFalse(
            AutomixWorkPolicy.videoPairNeedsPlainCrossfade(false, false, false),
        )
    }

    @Test
    fun `the marker needs a plan, a duration and both sides measured`() {
        assertTrue(
            AutomixWorkPolicy.markerVisible(
                blocked = false,
                markerVisible = true,
                durationMs = 180_000L,
                currentAnalysed = true,
                nextEnterable = true,
            ),
        )
        assertFalse(
            AutomixWorkPolicy.markerVisible(false, true, 180_000L, true, nextEnterable = false),
        )
        assertFalse(
            AutomixWorkPolicy.markerVisible(false, true, 180_000L, currentAnalysed = false, true),
        )
        assertFalse(
            AutomixWorkPolicy.markerVisible(false, true, durationMs = 0L, true, true),
        )
        assertFalse(
            AutomixWorkPolicy.markerVisible(false, markerVisible = false, 180_000L, true, true),
        )
        assertFalse(
            AutomixWorkPolicy.markerVisible(blocked = true, true, 180_000L, true, true),
        )
    }

    @Test
    fun `a window measured on another rendition is not reused`() {
        assertFalse(AutomixWorkPolicy.windowFitsPlayback(110.0, 173.0))
        assertTrue(AutomixWorkPolicy.windowFitsPlayback(173.0, 173.0))
        assertTrue(AutomixWorkPolicy.windowFitsPlayback(173.0, 173.4))
        // An unknown duration on either side is not evidence of a swap.
        assertTrue(AutomixWorkPolicy.windowFitsPlayback(0.0, 173.0))
        assertTrue(AutomixWorkPolicy.windowFitsPlayback(173.0, 0.0))
        assertTrue(AutomixWorkPolicy.windowFitsPlayback(Double.NaN, 173.0))
    }

    @Test
    fun `an implausibly early smart plan falls back but a real outro plan does not`() {
        assertTrue(
            AutomixWorkPolicy.planNeedsPlainEndFallback(
                blocked = false,
                fadeMs = 6_000L,
                transitionStartMs = 110_000L,
                durationMs = 173_000L,
            ),
        )
        assertFalse(
            AutomixWorkPolicy.planNeedsPlainEndFallback(
                blocked = false,
                fadeMs = 6_000L,
                transitionStartMs = 167_000L,
                durationMs = 173_000L,
            ),
        )
        // A blocked or zero-length smart plan was already not going to arm.
        assertFalse(
            AutomixWorkPolicy.planNeedsPlainEndFallback(
                blocked = true,
                fadeMs = 6_000L,
                transitionStartMs = 110_000L,
                durationMs = 173_000L,
            ),
        )
        assertFalse(
            AutomixWorkPolicy.planNeedsPlainEndFallback(
                blocked = false,
                fadeMs = 0L,
                transitionStartMs = 110_000L,
                durationMs = 173_000L,
            ),
        )
    }

    @Test
    fun `the incoming stream's cache is not part of the marker rule`() {
        // Under Direct Source Stream nothing prefetches the next track until
        // this same plan opens the window, so gating the marker on its bytes
        // hid it for the whole song.
        assertTrue(
            AutomixWorkPolicy.markerVisible(
                blocked = false,
                markerVisible = true,
                durationMs = 180_000L,
                currentAnalysed = true,
                nextEnterable = true,
            ),
        )
    }
}
