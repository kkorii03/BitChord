package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A provider chain must keep trying its remaining mirrors/candidates after one
 * step is refused. Before this, [LyricsHttpStatusException] escaped
 * [ProviderAttempts.orNull] (which only caught IOException) and ended the whole
 * chain even though another candidate could have answered.
 */
class ProviderAttemptsStatusTest {

    @Test
    fun aRefusalInOneStepDoesNotEndTheChain() {
        val attempts = ProviderAttempts()
        val refused = attempts.orNull<String> { throw LyricsHttpStatusException(403) }
        assertNull(refused)

        val answered = attempts.orNull { "from another mirror" }
        assertEquals("from another mirror", attempts.ifEmptyThrow(refused ?: answered))
    }

    @Test
    fun aRefusalIsSurfacedWhenNoStepAnswers() {
        val attempts = ProviderAttempts()
        assertNull(attempts.orNull<String> { throw LyricsHttpStatusException(429) })

        val error = assertFailsWith<LyricsHttpStatusException> {
            attempts.ifEmptyThrow<String>(null)
        }
        assertEquals(429, error.code)
    }
}
