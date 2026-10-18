package com.music.bitchord.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the cellular quality default means for the two installs that can reach
 * it: a fresh one gets High, and an existing one keeps hearing the Lossless it
 * already had. The listener's own stored choice never reaches this function.
 */
class CellularQualityDefaultTest {

    @Test
    fun `fresh install defaults to high on cellular`() {
        assertEquals(AudioQuality.HIGH, cellularQualityDefault(existingInstall = false))
    }

    @Test
    fun `existing install keeps the old lossless default`() {
        assertEquals(AudioQuality.LOSSLESS, cellularQualityDefault(existingInstall = true))
    }
}
