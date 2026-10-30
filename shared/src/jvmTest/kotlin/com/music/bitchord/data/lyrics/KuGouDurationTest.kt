package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** KuGou's song search must not read an unknown length as "shortest first". */
class KuGouDurationTest {

    private val hits = listOf(
        210 to "album cut",
        35 to "interlude",
        206 to "single",
    )

    @Test
    fun anUnknownLengthKeepsTheSearchOrder() {
        assertEquals(listOf("album cut", "interlude", "single"), KuGou.closestFirst(hits, seconds = 0))
    }

    @Test
    fun aKnownLengthOrdersClosestFirst() {
        assertEquals(listOf("single", "album cut", "interlude"), KuGou.closestFirst(hits, seconds = 205))
    }
}
