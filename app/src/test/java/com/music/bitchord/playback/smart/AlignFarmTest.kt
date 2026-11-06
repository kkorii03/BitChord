package com.music.bitchord.playback.smart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignFarmTest {

    /** Vector from farm.py's stream(), so the runner's files read back on the phone. */
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

    private fun cipher(text: String) = AlignFarm.decipher(text.toByteArray(), PASS) // the same both ways

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PASS = "secretvid12345678"
        const val BODY = """{"lang":"en","audio_s":200.0,"words":[{"t":"a","s":1000,"e":1400},{"t":"b","s":1500,"e":1900}]}"""
        const val PYTHON_40_BYTES = "26a0ddf10aab74a6abd66fa4190bab3255afa0b14b694f79e6d1d64c6508b38301df36de87dfb220"
    }
}
