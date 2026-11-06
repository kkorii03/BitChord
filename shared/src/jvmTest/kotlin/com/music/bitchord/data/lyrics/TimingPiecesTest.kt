package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sheet placed in stretches. The songs are made-up syllables on a fixed
 * generator, put through the real letter alignment: a fast rap and a long-held
 * song are the two shapes that break timing most (sync-stress set, 11-06).
 */
class TimingPiecesTest {

    private class Gen(seed: Long) {
        private var x = seed

        fun next(): Int {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            return (x shr 16).toInt()
        }

        fun word(): String = buildString {
            repeat(1 + next() % 3) {
                append("bdfgklmnprstvz"[next() % 14])
                append("aeiou"[next() % 5])
            }
        }
    }

    private class Sheet(val times: List<Long>, val texts: List<String>)

    private fun sheet(seed: Long, lines: Int, wordsPerLine: Int, gapMs: Long): Sheet {
        val gen = Gen(seed)
        return Sheet(
            times = List(lines) { 10_000L + it * gapMs },
            texts = List(lines) { List(wordsPerLine) { gen.word() }.joinToString(" ") },
        )
    }

    /** What a transcript hears: each line on [clock], unless it is left out or in [missed]. */
    private fun hear(
        sheet: Sheet,
        wordMs: Long,
        missed: Set<Int> = emptySet(),
        clock: (Int, Long) -> Long?,
    ): List<LyricsWordAlign.Heard> {
        val jitter = Gen(99)
        val words = ArrayList<LyricsWordAlign.Heard>()
        sheet.times.forEachIndexed { i, t ->
            val at = clock(i, t)
            val off = jitter.next() % 200 - 100
            if (at != null && i !in missed) {
                sheet.texts[i].split(" ").forEachIndexed { k, word ->
                    val start = at + off + k * wordMs
                    words += LyricsWordAlign.Heard(word, start, start + wordMs * 8 / 10)
                }
            }
        }
        return words.sortedBy { it.startMs }
    }

    private fun fit(sheet: Sheet, words: List<LyricsWordAlign.Heard>): TimingPieces.Outcome =
        TimingPieces.fit(sheet.times, sheet.texts, LyricsWordAlign.placeLinesDetailed(sheet.texts, words), words.maxOf { it.endMs })

    private fun assertOnClock(
        sheet: Sheet,
        found: TimingPieces.Result,
        limitMs: Long = 300L,
        clock: (Int, Long) -> Long?,
    ) {
        sheet.times.forEachIndexed { i, t ->
            val want = clock(i, t) ?: return@forEachIndexed
            val got = found.map.map(t)
            assertTrue(abs(got - want) <= limitMs, "line $i: at $got, sung at $want")
        }
    }

    // ---- Fast rap ------------------------------------------------------------------------

    @Test
    fun `fast rap with a section left out finds both stretches`() {
        val rap = sheet(7L, 40, 22, 3_600L)
        val clock = { i: Int, t: Long ->
            when {
                i < 16 -> t + 5_000L
                i < 24 -> null
                else -> t + 5_000L - 8 * 3_600L
            }
        }
        val found = assertNotNull(fit(rap, hear(rap, 160L, clock = clock)).result)
        assertEquals(2, found.sizes.size)
        assertTrue(abs(found.jumpsMs.single() + 8 * 3_600L) <= 300L, "jump ${found.jumpsMs}")
        assertOnClock(rap, found, clock = clock)
        val shown = rap.times.map { found.map.map(it) }
        assertEquals(shown.sorted(), shown)
        // The lines left out fall between the line before the cut and the line after it.
        val before = found.map.map(rap.times[15])
        val after = found.map.map(rap.times[24])
        for (i in 16 until 24) assertTrue(found.map.map(rap.times[i]) in before..after, "line $i")
    }

    // ---- Long-held notes -----------------------------------------------------------------

    @Test
    fun `a long-held song with a minute of intro and a break keeps every line on its stretch`() {
        val held = sheet(11L, 30, 6, 9_000L)
        val clock = { i: Int, t: Long -> if (i < 15) t + 74_000L else t + 94_000L }
        // A third of the lines are never heard, as Whisper leaves held notes out.
        val missed = (0 until 30).filter { it % 3 == 2 }.toSet()
        val found = assertNotNull(fit(held, hear(held, 1_200L, missed, clock)).result)
        assertEquals(listOf(10, 10), found.sizes)
        assertOnClock(held, found, clock = clock)
    }

    @Test
    fun `one clock is one stretch`() {
        val held = sheet(11L, 30, 6, 9_000L)
        val clock = { _: Int, t: Long -> t + 74_000L }
        val missed = (0 until 30).filter { it % 3 == 2 }.toSet()
        val found = assertNotNull(fit(held, hear(held, 1_200L, missed, clock)).result)
        assertEquals(1, found.sizes.size)
        assertEquals(1.0, found.rate)
        assertEquals(100, found.agreePercent)
        assertOnClock(held, found, clock = clock)
    }

    @Test
    fun `a line nobody heard takes its own stretch's clock, not one blended across the boundary`() {
        val held = sheet(11L, 30, 6, 9_000L)
        val clock = { i: Int, t: Long -> if (i <= 15) t + 3_000L else t + 33_000L }
        // The five lines either side of the boundary are all missed.
        val found = assertNotNull(fit(held, hear(held, 1_200L, setOf(13, 14, 15, 16, 17), clock)).result)
        assertEquals(2, found.sizes.size)
        // Only the middle of the gap is a guess; every line sits within a word of where it was sung.
        assertOnClock(held, found, clock = clock)
    }

    // ---- Covers --------------------------------------------------------------------------

    @Test
    fun `a slower cover with a cut is one pace and two stretches`() {
        val cover = sheet(13L, 36, 8, 4_000L)
        val clock = { i: Int, t: Long ->
            val on = t * 1.06 + 2_000L
            when {
                i < 14 -> on.roundToLong()
                i < 20 -> null
                else -> (on - 6 * 4_000L * 1.06).roundToLong()
            }
        }
        val found = assertNotNull(fit(cover, hear(cover, 400L, clock = clock)).result)
        assertEquals(2, found.sizes.size)
        assertEquals(1.06, found.rate, 0.005)
        assertOnClock(cover, found, clock = clock)
    }

    // ---- Wrong sheets --------------------------------------------------------------------

    @Test
    fun `another song's sheet gets nothing`() {
        val rap = sheet(7L, 40, 22, 3_600L)
        val held = sheet(11L, 30, 6, 9_000L)
        val rapWords = hear(rap, 160L) { i, t -> if (i in 16 until 24) null else t }
        val heldWords = hear(held, 1_200L) { _, t -> t + 74_000L }
        for (seed in 21L..23L) {
            assertNull(fit(sheet(seed, 40, 22, 3_600L), rapWords).result, "rap $seed")
            assertNull(fit(sheet(seed, 30, 6, 9_000L), heldWords).result, "held $seed")
        }
    }

    @Test
    fun `a wrong sheet sharing one chorus with the song is refused on cover`() {
        val held = sheet(11L, 30, 6, 9_000L)
        val other = sheet(31L, 30, 6, 9_000L)
        val mixed = Sheet(other.times, other.texts.mapIndexed { i, text -> if (i in 10 until 15) held.texts[i] else text })
        val outcome = fit(mixed, hear(held, 1_200L) { _, t -> t + 74_000L })
        assertNull(outcome.result)
        assertEquals("cover", outcome.refused)
    }

    @Test
    fun `a transcript in another script places nothing`() {
        val held = sheet(11L, 30, 6, 9_000L)
        val korean = Sheet(held.times, held.times.map { "가나다라 마바사아 자차카타" })
        assertNull(fit(korean, hear(held, 1_200L) { _, t -> t }).result)
    }

    // ---- A chorus read as an earlier repeat ------------------------------------------------

    /** Verse, chorus, verse, chorus, verse, chorus: the chorus is one text three times. */
    private fun verseChorus(seed: Long): Sheet {
        val gen = Gen(seed)
        val chorus = List(6) { List(6) { gen.word() }.joinToString(" ") }
        val texts = (0 until 3).flatMap { List(8) { List(6) { gen.word() }.joinToString(" ") } + chorus }
        return Sheet(List(texts.size) { 10_000L + it * 4_000L }, texts)
    }

    @Test
    fun `the last chorus heard landing on an earlier repeat moves nothing`() {
        for (seed in 41L..43L) {
            val song = verseChorus(seed)
            // Whisper hears the first verse and chorus, then only the last chorus. The sheet is right.
            val found = fit(song, hear(song, 600L, (14 until 36).toSet()) { _, t -> t }).result ?: continue
            assertEquals(1, found.sizes.size, "seed $seed")
            song.times.forEach { t -> assertTrue(abs(found.map.map(t) - t) <= 1_000L, "seed $seed at $t") }
        }
    }

    @Test
    fun `a last stretch past the last word is dropped and the first stands`() {
        // 5bIWIoFDv80's shape: 40 lines to 170 s on -1.7 s, the last 12 read 35 s on, last word at 170 s.
        val lines = placed(0, 28, -1_700L) + placed(28, 40, 33_000L)
        val found = assertNotNull(TimingPieces.fit(lines, lastHeardMs = 170_000L).result)
        assertEquals(listOf(28), found.sizes)
        assertEquals(-1_700L, found.largestMoveMs)
        // Past the last word by no more than the margin it stays a stretch.
        assertEquals(2, assertNotNull(TimingPieces.fit(lines, lastHeardMs = 195_000L).result).sizes.size)
    }

    @Test
    fun `one stretch past the last word is refused`() {
        // A right sheet with only its later choruses heard, read on the earlier ones.
        val heard = placed(0, 12, 44_000L)
        val unheard = placed(12, 33, 0L).map { it.copy(startMs = null) }
        assertEquals("past-end", TimingPieces.fit(heard + unheard, lastHeardMs = 145_000L).refused)
    }

    @Test
    fun `a large move needs most of the sheet`() {
        val heard = placed(0, 15, 20_000L)
        val unheard = placed(15, 30, 0L).map { it.copy(startMs = null) }
        assertEquals("thin", TimingPieces.fit(heard + unheard).refused)
        assertEquals("thin", TimingPieces.fit(placed(0, 8, 0L) + placed(8, 16, 20_000L) + placed(16, 40, 0L).map { it.copy(startMs = null) }).refused)
        // The same share on a small move stands, as does the large move on more of the sheet.
        assertNotNull(TimingPieces.fit(placed(0, 15, 5_000L) + unheard).result)
        assertEquals(20_000L, assertNotNull(TimingPieces.fit(heard + unheard.take(9)).result).largestMoveMs)
    }

    // ---- The guards, on placements directly ----------------------------------------------

    private fun placed(from: Int, until: Int, offsetMs: Long, gapMs: Long = 4_000L, rate: Double = 1.0): List<TimingPieces.Placed> =
        (from until until).map { i ->
            val t = 10_000L + i * gapMs
            TimingPieces.Placed(t, (t * rate).roundToLong() + offsetMs, 1.0, 20)
        }

    @Test
    fun `lines matched too thinly are not believed`() {
        val thin = placed(0, 30, 5_000L).map { it.copy(share = TimingPieces.MIN_SHARE - 0.01) }
        assertEquals("few-heard", TimingPieces.fit(thin).refused)
        val short = placed(0, 30, 5_000L).map { it.copy(letters = TimingPieces.MIN_LETTERS - 1) }
        assertEquals("few-heard", TimingPieces.fit(short).refused)
        val nothing = placed(0, 30, 5_000L).map { it.copy(startMs = null) }
        assertEquals("few-heard", TimingPieces.fit(nothing).refused)
    }

    @Test
    fun `a stretch shorter than the least lines is left out`() {
        val lines = placed(0, 20, 5_000L) + placed(20, 23, 40_000L) + placed(23, 26, -40_000L) + placed(26, 46, 5_000L)
        val found = assertNotNull(TimingPieces.fit(lines).result)
        assertEquals(listOf(40), found.sizes)
    }

    @Test
    fun `too little of the sheet in stretches is refused`() {
        val heard = placed(0, 10, 5_000L)
        val unheard = placed(10, 40, 0L).map { it.copy(startMs = null) }
        assertEquals("cover", TimingPieces.fit(heard + unheard).refused)
        val enough = placed(10, 25, 0L).map { it.copy(startMs = null) }
        assertNotNull(TimingPieces.fit(heard + enough).result)
    }

    @Test
    fun `the agreement is out of the lines the transcript could spell`() {
        val heard = placed(0, 10, 5_000L)
        val unheard = placed(10, 40, 0L).map { it.copy(startMs = null) }
        assertNotNull(TimingPieces.fit(heard + unheard, spellable = 12).result)
        // Too few spellable lines is no base: back to all of them.
        assertEquals("cover", TimingPieces.fit(heard + unheard, spellable = 2).refused)
    }

    @Test
    fun `too many stretches are refused`() {
        val lines = (0 until 5).flatMap { k -> placed(k * 10, k * 10 + 10, k * 30_000L) }
        assertEquals("too-many", TimingPieces.fit(lines).refused)
        val four = (0 until 4).flatMap { k -> placed(k * 10, k * 10 + 10, k * 30_000L) }
        assertEquals(4, assertNotNull(TimingPieces.fit(four).result).sizes.size)
    }

    @Test
    fun `a jump past the longest cut is refused`() {
        val far = placed(0, 15, 0L) + placed(15, 30, TimingPieces.MAX_JUMP_MS + 5_000L)
        assertEquals("jump", TimingPieces.fit(far).refused)
        val near = placed(0, 15, 0L) + placed(15, 30, TimingPieces.MAX_JUMP_MS - 5_000L)
        assertNotNull(TimingPieces.fit(near).result)
    }

    @Test
    fun `a pace no cover has is refused`() {
        assertEquals("pace", TimingPieces.fit(placed(0, 30, 0L, rate = 1.5)).refused)
        assertEquals("pace", TimingPieces.fit(placed(0, 30, 0L, rate = 0.6)).refused)
        assertNotNull(TimingPieces.fit(placed(0, 30, 0L, rate = 1.3)).result)
    }

    @Test
    fun `lines that scatter inside a stretch are refused`() {
        val wide = placed(0, 30, 5_000L).mapIndexed { i, line ->
            line.copy(startMs = line.startMs!! + if (i % 2 == 0) 450L else -450L)
        }
        assertEquals("scatter", TimingPieces.fit(wide).refused)
    }

    @Test
    fun `one small move on a wide scatter stays on the provider's clock`() {
        val small = placed(0, 30, 500L).mapIndexed { i, line ->
            line.copy(startMs = line.startMs!! + if (i % 2 == 0) 300L else -300L)
        }
        assertEquals("scatter-small", TimingPieces.fit(small).refused)
        // The same scatter on a large move is still a move.
        val large = placed(0, 30, 30_000L).mapIndexed { i, line ->
            line.copy(startMs = line.startMs!! + if (i % 2 == 0) 300L else -300L)
        }
        assertNotNull(TimingPieces.fit(large).result)
    }

    @Test
    fun `a later stretch beginning before the last one ended is refused`() {
        val lines = placed(0, 15, 40_000L) + placed(15, 30, -40_000L)
        assertEquals("order", TimingPieces.fit(lines).refused)
    }

    @Test
    fun `there and back to one clock is a repeated chorus, not a cut`() {
        val lines = placed(0, 20, 5_000L) + placed(20, 25, 13_000L) + placed(25, 45, 5_000L)
        val found = assertNotNull(TimingPieces.fit(lines).result)
        assertEquals(listOf(40), found.sizes)
        assertEquals(5_000L + 10_000L + 22 * 4_000L, found.map.map(10_000L + 22 * 4_000L))
    }

    @Test
    fun `a sped-up cover's own pace is not read as a cut at every line`() {
        val lines = placed(0, 40, 2_000L, rate = 0.87)
        val found = assertNotNull(TimingPieces.fit(lines).result)
        assertEquals(1, found.sizes.size)
        assertEquals(0.87, found.rate, 0.005)
    }

    // ---- Against the straight line -------------------------------------------------------

    @Test
    fun `a line that explains its lines is not overruled`() {
        val one = assertNotNull(TimingPieces.fit(placed(0, 40, 5_000L)).result)
        assertFalse(TimingPieces.overrules(LyricsTimeMap.shift(5_000L), one))
    }

    @Test
    fun `real structure overrules a line that split the difference`() {
        val found = assertNotNull(TimingPieces.fit(placed(0, 20, 0L) + placed(20, 40, 2_000L)).result)
        assertEquals(2, found.sizes.size)
        assertTrue(TimingPieces.overrules(LyricsTimeMap.shift(1_000L), found))
        // A map that already misses nothing has nothing to be overruled for.
        assertFalse(TimingPieces.overrules(found.map, found))
    }

    @Test
    fun `a short stretch does not overrule a line`() {
        val found = assertNotNull(TimingPieces.fit(placed(0, 40, 0L) + placed(40, 45, 13_000L)).result)
        assertEquals(listOf(40, 5), found.sizes)
        assertFalse(TimingPieces.overrules(LyricsTimeMap.shift(0L), found))
    }

    // ---- The map itself ------------------------------------------------------------------

    @Test
    fun `the map survives the disk and a clock change with its order intact`() {
        val found = assertNotNull(
            TimingPieces.fit(placed(0, 15, 2_000L) + placed(15, 30, 30_000L) + placed(30, 45, 60_000L)).result,
        )
        val map = found.map
        assertEquals(map, LyricsTimeMap.unflatten(map.flatten(), map.headRate, map.tailRate))
        // As LyricsTiming.composeMaps carries a base across to a verified copy's clock.
        val after = LyricsTimeMap.linear(1.002, -300L)
        val moved = map.flatten().chunked(2).flatMap { (src, dst) -> listOf(src, after.map(dst)) }
        assertEquals(map.knots, assertNotNull(LyricsTimeMap.unflatten(moved, map.headRate, map.tailRate)).knots)
        val flat = map.flatten().chunked(2)
        assertEquals(flat.map { it[0] }.sorted(), flat.map { it[0] })
        assertEquals(flat.map { it[1] }.sorted(), flat.map { it[1] })
    }

    @Test
    fun `words move with their line and stay in order`() {
        val found = assertNotNull(TimingPieces.fit(placed(0, 15, 2_000L) + placed(15, 30, 30_000L)).result)
        val line = LyricLine(
            timeMs = 10_000L + 20 * 4_000L,
            text = "ab cd",
            words = listOf(
                LyricWord(text = "ab", startMs = 90_000L, endMs = 90_500L),
                LyricWord(text = "cd", startMs = 90_600L, endMs = 91_000L),
            ),
        )
        val moved = listOf(line).timedBy(found.map).single()
        assertEquals(120_000L, moved.timeMs)
        assertEquals(120_600L, moved.words[1].startMs)
    }
}
