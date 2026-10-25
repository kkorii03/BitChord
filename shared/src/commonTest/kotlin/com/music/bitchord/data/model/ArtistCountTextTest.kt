package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistCountTextTest {

    @Test
    fun englishCountsKeepTheFigureAndMagnitude() {
        assertEquals("1.2M", artistCountValue("1.2M subscribers"))
        assertEquals("4.1M", artistCountValue("4.1M monthly listeners"))
        assertEquals("73", artistCountValue("73 subscribers"))
    }

    @Test
    fun koreanCountsTakeTheNumberNotTheLabel() {
        assertEquals("73", artistCountValue("구독자 73명"))
        assertEquals("4120만", artistCountValue("월간 시청자 4120만명"))
        assertEquals("1.2만", artistCountValue("구독자 1.2만명"))
    }

    @Test
    fun aLineWithNoNumberHasNoChip() {
        assertNull(artistCountValue(null))
        assertNull(artistCountValue(""))
        assertNull(artistCountValue("구독자 없음"))
    }
}
