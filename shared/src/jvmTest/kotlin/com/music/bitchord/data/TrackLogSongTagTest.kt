package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Logcat lines name their song (11-02): the smoke sync timeline
 * (scripts/smoke/sync_timeline.py) can only tell whose Deepgram answer or
 * measurement a line was when the line carries the id.
 */
class TrackLogSongTagTest {
    private fun echoed(message: String, about: String?): String {
        val seen = mutableListOf<String>()
        val previous = TrackLog.echo
        TrackLog.echo = { _, _, text, _ -> seen += text }
        try {
            TrackLog.d("LyricsTiming", message, about)
        } finally {
            TrackLog.echo = previous
        }
        return seen.single()
    }

    @Test
    fun aLineAboutASongNamesIt() {
        assertEquals("[ekzHIouo8Q4] Deepgram Whisper timing (en, 67s): no answer", echoed("Deepgram Whisper timing (en, 67s): no answer", "ekzHIouo8Q4"))
    }

    @Test
    fun aLineThatAlreadyNamesItsSongIsLeftAlone() {
        assertEquals("lyrics-sync: ekzHIouo8Q4 source=LRCLIB", echoed("lyrics-sync: ekzHIouo8Q4 source=LRCLIB", "ekzHIouo8Q4"))
    }

    @Test
    fun aLineAboutNoSongIsLeftAlone() {
        assertEquals("power: decoders active=2", echoed("power: decoders active=2", null))
    }
}
