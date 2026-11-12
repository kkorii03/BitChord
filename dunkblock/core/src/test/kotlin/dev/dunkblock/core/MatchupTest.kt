package dev.dunkblock.core

import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin port must give the Python answers (tools/analysis/mfixture.py wrote them next to the pictures):
 * five frames of the user's colour match recordings and two slices of scan logs with mpick.py's decisions.
 * The identity track (MatchupPicker(track = true)) is pinned to tools/analysis/mtrack.py -track the same way, on a slice
 * of recording C and one of the first phone match (bundle b1112_083909), both SF against SF.
 * The strict picker (MatchupPicker(track = true, strict = true)) is pinned to tools/analysis/mstrict.py -fixture.
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
    private fun replay(name: String, track: Boolean = false, strict: Boolean = false, relax: Boolean = false): IntArray {
        val pk = MatchupPicker(track = track, strict = strict, relax = relax)
        var lastDef = -9.0; var n = 0; var withTarget = 0; var withMe = 0; var fanTargets = 0; var trk = 0
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
            if (c.size > 12) assertEquals(c[12], if (p.trk) "1" else "0", "$at named by the track")
            if (c.size > 13) assertEquals(c[13].toInt(), p.src, "$at target source (1 tag, 2 fan alone)")
            n++; if (p.src > 0) withTarget++; if (p.src == 2) fanTargets++; if (p.myX >= 0) withMe++; if (p.trk) trk++
        }
        return intArrayOf(n, withTarget, fanTargets, withMe, trk)
    }

    @Test fun pickerMatchesPythonOnMatchE() {
        val r = replay("picks_e.csv")      // my SF against an SG: class learned, then tag and fan targets
        assertTrue(r[0] > 1000 && r[1] > 200 && r[2] > 0 && r[3] > 400, r.joinToString())
    }

    @Test fun pickerMatchesPythonOnMatchC() {
        val r = replay("picks_c.csv")      // my SF against an SF: my own tag must never be the target
        assertTrue(r[0] > 700 && r[1] > 100, r.joinToString())
    }

    @Test fun trackMatchesPythonOnMatchC() {
        val r = replay("picks_c_trk.csv", track = true)     // recording C again, with the identity track
        assertTrue(r[0] == 790 && r[1] == 161 && r[4] == 69, r.joinToString())
    }

    @Test fun trackMatchesPythonOnPhoneMatch() {
        val r = replay("picks_p_trk.csv", track = true)     // the app's own matchup.csv (vc1092 match, 133-240 s)
        assertTrue(r[0] == 918 && r[1] == 300 && r[4] == 151, r.joinToString())
    }

    /** strict = true against tools/analysis/mstrict.py -fixture on the vc1100 match (my SF vs Curry = PG, team mate
     *  LaMelo = PG), 88-262 s: holds the scenes where the old picker chased LaMelo (132, 144, 241 s). */
    @Test fun strictMatchesPythonOnPhoneMatch() {
        val r = replay("picks_p_strict.csv", track = true, strict = true)
        assertTrue(r[0] == 1508 && r[1] == 295 && r[2] == 30 && r[3] == 1141 && r[4] == 205, r.joinToString())
    }

    /** relax = true (the default) against mstrict.py -fixture -relax on the match of 11-12 19:55 (my SF vs an SF), 90-290 s:
     *  53 "unique" and 12 more "fan alone" targets than strict alone. */
    @Test fun relaxMatchesPythonOnPhoneMatch() {
        val r = replay("picks_p_relax.csv", track = true, strict = true, relax = true)
        assertTrue(r[0] == 1720 && r[1] == 371 && r[2] == 86 && r[3] == 1159 && r[4] == 306, r.joinToString())
    }

    // ---- relax rules on made-up scans: my position SF (fixed), the matchup a PG, no ring ----
    private fun pg(x: Float, grey: Float = 0.2f) = MatchupFinder.Tag(4, x, 300f, grey)
    private fun fanAt(q: MatchupFinder.Tag) = MatchupFinder.Fan(q.x + 45, q.y - 52, 100, 0.5f)
    private fun scan(tags: List<MatchupFinder.Tag>, fans: List<MatchupFinder.Fan> = emptyList()) = MatchupFinder.Scan(tags, fans, 0f, 0f, 0f)
    private fun at(pk: MatchupPicker, t: Double, tags: List<MatchupFinder.Tag>, fans: List<MatchupFinder.Fan> = emptyList()) = pk.step(t, true, scan(tags, fans))

    /** 4 s of defence with the fan at the PG's feet (class learned, 41 scans of the PG); [also] = more tags in every scan. */
    private fun learned(relax: Boolean = true, also: List<MatchupFinder.Tag> = emptyList()): MatchupPicker {
        val pk = MatchupPicker(fixedMyCls = 0, track = true, strict = true, relax = relax)
        var p = at(pk, 0.0, listOf(pg(500f)) + also, listOf(fanAt(pg(500f))))
        for (i in 1..40) p = at(pk, i * 0.1, listOf(pg(500f)) + also, listOf(fanAt(pg(500f))))
        assertEquals("fan", p.why); assertEquals(4, p.cls)
        return pk
    }

    @Test fun uniqueTakesTheOnlyTagOfTheClass() {
        val p = at(learned(), 10.0, listOf(pg(700f)))      // no fan, track long expired (CONF_S)
        assertEquals("unique", p.why); assertEquals(1, p.src); assertEquals(745f, p.x); assertEquals(248f, p.y)
        assertEquals(0, at(learned(relax = false), 10.0, listOf(pg(700f))).src, "strict alone has no target here")
    }

    @Test fun uniqueRefusesWhenItCannotBeSure() {
        // two far-apart PGs on screen in the last 60 s (team mate or second opponent): never "the only one"
        assertEquals(0, at(learned(also = listOf(pg(900f))), 10.0, listOf(pg(700f))).src)
        assertEquals(0, at(learned(), 10.0, listOf(pg(700f, grey = 0.02f))).src, "too dark: looks like my own tag")
        assertEquals(0, at(learned(), 4.0 + 20.5, listOf(pg(700f))).src, "last fan of the class older than FRESH_S")
        assertEquals(0, at(learned(), 10.0, listOf(pg(700f)), listOf(MatchupFinder.Fan(300f, 400f, 100, 0.5f))).src, "a fan in view")
    }

    @Test fun fanContinuesTheLastTarget() {
        // 0.5 s after the last scan (no fan "in the scan before"), tag hidden, a fan 55 px from the target's last feet
        val near = MatchupFinder.Fan(600f, 248f, 100, 0.5f)
        val p = at(learned(), 4.5, emptyList(), listOf(near))
        assertEquals("fan alone", p.why); assertEquals(2, p.src); assertEquals(600f, p.x)
        assertEquals(0, at(learned(relax = false), 4.5, emptyList(), listOf(near)).src, "strict alone: no fan the scan before")
        assertEquals(0, at(learned(), 4.7, emptyList(), listOf(near)).src, "target older than AGE")
        assertEquals(0, at(learned(), 4.5, emptyList(), listOf(MatchupFinder.Fan(800f, 248f, 100, 0.5f))).src, "beyond FAN_NEAR + GV * age")
    }

    @Test fun carriedTargetExpiresAndDoesNotJump() {
        val mate = pg(900f)                                 // a second PG (team mate) stands far away all along
        val pk = learned(also = listOf(mate))
        val a = at(pk, 4.1, listOf(pg(520f), mate))
        assertEquals("track", a.why); assertEquals(565f, a.x)
        val b = at(pk, 4.2, listOf(mate))                   // the matchup's tag hidden: never the other PG
        assertEquals(0, b.src, b.why)
        assertEquals("track", at(pk, 4.3, listOf(pg(540f), mate)).why)
        val c = at(pk, 6.5, listOf(pg(545f)))               // 2.5 s after the last fan: the carried target has expired
        assertEquals(0, c.src, c.why)
    }

    @Test fun fanUnderTwoTagsOfTheClassConfirmsNeither() {
        val two = listOf(pg(500f), pg(560f)); val fan = listOf(MatchupFinder.Fan(580f, 248f, 100, 0.5f))
        val p = at(learned(), 10.0, two, fan)
        assertEquals(0, p.src, p.why)
        assertEquals("fan", at(learned(relax = false), 10.0, two, fan).why, "strict alone takes one of them")
    }
}
