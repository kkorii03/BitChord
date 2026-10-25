package com.music.bitchord.playback

import com.music.bitchord.playback.TranslationAheadPolicy.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationAheadPolicyTest {

    @Test
    fun `metered and saving power prepares nothing`() {
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(
                metered = true,
                lowPower = true,
                allowHeavy = false,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `metered without low power gets the cheap lyrics pass`() {
        assertEquals(
            Plan.LYRICS_ONLY,
            TranslationAheadPolicy.plan(
                metered = true,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi gets the whole pass without a charger`() {
        assertEquals(
            Plan.FULL,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi while charging still relaxes low power`() {
        assertEquals(
            Plan.FULL,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = true,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi in low power off the charger only fetches lyrics`() {
        assertEquals(
            Plan.LYRICS_ONLY,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = true,
                allowHeavy = false,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `no network means nothing to fetch`() {
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(
                metered = null,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `an autoplay slot that may be replaced is not prepared`() {
        assertEquals(
            Plan.NONE,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = false,
                allowHeavy = true,
                autoplayUncertain = true,
            ),
        )
    }
}
