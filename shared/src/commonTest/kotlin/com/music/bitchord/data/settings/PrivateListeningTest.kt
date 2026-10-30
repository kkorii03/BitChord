package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the whole sink matrix rather than one call site: private mode silences
 * only writes that leave the device, while local history, recommendations and
 * the in-app search list keep recording.
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
    fun `local sinks record while private listening is on`() {
        listOf(
            ListeningSink.LISTENING_STATS,
            ListeningSink.TASTE,
            ListeningSink.SEARCH_HISTORY,
        ).forEach { sink ->
            assertTrue(
                PrivateListening.allows(sink, privateListening = true),
                "$sink is local and must keep recording",
            )
        }
    }

    @Test
    fun `account sinks stay silent while private listening is on`() {
        listOf(
            ListeningSink.YOUTUBE_HISTORY,
            ListeningSink.SCROBBLING,
            ListeningSink.DISCORD_PRESENCE,
        ).forEach { sink ->
            assertFalse(
                PrivateListening.allows(sink, privateListening = true),
                "$sink leaves the device and must stay silent",
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
    fun `search history keeps recording in private listening`() {
        assertTrue(
            PrivateListening.allows(ListeningSink.SEARCH_HISTORY, privateListening = true),
        )
    }

    @Test
    fun `last chance scrobble stays silent in private listening`() {
        assertFalse(PrivateListening.allowsLastChanceScrobble(privateListening = true))
        assertTrue(PrivateListening.allowsLastChanceScrobble(privateListening = false))
    }
}
