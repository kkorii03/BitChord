package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The policy boundaries around paid AI timing: agreement, shift, instrumentals and language. */
class LyricsAiGuardBoundariesTest {

    @Test
    fun thePublishedThresholdsAreUnchanged() {
        assertEquals(70, LyricsAiGuard.MIN_MATCH_PERCENT)
        assertEquals(20_000L, LyricsAiGuard.VIDEO_MAX_SHIFT_MS)
        assertEquals(15_000L, LyricsAiGuard.SONG_MAX_SHIFT_MS)
    }

    @Test
    fun aSongShiftIsCappedBothWaysAtFifteenSeconds() {
        assertTrue(LyricsAiGuard.songShiftAllows(0L))
        assertTrue(LyricsAiGuard.songShiftAllows(15_000L))
        assertTrue(LyricsAiGuard.songShiftAllows(-15_000L))
        assertFalse(LyricsAiGuard.songShiftAllows(15_001L))
        assertFalse(LyricsAiGuard.songShiftAllows(-15_001L))
        assertFalse(LyricsAiGuard.songShiftAllows(60_000L))
    }

    @Test
    fun theVideoCapDoesNotApplyToRecordTimedAudio() {
        assertTrue(LyricsAiGuard.shiftAllows(isVideo = false, firstLineShiftMs = 20_001L))
        assertTrue(LyricsAiGuard.shiftAllows(isVideo = false, firstLineShiftMs = -999_999L))
        assertFalse(LyricsAiGuard.shiftAllows(isVideo = true, firstLineShiftMs = 20_001L))
    }

    @Test
    fun anUnrecordedAgreementStillNeedsTheShiftToPass() {
        assertTrue(LyricsAiGuard.allowsAiTiming(isVideo = true, matchPercent = null, firstLineShiftMs = 0L))
        assertTrue(LyricsAiGuard.allowsAiTiming(isVideo = true, matchPercent = null, firstLineShiftMs = 20_000L))
        assertFalse(LyricsAiGuard.allowsAiTiming(isVideo = true, matchPercent = null, firstLineShiftMs = 20_001L))
        assertTrue(LyricsAiGuard.allowsAiTiming(isVideo = false, matchPercent = null, firstLineShiftMs = 60_000L))
    }

    @Test
    fun instrumentalTitlesAreRecognised() {
        listOf(
            "Instrumental",
            "instrumental version",
            "Song (Instrumental)",
            "Inst.",
            "Inst",
            "Song (Inst.)",
            "Song - Inst ver.",
            "MR",
            "(MR)",
            "Song - MR ver.",
            "MR제거",
            "Song [MR]",
        ).forEach { title ->
            assertTrue(LyricsAiGuard.isInstrumentalTitle(title), title)
        }
    }

    @Test
    fun aSongCalledMrIsNotAnInstrumental() {
        listOf(
            "Mr. Brightside",
            "Mr Brightside",
            "MRS",
            "Song - mr ver.",
            "Instrument",
            "Instumental",
            "",
            "   ",
        ).forEach { title ->
            assertFalse(LyricsAiGuard.isInstrumentalTitle(title), title)
        }
    }

    @Test
    fun deepgramIsSkippedForKoreanAndInstrumentalSongs() {
        assertFalse(LyricsAiGuard.wantsDeepgram("사랑해", "아이유", listOf("사랑해 너를")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved", "아이유", listOf("I love you")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved", "IU", listOf("사랑해 너를 영원히")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved (Instrumental)", "IU", listOf("I love you forever")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved (Inst.)", "IU", listOf("I love you forever")))
        assertFalse(LyricsAiGuard.wantsDeepgram("Loved (MR)", "IU", listOf("I love you forever")))
        assertTrue(LyricsAiGuard.wantsDeepgram("Loved", "IU", listOf("I love you forever")))
        assertTrue(LyricsAiGuard.wantsDeepgram("夜に駆ける", "YOASOBI", listOf("I love you forever")))
    }

    @Test
    fun koreanIsDecidedByTitleArtistOrLyrics() {
        assertTrue(LyricsAiGuard.isKoreanSong("사랑해", "IU", listOf("I love you")))
        assertTrue(LyricsAiGuard.isKoreanSong("Loved", "아이유", listOf("I love you")))
        assertTrue(LyricsAiGuard.isKoreanSong("Loved", "IU", listOf("사랑해 너를 영원히")))
        assertFalse(LyricsAiGuard.isKoreanSong("Loved", "IU", listOf("I love you forever")))
        assertFalse(LyricsAiGuard.isKoreanSong("夜に駆ける", "YOASOBI", listOf("I love you forever")))
    }

    @Test
    fun aSongCheckNeedsThreeSungLines() {
        val english = listOf("I know you love me", "and I don't know what to do", "with all of it", "so I wait")
        assertTrue(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 3, title = "Loved", artist = "IU", texts = english,
            ),
        )
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 2, title = "Loved", artist = "IU", texts = english,
            ),
        )
    }

    @Test
    fun aSongCheckNeedsTimingAndAHeardSong() {
        val english = listOf("I know you love me", "and I don't know what to do", "with all of it", "so I wait")
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = false, sungLines = 4, title = "Loved", artist = "IU", texts = english,
            ),
        )
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = true, timed = true, sungLines = 4, title = "Loved", artist = "IU", texts = english,
            ),
        )
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false,
                timed = true,
                sungLines = 4,
                title = "Loved (Instrumental)",
                artist = "IU",
                texts = english,
            ),
        )
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 4, title = "사랑해", artist = "아이유", texts = english,
            ),
        )
    }

    /**
     * The cap is an inclusive range, so the smallest Long is outside it
     * instead of overflowing to a negative absolute value.
     */
    @Test
    fun theSmallestLongIsNotAcceptedAsASmallShift() {
        assertFalse(LyricsAiGuard.shiftAllows(isVideo = true, firstLineShiftMs = Long.MIN_VALUE))
        assertFalse(LyricsAiGuard.songShiftAllows(Long.MIN_VALUE))
    }
}
