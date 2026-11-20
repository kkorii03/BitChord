package com.music.bitchord.playback.smart

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoSungLanguage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class AlignFarmTest {

    /** Vector from farm.py's stream(), so the runner's files read back on the phone. */
    @Test
    fun `the read token matches r2_tool`() {
        assertEquals("708eca2c9fc544ca25b8ecff5e4034b3833bc2800f377690968b83ad5cf1104c", AlignFarm.readToken("test-secret"))
    }

    @Test
    fun `the cipher matches the runner's`() {
        assertArrayEquals(ByteArray(40) { it.toByte() }, AlignFarm.decipher(hex(PYTHON_40_BYTES), "pw"))
    }

    /** Vector from farm.py's data_name(). */
    @Test
    fun `the file name matches the runner's`() {
        assertEquals("ecdd18a2c7b53dd8c30cc33233329aa5", AlignFarm.nameOf("vid12345678", "secret"))
    }

    @Test
    fun `the farm's words become a whole transcript of the YouTube stream`() {
        val saved = AlignFarm.savedFrom(cipher(BODY), PASS)
        assertNotNull(saved)
        assertTrue(saved!!.whole)
        assertEquals("en", saved.language)
        assertEquals(200.0, saved.seconds, 0.0)
        assertEquals(2, saved.words.size)
        assertEquals(1500L, saved.words[1].s)
        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.clockFromKey(saved.clock))
    }

    @Test
    fun `another key, a bad file or a file with no words gives nothing`() {
        assertNull(AlignFarm.savedFrom(cipher(BODY), "other"))
        assertNull(AlignFarm.savedFrom(byteArrayOf(1, 2, 3), PASS))
        assertNull(AlignFarm.savedFrom(cipher("""{"lang":"en","audio_s":200.0,"words":[]}"""), PASS))
        assertNull(AlignFarm.savedFrom(cipher("""{"lang":"","audio_s":200.0,"words":[{"t":"a","s":1000,"e":1400}]}"""), PASS))
    }

    /**
     * 11-09 rs28 215 pB-5XG-DbAA: the run's one GET went to a "multi" listen, which threw the
     * "en" file away, and the "en" whole-song hearing found the farm spent. The file is kept
     * under its own language: the "en" hearing reads it, a "multi" one still does not.
     */
    @Test
    fun `a hearing in another language keeps the file for the one it suits`() = runBlocking {
        val id = "langtest001"
        val saved = AlignFarm.answer(id, "multi", PASS) { cipher(BODY) }
        assertNotNull(saved)
        assertEquals("en", saved!!.language)
        val clock = LyricsTiming.clockToKey(LyricsTiming.Clock.YouTube)!!
        val stream = SongHearing.streamRecording(clock)
        assertNotNull(SongHearing.covering(listOf(saved), stream, "en", 420.0))
        assertNull(SongHearing.covering(listOf(saved), stream, "multi", 90.0))
        // Still one GET a run.
        assertNull(AlignFarm.answer(id, "en", PASS) { noGet("asked a second time") })
    }

    /** 117b 8gyLR4NfMiI: the start's GET timed out; the ask after the wait for sound must still go, a fourth must not. */
    @Test
    fun `a failed GET may be asked again, three failures leave the farm alone`() {
        val id = "failtest001"
        assertTrue(AlignFarm.mayAsk(id))
        AlignFarm.noteFailure(id)
        assertTrue(AlignFarm.mayAsk(id))
        repeat(AlignFarm.MAX_FAILED_ASKS - 1) { AlignFarm.noteFailure(id) }
        assertFalse(AlignFarm.mayAsk(id))
        assertTrue(AlignFarm.mayAsk("othervideo1"))
    }

    /**
     * 161a 6rS7OUGXUik: three GETs at the row's start, all dropped by the route, and the row
     * played uncorrected. The file asked while the row was next is its first answer, no GET then.
     */
    @Test
    fun `an answer asked ahead is the row's own, with no GET at its start`() = runBlocking {
        val id = "aheadtest01"
        assertTrue(AlignFarm.askAhead(id) { cipher(BODY) })
        val saved = AlignFarm.answer(id, "en", PASS) { noGet("the row asked again") }
        assertNotNull(saved)
        assertEquals(2, saved!!.words.size)
        // Asked once a run: nothing more, ahead or at the row.
        assertNull(AlignFarm.answer(id, "en", PASS) { noGet("asked a second time") })
        assertFalse(AlignFarm.askAhead(id) { noGet("asked ahead a second time") })
    }

    @Test
    fun `a row starting while its ahead GET flies waits on that one`() = runBlocking {
        val id = "aheadtest02"
        val reply = CompletableDeferred<ByteArray?>()
        assertTrue(AlignFarm.askAhead(id) { reply.await() })
        assertFalse(AlignFarm.askAhead(id) { noGet("two GETs in flight") })
        val row = async { AlignFarm.answer(id, "en", PASS) { noGet("a GET of its own while one flies") } }
        reply.complete(cipher(BODY))
        assertNotNull(row.await())
    }

    @Test
    fun `an ahead GET with no reply is counted and the row asks for itself`() = runBlocking {
        val id = "aheadtest03"
        assertTrue(AlignFarm.askAhead(id) { null })
        var gets = 0
        val saved = AlignFarm.answer(id, "en", PASS) {
            gets++
            cipher(BODY)
        }
        assertNotNull(saved)
        assertEquals(1, gets)
    }

    @Test
    fun `a farm that has no file, asked ahead, is not asked at the row`() = runBlocking {
        val id = "aheadtest04"
        assertTrue(AlignFarm.askAhead(id) { ByteArray(0) })
        assertNull(AlignFarm.answer(id, "en", PASS) { noGet("asked at the row") })
        assertNull(AlignFarm.answer(id, "en", PASS) { noGet("asked at the row") })
    }

    /** The row's own asks keep at least one of the three: asking ahead never leaves it none. */
    @Test
    fun `an ask ahead never takes the row's last ask`() {
        assertTrue(AlignFarm.mayAskAhead(failed = 0, askedByRow = false, held = false))
        assertTrue(AlignFarm.mayAskAhead(failed = 1, askedByRow = false, held = false))
        assertFalse(AlignFarm.mayAskAhead(failed = AlignFarm.MAX_FAILED_ASKS - 1, askedByRow = false, held = false))
        assertFalse(AlignFarm.mayAskAhead(failed = 0, askedByRow = true, held = false))
        assertFalse(AlignFarm.mayAskAhead(failed = 0, askedByRow = false, held = true))
    }

    /**
     * coverahead, phone 11-20 vc224: Last Stardust WANYQ3JC298 (row "Last Stardust",
     * "Aimer · 오화요 커버") logged `align-farm: 100 words lang=ko asked ahead for=ja`, then
     * sent 4 MB to Deepgram in ja (67%, refused); Fansa vRjfYdwM5L4 the same (43%). The
     * Latin/Japanese title never read as Korean, so the farm's Korean words sat unused.
     * Now the farm's Korean answer moves the cover's listen to ko, where its words are kept.
     */
    @Test
    fun `a cover whose farm file is Korean is heard in Korean, from the farm's words`() = runBlocking {
        val id = "farmko00001"
        val row = Song(videoId = id, title = "Last Stardust", artist = "Aimer · 오화요 커버", thumbnailUrl = null, isVideo = true)
        val query = CoverTitles.lyricsQuery(row)
        // As placeWithAi builds it: the query's title and artist, no cover fields.
        val asked = Song(videoId = id, title = query.title, artist = query.artist, thumbnailUrl = null, isVideo = query.isVideo)
        KoSungLanguage.noteRow(row) // next-prep / the screen, with the real row
        // Old code and before the farm answers: the sheet's Japanese.
        assertEquals("ja", KoSungLanguage.listenLanguage("ja", asked))
        assertTrue(AlignFarm.askAhead(id) { cipher(KO_BODY) })
        val saved = AlignFarm.answer(id, "ja", PASS) { noGet("the row asked again") }
        assertEquals("ko", saved!!.language)
        assertEquals("ko", KoSungLanguage.listenLanguage("ja", asked))
        assertEquals("ko", KoSungLanguage.listenLanguage("zh", asked))
        assertEquals("farm-Korean", KoSungLanguage.evidence(asked))
        // In ko the farm's whole file answers the 420 s ask: no request, no upload.
        val stream = SongHearing.streamRecording(LyricsTiming.clockToKey(LyricsTiming.Clock.YouTube)!!)
        assertNotNull(SongHearing.covering(listOf(saved), stream, "ko", 420.0))
        // An English or Korean sheet keeps its own language.
        assertEquals("en", KoSungLanguage.listenLanguage("en", asked))
        assertEquals("ko", KoSungLanguage.listenLanguage("ko", asked))
    }

    @Test
    fun `a Korean farm file alone does not move a row not known as a cover`() = runBlocking {
        val id = "farmko00002"
        assertNotNull(AlignFarm.answer(id, "ja", PASS) { cipher(KO_BODY) })
        val original = Song(videoId = id, title = "ファンサ", artist = "HoneyWorks", thumbnailUrl = null, isVideo = true)
        assertEquals("ja", KoSungLanguage.listenLanguage("ja", original))
        // A later farm answer in Japanese takes the note back for a cover too.
        val cover = Song(videoId = "farmko00003", title = "ファンサ - Fansa", artist = "HoneyWorks · 오화요 커버", thumbnailUrl = null, isVideo = true)
        KoSungLanguage.noteRow(cover)
        KoSungLanguage.noteFarm(cover.videoId, "ko")
        assertEquals("ko", KoSungLanguage.listenLanguage("ja", cover))
        KoSungLanguage.noteFarm(cover.videoId, "ja")
        assertEquals("ja", KoSungLanguage.listenLanguage("ja", cover))
    }

    /** A GET that must not happen. */
    private fun noGet(why: String): ByteArray? = throw AssertionError(why)

    private fun cipher(text: String) = AlignFarm.decipher(text.toByteArray(), PASS) // the same both ways

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PASS = "secretvid12345678"
        const val BODY = """{"lang":"en","audio_s":200.0,"words":[{"t":"a","s":1000,"e":1400},{"t":"b","s":1500,"e":1900}]}"""
        const val KO_BODY = """{"lang":"ko","audio_s":245.0,"words":[{"t":"가","s":1000,"e":1400},{"t":"나","s":1500,"e":1900}]}"""
        const val PYTHON_40_BYTES = "26a0ddf10aab74a6abd66fa4190bab3255afa0b14b694f79e6d1d64c6508b38301df36de87dfb220"
    }
}
