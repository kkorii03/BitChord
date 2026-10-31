package com.music.bitchord

import com.music.bitchord.telegram.TelegramAuthStage
import com.music.bitchord.telegram.TelegramFileNames
import com.music.bitchord.telegram.TelegramUpgradeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review corrections for stage three: a resting client needs a persisted
 * login marker before it may join automatic walks, and file names are only
 * stripped of recognised audio suffixes and real track numbers.
 */
class TelegramSourceStageThreeReviewTest {

    @Test
    fun `a closed or starting client without a completed login is signed out`() {
        for (stage in listOf(TelegramAuthStage.CLOSED, TelegramAuthStage.STARTING)) {
            assertEquals(
                TelegramUpgradeGate.SIGNED_OUT,
                TelegramUpgradeGate.ineligibleReason(
                    stage = stage,
                    selectedChats = 2,
                    enabled = true,
                    validatedSession = false,
                ),
            )
            assertFalse(
                TelegramUpgradeGate.isEligible(
                    stage = stage,
                    selectedChats = 2,
                    enabled = true,
                    validatedSession = false,
                ),
            )
        }
    }

    @Test
    fun `a stored login keeps a resting client eligible for automatic walks`() {
        for (stage in listOf(TelegramAuthStage.CLOSED, TelegramAuthStage.STARTING)) {
            assertTrue(
                TelegramUpgradeGate.isEligible(
                    stage = stage,
                    selectedChats = 2,
                    enabled = true,
                    validatedSession = true,
                ),
            )
        }
        assertEquals(
            TelegramUpgradeGate.SIGNED_OUT,
            TelegramUpgradeGate.ineligibleReason(
                stage = TelegramAuthStage.CLOSED,
                selectedChats = 0,
                enabled = true,
                validatedSession = true,
            ),
        )
    }

    @Test
    fun `a dot in an extensionless title is not an audio extension`() {
        val parsed = TelegramFileNames.parse("The Killers - Mr. Brightside")

        assertEquals("The Killers", parsed?.artist)
        assertEquals("Mr. Brightside", parsed?.title)
    }

    @Test
    fun `an all digit artist is not mistaken for a track number`() {
        val parsed = TelegramFileNames.parse("112 - Peaches & Cream.flac")

        assertEquals("112", parsed?.artist)
        assertEquals("Peaches & Cream", parsed?.title)
    }

    @Test
    fun `a real track number is still dropped when a separator remains`() {
        val parsed = TelegramFileNames.parse("07. The Killers - Mr. Brightside.flac")

        assertEquals("The Killers", parsed?.artist)
        assertEquals("Mr. Brightside", parsed?.title)
    }

    @Test
    fun `an unknown suffix is part of the title not an extension`() {
        val parsed = TelegramFileNames.parse("The Killers - Mr. Brightside.demo")

        assertEquals("The Killers", parsed?.artist)
        assertEquals("Mr. Brightside.demo", parsed?.title)
        assertNull(TelegramFileNames.parse("Mr. Brightside.flac"))
    }
}
