package com.music.bitchord.data.innertube

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A process can be started in the background without a screen or a playback
 * request. The cold InnerTubeX walk belongs to the first screen or the first
 * play, not to a session refresh on its own.
 */
class InnerTubeXWarmupDeferralTest {

    private lateinit var dir: File

    private val store = object : InnerTubeXResolver.ConfigStore {
        private val strings = mutableMapOf<String, String>()
        private val longs = mutableMapOf<String, Long>()

        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String): Long? = longs[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bitchord-itx-warmup").toFile()
        InnerTubeXResolver.resetWarmForTest()
    }

    @AfterTest
    fun tearDown() {
        InnerTubeXResolver.resetWarmForTest()
        dir.deleteRecursively()
    }

    @Test
    fun `a session change before any screen or playback does not start the walk`() {
        InnerTubeXResolver.init(dir, store, null)

        InnerTubeXResolver.onSessionChanged()

        assertFalse(InnerTubeXResolver.warmStartedForTest())
    }

    @Test
    fun `a session change after the warm-up started still refreshes it`() {
        assertTrue(InnerTubeXResolver.shouldWarmOnSessionChanged(warmStarted = true))
        assertFalse(InnerTubeXResolver.shouldWarmOnSessionChanged(warmStarted = false))
    }
}
