package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The next-prep line has to say why a sync was skipped, not only that it was:
 * the diagnostic tool leaves non-attempts (video, switches off) out of the
 * denominator and counts a genuine "no timing found" as a miss.
 */
class NextPrepLogTest {

    private fun value(
        isVideo: Boolean = false,
        nextPrepLyrics: Boolean = true,
        syncedLyrics: Boolean = true,
        heavyWorkAllowed: Boolean = true,
        aiTimingAhead: Boolean = true,
        deepgramUsable: Boolean = true,
        hasLyrics: Boolean = true,
        hasTiming: Boolean = true,
    ) = NextPrepPolicy.syncValue(
        isVideo = isVideo,
        nextPrepLyrics = nextPrepLyrics,
        syncedLyrics = syncedLyrics,
        heavyWorkAllowed = heavyWorkAllowed,
        aiTimingAhead = aiTimingAhead,
        deepgramUsable = deepgramUsable,
        hasLyrics = hasLyrics,
        hasTiming = hasTiming,
    )

    @Test
    fun `a timed song is applied`() {
        assertEquals("applied", value())
    }

    @Test
    fun `a video is not a timing attempt`() {
        assertEquals("skipped(video)", value(isVideo = true, hasLyrics = false, hasTiming = false))
    }

    @Test
    fun `each switch says which one turned it off`() {
        assertEquals("skipped(lyrics-off)", value(syncedLyrics = false, hasTiming = false))
        assertEquals("skipped(heavy-off)", value(heavyWorkAllowed = false, hasTiming = false))
        assertEquals("skipped(ai-off)", value(aiTimingAhead = false, hasTiming = false))
        assertEquals(
            "skipped(deepgram-unavailable)",
            value(deepgramUsable = false, hasTiming = false),
        )
    }

    @Test
    fun `a cached timing still counts as applied when a switch is off`() {
        assertEquals("applied", value(syncedLyrics = false, hasTiming = true))
    }

    @Test
    fun `no lyrics and no timing are separate misses`() {
        assertEquals("skipped(no-lyrics)", value(hasLyrics = false, hasTiming = false))
        assertEquals("skipped(no-timing)", value(hasTiming = false))
    }

    @Test
    fun `the next-prep lyrics switch is not mistaken for missing lyrics`() {
        assertEquals(
            "skipped(switch-off)",
            value(nextPrepLyrics = false, hasLyrics = false, hasTiming = false),
        )
    }
}
