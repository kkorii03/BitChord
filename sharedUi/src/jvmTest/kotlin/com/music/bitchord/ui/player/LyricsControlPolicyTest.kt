package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsControlPolicyTest {

    @Test
    fun aiTimingNeedsAnAdjustableSongAndAUsableProvider() {
        // Regression: removing the old Gemini-key guard left no provider
        // guard at all, so the action was offered with no Deepgram or Gemini
        // key and the press could only end in aiFailed.
        assertTrue(
            aiTimingActionEnabled(LyricsTimingUi(adjustable = true, aiAvailable = true)),
        )
        assertFalse(
            aiTimingActionEnabled(LyricsTimingUi(adjustable = true, aiAvailable = false)),
        )
        assertFalse(
            aiTimingActionEnabled(LyricsTimingUi(adjustable = false, aiAvailable = true)),
        )
    }

    @Test
    fun aiTimingStaysOfferedWhenTheShownAnswerCanBeTakenBack() {
        // Taking a Gemini answer back needs no key, so the action remains even
        // after the provider that made it is gone.
        assertTrue(
            aiTimingActionEnabled(
                LyricsTimingUi(adjustable = true, byAi = true, aiAvailable = false),
            ),
        )
    }
}
