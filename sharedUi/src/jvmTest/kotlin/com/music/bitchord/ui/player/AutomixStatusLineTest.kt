package com.music.bitchord.ui.player

import com.music.bitchord.data.settings.TrackAnalysisState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AutomixStatusLineTest {

    private fun line(
        showNerdStats: Boolean = true,
        automixEnabled: Boolean = true,
        inParty: Boolean = false,
        isVideoOrigin: Boolean = false,
        automixVideos: Boolean = true,
        current: TrackAnalysisState = TrackAnalysisState.WAITING,
        next: TrackAnalysisState = TrackAnalysisState.WAITING,
    ) = automixStatusLine(
        showNerdStats = showNerdStats,
        automixEnabled = automixEnabled,
        inParty = inParty,
        isVideoOrigin = isVideoOrigin,
        automixVideos = automixVideos,
        current = current,
        next = next,
    )

    @Test
    fun `the line is hidden without the stats switch`() {
        assertSame(AutomixStatusLine.Hidden, line(showNerdStats = false))
    }

    @Test
    fun `the line is hidden while automix is off`() {
        assertSame(AutomixStatusLine.Hidden, line(automixEnabled = false))
    }

    @Test
    fun `a party explains why analysis is stopped`() {
        assertSame(AutomixStatusLine.StoppedInParty, line(inParty = true))
    }

    @Test
    fun `a video song says it only crossfades while video automix is off`() {
        assertSame(
            AutomixStatusLine.VideoCrossfade,
            line(isVideoOrigin = true, automixVideos = false),
        )
        // With video Automix on there is nothing special to report.
        assertEquals(
            AutomixStatusLine.Analysis(TrackAnalysisState.WAITING, TrackAnalysisState.WAITING),
            line(isVideoOrigin = true, automixVideos = true),
        )
    }

    @Test
    fun `each analysis stage is carried through for both songs`() {
        val stages = TrackAnalysisState.entries
        for (current in stages) {
            for (next in stages) {
                assertEquals(
                    AutomixStatusLine.Analysis(current, next),
                    line(current = current, next = next),
                )
            }
        }
    }
}
