package com.music.bitchord.playback.smart

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
        val saved = AlignFarm.savedFrom(cipher(BODY), "en", PASS)
        assertNotNull(saved)
        assertTrue(saved!!.whole)
        assertEquals(200.0, saved.seconds, 0.0)
        assertEquals(2, saved.words.size)
        assertEquals(1500L, saved.words[1].s)
        assertEquals(LyricsTiming.Clock.YouTube, LyricsTiming.clockFromKey(saved.clock))
    }

    @Test
    fun `another language, another key or a bad file gives nothing`() {
        assertNull(AlignFarm.savedFrom(cipher(BODY), "ko", PASS))
        assertNull(AlignFarm.savedFrom(cipher(BODY), "en", "other"))
        assertNull(AlignFarm.savedFrom(byteArrayOf(1, 2, 3), "en", PASS))
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

    /** A GET that must not happen. */
    private fun noGet(why: String): ByteArray? = throw AssertionError(why)

    private fun cipher(text: String) = AlignFarm.decipher(text.toByteArray(), PASS) // the same both ways

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PASS = "secretvid12345678"
        const val BODY = """{"lang":"en","audio_s":200.0,"words":[{"t":"a","s":1000,"e":1400},{"t":"b","s":1500,"e":1900}]}"""
        const val PYTHON_40_BYTES = "26a0ddf10aab74a6abd66fa4190bab3255afa0b14b694f79e6d1d64c6508b38301df36de87dfb220"
    }
}
