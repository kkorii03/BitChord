package dev.dunkblock.core

import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin port must give the Python answers (tools/analysis/mfixture.py wrote them next to the pictures):
 * five frames of the user's colour match recordings and two slices of scan logs with mpick.py's decisions.
 */
class MatchupTest {
    private fun res(name: String) = checkNotNull(javaClass.getResourceAsStream("/matchup/$name")) { "missing test resource $name" }

    private fun rgb(name: String): ByteArray {
        val im = res(name).use { ImageIO.read(it) }
        assertEquals(MatchupFinder.W, im.width); assertEquals(MatchupFinder.H, im.height)
        val px = im.getRGB(0, 0, im.width, im.height, null, 0, im.width)
        val out = ByteArray(px.size * 3)
        for (i in px.indices) { out[i * 3] = (px[i] shr 16).toByte(); out[i * 3 + 1] = (px[i] shr 8).toByte(); out[i * 3 + 2] = px[i].toByte() }
        return out
    }

    private fun near(want: Double, got: Float, tol: Double, what: String) = assertTrue(abs(want - got) <= tol, "$what: Python $want, Kotlin $got")

    @Test fun finderMatchesPythonOnRecordedFrames() {
        val f = MatchupFinder()
        var tags = 0; var fans = 0
        for (line in res("frames.txt").bufferedReader().readLines().filter { it.isNotBlank() }) {
            val p = line.split("|"); val name = p[0]
            val s = f.scan(rgb(name))
            val me = p[1].split(" ").map { it.toDouble() }
            near(me[2], s.meConf, 0.0002, "$name ring conf")
            if (me[2] > 0) { near(me[0], s.meX, 0.01, "$name ring x"); near(me[1], s.meY, 0.01, "$name ring y") }
            val wt = p[2].split(";").filter { it.isNotEmpty() }.map { it.split(":") }
            assertEquals(wt.joinToString(" ") { it[0] }, s.tags.joinToString(" ") { MatchupFinder.CLASSES[it.cls] }, "$name tag classes")
            for ((w, g) in wt.zip(s.tags)) {
                near(w[1].toDouble(), g.x, 0.002, "$name tag ${w[0]} x"); near(w[2].toDouble(), g.y, 0.002, "$name tag ${w[0]} y")
                near(w[3].toDouble(), g.grey, 0.0002, "$name tag ${w[0]} grey"); tags++
            }
            val wf = p[3].split(";").filter { it.isNotEmpty() }.map { it.split(":") }
            assertEquals(wf.size, s.fans.size, "$name number of fans")
            for ((w, g) in wf.zip(s.fans)) {
                near(w[0].toDouble(), g.x, 0.002, "$name fan x"); near(w[1].toDouble(), g.y, 0.002, "$name fan y")
                assertEquals(w[2].toInt(), g.area, "$name fan area"); near(w[3].toDouble(), g.rim, 0.006, "$name fan rim"); fans++
            }
        }
        assertTrue(tags >= 15 && fans >= 3, "fixtures too thin: $tags tags, $fans fans")
    }

    /** Replays a scan log through the picker; the log holds mpick.py's answer for every scan. */
    private fun replay(name: String): IntArray {
        val pk = MatchupPicker()
        var lastDef = -9.0; var n = 0; var withTarget = 0; var withMe = 0; var fanTargets = 0
        for (line in res(name).bufferedReader().readLines().drop(1).filter { it.isNotBlank() }) {
            val c = line.split(","); val t = c[0].toDouble()
            val tags = c[4].split(";").filter { it.isNotEmpty() }.map { it.split(":") }
                .map { MatchupFinder.Tag(MatchupFinder.CLASSES.indexOf(it[0]), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
            val fans = c[5].split(";").filter { it.isNotEmpty() }.map { it.split(":") }
                .map { MatchupFinder.Fan(it[0].toFloat(), it[1].toFloat(), it[2].toInt(), it[3].toFloat()) }
            val label = c[6].toDouble()
            if (label in 0.20..0.27) lastDef = t
            val p = pk.step(t, t - lastDef < 0.3, MatchupFinder.Scan(tags, fans, c[1].toFloat(), c[2].toFloat(), c[3].toFloat()))
            val at = "$name t=${c[0]}"
            assertEquals(c[7], if (p.cls >= 0) MatchupFinder.CLASSES[p.cls] else "", "$at matchup class")
            near(c[8].toDouble(), if (p.src > 0) p.x else -1f, 0.01, "$at target x"); near(c[9].toDouble(), if (p.src > 0) p.y else -1f, 0.01, "$at target y")
            near(c[10].toDouble(), p.myX, 0.01, "$at my x"); near(c[11].toDouble(), p.myY, 0.01, "$at my y")
            n++; if (p.src > 0) withTarget++; if (p.src == 2) fanTargets++; if (p.myX >= 0) withMe++
        }
        return intArrayOf(n, withTarget, fanTargets, withMe)
    }

    @Test fun pickerMatchesPythonOnMatchE() {
        val r = replay("picks_e.csv")      // my SF against an SG: class learned, then tag and fan targets
        assertTrue(r[0] > 1000 && r[1] > 200 && r[2] > 0 && r[3] > 400, r.joinToString())
    }

    @Test fun pickerMatchesPythonOnMatchC() {
        val r = replay("picks_c.csv")      // my SF against an SF: my own tag must never be the target
        assertTrue(r[0] > 700 && r[1] > 100, r.joinToString())
    }
}
