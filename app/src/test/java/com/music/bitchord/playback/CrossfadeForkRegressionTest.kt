package com.music.bitchord.playback

import com.music.bitchord.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crossfade behaviour the v1.9 merge changed away from the Korean fork.
 *
 * Source-level checks, like [com.music.bitchord.ui.SettingsForkRegressionTest]:
 * the arming path needs two ExoPlayers and cannot be driven in a JVM test, but
 * the lost piece is one assignment a reader can find and a merge can be told
 * apart from.
 */
class CrossfadeForkRegressionTest {

    @Test
    fun `the arming deadline includes the standby lead`() {
        val source = TestFiles.file(SOURCE).readText()
        val begin = source
            .substringAfter("    private fun begin(")
            .substringBefore("        holdAtEnd(out)")

        assertTrue(
            "the standby is armed a full STANDBY_LEAD_MS before the fade, so its " +
                "readiness grace must start after that lead",
            begin.contains("AutomixWorkPolicy.STANDBY_LEAD_MS"),
        )
        assertEquals(
            "a later assignment must not overwrite the deadline with the grace alone",
            1,
            Regex("armDeadline =").findAll(begin).count(),
        )
    }

    @Test
    fun `the Automix setting keeps the fork's Korean label`() {
        val strings = TestFiles.file("app/src/main/res/values-ko/strings.xml").readText()
        assertTrue(
            "the setting reads [베타] on 1.8, matching the English [BETA]",
            strings.contains("<string name=\"automix\">자동 믹스 [베타]</string>"),
        )
    }

    @Test
    fun `the video-song Automix line keeps the fork's Korean wording`() {
        val strings = TestFiles
            .file("sharedUi/src/commonMain/composeResources/values-ko/strings.xml")
            .readText()
        assertTrue(
            "a video pair is a simple crossfade, so the line must still say so",
            strings.contains(
                "<string name=\"automix_not_supported_video\">" +
                    "자동 믹스 · 비디오 노래: 단순 크로스페이드</string>",
            ),
        )
    }

    private companion object {
        const val SOURCE =
            "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt"
    }
}
