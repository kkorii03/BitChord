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
                charging = false,
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
                charging = false,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi while charging gets the whole pass`() {
        assertEquals(
            Plan.FULL,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = false,
                charging = true,
                autoplayUncertain = false,
            ),
        )
        assertEquals(
            Plan.FULL,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = true,
                charging = true,
                autoplayUncertain = false,
            ),
        )
    }

    @Test
    fun `wifi off the charger only fetches lyrics`() {
        assertEquals(
            Plan.LYRICS_ONLY,
            TranslationAheadPolicy.plan(
                metered = false,
                lowPower = false,
                charging = false,
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
                charging = true,
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
                charging = true,
                autoplayUncertain = true,
            ),
        )
    }
}
