package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two lines the diagnostic export gains, assembled without a device. */
class TrackSummaryFormatTest {

    @Test
    fun `a full summary carries every field in the documented order`() {
        val line = TrackSummaryFormat.format(
            id = "abc123",
            title = "Song",
            video = true,
            source = "browse:MPREb_x",
            resolve = "proxy",
            firstAudioMs = 1_234L,
            playedSeconds = 210L,
            lyrics = "lrc.red",
            sync = "ai",
            shiftMs = -820L,
            matchPercent = 91,
            manualOffsetMs = 250L,
            ended = "natural",
        )
        assertEquals(
            "track-summary: abc123 'Song' video=y source=browse:MPREb_x resolve=proxy " +
                "firstAudio=1234 played=210 lyrics=lrc.red sync=ai shift=-820 match=91 " +
                "manualOffset=250 ended=natural",
            line,
        )
    }

    @Test
    fun `unknown timing and audio are written as a dash rather than a zero`() {
        val line = TrackSummaryFormat.format(
            id = "id",
            title = "T",
            video = false,
            source = "none",
            resolve = "cache",
            firstAudioMs = null,
            playedSeconds = 0L,
            lyrics = "none",
            sync = "none",
            shiftMs = null,
            matchPercent = null,
            manualOffsetMs = 0L,
            ended = "skipped",
        )
        assertTrue(line.contains("firstAudio=- played=0 lyrics=none sync=none shift=- match=-"))
    }

    @Test
    fun `a title never introduces a second log line`() {
        val line = TrackSummaryFormat.format(
            id = "id",
            title = "one\ntwo",
            video = false,
            source = "search",
            resolve = "direct",
            firstAudioMs = 0L,
            playedSeconds = 1L,
            lyrics = "file",
            sync = "manual",
            shiftMs = 10L,
            matchPercent = 100,
            manualOffsetMs = 10L,
            ended = "error",
        )
        assertEquals(1, line.lines().size)
        assertTrue(line.contains("'one two'"))
    }

    @Test
    fun `a mark names the moment and the lyric line`() {
        val line = TrackSummaryFormat.mark(
            id = "abc123",
            title = "Song",
            positionMs = 42_000L,
            lyricIndex = 7,
            lyricTimeMs = 41_500L,
            video = true,
            source = "history",
            sync = "measured",
        )
        assertEquals(
            "MARK by user: abc123 'Song' pos=42000 lyricLine=7/41500 " +
                "video=y source=history sync=measured",
            line,
        )
    }

    @Test
    fun `a mark without lyrics says so`() {
        val line = TrackSummaryFormat.mark(
            id = "abc",
            title = "No lyrics",
            positionMs = 0L,
            lyricIndex = -1,
            lyricTimeMs = -1L,
            video = false,
            source = "none",
            sync = "none",
        )
        assertTrue(line.contains("lyricLine=-/- video=n"))
    }
}
