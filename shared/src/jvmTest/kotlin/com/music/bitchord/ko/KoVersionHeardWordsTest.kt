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
    fun `no words answer is unmeasured, never accepted; chroma refusal still rejects`() {
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
}
