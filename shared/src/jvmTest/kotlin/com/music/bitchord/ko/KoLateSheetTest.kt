package com.music.bitchord.ko

import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phone 11-19 oZ4Tr4bINNY: the screen's lookup missed, and VIBE's sheet the
 * translation-ahead lookup found 10 s later for the same song never reached the
 * screen. A hit after a miss is now offered once; the screen's own miss keeps it.
 */
class KoLateSheetTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @AfterTest
    fun forget() = KoLateSheet.clear()

    @Test
    fun aSheetFoundAfterAMissIsOfferedOnce() {
        assertFalse(KoLateSheet.answered("oZ4Tr4bINNY", found = false))
        assertTrue(KoLateSheet.answered("oZ4Tr4bINNY", found = true))
        // The next lookup's hit for the same song is not news.
        assertFalse(KoLateSheet.answered("oZ4Tr4bINNY", found = true))
    }

    @Test
    fun aSheetWithNoMissBeforeItIsNotOffered() {
        assertFalse(KoLateSheet.answered("found-first", found = true))
        // Another song's miss does not make this one late.
        assertFalse(KoLateSheet.answered("missed-elsewhere", found = false))
        assertFalse(KoLateSheet.answered("found-again", found = true))
        assertFalse(KoLateSheet.answered(" ", found = false))
        assertFalse(KoLateSheet.answered(" ", found = true))
    }

    @Test
    fun onlyTheOldestMissesAreForgotten() {
        repeat(65) { KoLateSheet.answered("miss-$it", found = false) }
        assertFalse(KoLateSheet.answered("miss-0", found = true))
        assertTrue(KoLateSheet.answered("miss-64", found = true))
    }

    @Test
    fun theScreensMissKeepsOnlyALateSheetThatIsShowing() {
        assertTrue(KoLateSheet.keepsLateSheet(missed = true, pickedByHand = false, lateSheetShown = true))
        assertFalse(KoLateSheet.keepsLateSheet(missed = false, pickedByHand = false, lateSheetShown = true))
        assertFalse(KoLateSheet.keepsLateSheet(missed = true, pickedByHand = true, lateSheetShown = true))
        assertFalse(KoLateSheet.keepsLateSheet(missed = true, pickedByHand = false, lateSheetShown = false))
    }
}
