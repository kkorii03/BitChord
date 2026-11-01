package com.music.bitchord.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A translation made from the player belongs beside the download only for a
 * downloaded song and only while "keep downloaded translations" is on. Before
 * this, the manual path never passed `keep`, so a downloaded song translated
 * online had nothing to read on the next offline play.
 */
class DownloadedTranslationKeepTest {

    @Test
    fun aDownloadedSongKeepsItsTranslationWhenTheSettingIsOn() {
        assertTrue(keepDownloadedTranslation(downloaded = true, keepSetting = true))
    }

    @Test
    fun aStreamedSongIsNotWrittenToTheKeptDirectory() {
        assertFalse(keepDownloadedTranslation(downloaded = false, keepSetting = true))
    }

    @Test
    fun turningTheDownloadedTranslationSettingOffStopsKeepingIt() {
        assertFalse(keepDownloadedTranslation(downloaded = true, keepSetting = false))
        assertFalse(keepDownloadedTranslation(downloaded = false, keepSetting = false))
    }
}
