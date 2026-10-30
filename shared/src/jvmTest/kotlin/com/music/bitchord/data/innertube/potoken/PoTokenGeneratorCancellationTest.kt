package com.music.bitchord.data.innertube.potoken

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * A cancelled mint is the caller moving on, not a broken BotGuard page. It
 * must propagate without recreating the WebView: recreation spends another
 * page load after the work was already abandoned.
 */
class PoTokenGeneratorCancellationTest {

    private class FakeMinter : PoTokenMinter {
        override val isExpired: Boolean = false
        override val isDead: Boolean = false

        override suspend fun generatePoToken(identifier: String): String {
            if (identifier == "video") throw CancellationException("caller moved on")
            return "pot"
        }

        override fun close() = Unit
    }

    @Test
    fun `a cancelled mint on a reused page is not retried by recreating`() = runBlocking {
        var creates = 0
        val generator = PoTokenGenerator(createMinter = {
            creates++
            FakeMinter()
        })

        // First call creates the page and mints a token for a normal track.
        generator.getWebClientPoToken("track", "session")
        assertEquals(1, creates)

        assertFailsWith<CancellationException> {
            generator.getWebClientPoToken("video", "session")
        }
        assertEquals("a cancelled mint must not rebuild the page", 1, creates)
    }
}
