package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L11: a title that only starts with the wanted one ("사랑했나봐" for "사랑")
 * used to win over the exact title whenever its length was nearer.
 */
class KoreanMatchExactTitleTest {

    private val artist = "가수"

    @Test
    fun theExactTitleBeatsALongerTitleNearerInLength() {
        val hits = listOf(
            KoreanMatch.Hit("1", "사랑했나봐", artist, 262),
            KoreanMatch.Hit("2", "사랑", artist, 215),
        )
        assertEquals("2", KoreanMatch.best(hits, "사랑", artist, 260_000L)?.id)
    }

    @Test
    fun aBracketDoesNotMakeATitleInexact() {
        val hits = listOf(
            KoreanMatch.Hit("1", "사랑했나봐", artist, 260),
            KoreanMatch.Hit("2", "사랑 (Feat. 누구)", artist, 230),
        )
        assertEquals("2", KoreanMatch.best(hits, "사랑", artist, 260_000L)?.id)
    }

    @Test
    fun aPrefixTitleStillAnswersWhenNoExactOnePassed() {
        val hits = listOf(KoreanMatch.Hit("1", "회전목마 Remastered", artist, 250))
        assertEquals("1", KoreanMatch.best(hits, "회전목마", artist, 252_000L)?.id)
    }

    @Test
    fun amongExactTitlesTheNearestLengthWins() {
        val hits = listOf(
            KoreanMatch.Hit("1", "사랑", artist, 200),
            KoreanMatch.Hit("2", "사랑", artist, 255),
        )
        assertEquals("2", KoreanMatch.best(hits, "사랑", artist, 260_000L)?.id)
    }
}
