package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqMatchPolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 11-13 Despacito: a Telegram FLAC tagged 'Despacito' / 'Luis Fonsi' was the
 * remix with another singer's verse over the same instrumental. Chroma scored
 * it 96% at -351 ms and the old code swapped it in. The same search held a row
 * crediting the extra singer, so the pair now needs the words check, and words
 * that disagree reject it while the same recording heard twice still passes.
 *
 * All words below are invented; no lyric text is used.
 */
class KoVersionHeardWordsTest {

    @AfterTest
    fun clear() = KoVersionGuard.clearForTest()

    private fun row(id: String, title: String, artist: String) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    /** One invented word every 1.5 s from 3 s on, on the clock it is given in. */
    private fun heard(words: List<String>, shiftMs: Long = 0L): List<LyricsWordAlign.Heard> =
        words.mapIndexed { i, w ->
            val at = 3_000L + i * 1_500L + shiftMs
            LyricsWordAlign.Heard(w, at, at + 400L)
        }

    private val firstSinger = listOf(
        "palomar", "tirena", "cuvado", "mesiral", "brontalo", "senvira", "olumbre",
        "tracida", "venoral", "quimbal", "dorasco", "fenilla", "garvento", "hulisma",
        "lorvena", "mandiro", "nubrasol", "portelo", "rasquina", "selvanto",
    )
    private val otherSinger = listOf(
        "glimber", "throcket", "wundle", "spanger", "frillow", "blotchy", "crandle",
        "smorvin", "plinket", "drabble", "quorple", "snizzle", "flontar", "grubbit",
        "harkle", "jindle", "klomper", "lurvish", "mupple", "norbish",
    )

    @Test
    fun `old chroma rule accepts the remix, the words check rejects it`() {
        // What the old code did: this figure alone was a pass.
        assertTrue(HqMatchPolicy.verdict(96, -351L, 1.0).accepted)

        val words = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 90_000L,
            candidate = heard(otherSinger, shiftMs = -351L),
            candidateUntilMs = 60_000L,
            offsetMs = -351L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.DIFFERENT, words.verdict)
        assertEquals(0, words.shared)
        assertEquals(
            KoVersionGuard.Decision.REJECT,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = true, words = words),
        )
    }

    @Test
    fun `the same recording on a shifted clock passes`() {
        val words = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 90_000L,
            candidate = heard(firstSinger, shiftMs = -351L),
            candidateUntilMs = 60_000L,
            offsetMs = -351L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.SAME, words.verdict)
        assertTrue(words.shared >= KoVersionGuard.MIN_WORDS)
        assertEquals(
            KoVersionGuard.Decision.ACCEPT,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = true, words = words),
        )
    }

    @Test
    fun `a rough second hearing of the same recording still passes`() {
        // Half the words misheard on the candidate side.
        val rough = firstSinger.mapIndexed { i, w -> if (i % 2 == 0) w else otherSinger[i] }
        val words = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 90_000L,
            candidate = heard(rough, shiftMs = 2_000L),
            candidateUntilMs = 60_000L,
            offsetMs = 2_000L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.SAME, words.verdict)
    }

    @Test
    fun `an instrumental opening leaves the chroma verdict standing`() {
        val words = KoVersionGuard.compareWords(
            reference = heard(firstSinger.take(3)),
            referenceUntilMs = 90_000L,
            candidate = emptyList(),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.UNDECIDED, words.verdict)
        assertEquals(
            KoVersionGuard.Decision.ACCEPT,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = true, words = words),
        )
    }

    @Test
    fun `no words answer is unmeasured and never accepted, a chroma refusal still rejects`() {
        assertEquals(
            KoVersionGuard.Decision.UNMEASURED,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = true, words = null),
        )
        assertEquals(
            KoVersionGuard.Decision.ACCEPT,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = false, words = null),
        )
        assertEquals(
            KoVersionGuard.Decision.REJECT,
            KoVersionGuard.decide(chromaAccepted = false, needsWords = false, words = null),
        )
    }

    @Test
    fun `the Despacito search marks its rows for the words check`() {
        val rows = listOf(
            row("src::1", "Despacito", "Luis Fonsi"),
            row("src::2", "Despacito", "Luis Fonsi, Daddy Yankee, Justin Bieber"),
        )
        assertTrue(
            KoVersionGuard.namesOtherVersion(
                "Despacito", "Luis Fonsi, Daddy Yankee",
                "Despacito", "Luis Fonsi, Daddy Yankee, Justin Bieber",
            ),
        )
        KoVersionGuard.noteSearch("telegram", "Despacito", "Luis Fonsi, Daddy Yankee", rows) {
            it.videoId.substringAfter("::")
        }
        assertTrue(KoVersionGuard.risky("telegram", "1"))
        assertTrue(KoVersionGuard.risky("telegram", "2"))
        assertFalse(KoVersionGuard.risky("other", "1"))
        assertFalse(KoVersionGuard.risky(null, "1"))
    }

    // Review 11-15 (H draft-4): the table used to be emptied when it filled, so a
    // long evening of searches dropped the mark of the song playing right now.
    @Test
    fun `a full table drops its oldest marks, not every mark`() {
        fun search(prefix: String, count: Int) {
            val rows = (1..count).map { row("src::$prefix$it", "Despacito", "Luis Fonsi") } +
                row("src::${prefix}x", "Despacito", "Luis Fonsi, Daddy Yankee, Justin Bieber")
            KoVersionGuard.noteSearch("telegram", "Despacito", "Luis Fonsi, Daddy Yankee", rows) {
                it.videoId.substringAfter("::")
            }
        }
        search("old", 9)
        repeat(10) { search("mid$it-", 99) }
        search("new", 29)
        // 10 + 1000 + 30 rows went in; the newest 1024 are still marked.
        assertTrue(KoVersionGuard.risky("telegram", "new1"))
        assertTrue(KoVersionGuard.risky("telegram", "mid9-1"))
        assertTrue(KoVersionGuard.risky("telegram", "mid0-99"))
        assertFalse(KoVersionGuard.risky("telegram", "old1"))
    }

    // Review 11-15 (H draft-2): a words check over a few seconds was stored as
    // "words checked" and the risky row was never heard again.
    @Test
    fun `a short stretch does not close the question, a long empty one does`() {
        val short = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 12_000L,
            candidate = heard(otherSinger),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.UNDECIDED, short.verdict)
        assertFalse(KoVersionGuard.settles(short))

        val instrumental = KoVersionGuard.compareWords(
            reference = heard(firstSinger.take(3)),
            referenceUntilMs = 90_000L,
            candidate = emptyList(),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.UNDECIDED, instrumental.verdict)
        assertTrue(KoVersionGuard.settles(instrumental))

        val same = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 90_000L,
            candidate = heard(firstSinger),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertTrue(KoVersionGuard.settles(same))
        assertFalse(KoVersionGuard.settles(null))
    }

    @Test
    fun `words that disagree reject even when the search did not ask for them`() {
        val words = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 90_000L,
            candidate = heard(otherSinger),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertEquals(
            KoVersionGuard.Decision.REJECT,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = false, words = words),
        )
    }

    // Review 11-15 (H draft-6): a remix filed alone shows no other version in its
    // search, so nobody asked for its words and the sound alone accepted it.
    @Test
    fun `a candidate nobody asked about is heard once the kept words can decide`() {
        // A few seconds of kept words can only come back undecided: not worth a hearing.
        assertEquals(false, KoVersionGuard.worthHearingUnasked(keptSeconds = 8.0))
        val short = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 8_000L,
            candidate = heard(otherSinger),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.Words.UNDECIDED, short.verdict)
        // The shortest stretch the comparison calls either way, edges trimmed.
        assertEquals(true, KoVersionGuard.worthHearingUnasked(keptSeconds = 14.0))
        assertEquals(true, KoVersionGuard.worthHearingUnasked(keptSeconds = 90.0))
    }

    // Review 11-15 (H3): the remix swapped in before the words check sat whole in
    // the cache and every plain play opened it with no search and no check.
    @Test
    fun `a cached copy whose verdict never met the guard owes one hearing, a marked one none`() {
        fun owes(words: Boolean, seen: Boolean, attempts: Int) =
            KoVersionGuard.cachedCopyOwesWords(words, seen, attempts, maxAttempts = 3)
        // A verdict stored before the guard: neither mark.
        assertTrue(owes(words = false, seen = false, attempts = 0))
        // Words compared, or a search with no other version: plays straight from the cache.
        assertFalse(owes(words = true, seen = false, attempts = 0))
        assertFalse(owes(words = false, seen = true, attempts = 0))
        // Hearings that brought no answer are bounded.
        assertTrue(owes(words = false, seen = false, attempts = 2))
        assertFalse(owes(words = false, seen = false, attempts = 3))
    }

    @Test
    fun `the cached remix is refused, the same recording settled, no answer asks again`() {
        val remix = KoVersionGuard.compareWords(
            reference = heard(firstSinger),
            referenceUntilMs = 90_000L,
            candidate = heard(otherSinger),
            candidateUntilMs = 60_000L,
            offsetMs = 0L,
            rate = 1.0,
        )
        assertEquals(KoVersionGuard.CachedCopy.REFUSE, KoVersionGuard.cachedCopyVerdict(remix))
        val same = KoVersionGuard.WordsCheck(KoVersionGuard.Words.SAME, 20, 20, 18, 50_000L)
        assertEquals(KoVersionGuard.CachedCopy.SETTLED, KoVersionGuard.cachedCopyVerdict(same))
        val instrumental = KoVersionGuard.WordsCheck(KoVersionGuard.Words.UNDECIDED, 2, 3, 1, 50_000L)
        assertEquals(KoVersionGuard.CachedCopy.SETTLED, KoVersionGuard.cachedCopyVerdict(instrumental))
        // No reply, or the YouTube copy's words kept for a few seconds only: nothing is refused.
        assertEquals(KoVersionGuard.CachedCopy.ASK_AGAIN, KoVersionGuard.cachedCopyVerdict(null))
        val short = KoVersionGuard.WordsCheck(KoVersionGuard.Words.UNDECIDED, 3, 3, 3, 6_000L)
        assertEquals(KoVersionGuard.CachedCopy.ASK_AGAIN, KoVersionGuard.cachedCopyVerdict(short))
    }

    @Test
    fun `a featured credit in the title also counts as another version`() {
        assertTrue(
            KoVersionGuard.namesOtherVersion(
                "Despacito", "Luis Fonsi",
                "Despacito (feat. Justin Bieber)", "Luis Fonsi",
            ),
        )
        assertTrue(
            KoVersionGuard.namesOtherVersion(
                "Despacito", "Luis Fonsi",
                "Despacito (Remix)", "Luis Fonsi",
            ),
        )
    }

    @Test
    fun `plain searches pay nothing`() {
        // Same credit, a credit the song already has, another song, another artist.
        assertFalse(KoVersionGuard.namesOtherVersion("Despacito", "Luis Fonsi", "Despacito", "Luis Fonsi"))
        assertFalse(
            KoVersionGuard.namesOtherVersion(
                "Despacito", "Luis Fonsi, Daddy Yankee",
                "Despacito", "Luis Fonsi",
            ),
        )
        assertFalse(KoVersionGuard.namesOtherVersion("Despacito", "Luis Fonsi", "Echame La Culpa", "Luis Fonsi, Demi Lovato"))
        assertFalse(KoVersionGuard.namesOtherVersion("Despacito", "Luis Fonsi", "Despacito", "Some Cover Band"))
        val rows = listOf(row("src::1", "Despacito", "Luis Fonsi"), row("src::3", "Despacito", "Luis Fonsi"))
        KoVersionGuard.noteSearch("telegram", "Despacito", "Luis Fonsi", rows) { it.videoId.substringAfter("::") }
        assertFalse(KoVersionGuard.risky("telegram", "1"))
    }

    @Test
    fun `the kept opening is a 16 kHz mono WAV of the asked length`() {
        val clip = KoOpeningClip(2.0)
        assertNull(clip.wav())
        // Three seconds at 48 kHz in two chunks; only two are kept.
        clip.push(FloatArray(48_000 * 2) { 0.5f }, 48_000)
        clip.push(FloatArray(48_000) { 0.5f }, 48_000)
        clip.push(FloatArray(100) { 0.5f }, 44_100) // another rate: ignored
        assertEquals(2_000L, clip.durationMs)
        val wav = assertNotNull(clip.wav())
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        fun int(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8) or
            ((wav[at + 2].toInt() and 0xFF) shl 16) or ((wav[at + 3].toInt() and 0xFF) shl 24)
        assertEquals(16_000, int(24))
        assertEquals(16_000 * 2 * 2, int(40))
        assertEquals(44 + 16_000 * 2 * 2, wav.size)
    }

    // Review 11-17 (H draft-5): with no Groq key the candidate was never heard, so a
    // Deepgram-only listener's risky row stayed on YouTube on every play.
    @Test
    fun `Groq hears when it has a key, Deepgram without it, nobody with neither`() {
        assertEquals(KoVersionGuard.Hearer.GROQ, KoVersionGuard.hearer("fake-groq-key", deepgramUsable = true))
        assertEquals(KoVersionGuard.Hearer.GROQ, KoVersionGuard.hearer("fake-groq-key", deepgramUsable = false))
        assertEquals(KoVersionGuard.Hearer.DEEPGRAM, KoVersionGuard.hearer("", deepgramUsable = true))
        assertEquals(KoVersionGuard.Hearer.DEEPGRAM, KoVersionGuard.hearer("   ", deepgramUsable = true))
        assertEquals(KoVersionGuard.Hearer.NONE, KoVersionGuard.hearer("", deepgramUsable = false))
        // No engine still never accepts a row that needs the words.
        assertEquals(KoVersionGuard.Decision.UNMEASURED, KoVersionGuard.decide(chromaAccepted = true, needsWords = true, words = null))
    }
}
