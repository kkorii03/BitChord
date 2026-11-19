package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.lyrics.OriginalNames
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phone 11-19 oZ4Tr4bINNY "히어로 - 타비 커버" and pip-QThrNwE "개화": YouTube Music
 * resolved the titles to LUCY's English rows ("Hero", "Flowering"), the retry asked
 * only those names, every Korean catalogue missed, and the lookup ended with no
 * lyrics. The Hangul row title under the resolved artist is what Bugs and VIBE file.
 */
class KoCoverHangulTitleTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class FakeProvider(private val sheets: Map<Pair<String, String>, String>) {
        val asked = mutableListOf<Pair<String, String>>()
        suspend fun ask(name: Pair<String, String>): String? {
            asked += name
            return sheets[name]
        }
    }

    private fun retry(rowTitle: String, resolved: OriginalNames.Name, provider: FakeProvider): String? =
        runBlocking {
            KoCoverLyrics.retryWithoutArtist(
                alternatives = emptyList(),
                resolve = { resolved },
                variants = listOf(LyricsQueryVariants.Name(rowTitle, "")),
                retry = { provider.ask(it) },
                rowTitle = rowTitle,
            )
        }

    @Test
    fun theHangulTitleIsAskedUnderTheResolvedArtistForBothPhoneSongs() {
        for ((row, english) in listOf("히어로" to "Hero", "개화" to "Flowering")) {
            // Only the Hangul title under LUCY is filed; the English row and the title alone miss.
            val provider = FakeProvider(mapOf((row to "lucy") to "sheet"))
            val found = retry(row, OriginalNames.Name(english, "lucy"), provider)
            assertEquals("sheet", found, "$row: ${provider.asked}")
            // A Latin resolved title: the Hangul one goes first, nothing else was needed.
            assertEquals(listOf(row to "lucy"), provider.asked)

            // Both miss: the English row is still asked, then the title alone.
            val missing = FakeProvider(emptyMap())
            assertNull(retry(row, OriginalNames.Name(english, "lucy"), missing))
            assertEquals(listOf(row to "lucy", english to "lucy", row to ""), missing.asked)
        }
    }

    @Test
    fun aJapaneseOriginalKeepsItsOwnNameFirst() {
        val provider = FakeProvider(emptyMap())
        assertNull(retry("만찬가", OriginalNames.Name("晩餐歌", "tuki."), provider))
        assertEquals(listOf("晩餐歌" to "tuki.", "만찬가" to "tuki.", "만찬가" to ""), provider.asked)
    }

    @Test
    fun aHangulOriginalOrNoArtistAddsNothing() {
        assertEquals(
            listOf("공백" to "원곡가수"),
            KoCoverLyrics.resolvedNames("공백", OriginalNames.Name("공백", "원곡가수")),
        )
        assertEquals(
            listOf("Hero" to ""),
            KoCoverLyrics.resolvedNames("히어로", OriginalNames.Name("Hero", " ")),
        )
        assertEquals(
            listOf("Hero" to "lucy"),
            KoCoverLyrics.resolvedNames("Hero", OriginalNames.Name("Hero", "lucy")),
        )
        assertEquals(listOf("Hero" to "lucy"), KoCoverLyrics.resolvedNames("", OriginalNames.Name("Hero", "lucy")))
        assertEquals(emptyList<Pair<String, String>>(),KoCoverLyrics.resolvedNames("히어로", null))
    }
}
