package dev.dunkblock.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 20 auto presses of two phone matches (tools/analysis/megate2.py, newest scan 45 ms old): dx and "ball above my
 * ring" in px of the 1170 x 540 picture, and who shot by eye (true = my matchup, or a shooter right next to me).
 */
class NearGateTest {
    private fun near(dx: Int, up: Int) = NearGate.near(up / 540f, abs(dx) / 1170f)

    @Test fun curryMatchTheDesignData() {
        val mine = listOf(31 to 122, 53 to 158, -28 to 182, -11 to 188, 71 to 106, 134 to 145)
        val others = listOf(-1 to 253, 52 to 221, 5 to 247, -157 to 209, -22 to 265, 61 to 245)
        for ((dx, up) in mine) assertEquals(true, near(dx, up), "mine $dx $up")
        for ((dx, up) in others) assertEquals(false, near(dx, up), "others $dx $up")
    }

    @Test fun paulGeorgeMatchTheCheck() {
        val mine = listOf(133 to 160, -41 to 176, -67 to 121, -64 to 101, -55 to 119, 56 to 149)
        val others = listOf(-52 to 220, 110 to 247)
        for ((dx, up) in mine) assertEquals(true, near(dx, up), "mine $dx $up")
        for ((dx, up) in others) assertEquals(false, near(dx, up), "others $dx $up")
    }

    @Test fun farToTheSideIsNotNear() {
        assertEquals(false, near(200, 150))
    }
}
