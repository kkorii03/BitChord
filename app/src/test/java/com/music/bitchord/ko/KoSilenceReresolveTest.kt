package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sweep 11-18 (SW7ps-1): the silence ladder's re-resolve pinned a YouTube URL
 * over a substitute or an upgraded copy, and over a different rendition.
 */
class KoSilenceReresolveTest {

    private val webm = "https://rr1.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm&clen=100"
    private val webmOther = "https://rr2.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm&clen=100&x=1"
    private val mp4 = "https://rr3.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&clen=200"

    @Test
    fun `a substitute or an upgrade is never replaced by a YouTube pin`() {
        assertFalse(KoSilenceReresolve.asks(substituted = true, upgraded = false))
        assertFalse(KoSilenceReresolve.asks(substituted = false, upgraded = true))
        assertFalse(KoSilenceReresolve.mayPin(webm, webmOther, substituted = true, upgraded = false))
        assertFalse(KoSilenceReresolve.mayPin(webm, webmOther, substituted = false, upgraded = true))
    }

    @Test
    fun `only the same rendition is pinned`() {
        assertFalse(KoSilenceReresolve.mayPin(webm, mp4, substituted = false, upgraded = false))
        assertFalse(KoSilenceReresolve.mayPin(webm, webm, substituted = false, upgraded = false))
        assertTrue(KoSilenceReresolve.mayPin(webm, webmOther, substituted = false, upgraded = false))
        assertTrue(KoSilenceReresolve.mayPin(null, webm, substituted = false, upgraded = false))
    }

    @Test
    fun `the silence rung asks the rule before it pins`() {
        val body = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
            .substringAfter("private fun reresolveCurrentStreamForSilence()")
            .substringBefore("private fun recoverAudibleGains()")
        assertTrue("KoSilenceReresolve.asks(StreamChoice.soundsSubstitute(mediaId), upgraded)" in body)
        val pin = body.indexOf("StreamChoice.remember(")
        val rule = body.indexOf("KoSilenceReresolve.mayPin(")
        assertTrue("the pin must be guarded by mayPin", rule in 0 until pin)
    }

    @Test
    fun `a track picked from a source is re-prepared, not re-resolved as YouTube`() {
        assertTrue(KoSilenceReresolve.pickedFromSource("source"))
        assertFalse(KoSilenceReresolve.pickedFromSource("youtube"))
        assertFalse(KoSilenceReresolve.pickedFromSource(null))
        val body = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
            .substringAfter("private fun reresolveCurrentStreamForSilence()")
            .substringBefore("private fun recoverAudibleGains()")
        val check = body.indexOf("KoSilenceReresolve.pickedFromSource(uri?.authority)")
        val resolve = body.indexOf("StreamResolver.reResolveTrack(")
        assertTrue("the source check must come before the YouTube re-resolve", check in 0 until resolve)
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }
}
