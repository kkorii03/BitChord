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

    /**
     * Review RG6 V-2-A: after V-2 a tap on "song version" whose search timed out
     * did nothing at all, not even a notice. The tap now says to try again; the
     * automatic before-audio swap (beforeAudioOnly = true) stays silent.
     */
    @Test
    fun `a manual switch whose search is unsettled says to try again`() {
        val source = File("src/main/java/com/music/bitchord/MainActivity.kt").readText()
        val start = source.indexOf("suspend fun switchToMusicOnly(")
        assertTrue(start >= 0)
        val unsettled = source.indexOf("audio switch lookup unsettled", start)
        assertTrue(unsettled > start)
        val branch = source.substring(unsettled, source.indexOf("return false", unsettled))
        val notice: String = branch.lines().singleOrNull { "R.string.audio_version_try_again" in it }
            ?: throw AssertionError("the unsettled branch shows no notice:\n$branch")
        assertTrue("the notice must be for a tap only: $notice", "!beforeAudioOnly" in notice)
        assertTrue("stillCurrent" in notice)
        // The automatic caller is the one passing beforeAudioOnly = true.
        val effects = File("src/main/java/com/music/bitchord/ko/KoVersionEffects.kt").readText()
        val auto = effects.substringAfter("internal fun KoAutoMusicOnlyEffect(").substringBefore("\n/**")
        val call = auto.substringAfter("switchToMusicOnly(").substringBefore(")")
        assertEquals(listOf("song", "false", "false", "true"), call.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test
    fun `the try-again notice is written in English and in Korean`() {
        val english = stringValue("src/main/res/values/strings.xml", "audio_version_try_again").orEmpty()
        val korean = stringValue("src/main/res/values-ko/strings.xml", "audio_version_try_again").orEmpty()
        assertTrue("missing English string", english.isNotBlank())
        assertTrue("missing Korean string", korean.isNotBlank())
        // 합니다체, as the neighbouring version notices.
        assertTrue(korean, korean.endsWith("시오") || korean.endsWith("니다"))
    }

    private fun stringValue(path: String, key: String): String? =
        Regex("""<string name="${Regex.escape(key)}">([^<]*)</string>""")
            .find(File(path).readText())?.groupValues?.get(1)
}
