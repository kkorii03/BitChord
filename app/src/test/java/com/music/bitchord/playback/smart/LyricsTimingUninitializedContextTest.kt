package com.music.bitchord.playback.smart

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * A timing check must not crash when the context has no application context or
 * files directory yet. This raced a cancelled measurement job against a test
 * that resets the store, and the uncaught NPE was reported in the next test.
 */
class LyricsTimingUninitializedContextTest {

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    @Before
    fun clearStore() {
        storeField.set(LyricsTiming, null)
    }

    @After
    fun restoreStore() {
        storeField.set(LyricsTiming, null)
    }

    @Test
    fun `an uninitialised context does not crash the measurement check`() = runBlocking {
        assertFalse(LyricsTiming.measured(Application(), "no-context"))
    }
}
