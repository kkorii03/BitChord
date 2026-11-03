package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Next-prep may only claim a downloaded file was re-checked when the check
 * recorded an answer. A skipped guard, a 429 or a file replaced mid-check
 * leaves the file's own foreign-clock refusal in place.
 */
class DownloadedRecheckTest {

    private val ownLines = listOf(LyricLine(1_000L, "own"))
    private val freshLines = listOf(LyricLine(2_000L, "fresh"))

    @Test
    fun aFreshSheetWinsAndItsRecordedAnswerSetsTheOverride() {
        val kept = DownloadedRecheck.keep(
            originalLines = ownLines,
            originalSource = LyricsSource.LRCLIB,
            originalSearchedAs = null,
            originalRecheckOverride = false,
            replacement = LyricsRepository.Result(LyricsSource.LRC_RED, freshLines, "Fresh" to "Artist"),
            checkRecorded = true,
        )
        assertEquals(freshLines, kept.lines)
        assertEquals(LyricsSource.LRC_RED, kept.source)
        assertEquals("Fresh" to "Artist", kept.searchedAs)
        assertTrue(kept.recheckOverride)
    }

    @Test
    fun theFilesOwnTimingIsKeptWhenTheCheckRecordedIt() {
        val kept = DownloadedRecheck.keep(
            originalLines = ownLines,
            originalSource = LyricsSource.LRCLIB,
            originalSearchedAs = null,
            originalRecheckOverride = false,
            replacement = null,
            checkRecorded = true,
        )
        assertSame(ownLines, kept.lines)
        assertEquals(LyricsSource.LRCLIB, kept.source)
        assertTrue(kept.recheckOverride)
    }

    @Test
    fun aSkippedOrFailedCheckDoesNotPretendTheFileWasRechecked() {
        val kept = DownloadedRecheck.keep(
            originalLines = ownLines,
            originalSource = LyricsSource.LRCLIB,
            originalSearchedAs = null,
            originalRecheckOverride = false,
            replacement = null,
            checkRecorded = false,
        )
        assertSame(ownLines, kept.lines)
        assertFalse(kept.recheckOverride)
    }

    @Test
    fun anEarlierOverrideSurvivesARepeatCheck() {
        val kept = DownloadedRecheck.keep(
            originalLines = ownLines,
            originalSource = LyricsSource.LRCLIB,
            originalSearchedAs = null,
            originalRecheckOverride = true,
            replacement = null,
            checkRecorded = false,
        )
        assertTrue(kept.recheckOverride)
    }

    @Test
    fun theSharedArgumentsAreTheQuerysOwnNamesAndTheSongsDuration() {
        val args = DownloadedRecheck.args(
            query = com.music.bitchord.data.cover.CoverTitles.LyricsQuery(
                title = "Title",
                artist = "Artist",
                alternatives = listOf("Alt" to "Other"),
                isVideo = false,
                artistKnown = false,
                artistAliases = listOf("Alias"),
            ),
            durationMs = 123_000L,
            album = "Album",
            providerUnknown = true,
        )
        assertEquals("Title", args.title)
        assertEquals("Artist", args.artist)
        assertEquals(123_000L, args.durationMs)
        assertEquals("Album", args.album)
        assertEquals(listOf("Alt" to "Other"), args.alternatives)
        assertFalse(args.artistKnown)
        assertEquals(listOf("Alias"), args.artistAliases)
        assertTrue(args.providerUnknown)
    }
}
