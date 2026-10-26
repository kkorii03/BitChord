package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLyricsOriginTest {

    @Test
    fun `the label names the provider and the recording the times belong to`() {
        assertEquals("file:LRCLIB@PMivT7MJ41M", FileLyricsOrigin.label("LRCLIB", "PMivT7MJ41M"))
    }

    @Test
    fun `an older file without an id says unknown rather than guessing`() {
        assertEquals("file:MUSIXMATCH@unknown", FileLyricsOrigin.label("MUSIXMATCH", null))
        assertEquals("file:unknown@unknown", FileLyricsOrigin.label(null, null))
    }

    @Test
    fun `lyrics timed for another recording need measuring`() {
        assertTrue(FileLyricsOrigin.timingBelongsToAnotherRecording("PMivT7MJ41M", "JucvYrdSIcM"))
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording("PMivT7MJ41M", "PMivT7MJ41M"))
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording(null, "JucvYrdSIcM"))
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording("PMivT7MJ41M", ""))
    }

    @Test
    fun `an older file without an id has never been verified`() {
        // No id is not a mismatch for the existing rule, but it is the reason
        // the song check runs once: nothing ever confirmed these times.
        assertTrue(FileLyricsOrigin.timingIsUnverified(null, "JucvYrdSIcM"))
        assertTrue(FileLyricsOrigin.timingIsUnverified("", "JucvYrdSIcM"))
        assertTrue(FileLyricsOrigin.timingIsUnverified("PMivT7MJ41M", "JucvYrdSIcM"))
        assertFalse(FileLyricsOrigin.timingIsUnverified("PMivT7MJ41M", "PMivT7MJ41M"))
        assertFalse(FileLyricsOrigin.timingIsUnverified(null, ""))
        assertFalse(FileLyricsOrigin.timingIsUnverified("PMivT7MJ41M", ""))
    }

    @Test
    fun `a blank provider or id is named unknown rather than printed empty`() {
        assertEquals("file:unknown@PMivT7MJ41M", FileLyricsOrigin.label("", "PMivT7MJ41M"))
        assertEquals("file:LRCLIB@unknown", FileLyricsOrigin.label("LRCLIB", ""))
        assertEquals("file:unknown@unknown", FileLyricsOrigin.label("   ", "   "))
    }

    @Test
    fun `a blank stored id is never a mismatch`() {
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording("", "PMivT7MJ41M"))
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording("   ", "PMivT7MJ41M"))
        assertFalse(FileLyricsOrigin.timingIsUnverified("   ", ""))
    }

    @Test
    fun `the at-unknown sentinel names no recording rather than a different one`() {
        // "@unknown" is the sentinel for "no id is known". It must read the
        // same as a blank id: never foreign, but still worth one check.
        assertEquals("file:LRCLIB@unknown", FileLyricsOrigin.label("LRCLIB", "unknown"))
        assertNull(FileLyricsOrigin.knownVideoId("unknown"))
        assertNull(FileLyricsOrigin.knownVideoId("  UNKNOWN  "))
        assertEquals("PMivT7MJ41M", FileLyricsOrigin.knownVideoId(" PMivT7MJ41M "))
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording("unknown", "PMivT7MJ41M"))
        assertFalse(FileLyricsOrigin.timingBelongsToAnotherRecording("UNKNOWN", "PMivT7MJ41M"))
        assertTrue(FileLyricsOrigin.timingIsUnverified("unknown", "PMivT7MJ41M"))
        assertTrue(FileLyricsOrigin.timingIsUnverified("UNKNOWN", "PMivT7MJ41M"))
    }
}
