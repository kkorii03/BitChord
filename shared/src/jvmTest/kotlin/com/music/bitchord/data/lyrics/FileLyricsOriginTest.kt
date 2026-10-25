package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
