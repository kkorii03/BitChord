package com.music.bitchord.data.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floors that stop a low-agreement AI answer moving lyrics, and the songs Deepgram should skip. */
class LyricsAiGuardTest {

    @Test
    fun lowAgreementAnswerIsNotApplied() {
        assertTrue(LyricsAiGuard.agreementAllows(100))
        assertTrue(LyricsAiGuard.agreementAllows(LyricsAiGuard.MIN_MATCH_PERCENT))
        assertFalse(LyricsAiGuard.agreementAllows(LyricsAiGuard.MIN_MATCH_PERCENT - 1))
        assertFalse(LyricsAiGuard.agreementAllows(50))
        // An answer kept before agreement was recorded is left as it was.
        assertTrue(LyricsAiGuard.agreementAllows(null))
    }

    @Test
    fun aVideoMayNotBePushedMoreThanTwentySeconds() {
        assertTrue(LyricsAiGuard.shiftAllows(isVideo = true, firstLineShiftMs = 20_000L))
        assertTrue(LyricsAiGuard.shiftAllows(isVideo = true, firstLineShiftMs = -20_000L))
        assertFalse(LyricsAiGuard.shiftAllows(isVideo = true, firstLineShiftMs = 20_001L))
        assertFalse(LyricsAiGuard.shiftAllows(isVideo = true, firstLineShiftMs = -20_001L))
        // Record-timed audio has no such limit.
        assertTrue(LyricsAiGuard.shiftAllows(isVideo = false, firstLineShiftMs = 60_000L))
    }

    @Test
    fun bothFloorsMustPassTogether() {
        assertTrue(LyricsAiGuard.allowsAiTiming(isVideo = true, matchPercent = 80, firstLineShiftMs = 5_000L))
        assertFalse(LyricsAiGuard.allowsAiTiming(isVideo = true, matchPercent = 60, firstLineShiftMs = 5_000L))
        assertFalse(LyricsAiGuard.allowsAiTiming(isVideo = true, matchPercent = 80, firstLineShiftMs = 30_000L))
        assertTrue(LyricsAiGuard.allowsAiTiming(isVideo = false, matchPercent = 80, firstLineShiftMs = 30_000L))
    }

    @Test
    fun koreanSongsSkipDeepgram() {
        assertFalse(LyricsAiGuard.wantsDeepgram("사랑해", "아이유", listOf("사랑해 너를")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved", "아이유", listOf("I love you")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved", "IU", listOf("사랑해 너를 영원히")))
        assertTrue(LyricsAiGuard.wantsDeepgram("Loved", "IU", listOf("I love you forever")))
    }

    @Test
    fun aSongCheckIsWantedOnlyForTimedSongsWithWordsToHear() {
        val english = listOf("I know you love me", "and I don't know what to do", "with all of it", "so I wait")
        assertTrue(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 4, title = "Loved", artist = "IU", texts = english,
            ),
        )
        // A video is measured against the record instead.
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = true, timed = true, sungLines = 4, title = "Loved", artist = "IU", texts = english,
            ),
        )
        // Nothing to fit a line through.
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 2, title = "Loved", artist = "IU", texts = english,
            ),
        )
        // Untimed lyrics have no times to check.
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = false, sungLines = 4, title = "Loved", artist = "IU", texts = english,
            ),
        )
        // Korean songs and instrumentals are refused by the same test as on video.
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false,
                timed = true,
                sungLines = 4,
                title = "사랑해",
                artist = "아이유",
                texts = listOf("사랑해 너를 영원히", "이 밤이 지나면", "다시 만나"),
            ),
        )
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false,
                timed = true,
                sungLines = 4,
                title = "River Flows in You (Inst.)",
                artist = "Yiruma",
                texts = english,
            ),
        )
    }

    @Test
    fun aSongCheckMayNotStartTheWordsFifteenSecondsOff() {
        assertTrue(LyricsAiGuard.songShiftAllows(15_000L))
        assertTrue(LyricsAiGuard.songShiftAllows(-15_000L))
        assertTrue(LyricsAiGuard.songShiftAllows(0L))
        assertFalse(LyricsAiGuard.songShiftAllows(15_001L))
        assertFalse(LyricsAiGuard.songShiftAllows(-15_001L))
        // The video rule is unchanged: record-timed audio still has no limit there.
        assertTrue(LyricsAiGuard.shiftAllows(isVideo = false, firstLineShiftMs = 60_000L))
    }

    @Test
    fun instrumentalTitlesSkipTranscriptionAndDeepgram() {
        assertTrue(LyricsAiGuard.isInstrumentalTitle("River Flows in You (Instrumental)"))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("River Flows in You [Inst.]"))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("River Flows in You MR"))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("River Flows in You (MR)"))
        assertFalse(LyricsAiGuard.isInstrumentalTitle("Mr. Brightside"))
        assertFalse(LyricsAiGuard.isInstrumentalTitle("Instrument"))
        assertFalse(LyricsAiGuard.isInstrumentalTitle("Instinct"))
        assertFalse(LyricsAiGuard.wantsDeepgram("River Flows in You (Inst.)", "Yiruma", listOf("la la")))
    }
}
