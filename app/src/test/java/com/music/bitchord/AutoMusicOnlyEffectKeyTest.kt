package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The before-audio music-only swap reads the video-radio switch, so that
 * switch has to be one of the effect's keys. Before this was fixed, turning
 * video radio off (or on) while a video row was playing did not re-evaluate
 * the swap until the next track.
 */
class AutoMusicOnlyEffectKeyTest {

    @Test
    fun changingTheVideoRadioSwitchChangesTheEffectKey() {
        val off = autoMusicOnlyEffectKey("video-1", preferMusicOnly = true, videoRadio = false)
        val on = autoMusicOnlyEffectKey("video-1", preferMusicOnly = true, videoRadio = true)

        assertNotEquals(off, on)
    }

    @Test
    fun theSongAndMusicOnlyPreferenceStillChangeTheEffectKey() {
        assertNotEquals(
            autoMusicOnlyEffectKey("video-1", preferMusicOnly = true, videoRadio = true),
            autoMusicOnlyEffectKey("video-2", preferMusicOnly = true, videoRadio = true),
        )
        assertNotEquals(
            autoMusicOnlyEffectKey("video-1", preferMusicOnly = true, videoRadio = true),
            autoMusicOnlyEffectKey("video-1", preferMusicOnly = false, videoRadio = true),
        )
    }

    @Test
    fun anUnchangedDecisionKeepsTheSameKey() {
        assertEquals(
            autoMusicOnlyEffectKey("video-1", preferMusicOnly = true, videoRadio = true),
            autoMusicOnlyEffectKey("video-1", preferMusicOnly = true, videoRadio = true),
        )
    }
}
