package com.music.bitchord.data.innertube.potoken

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A cold BotGuard page can take longer than the old eight-second mint window
 * on a slow connection. The page's own init budget is forty-five seconds, so
 * the outer timeout must not give up first: that made every attempt fail
 * before the page had a chance to finish.
 */
class PoTokenSlowCreationTest {

    private class FakeMinter : PoTokenMinter {
        override val isExpired: Boolean = false
        override val isDead: Boolean = false

        override suspend fun generatePoToken(identifier: String): String = "pot"

        override fun close() = Unit
    }

    @Test
    fun `a page that takes longer than eight seconds still mints`() = runTest {
        var creates = 0
        val generator = PoTokenGenerator(createMinter = {
            delay(10_000)
            creates++
            FakeMinter()
        })

        val token = generator.getWebClientPoToken("track", "session")

        assertNotNull(token)
        assertEquals(1, creates)
    }

    @Test
    fun `a page that uses its whole init budget still mints`() = runTest {
        var creates = 0
        val generator = PoTokenGenerator(createMinter = {
            // The platform browser's own init ceiling is 45s; the outer timeout
            // must not fire at the same instant and cancel the first mint.
            delay(46_000)
            creates++
            FakeMinter()
        })

        val token = generator.getWebClientPoToken("track", "session")

        assertNotNull(token)
        assertEquals(1, creates)
    }
}
