package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.ko.ui.player.KO_ACTIVE_LINE_ALPHA
import com.music.bitchord.ko.ui.player.KO_INACTIVE_SCALE
import com.music.bitchord.ko.ui.player.koInterludeBreathScale
import com.music.bitchord.ko.ui.player.koInterludeBreathes
import com.music.bitchord.ko.ui.player.koInterludeContainerAlpha
import com.music.bitchord.ko.ui.player.koInterludeDotEntryAlpha
import com.music.bitchord.ko.ui.player.koInterludeExitAlpha
import com.music.bitchord.ko.ui.player.koLineBlurDp
import com.music.bitchord.ko.ui.player.koLineBlurAllowed
import com.music.bitchord.ko.ui.player.koLyricSpringForInterval
import com.music.bitchord.ko.ui.player.koStaggerDelayMs
import com.music.bitchord.ko.ui.player.koWordFeatherPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AMLL numbers the lyric panel now uses. Each assertion pins a value that
 * differs from what the panel did before the port (fixed 30 dp feather, 4 s
 * interlude, 0.98 inactive scale, index-ladder blur, fixed stagger).
 */
class KoLyricsMotionTest {
    @Test fun featherIsHalfTheFontHeight() {
        assertEquals(20f, koWordFeatherPx(40f), 0.001f)
        assertEquals(17.5f, koWordFeatherPx(35f), 0.001f)
        // Adjustable 0.5..1.0 only.
        assertEquals(30f, koWordFeatherPx(40f, 0.75f), 0.001f)
        assertEquals(20f, koWordFeatherPx(40f, 0.1f), 0.001f)
        assertEquals(40f, koWordFeatherPx(40f, 3f), 0.001f)
    }

    @Test fun interludesBreatheOnlyFromSevenSeconds() {
        assertFalse(koInterludeBreathes(6_999))
        assertTrue(koInterludeBreathes(7_000))
        assertEquals(1f, koInterludeBreathScale(2_000, 6_999), 0.001f)
        // 0.4 at the start of a cycle, 1.25 halfway through the 4 s period.
        assertEquals(0.4f, koInterludeBreathScale(0, 8_000), 0.001f)
        assertEquals(1.25f, koInterludeBreathScale(2_000, 8_000), 0.001f)
        assertEquals(0.4f, koInterludeBreathScale(4_000, 8_000), 0.001f)
    }

    @Test fun interludeEntryAndExitAreStaged() {
        assertEquals(0f, koInterludeContainerAlpha(0), 0.001f)
        assertEquals(1f, koInterludeContainerAlpha(180), 0.001f)
        // Dot 2 waits 160 ms before it starts its own 750 ms fade.
        assertEquals(0f, koInterludeDotEntryAlpha(2, 100), 0.001f)
        assertEquals(0f, koInterludeDotEntryAlpha(0, 0), 0.001f)
        val dot0 = koInterludeDotEntryAlpha(0, 375)
        val dot2 = koInterludeDotEntryAlpha(2, 375)
        assertTrue(dot0 > dot2)
        assertEquals(0.25f, koInterludeExitAlpha(62), 0.01f)
        assertEquals(0f, koInterludeExitAlpha(0), 0.001f)
        assertEquals(1f, koInterludeExitAlpha(250), 0.001f)
    }

    @Test fun hierarchyUsesAmllOpacitiesAndDistanceBlur() {
        assertEquals(0.85f, KO_ACTIVE_LINE_ALPHA, 0.001f)
        assertEquals(0.97f, KO_INACTIVE_SCALE, 0.001f)
        assertEquals(0.dp, koLineBlurDp(0, narrow = false))
        assertEquals(2.dp, koLineBlurDp(1, narrow = false))
        assertEquals(4.dp, koLineBlurDp(3, narrow = false))
        assertEquals(5.dp, koLineBlurDp(12, narrow = false))
        assertEquals(4f, koLineBlurDp(4, narrow = true).value, 0.001f)
        assertEquals(5.dp, koLineBlurDp(20, narrow = true))
    }

    @Test fun lineBlurHonoursReduceAnimationAndLowPower() {
        fun allowed(reduceAnimation: Boolean = false, lowPower: Boolean = false) = koLineBlurAllowed(
            isSynced = true,
            reduceDynamicBlur = false,
            reduceAnimation = reduceAnimation,
            lowPower = lowPower,
            lyricsBlur = true,
            browsing = false,
            isActive = false,
        )
        assertTrue(allowed())
        assertFalse(allowed(reduceAnimation = true))
        assertFalse(allowed(lowPower = true))
        assertFalse(
            koLineBlurAllowed(
                isSynced = true,
                reduceDynamicBlur = false,
                reduceAnimation = false,
                lowPower = false,
                lyricsBlur = true,
                browsing = false,
                isActive = true,
            ),
        )
    }

    @Test fun staggerFansOutThenWrapsAtThreeSteps() {
        assertEquals(0f, koStaggerDelayMs(0, 400), 0.001f)
        assertEquals(24f, koStaggerDelayMs(1, 400), 0.001f)
        assertEquals(72f, koStaggerDelayMs(3, 400), 0.001f)
        assertEquals(72f, koStaggerDelayMs(9, 400), 0.001f)
    }

    @Test fun springGetsFasterAsTheGapCloses() {
        val slow = koLyricSpringForInterval(null)
        assertEquals(90f, slow.stiffness, 0.001f)
        assertEquals(15f / (2f * kotlin.math.sqrt(90f)), slow.dampingRatio, 0.001f)

        val far = koLyricSpringForInterval(800)
        val near = koLyricSpringForInterval(100)
        assertEquals(170f, far.stiffness, 0.001f)
        assertEquals(220f, near.stiffness, 0.001f)
        assertEquals(1.1f, near.dampingRatio, 0.001f)
        assertTrue(near.stiffness > far.stiffness)
        // Outside the 100..800 window AMLL falls back to the slow spring.
        assertEquals(90f, koLyricSpringForInterval(99).stiffness, 0.001f)
        assertEquals(90f, koLyricSpringForInterval(801).stiffness, 0.001f)
    }

    @Test fun longWordRuleIsDurationFirst() {
        fun held(text: String, holdMs: Long) =
            LyricLine(0, text, words = listOf(LyricWord(0, holdMs, text)))

        // max(900 ms, 200 ms x chars): five letters need 1000 ms, seven 1400.
        assertTrue(held("hello", 1_000).growingWords.isNotEmpty())
        assertTrue(held("hello", 999).growingWords.isEmpty())
        assertTrue(held("seventy", 1_400).growingWords.isNotEmpty())
        assertTrue(held("seventy", 1_399).growingWords.isEmpty())
        // Four letters keep the 1050 ms floor the panel already had.
        assertTrue(held("word", 1_050).growingWords.isNotEmpty())
        assertTrue(held("word", 1_049).growingWords.isEmpty())
        // Over seven letters is a ripple, not a held word.
        assertTrue(held("eightletters", 5_000).growingWords.isEmpty())
    }
}
