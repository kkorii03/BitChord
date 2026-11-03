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
        currentIsVideo: Boolean = false,
        nextIsVideo: Boolean = false,
        automixVideos: Boolean = true,
        current: TrackAnalysisState = TrackAnalysisState.WAITING,
        next: TrackAnalysisState = TrackAnalysisState.WAITING,
    ) = automixStatusLine(
        showNerdStats = showNerdStats,
        automixEnabled = automixEnabled,
        inParty = inParty,
        currentIsVideo = currentIsVideo,
        nextIsVideo = nextIsVideo,
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
    fun `a video on either side says it only crossfades while video automix is off`() {
        assertSame(
            AutomixStatusLine.VideoCrossfade,
            line(currentIsVideo = true, automixVideos = false),
        )
        assertSame(
            AutomixStatusLine.VideoCrossfade,
            line(nextIsVideo = true, automixVideos = false),
        )
        // With video Automix on there is nothing special to report.
        assertEquals(
            AutomixStatusLine.Analysis(TrackAnalysisState.WAITING, TrackAnalysisState.WAITING),
            line(currentIsVideo = true, automixVideos = true),
        )
        assertEquals(
            AutomixStatusLine.Analysis(TrackAnalysisState.WAITING, TrackAnalysisState.WAITING),
            line(nextIsVideo = true, automixVideos = true),
        )
    }

    @Test
    fun `a song version converted from a video is not a video row`() {
        // Both sides are ordinary audio rows now: the old video-origin flag
        // must not keep the caption saying video crossfade.
        assertEquals(
            AutomixStatusLine.Analysis(TrackAnalysisState.ANALYSED, TrackAnalysisState.ANALYSED),
            line(
                currentIsVideo = false,
                nextIsVideo = false,
                automixVideos = false,
                current = TrackAnalysisState.ANALYSED,
                next = TrackAnalysisState.ANALYSED,
            ),
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
