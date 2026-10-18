package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the whole sink matrix rather than one call site: private listening must
 * silence every history-bearing write, and ordinary listening must leave all of
 * them alone.
 */
class PrivateListeningTest {

    @Test
    fun `every sink records when private listening is off`() {
        ListeningSink.entries.forEach { sink ->
            assertTrue(
                PrivateListening.allows(sink, privateListening = false),
                "$sink must be recorded outside private listening",
            )
        }
    }

    @Test
    fun `no sink records while private listening is on`() {
        ListeningSink.entries.forEach { sink ->
            assertFalse(
                PrivateListening.allows(sink, privateListening = true),
                "$sink must be silent in private listening",
            )
        }
    }

    @Test
    fun `youtube history is never written in private listening`() {
        assertFalse(
            PrivateListening.allows(ListeningSink.YOUTUBE_HISTORY, privateListening = true),
        )
    }

    @Test
    fun `search history is never written in private listening`() {
        assertFalse(
            PrivateListening.allows(ListeningSink.SEARCH_HISTORY, privateListening = true),
        )
    }
}
