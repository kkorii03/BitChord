package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Phone 11-25: AutoPlay queued "Viva La Vida" by Cale, HALUNA and Deep Mage, a
 * cover with no marker in its title. Every row rule passed it; the old code had
 * nothing that could tell. Words here are invented placeholders.
 */
class KoAutoplayCoverTest {

    @get:Rule
    val koTimeout: Timeout = Timeout.seconds(60)

    private fun song(id: String, title: String, artist: String) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = "4:02")

    private val cover = song("RKLdfpVu2Qk", "Viva La Vida", "Cale, HALUNA 및 Deep Mage")
    private val coldplay = song("coldplay", "Viva La Vida", "Coldplay")

    private fun words(prefix: String, count: Int) = (1..count).mapTo(HashSet()) { "$prefix$it" }

    @Test
    fun `the phone's cover is compared with Coldplay and its words make it a cover`() {
        val page = listOf(coldplay, song("live", "Viva La Vida (Live)", "Coldplay"))
        val found = KoAutoplayCover.candidate(cover, page)!!
        assertEquals("coldplay", found.original?.videoId)
        val own = words("sharedword", 30) + words("extra", 6)
        val original = words("sharedword", 30) + words("other", 10)
        val verdict = KoAutoplayCover.compare(own, original)
        assertTrue(verdict.cover)
        assertEquals("shared=30/36:40", verdict.detail)
    }

    @Test
    fun `Coldplay's own row is kept`() {
        assertEquals(
            KoAutoplayCover.Candidate(null, "same-artist"),
            KoAutoplayCover.candidate(song("other", "Viva la Vida (Radio Edit)", "Coldplay"), listOf(coldplay, cover)),
        )
    }

    @Test
    fun `two songs sharing a title are told apart by their words`() {
        val golden = song("g1", "Golden", "Jill Scott")
        val found = KoAutoplayCover.candidate(golden, listOf(song("g2", "Golden", "Harry Styles")))!!
        assertEquals("g2", found.original?.videoId)
        assertFalse(
            KoAutoplayCover.compare(words("one", 40) + words("common", 10), words("two", 40) + words("common", 10)).cover,
        )
    }

    @Test
    fun `a same artist row in the title's top three is a known song`() {
        val golden = song("g1", "Golden", "Jill Scott")
        val page = listOf(
            song("g2", "Golden", "Harry Styles"),
            song("g3", "Golden Hour", "JVKE"),
            song("g4", "Golden", "Jill Scott"),
        )
        assertEquals("same-artist", KoAutoplayCover.candidate(golden, page)?.reason)
    }

    @Test
    fun `one act in another script or word order is never called a cover`() {
        fun reason(mine: String, theirs: String) =
            KoAutoplayCover.candidate(song("a", "Placeholder", mine), listOf(song("b", "Placeholder", theirs)))!!.reason
        assertEquals("artist-scripts", reason("아이유", "IU"))
        assertEquals("artist-scripts", reason("에픽하이, Younha", "EPIK HIGH"))
        assertEquals("same-artist", reason("Hikaru Utada", "Utada Hikaru"))
        assertEquals("same-artist", reason("Lee Seung Chul", "Lee Seungchul"))
        assertEquals("generic-credit", reason("Cale", "Various Artists"))
    }

    @Test
    fun `too few words on either side keep the row`() {
        val few = words("word", 19)
        assertFalse(KoAutoplayCover.enough(few))
        assertFalse(KoAutoplayCover.compare(few, words("word", 40)).cover)
        assertFalse(KoAutoplayCover.compare(words("word", 40), few).cover)
        assertTrue(KoAutoplayCover.enough(KoAutoplayCover.words((1..20).map { "placeholder$it placeholder$it" })))
    }

    @Test
    fun `a failed or empty search is no verdict and a page without the title keeps the row`() {
        assertNull(KoAutoplayCover.candidate(cover, null))
        assertNull(KoAutoplayCover.candidate(cover, emptyList()))
        assertEquals("no-original", KoAutoplayCover.candidate(cover, listOf(song("x", "Yellow", "Coldplay")))?.reason)
    }

    @Test
    fun `a sheet found under another act's name is no evidence`() {
        assertTrue(KoAutoplayCover.borrowedSheet(cover, "Coldplay"))
        assertFalse(KoAutoplayCover.borrowedSheet(cover, "HALUNA"))
        assertFalse(KoAutoplayCover.borrowedSheet(song("a", "Placeholder", "IU"), "아이유"))
        assertFalse(KoAutoplayCover.borrowedSheet(cover, null))
    }

    @Test
    fun `the search asks the bare title in its own listing`() {
        assertEquals(KoAutoplayCover.Ask("viva la vida", "en", "US"), KoAutoplayCover.ask(cover))
        assertEquals("KR", KoAutoplayCover.ask(song("a", "눈의 꽃", "누군가"))?.gl)
    }
}
