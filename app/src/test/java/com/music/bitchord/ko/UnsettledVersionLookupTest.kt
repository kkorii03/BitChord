package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review V-1 / V-2: a search that timed out answers null without throwing. It was
 * taken as "no other version": the video/audio switch stayed hidden for the life
 * of the screen, and the auto music-only swap showed a false "no audio version"
 * notice and never tried that video again in the process.
 */
class UnsettledVersionLookupTest {

    @Test
    fun `only a definitive miss is remembered as no alternate`() {
        assertEquals(true, koAlternateVerdict(found = true, matches = true, definitiveMiss = false))
        assertEquals(false, koAlternateVerdict(found = true, matches = false, definitiveMiss = false))
        assertEquals(false, koAlternateVerdict(found = false, matches = false, definitiveMiss = true))
        assertNull(koAlternateVerdict(found = false, matches = false, definitiveMiss = false))
    }

    @Test
    fun `the alternate effect asks for the definitive miss on both branches`() {
        val source = File("src/main/java/com/music/bitchord/ko/KoVersionEffects.kt").readText()
        val body = source.substringAfter("internal fun KoAlternateVersionEffect(").substringBefore("\n/**")
        assertTrue(body.contains("isSongVersionMissing(song.videoId)"))
        assertTrue(body.contains("isVideoVersionMissing(song.videoId)"))
    }

    @Test
    fun `the music-only switch does not settle on an unsettled null`() {
        val source = File("src/main/java/com/music/bitchord/MainActivity.kt").readText()
        val start = source.indexOf("suspend fun switchToMusicOnly(")
        assertTrue(start >= 0)
        val body = source.substring(start, source.indexOf("R.string.no_audio_version", start))
        assertTrue(body.contains("audio == null && !YtMusicRepository.isSongVersionMissing(song.videoId)"))
    }
}
