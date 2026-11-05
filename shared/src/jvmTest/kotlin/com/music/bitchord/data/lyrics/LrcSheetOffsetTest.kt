package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sheet's own `[offset:±N]` tag.
 *
 * 11-05, emulator sync run: one track's every line was uniformly 3.3 s late
 * with no base applied at all (`base=none shift=0`), which is the shape a
 * dropped offset tag makes — the file says where its stamps sit relative to
 * the audio and the reader has to add it. These are the three things that must
 * hold: the tag is applied, exactly once, and a sheet that declares nothing
 * (or declares a move no sync tweak could be) comes out untouched.
 */
class LrcSheetOffsetTest {

    private val sheet = """
        [ar:DEAN]
        [offset:3300]
        [00:11.89] 첫 줄
        [00:16.91] 둘째 줄
    """.trimIndent()

    private fun sung(lines: List<LyricLine>) = lines.filterNot { it.isGap }

    @Test
    fun honoursTheSheetsOwnOffset() {
        val lines = sung(LrcLib.parseLrc(sheet))
        assertEquals(11_890L + 3_300, lines[0].timeMs)
        assertEquals(16_910L + 3_300, lines[1].timeMs)
    }

    /** A provider that writes a header tag and then the real one must not move the sheet twice. */
    @Test
    fun movesOnceWhenTheTagIsWrittenTwice() {
        val twice = "[offset:3300]\n[offset:3300]\n[00:11.89] 첫 줄"
        assertEquals(11_890L + 3_300, sung(LrcLib.parseLrc(twice)).single().timeMs)
    }

    /** A move past the widest the free evidence can express is another recording's sheet, not this one's. */
    @Test
    fun refusesAnOffsetTooLargeToBeASyncTweak() {
        val far = "[offset:45000]\n[00:11.89] 첫 줄"
        assertEquals(11_890L, sung(LrcLib.parseLrc(far)).single().timeMs)
        assertEquals(0L, EnhancedLrc.sheetOffsetMs(far))
    }

    @Test
    fun leavesASheetWithoutTheTagUntouched() {
        val plain = "[00:11.89] 첫 줄\n[00:16.91] 둘째 줄"
        val lines = sung(LrcLib.parseLrc(plain))
        assertEquals(11_890L, lines[0].timeMs)
        assertEquals(16_910L, lines[1].timeMs)
        assertEquals(0L, EnhancedLrc.sheetOffsetMs(plain))
    }

    @Test
    fun movesEarlierForANegativeOffset() {
        val early = "[offset:-1500]\n[00:11.89] 첫 줄"
        assertEquals(11_890L - 1_500, sung(LrcLib.parseLrc(early)).single().timeMs)
    }

    @Test
    fun readsTheTagWhateverItsCaseAndSpacing() {
        assertEquals(3_300L, EnhancedLrc.sheetOffsetMs("[ Offset : +3300 ]\n[00:01.00] x"))
        assertEquals(-1_200L, EnhancedLrc.sheetOffsetMs("[OFFSET:-1200]\n[00:01.00] x"))
        assertEquals(0L, EnhancedLrc.sheetOffsetMs("[00:01.00] [offset] is not a tag"))
    }

    /** Word and syllable stamps belong to the line: they move with it, not on their own clock. */
    @Test
    fun movesWordsAndSyllablesWithTheLine() {
        val a2 = "[offset:3300]\n[00:01.00]<00:01.00>e<00:01.20>nough <00:01.50>done"
        val line = sung(EnhancedLrc.parse(a2)).single()
        assertEquals(1_000L + 3_300, line.timeMs)
        assertEquals(1_000L + 3_300, line.words[0].startMs)
        assertEquals(1_000L + 3_300, line.words[0].syllables[0].startMs)
        assertEquals(1_200L + 3_300, line.words[0].syllables[1].startMs)
        assertEquals(1_500L + 3_300, line.words[1].startMs)
    }

    /** The provider entry point picks one parser, so the tag is not applied by both. */
    @Test
    fun providerParseAppliesItOnce() {
        val a2 = "[offset:3300]\n[00:01.00]<00:01.00>e<00:01.20>nough <00:01.50>done"
        assertEquals(1_000L + 3_300, sung(ProviderLyrics.parse(a2)!!).single().timeMs)
        val plain = "[offset:3300]\n[00:01.00] one line only"
        assertEquals(1_000L + 3_300, sung(ProviderLyrics.parse(plain)!!).single().timeMs)
    }

    /** Nothing goes negative: the sheet's first line is held at zero like every other move here. */
    @Test
    fun holdsTheFirstLineAtZero() {
        val early = "[offset:-5000]\n[00:01.00] 첫 줄\n[00:09.00] 둘째 줄"
        val lines = sung(LrcLib.parseLrc(early))
        assertEquals(0L, lines[0].timeMs)
        assertEquals(4_000L, lines[1].timeMs)
    }
}
