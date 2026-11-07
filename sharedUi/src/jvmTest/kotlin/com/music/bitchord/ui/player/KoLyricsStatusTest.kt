package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.ko.ui.player.KoLyricsBarStatus
import com.music.bitchord.ko.ui.player.KoTimingDetail
import com.music.bitchord.ko.ui.player.KoTranslationDetail
import com.music.bitchord.ko.ui.player.koLyricsBarStatus
import com.music.bitchord.ko.ui.player.koTimingDetails
import com.music.bitchord.ko.ui.player.koTranslationDetail
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoLyricsStatusTest {

    @Test
    fun theBarLabelFollowsTheLookupOnly() {
        assertEquals(
            KoLyricsBarStatus.Source,
            koLyricsBarStatus(hasSource = true, unavailable = false, hasLyrics = true),
        )
        assertEquals(
            KoLyricsBarStatus.Looking,
            koLyricsBarStatus(hasSource = false, unavailable = false, hasLyrics = false),
        )
        assertEquals(
            KoLyricsBarStatus.NoLyrics,
            koLyricsBarStatus(hasSource = false, unavailable = true, hasLyrics = false),
        )
        assertEquals(
            KoLyricsBarStatus.Saved,
            koLyricsBarStatus(hasSource = false, unavailable = false, hasLyrics = true),
        )
    }

    @Test
    fun alreadyKoreanLyricsAreNeverDescribed() {
        val alreadyKorean = LyricsTranslationUiState.SameLanguage
        assertNull(koTranslationDetail(alreadyKorean, showing = false))
        assertNull(koTranslationDetail(alreadyKorean, showing = true))
    }

    @Test
    fun theTranslationIsDescribedOnlyWhileItIsHappeningOrShown() {
        val byGoogle = LyricsTranslationUiState.Ready(emptyList(), byGemini = false)
        val byGemini = LyricsTranslationUiState.Ready(emptyList(), byGemini = true)
        assertEquals(
            KoTranslationDetail.Translating,
            koTranslationDetail(LyricsTranslationUiState.Loading, showing = false),
        )
        assertEquals(KoTranslationDetail.Translated, koTranslationDetail(byGoogle, showing = true))
        assertEquals(KoTranslationDetail.ByGemini, koTranslationDetail(byGemini, showing = true))
        assertNull(koTranslationDetail(byGemini, showing = false))
        assertNull(koTranslationDetail(LyricsTranslationUiState.Idle, showing = false))
    }

    @Test
    fun timingDetailsNameWhatMovedTheLines() {
        assertEquals(emptyList(), koTimingDetails(LyricsTimingUi()))
        assertEquals(
            listOf(KoTimingDetail.Moved),
            koTimingDetails(LyricsTimingUi(shiftMs = 3_200L)),
        )
        assertEquals(
            listOf(KoTimingDetail.Running),
            koTimingDetails(LyricsTimingUi(aiRunning = true, shiftMs = 3_200L)),
        )
        assertEquals(
            listOf(KoTimingDetail.Moved, KoTimingDetail.ByHand),
            koTimingDetails(LyricsTimingUi(byAi = true, manualMs = 500L)),
        )
        assertEquals(
            listOf(KoTimingDetail.ByHand),
            koTimingDetails(LyricsTimingUi(manualMs = -500L)),
        )
    }

    /** Longest bar label is "가사 제공: " plus this: it must keep fitting beside the button. */
    @Test
    fun everyProviderNameIsShortEnoughForTheBar() {
        LyricsSource.entries.forEach { source ->
            assertTrue(source.label.length <= 20, "${source.label} is too long for the bar")
        }
    }

    /** The noun labels have room on one line on the narrowest phones; sentences do not. */
    @Test
    fun koreanBarLabelsStayShortNouns() {
        val strings = koreanStrings()
        listOf("lyrics_looking", "lyrics_not_available", "lyrics_saved_with_download").forEach { key ->
            val label = strings.getValue(key)
            assertTrue(label.length <= 8, "$key is too long for the bar: $label")
            assertFalse(label.endsWith("."), "$key must be a noun label, not a sentence: $label")
        }
    }

    private fun koreanStrings(): Map<String, String> {
        val path = "src/commonMain/composeResources/values-ko/strings.xml"
        val file = listOf(File(path), File("sharedUi/$path")).first(File::exists)
        return Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
