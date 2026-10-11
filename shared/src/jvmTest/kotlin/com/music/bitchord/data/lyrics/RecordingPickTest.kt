package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cases taken from a real cover singer's playlist, where exact matching found no original. */
class RecordingPickTest {

    private fun row(id: String, title: String, artist: String, length: String = "3:30", video: Boolean = false) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length, isVideo = video)

    private fun pick(title: String, artist: String, rows: List<Song>, videoMs: Long = 215_000L) =
        RecordingPick.rank(listOf(title), listOf(artist), rows, videoMs).map { it.videoId }

    @Test
    fun aTranslationInBracketsStillFindsTheJapaneseTitle() {
        val rows = listOf(
            row("a", "ダンスホール - Dance Hall", "Mrs. GREEN APPLE"),
            row("b", "ダンスホール (Instrumental) - Dance Hall (Instrumental)", "Mrs. GREEN APPLE"),
            row("c", "ダンスホール (~The White Lounge Version~ / Live) - Dance Hall", "Mrs. GREEN APPLE", "4:10"),
            row("d", "夢見るダンスホール - Dreaming dance hall", "CHOP STICK, Chee"),
        )
        assertEquals("a", pick("ダンスホール(Dance Hall, 댄스홀)", "Mrs. Green Apple", rows).first())
    }

    @Test
    fun theWrongArtistDoesNotHideTheRightTitle() {
        val rows = listOf(
            row("a", "がらくた - JUNK - Garakuta - JUNK", "Kenshi Yonezu"),
            row("b", "곱게 자랐어요 (feat. 하은)", "커피소년"),
            row("c", "あくびがうつる - Yawning is contagious", "Garakuta"),
        )
        assertEquals("a", pick("がらくた(잡동사니)", "오화요", rows).first())
    }

    @Test
    fun aKoreanTitleMeetsItsEnglishListingInBrackets() {
        val rows = listOf(
            row("a", "The Western Sky (서쪽 하늘)", "Lee Seung Chul", "4:50"),
            row("b", "서쪽 하늘", "울랄라세션", "4:40"),
            row("c", "The Western Sky (inst.) (서쪽하늘 (inst.))", "Lee Seung Chul", "4:50"),
        )
        val picked = pick("서쪽하늘", "이승철", rows, videoMs = 290_000L)
        assertTrue(picked.first() in setOf("a", "b") && "c" !in picked, "$picked")
    }

    @Test
    fun anEnglishOnlyListingIsTriedWhenItLeadsTheSearchAndFits() {
        val rows = listOf(
            row("a", "This Love", "Davichi", "4:05"),
            row("b", "외사랑", "Davichi", "3:40"),
            row("c", "Everytime", "CHEN, Punch", "3:10"),
        )
        assertEquals("a", pick("이 사랑", "다비치", rows, videoMs = 248_000L).first())
    }

    @Test
    fun versionsTheCoverDidNotAskForGoLast() {
        val rows = listOf(
            row("b", "踊 (Cover Ver.) [Mixed] - Odo (Cover Ver.) [Mixed]", "MIKA HIRAMA"),
            row("c", "踊 (Bon-Odo Remix) - Odo (Bon-Odo Remix)", "Ado"),
            row("a", "踊 - Odo", "Ado"),
        )
        assertEquals("a", pick("踊 (Odo, 춤)", "Ado", rows).first())
        val lists = listOf(
            row("x", "Let It Go (From \"Frozen\"/Soundtrack Version)", "Idina Menzel", "3:44"),
            row("y", "Let It Go (Karaoke Version) (Originally Performed By Idina Menzel)", "Karaoke Galaxy", "3:44"),
        )
        assertEquals(listOf("x"), pick("Let It Go", "Idina Menzel", lists, videoMs = 225_000L).take(1))
    }

    @Test
    fun videosAndStrangersAreNotRecords() {
        val rows = listOf(
            row("v", "Flashlight", "Jessie J", video = true),
            row("s", "Completely Different", "Somebody Else", "9:59"),
        )
        assertEquals(emptyList(), pick("Flashlight", "Jessie J", rows))
    }
}
