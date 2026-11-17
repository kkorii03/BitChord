package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.ko.koFollowerPlays
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review X2-1: mid-blend, a phone call suppresses the session player without
 * clearing its playWhenReady, and the other player copied playWhenReady alone,
 * so the other song kept playing under the call.
 */
class CrossfadeFollowerCallTest {

    @Test
    fun `a call silences the follower`() {
        assertFalse(koFollowerPlays(true, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
        assertFalse(koFollowerPlays(false, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertTrue(koFollowerPlays(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
    }

    @Test
    fun `tick mirrors the session through the suppression check both ways`() {
        val source = File("src/main/java/com/music/bitchord/playback/CrossfadeController.kt").readText()
        val start = source.indexOf("internal fun tick()")
        assertTrue("tick not found", start >= 0)
        val body = source.substring(start, source.indexOf("publishAnalysisState()", start))
        assertTrue(Regex("""koFollowerPlays\(""").findAll(body).count() == 2)
        assertFalse("a bare playWhenReady copy ignores the call", "?.let { incoming?.playWhenReady = it }" in body)
    }
}
