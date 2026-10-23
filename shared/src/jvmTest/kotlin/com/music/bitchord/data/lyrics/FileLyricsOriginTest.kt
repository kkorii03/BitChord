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
}
