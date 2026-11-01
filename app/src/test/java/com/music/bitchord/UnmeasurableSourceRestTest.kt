package com.music.bitchord

import com.music.bitchord.data.sources.SourceResolver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A source whose candidates cannot be measured on several different tracks is
 * parked, so the listener's Deezer addon (403 on every download) and a
 * preview-only source stop costing every upgrade a search and a download.
 */
class UnmeasurableSourceRestTest {

    private val cooldown = SourceResolver.SourceCooldown

    @Before
    fun reset() = cooldown.resetForTest()

    @After
    fun cleanUp() = cooldown.resetForTest()

    @Test
    fun `three different unmeasurable tracks park the source`() {
        val now = 1_000_000L
        cooldown.unmeasured("deezer", "t1", "HTTP 403", now)
        cooldown.unmeasured("deezer", "t2", "HTTP 403", now)
        assertFalse(cooldown.isUnmeasurable("deezer", now))
        cooldown.unmeasured("deezer", "t3", "HTTP 403", now)
        assertTrue(cooldown.isUnmeasurable("deezer", now))
    }

    @Test
    fun `the same track three times is not three tracks`() {
        val now = 1_000_000L
        repeat(3) { cooldown.unmeasured("deezer", "t1", "HTTP 403", now) }
        assertFalse(cooldown.isUnmeasurable("deezer", now))
    }

    @Test
    fun `a measured candidate starts the count again`() {
        val now = 1_000_000L
        cooldown.unmeasured("deezer", "t1", "HTTP 403", now)
        cooldown.unmeasured("deezer", "t2", "HTTP 403", now)
        cooldown.measured("deezer")
        cooldown.unmeasured("deezer", "t3", "HTTP 403", now)
        assertFalse(cooldown.isUnmeasurable("deezer", now))
    }

    @Test
    fun `the rest ends after six hours and a settings change ends it at once`() {
        val now = 1_000_000L
        listOf("t1", "t2", "t3").forEach { cooldown.unmeasured("lastwave", it, "preview", now) }
        assertTrue(cooldown.isUnmeasurable("lastwave", now + 5 * 3_600_000L))
        assertFalse(cooldown.isUnmeasurable("lastwave", now + 6 * 3_600_000L + 1))

        listOf("t1", "t2", "t3").forEach { cooldown.unmeasured("deezer", it, "HTTP 403", now) }
        cooldown.clear("deezer")
        assertFalse(cooldown.isUnmeasurable("deezer", now))
    }

    @Test
    fun `another source is not affected`() {
        val now = 1_000_000L
        listOf("t1", "t2", "t3").forEach { cooldown.unmeasured("deezer", it, "HTTP 403", now) }
        assertFalse(cooldown.isUnmeasurable("qobuz", now))
    }
}
