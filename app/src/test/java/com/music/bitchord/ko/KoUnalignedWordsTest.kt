package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsWordAlign
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] unaligned-words. Phone 11-16 21:15/21:16, AKMU's 외국인의 고백
 * (p7nJWAVnRvc): a title-unverified Telegram row (AKMU's Love Lee, the same
 * 3:00) was fetched whole twice (19,260,150 bytes each), decoded, and logged
 * "match=0% offset=0 -> unmeasured": the aligner returned nothing and nothing
 * was stored. When the aligner gives no figures to refuse on, a doubted row
 * whose two openings are full is asked its words; only different words at
 * every tried shift refuse it. Placeholder tokens only, no lyric text.
 */
class KoUnalignedWordsTest {

    private fun words(prefix: String, from: Int, until: Int, startMs: Long, stepMs: Long = 1_000L) =
        (from until until).mapIndexed { i, n -> LyricsWordAlign.Heard("$prefix${n}x", startMs + i * stepMs, startMs + i * stepMs + 500L) }

    private fun compare(reference: List<LyricsWordAlign.Heard>, candidate: List<LyricsWordAlign.Heard>, shiftMs: Long) =
        KoVersionGuard.compareWords(reference, 30_000L, candidate, 60_000L, shiftMs, 1.0)

    @Test
    fun `another song's words differ at every shift`() {
        val youtube = words("ref", 0, 30, 0L)
        val other = words("oth", 0, 60, 0L)
        val check = KoUnverifiedRows.unalignedWords { compare(youtube, other, it) }
        assertEquals(KoVersionGuard.Words.DIFFERENT, check.verdict)
    }

    @Test
    fun `the same song behind a longer intro is not refused`() {
        val youtube = words("ref", 0, 30, 0L)
        // 25 s of other words (a spoken intro), then the song's words from the top.
        val sameLater = words("intro", 0, 25, 0L) + words("ref", 0, 35, 25_000L)
        assertEquals(
            "at shift 0 alone the intro reads as another song",
            KoVersionGuard.Words.DIFFERENT,
            compare(youtube, sameLater, 0L).verdict,
        )
        val check = KoUnverifiedRows.unalignedWords { compare(youtube, sameLater, it) }
        assertEquals(KoVersionGuard.Words.SAME, check.verdict)
    }

    @Test
    fun `the same words are the same`() {
        val youtube = words("ref", 0, 30, 0L)
        val check = KoUnverifiedRows.unalignedWords { compare(youtube, words("ref", 0, 60, 0L), it) }
        assertEquals(KoVersionGuard.Words.SAME, check.verdict)
    }

    @Test
    fun `too few words anywhere stays undecided`() {
        val youtube = words("ref", 0, 30, 0L)
        val hum = words("hum", 0, 3, 0L)
        assertEquals(KoVersionGuard.Words.UNDECIDED, KoUnverifiedRows.unalignedWords { compare(youtube, hum, it) }.verdict)
    }

    @Test
    fun `one same reading wins over different and undecided ones`() {
        fun check(v: KoVersionGuard.Words, shared: Int) = KoVersionGuard.WordsCheck(v, 20, 20, shared, 30_000L)
        val asked = mutableListOf<Long>()
        val mixed = KoUnverifiedRows.unalignedWords { shift ->
            asked += shift
            when (shift) {
                -15_000L -> check(KoVersionGuard.Words.SAME, 9)
                0L -> check(KoVersionGuard.Words.DIFFERENT, 1)
                else -> check(KoVersionGuard.Words.UNDECIDED, 0)
            }
        }
        assertEquals(KoVersionGuard.Words.SAME, mixed.verdict)
        assertEquals("zero is tried first", 0L, asked.first())
        assertTrue("both directions up to 20 s", asked.containsAll(listOf(20_000L, -20_000L, 5_000L, -5_000L)))
        val differentOnly = KoUnverifiedRows.unalignedWords { shift ->
            if (shift == 10_000L) check(KoVersionGuard.Words.DIFFERENT, 2) else check(KoVersionGuard.Words.UNDECIDED, 0)
        }
        assertEquals(KoVersionGuard.Words.DIFFERENT, differentOnly.verdict)
    }

    @Test
    fun `only a doubted row with two full openings is asked`() {
        assertTrue(KoUnverifiedRows.wordsDecide(unverified = true, referenceComplete = true, candidateComplete = true))
        assertFalse("a credited row stays unmeasured", KoUnverifiedRows.wordsDecide(false, true, true))
        assertFalse("a partial reference may still fill in", KoUnverifiedRows.wordsDecide(true, false, true))
        assertFalse("a short head is not an answer", KoUnverifiedRows.wordsDecide(true, true, false))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `a null alignment of a doubted row asks its words and stores a refusal`() {
        val verify = source("playback/smart/HqAudioVerify.kt")
        val noAlignment = verify.substringAfter("if (result == null) {")
            .substringBefore("return@coroutineScope Outcome.Unavailable(transient = true)")
        val asked = noAlignment.substringAfter("// [KO] unaligned-words", "").substringBefore("UnmeasuredMemory.note(", "")
        for (part in listOf(
            "KoUnverifiedRows.wordsDecide(KoUnverifiedRows.contains(sourceId, trackId), referenceComplete, candidateComplete)",
            // [KO] UPG-5: the same hearing, taken from the early ask when one was made for this clip.
            "heardWords(youtubeId, unalignedWords, candidate.opening, offsetMs = 0L, rate = 1.0, unaligned = true, ahead = early?.take(candidate.opening))",
            "heard?.verdict == KoVersionGuard.Words.DIFFERENT",
            "reject(context, youtubeId, sourceId, trackId)",
            "return@coroutineScope Outcome.Mismatch",
        )) {
            assertTrue("the unaligned words path must carry $part", asked.contains(part))
        }
        assertTrue(
            "a doubted row's opening must be kept for the words",
            verify.contains("val unalignedWords = youtubeWords ?: keptWords?.takeIf { KoUnverifiedRows.contains(sourceId, trackId)") &&
                !verify.contains("keepOpening = youtubeWords != null") &&
                verify.contains("keepOpening = unalignedWords != null"),
        )
        val heard = verify.substringAfter("private suspend fun heardWords(").substringBefore("return check")
        assertTrue(
            "the unaligned hearing must try the shifts",
            heard.contains("if (unaligned) KoUnverifiedRows.unalignedWords { compare(it, 1.0) } else compare(offsetMs, rate)"),
        )
    }
}
