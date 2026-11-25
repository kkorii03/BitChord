package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.lyrics.LyricsOverrideFile.Kind
import com.music.bitchord.data.lyrics.LyricsOverrideFile.Stored
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.toLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LYR6: the rules for a downloaded song's saved sheet. Sheets are made-up tokens. */
class KoSavedLyricsTest {

    private val old = List(4) { LyricLine(1_000L + it * 2_000L, "aa$it") }
    private val new = List(4) { LyricLine(1_000L + it * 2_500L, "bb$it") }

    @Test
    fun `online a downloaded song asks the providers first, offline or in a preview its saved sheet`() {
        assertTrue(KoSavedLyrics.providersFirst(localFile = true, offline = false, previewOnly = false))
        assertFalse(KoSavedLyrics.providersFirst(localFile = true, offline = true, previewOnly = false))
        assertFalse(KoSavedLyrics.providersFirst(localFile = true, offline = false, previewOnly = true))
        assertFalse(KoSavedLyrics.providersFirst(localFile = false, offline = false, previewOnly = false))
    }

    /** The lock screen's and the next-song prep's one rule: the listener's own pick stays first. */
    @Test
    fun `the saved copy is the last fallback online unless it is the listener's own pick`() {
        assertTrue(KoSavedLyrics.savedLast(Kind.RECHECK, localFile = true, offline = false))
        assertTrue(KoSavedLyrics.savedLast(null, localFile = true, offline = false))
        assertFalse(KoSavedLyrics.savedLast(Kind.USER, localFile = true, offline = false))
        assertFalse(KoSavedLyrics.savedLast(Kind.RECHECK, localFile = true, offline = true))
        assertFalse(KoSavedLyrics.savedLast(null, localFile = false, offline = false))
    }

    @Test
    fun `only the listener's own pick names the provider to ask for`() {
        assertEquals(LyricsSource.VIBE, KoSavedLyrics.preselected(Kind.USER, LyricsSource.VIBE))
        assertNull(KoSavedLyrics.preselected(Kind.RECHECK, LyricsSource.VIBE))
        assertNull(KoSavedLyrics.preselected(null, null))
    }

    @Test
    fun `a new sheet picked by hand replaces the saved copy as the listener's`() {
        assertEquals(
            Kind.USER,
            KoSavedLyrics.kindToSave(old, LyricsSource.KUGOU, Kind.RECHECK, new, LyricsSource.VIBE, byHand = true, localFile = true),
        )
        // The file's own sheet, no override yet.
        assertEquals(
            Kind.USER,
            KoSavedLyrics.kindToSave(old, LyricsSource.KUGOU, null, new, LyricsSource.VIBE, byHand = true, localFile = true),
        )
    }

    @Test
    fun `a fresh sheet settling online replaces the saved copy, the same sheet is not written again`() {
        assertEquals(
            Kind.RECHECK,
            KoSavedLyrics.kindToSave(old, LyricsSource.KUGOU, Kind.RECHECK, new, LyricsSource.KUGOU, byHand = false, localFile = true),
        )
        assertNull(KoSavedLyrics.kindToSave(new, LyricsSource.KUGOU, Kind.RECHECK, new, LyricsSource.KUGOU, byHand = false, localFile = true))
        assertNull(KoSavedLyrics.kindToSave(new, LyricsSource.KUGOU, null, new, LyricsSource.KUGOU, byHand = false, localFile = true))
        assertNull(KoSavedLyrics.kindToSave(new, LyricsSource.KUGOU, Kind.USER, new, LyricsSource.KUGOU, byHand = true, localFile = true))
        // Display-only fields are not a change.
        val shown = new.map { it.copy(timingSource = it, wordsEstimated = true) }
        assertNull(KoSavedLyrics.kindToSave(new, LyricsSource.KUGOU, Kind.RECHECK, shown, LyricsSource.KUGOU, byHand = false, localFile = true))
    }

    @Test
    fun `an automatic pick never replaces the listener's pick of another provider`() {
        assertNull(KoSavedLyrics.kindToSave(old, LyricsSource.VIBE, Kind.USER, new, LyricsSource.KUGOU, byHand = false, localFile = true))
        // The same provider's newer sheet stays the listener's.
        assertEquals(
            Kind.USER,
            KoSavedLyrics.kindToSave(old, LyricsSource.VIBE, Kind.USER, new, LyricsSource.VIBE, byHand = false, localFile = true),
        )
    }

    @Test
    fun `a streamed song's pick only replaces a stored choice`() {
        assertNull(KoSavedLyrics.kindToSave(null, null, null, new, LyricsSource.VIBE, byHand = true, localFile = false))
        assertEquals(
            Kind.USER,
            KoSavedLyrics.kindToSave(old, LyricsSource.KUGOU, Kind.RECHECK, new, LyricsSource.VIBE, byHand = true, localFile = false),
        )
        // The words choosing a sheet keep it, as before.
        assertEquals(
            Kind.RECHECK,
            KoSavedLyrics.kindToSave(null, null, null, new, LyricsSource.VIBE, byHand = false, localFile = false),
        )
    }

    @Test
    fun `a picked sheet on a downloaded song keeps its correction offline`() {
        assertTrue(KoSavedLyrics.ownPickTimeable(isVideo = false, timed = true, byHand = true, timedToVideo = false))
        assertFalse(KoSavedLyrics.ownPickTimeable(isVideo = true, timed = true, byHand = true, timedToVideo = false))
        assertFalse(KoSavedLyrics.ownPickTimeable(isVideo = false, timed = true, byHand = false, timedToVideo = false))
    }

    @Test
    fun `the saved sheet's check goes when a provider sheet takes over`() {
        assertEquals(KoSavedLyrics.SavedCheck.KEEP, KoSavedLyrics.savedCheck(0, answerForSavedSheet = true))
        assertEquals(KoSavedLyrics.SavedCheck.FORGET_ANSWER, KoSavedLyrics.savedCheck(1, answerForSavedSheet = true))
        assertEquals(KoSavedLyrics.SavedCheck.FORGET_VERSION, KoSavedLyrics.savedCheck(1, answerForSavedSheet = false))
    }

    /** LYR7-B: the listener's picked provider missed; another's answer does not stand in for it. */
    @Test
    fun `a picked provider that missed is not stood in for by another`() {
        assertTrue(KoSavedLyrics.pickMissed(LyricsSource.VIBE, LyricsSource.VIBE, LyricsSource.KUGOU))
        assertFalse(KoSavedLyrics.pickMissed(LyricsSource.VIBE, LyricsSource.VIBE, LyricsSource.VIBE))
        // Picked again by hand while the lookup ran: that pick decides, as on a streamed song.
        assertFalse(KoSavedLyrics.pickMissed(LyricsSource.VIBE, LyricsSource.KUGOU, LyricsSource.KUGOU))
        assertFalse(KoSavedLyrics.pickMissed(null, null, LyricsSource.KUGOU))
    }

    /** LYR7-C: only other lines make the saved sheet's check stale. */
    @Test
    fun `the saved sheet's check is stale only for other lines`() {
        // Display-only fields are the same sheet.
        assertFalse(KoSavedLyrics.savedCheckStale(old, old.map { it.copy(timingSource = it, wordsEstimated = true) }))
        assertTrue(KoSavedLyrics.savedCheckStale(old, new))
        // The same words on other times: the answer, keyed by the words, would land on the wrong times.
        assertTrue(KoSavedLyrics.savedCheckStale(old, old.map { it.copy(timeMs = it.timeMs + 500L) }))
        assertTrue(KoSavedLyrics.savedCheckStale(old, old.dropLast(1)))
        assertFalse(KoSavedLyrics.savedCheckStale(null, new))
    }

    /** 4WpwsUGuoGQ 11-25: a provider's 21 lines came back from the file tag as 22. */
    @Test
    fun `a sheet read back from a file tag is the same sung sheet`() {
        val provider = List(4) { LyricLine(12_344L + it * 2_507L, "cc$it dd$it") }
        val readBack = LrcLib.parseLrc(provider.toLrc())
        assertEquals(provider.size + 1, readBack.size)
        assertFalse(KoSavedLyrics.sameLines(readBack, provider))
        assertTrue(KoSavedLyrics.sameSung(readBack, provider))

        assertFalse(KoSavedLyrics.sameSung(readBack, provider.map { it.copy(timeMs = it.timeMs + 500L) }))
        assertFalse(KoSavedLyrics.sameSung(readBack, provider.dropLast(1)))
        assertFalse(KoSavedLyrics.sameSung(readBack, provider.map { it.copy(text = it.text + "x") }))
        assertFalse(KoSavedLyrics.sameSung(emptyList(), emptyList()))
        assertFalse(KoSavedLyrics.sameSung(null, provider))
    }

    /** LYR7-A: an automatic copy of a sheet the words rejected is passed over; the listener's own pick never. */
    @Test
    fun `an automatic copy the words rejected is passed over, the listener's own pick is not`() {
        val id = "lyr7-copy"
        val verdict = LyricsMismatch.Verdict(heardWords = 60, matchedLines = 0, totalLines = new.size, rejected = true)
        LyricsMismatch.rememberRejected(id, LyricsSource.KUGOU, new, verdict)
        // A copy read back from the file is another list holding the same lines.
        val readBack = new.map { it.copy() }
        assertTrue(KoSavedLyrics.rejectedCopy(id, Stored(readBack, LyricsSource.KUGOU, 0L, Kind.RECHECK)))
        assertFalse(KoSavedLyrics.rejectedCopy(id, Stored(readBack, LyricsSource.KUGOU, 0L, Kind.USER)))
        assertFalse(KoSavedLyrics.rejectedCopy(id, Stored(old, LyricsSource.KUGOU, 0L, Kind.RECHECK)))
        assertFalse(KoSavedLyrics.rejectedCopy(id, Stored(readBack, LyricsSource.VIBE, 0L, Kind.RECHECK)))
        assertFalse(KoSavedLyrics.rejectedCopy("lyr7-other", Stored(readBack, LyricsSource.KUGOU, 0L, Kind.RECHECK)))
    }

    /** WDYM (DK_0jXPuIr0): a lower provider standing in for the saved sheet's missed one is no better sheet. */
    @Test
    fun `the saved sheet outranks a lower provider only while its own provider gave nothing`() {
        val order = listOf(LyricsSource.LRC_RED, LyricsSource.KUGOU, LyricsSource.QQ_MUSIC)
        assertTrue(KoSavedLyrics.savedOutranks(LyricsSource.LRC_RED, savedAnswered = false, answered = LyricsSource.QQ_MUSIC, order = order))
        // Its provider answered: the lookup chose between both (word sync first, say), as before.
        assertFalse(KoSavedLyrics.savedOutranks(LyricsSource.LRC_RED, savedAnswered = true, answered = LyricsSource.QQ_MUSIC, order = order))
        // A higher or the same provider answering, or a saved sheet of no known or listed provider: the providers decide.
        assertFalse(KoSavedLyrics.savedOutranks(LyricsSource.KUGOU, savedAnswered = false, answered = LyricsSource.LRC_RED, order = order))
        assertFalse(KoSavedLyrics.savedOutranks(LyricsSource.KUGOU, savedAnswered = false, answered = LyricsSource.KUGOU, order = order))
        assertFalse(KoSavedLyrics.savedOutranks(null, savedAnswered = false, answered = LyricsSource.QQ_MUSIC, order = order))
        assertFalse(KoSavedLyrics.savedOutranks(LyricsSource.VIBE, savedAnswered = false, answered = LyricsSource.QQ_MUSIC, order = order))
        // The copy QQ wrote over the file's lrc.red sheet gives way to it; the listener's own pick never.
        assertTrue(KoSavedLyrics.copyPassedOver(Kind.RECHECK, LyricsSource.QQ_MUSIC, LyricsSource.LRC_RED, fileAnswered = false, order = order))
        assertFalse(KoSavedLyrics.copyPassedOver(Kind.USER, LyricsSource.QQ_MUSIC, LyricsSource.LRC_RED, fileAnswered = false, order = order))
        assertFalse(KoSavedLyrics.copyPassedOver(Kind.RECHECK, LyricsSource.LRC_RED, LyricsSource.QQ_MUSIC, fileAnswered = false, order = order))
        assertFalse(KoSavedLyrics.copyPassedOver(Kind.RECHECK, LyricsSource.QQ_MUSIC, null, fileAnswered = false, order = order))
    }
}
