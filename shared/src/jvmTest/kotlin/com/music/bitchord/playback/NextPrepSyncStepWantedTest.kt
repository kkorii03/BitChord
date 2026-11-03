package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SYNC step must stay planned even while a paid gate is closed: its free
 * half (stored timing and a video's measurement) needs no engine. The old
 * plan dropped it whenever low power, AI timing ahead or Deepgram was off and
 * logged `sync=off`, so the stored correction was never prepared ahead.
 */
class NextPrepSyncStepWantedTest {

    @Test
    fun lyricsOnPlansSyncForEveryNonSubstituteRow() {
        assertTrue(NextPrepPolicy.syncStepWanted(syncedLyrics = true, substituted = false))
    }

    @Test
    fun lyricsOffPlansNoSync() {
        assertFalse(NextPrepPolicy.syncStepWanted(syncedLyrics = false, substituted = false))
    }

    @Test
    fun aSubstituteStreamPlansNoSync() {
        assertFalse(NextPrepPolicy.syncStepWanted(syncedLyrics = true, substituted = true))
    }
}
