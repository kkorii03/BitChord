package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExploreCategoryNamesTest {

    @Test
    fun theFourEnglishTilesGetTheirKoreanNames() {
        assertEquals("엔카/가요곡", ExploreCategoryNames.koreanTitle("Enka/kayokyoku"))
        assertEquals("애니메이션 & 사운드트랙", ExploreCategoryNames.koreanTitle("Anime & soundtracks"))
        assertEquals("시즌", ExploreCategoryNames.koreanTitle("Seasonal"))
        assertEquals("보컬로이드 & 우타이테", ExploreCategoryNames.koreanTitle("Vocaloid & utaite"))
    }

    @Test
    fun matchingIgnoresCaseAndSpacing() {
        assertEquals("애니메이션 & 사운드트랙", ExploreCategoryNames.koreanTitle("  anime   & SOUNDTRACKS "))
        assertEquals("보컬로이드 & 우타이테", ExploreCategoryNames.koreanTitle("Vocaloid and utaite"))
    }

    @Test
    fun onlyTheKoreanBuildIsTranslated() {
        assertEquals("Seasonal", ExploreCategoryNames.displayTitle("Seasonal", "en"))
        assertEquals("시즌", ExploreCategoryNames.displayTitle("Seasonal", "ko-KR"))
        assertEquals("J-Rock", ExploreCategoryNames.displayTitle("J-Rock", "ko"))
        assertNull(ExploreCategoryNames.koreanTitle("J-Rock"))
    }
}
