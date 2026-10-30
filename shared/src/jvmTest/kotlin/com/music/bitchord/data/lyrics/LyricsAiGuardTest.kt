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

    @Test
    fun aSongCheckNeedsExactlyThreeSungLinesAtTheFloor() {
        val english = listOf("one two three", "four five six", "seven eight nine")
        assertFalse(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 2, title = "Loved", artist = "IU", texts = english,
            ),
        )
        assertTrue(
            LyricsAiGuard.songTimingWanted(
                isVideo = false, timed = true, sungLines = 3, title = "Loved", artist = "IU", texts = english,
            ),
        )
    }

    @Test
    fun koreanIsRecognisedFromAnyOfTitleArtistOrLyrics() {
        assertTrue(LyricsAiGuard.isKoreanSong("Loved", "IU", listOf("사랑해 너를 영원히")))
        assertTrue(LyricsAiGuard.isKoreanSong("사랑해", "IU", listOf("I love you")))
        assertTrue(LyricsAiGuard.isKoreanSong("Loved", "아이유", listOf("I love you")))
        assertFalse(LyricsAiGuard.isKoreanSong("Loved", "IU", listOf("I love you")))
        assertTrue(LyricsAiGuard.wantsDeepgram("Loved", "IU", listOf("I love you")))
    }

    @Test
    fun aHandTimingNeedsAnEngineThatCanAnswerForThisSong() {
        // Regression: the action was offered from key presence alone. A
        // Deepgram-only setup then pressed it on Korean/instrumental songs or
        // before the audio was on disk and only got aiFailed.
        val english = listOf("one two three", "four five six", "seven eight nine")
        assertTrue(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = false,
                deepgramUsable = true,
                audioAvailable = true,
                durationMs = 180_000L,
                title = "Loved",
                artist = "IU",
                lyricsTexts = english,
            ),
        )
        assertFalse(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = false,
                deepgramUsable = true,
                audioAvailable = true,
                durationMs = 180_000L,
                title = "사랑해",
                artist = "아이유",
                lyricsTexts = listOf("사랑해 너를 영원히", "너만 바라봐", "언제나 함께"),
            ),
        )
        assertFalse(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = false,
                deepgramUsable = true,
                audioAvailable = true,
                durationMs = 180_000L,
                title = "River Flows in You (Inst.)",
                artist = "Yiruma",
                lyricsTexts = english,
            ),
        )
        assertFalse(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = false,
                deepgramUsable = true,
                audioAvailable = false,
                durationMs = 180_000L,
                title = "Loved",
                artist = "IU",
                lyricsTexts = english,
            ),
        )
        // Gemini fetches the video, so it needs neither Deepgram's title rule
        // nor the audio on disk.
        assertTrue(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = true,
                deepgramUsable = false,
                audioAvailable = false,
                durationMs = 180_000L,
                title = "사랑해",
                artist = "아이유",
                lyricsTexts = listOf("사랑해 너를 영원히", "너만 바라봐", "언제나 함께"),
            ),
        )
    }

    @Test
    fun noEngineCanMoveFewerThanThreeSungLines() {
        // placeWithAi returns before asking any engine, so the action must not
        // be offered for a track with only one or two non-blank lines — even
        // when Gemini is set up. Blank lines do not count.
        val lines = listOf("only one line", "   ", "", "and a second")
        assertFalse(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = true,
                deepgramUsable = false,
                audioAvailable = false,
                durationMs = 180_000L,
                title = "Loved",
                artist = "IU",
                lyricsTexts = lines,
            ),
        )
        assertTrue(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = true,
                deepgramUsable = false,
                audioAvailable = false,
                durationMs = 180_000L,
                title = "Loved",
                artist = "IU",
                lyricsTexts = lines + "and a third",
            ),
        )
    }

    @Test
    fun deepgramOnlyHidesWhenTheInlineAudioWindowCannotHoldTheSong() {
        val english = listOf("one two three", "four five six", "seven eight nine")
        // Over VideoAudio's seven-minute inline cap, or under the ten-second
        // floor, decode returns null, so a Deepgram-only action fails.
        assertFalse(LyricsAiGuard.audioFitsInline(LyricsAiGuard.MAX_TIMING_AUDIO_MS + 1))
        assertFalse(LyricsAiGuard.audioFitsInline(LyricsAiGuard.MIN_TIMING_AUDIO_MS - 1))
        assertTrue(LyricsAiGuard.audioFitsInline(LyricsAiGuard.MAX_TIMING_AUDIO_MS))
        assertTrue(LyricsAiGuard.audioFitsInline(LyricsAiGuard.MIN_TIMING_AUDIO_MS))
        // An unknown length waits for the player to report it instead of hiding.
        assertTrue(LyricsAiGuard.audioFitsInline(0L))
        assertFalse(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = false,
                deepgramUsable = true,
                audioAvailable = true,
                durationMs = LyricsAiGuard.MAX_TIMING_AUDIO_MS + 1,
                title = "Loved",
                artist = "IU",
                lyricsTexts = english,
            ),
        )
        // Gemini still fetches the video itself.
        assertTrue(
            LyricsAiGuard.manualTimingAvailable(
                geminiUsable = true,
                deepgramUsable = false,
                audioAvailable = false,
                durationMs = LyricsAiGuard.MAX_TIMING_AUDIO_MS + 1,
                title = "Loved",
                artist = "IU",
                lyricsTexts = english,
            ),
        )
    }

    @Test
    fun mrMustBeACapitalisedStandaloneMarker() {
        assertTrue(LyricsAiGuard.isInstrumentalTitle("Song MR"))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("Song (MR)"))
        assertFalse(LyricsAiGuard.isInstrumentalTitle("Song mr"))
        assertFalse(LyricsAiGuard.isInstrumentalTitle("Song MRA"))
    }
}
