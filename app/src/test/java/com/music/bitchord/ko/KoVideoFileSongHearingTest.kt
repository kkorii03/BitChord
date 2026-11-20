package com.music.bitchord.ko

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [KO] x1120-nextdl: 11-20 phone dA4qZ4qJGP4 and lYjm_KXe7MU, downloaded video rows,
 * were heard for their lyric timing off a freshly resolved YouTube stream (10.4 s and
 * 8.8 s of resolve, 3.3 and 3.9 MB) while their opus file sat on the phone and played.
 */
class KoVideoFileSongHearingTest {

    private fun hears(
        isVideo: Boolean = true,
        saved: Boolean = true,
        wrong: Boolean = false,
        hq: String? = null,
        soundSource: String? = null,
        streamChosen: Boolean = false,
    ) = KoVideoFileHearing.hearsFile(isVideo, saved, wrong, hq, soundSource, "dA4qZ4qJGP4", streamChosen)

    @Test
    fun `a downloaded video row hears its own file`() {
        assertTrue(hears())
        assertTrue(hears(soundSource = "dA4qZ4qJGP4"))
    }

    @Test
    fun `what playback would not play keeps the stream`() {
        assertFalse(hears(isVideo = false))
        assertFalse(hears(saved = false))
        assertFalse(hears(wrong = true))
        assertFalse(hears(streamChosen = true))
    }

    @Test
    fun `a quality copy or another upload's file keeps the old path`() {
        assertFalse(hears(hq = "addon"))
        assertFalse(hears(soundSource = "other-upload"))
    }

    @Test
    fun `the AI ask builds its song with the downloaded file`() {
        val root = File(".").canonicalFile
        val ask = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt") }
            .first { it.exists() }.readText()
            .substringAfter("private suspend fun askAi(")
            .substringBefore("quickVideoAnswer(")
        assertTrue("KoVideoFileHearing.fileFor(context, videoId, isVideo)" in ask)
    }
}
