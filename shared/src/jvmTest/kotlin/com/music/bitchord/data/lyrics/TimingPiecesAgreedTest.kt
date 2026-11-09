package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 11-09 -RJSbO8UZVY (loop 220-c), SyncLRC's 56 lines: the whole-song transcript missed
 * about 48 s of the middle (329 words; 436-446 on other runs), the alignment read the
 * second chorus block onto the last ones (+66.5 s, dropped as past-end), and the rest,
 * lines 3-22 at +18.55 s, was refused as "thin" while the quick listen had +18.42 s at 94 %.
 *
 * The placements are the real letter alignment of the sheet (sync-dump) against the
 * scorer's whisper-large transcript of that run with 123-171 s left out: 40 of 56 placed,
 * straight line and stretches refused exactly as on the emulator (39 of 56 there).
 */
class TimingPiecesAgreedTest {

    private fun p(timeMs: Long, startMs: Long?, share: Double, letters: Int) =
        TimingPieces.Placed(timeMs, startMs, share, letters)

    private val placed = listOf(
        p(910L, 8350L, 0.44, 45), p(8560L, 16418L, 0.57, 49), p(16500L, 35080L, 1.00, 42),
        p(24760L, 43480L, 0.89, 45), p(32920L, 51360L, 0.88, 33), p(35170L, 53680L, 1.00, 18),
        p(37030L, 55440L, 1.00, 31), p(39160L, 57600L, 1.00, 14), p(40610L, 59340L, 1.00, 10),
        p(41800L, 60240L, 0.89, 37), p(45410L, 63960L, 1.00, 31), p(49820L, 68220L, 1.00, 37),
        p(53400L, 71960L, 1.00, 4), p(56630L, 75280L, 1.00, 10), p(57780L, 76260L, 0.89, 38),
        p(61400L, 79900L, 1.00, 31), p(65730L, 84280L, 1.00, 35), p(69400L, 87980L, 1.00, 4),
        p(73060L, 91400L, 1.00, 46), p(80570L, 99120L, 1.00, 48), p(88520L, 107200L, 0.93, 45),
        p(96560L, 115180L, 0.96, 47), p(104600L, 171220L, 1.00, 10), p(105770L, 172360L, 1.00, 37),
        p(109440L, 176040L, 1.00, 31), p(113780L, 180280L, 1.00, 37), p(117390L, 183920L, 1.00, 4),
        p(120610L, 187000L, 1.00, 10), p(121800L, 188320L, 1.00, 38), p(125400L, 191880L, 1.00, 31),
        p(129740L, 196240L, 1.00, 35), p(133450L, 199900L, 1.00, 4), p(137100L, 203560L, 0.83, 30),
        p(139180L, 205600L, 0.61, 18), p(140920L, 207380L, 1.00, 31), p(143080L, 209580L, 0.93, 14),
        p(144860L, null, 0.03, 34), p(147080L, null, 0.00, 18), p(150070L, null, 0.00, 10),
        p(152570L, 211240L, 1.00, 10), p(153760L, 212240L, 0.78, 37), p(157390L, 215900L, 0.97, 31),
        p(161760L, 219248L, 0.11, 37), p(165380L, null, 0.00, 4), p(168600L, null, 0.00, 10),
        p(169780L, null, 0.00, 38), p(173300L, null, 0.00, 31), p(177750L, null, 0.00, 35),
        p(181320L, null, 0.00, 4), p(185140L, null, 0.00, 30), p(187100L, null, 0.00, 18),
        p(188890L, null, 0.00, 31), p(191040L, null, 0.00, 14), p(192530L, null, 0.00, 10),
        p(193760L, null, 0.00, 37), p(197380L, null, 0.00, 31),
    )

    private val lastHeardMs = 226_100L

    /** Where the scorer heard lines 3-56 sung in that run (lines.csv), video ms. */
    private val sung = listOf(
        35080L, 43420L, 51900L, 53660L, 56480L, 57600L, 59340L, 61260L, 63960L, 68240L, 71980L, 75280L,
        77300L, 79900L, 84280L, 87980L, 91460L, 99120L, 107140L, 115160L, 123240L, 125280L, 127920L,
        132240L, 135900L, 139120L, 141300L, 143860L, 148260L, 151880L, 156140L, 157600L, 160460L,
        161600L, 163380L, 165620L, 168720L, 171380L, 173393L, 176040L, 180280L, 183920L, 187000L,
        189320L, 191880L, 196240L, 199900L, 204140L, 205640L, 208440L, 209580L, 211420L, 213260L, 215940L,
    )

    /** The quick listen's answer for this sheet that run: +18416 ms, 94 % (multi, 102 s). */
    private val quick = LyricsTimeMap.shift(18_416L)

    @Test
    fun `alone the whole-song stretch is still too thin a big move`() {
        assertEquals("thin", TimingPieces.fit(placed, lastHeardMs).refused)
    }

    @Test
    fun `the stretch the quick listen agrees with is kept and syncs the whole song`() {
        val found = assertNotNull(TimingPieces.fit(placed, lastHeardMs, agreed = quick).result)
        assertTrue(found.agreed)
        assertEquals(listOf(20), found.sizes)
        // 20 of the 23 lines heard outside the misread stretch: over the 70 % floor.
        assertEquals(87, found.agreePercent)
        assertTrue(found.agreePercent >= LyricsAiGuard.MIN_MATCH_PERCENT)
        // Every line of the sheet within 1.1 s of where it was sung, the last chorus too.
        sung.forEachIndexed { i, at ->
            val line = placed[i + 2]
            assertTrue(abs(found.map.map(line.timeMs) - at) <= 1_100L, "line ${i + 3}: ${found.map.map(line.timeMs)} vs $at")
        }
    }

    @Test
    fun `an answer elsewhere does not lift the guard`() {
        // On the sheet's own clock (5bIWIoFDv80's and eqKhhG4FVoQ's shape), past the
        // one-clock tolerance, or a different stretch altogether: refused as before.
        for (other in listOf(LyricsTimeMap.shift(0L), LyricsTimeMap.shift(17_400L), LyricsTimeMap.shift(19_700L), LyricsTimeMap.shift(66_500L))) {
            assertEquals("thin", TimingPieces.fit(placed, lastHeardMs, agreed = other).refused, "agreed $other")
        }
        // Agreeing on the opening only, then another clock, is not agreeing.
        val cut = assertNotNull(LyricsTimeMap.through(listOf(0L, 100_000L, 100_001L), listOf(18_416L, 118_416L, 128_416L), 1.0))
        assertEquals("thin", TimingPieces.fit(placed, lastHeardMs, agreed = cut).refused)
    }

    @Test
    fun `the other refusals still stand with an agreed answer`() {
        // Too little of the sheet: the cover floor is not the big-move guard.
        val fewer = placed.mapIndexed { i, it -> if (i in 9..21) it.copy(startMs = null, share = 0.0) else it }
        assertEquals("cover", TimingPieces.fit(fewer, lastHeardMs, agreed = LyricsTimeMap.shift(18_550L)).refused)
        // Every stretch past the end: nothing left to keep.
        assertEquals("past-end", TimingPieces.fit(placed, 150_000L, agreed = quick).refused)
    }

    @Test
    fun `an answer that needs no agreement is unchanged by one`() {
        val whole = placed.map { if (it.startMs == null) it else it.copy(startMs = it.timeMs + 18_550L, share = 1.0) }
        val alone = assertNotNull(TimingPieces.fit(whole, lastHeardMs).result)
        val with = assertNotNull(TimingPieces.fit(whole, lastHeardMs, agreed = quick).result)
        assertEquals(alone.agreePercent, with.agreePercent)
        assertEquals(alone.map, with.map)
    }
}
