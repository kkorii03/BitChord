package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.ListenTogether
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a party switch leaves behind in the two places that outlive the session:
 * the custom-server preference and the address a stale slot is released on.
 */
class PartyServerPreferenceTest {

    @Test
    fun `switching to the built-in server never stores it as a custom one`() {
        // The built-in address is deliberately unpublished. Copying it into the
        // custom-server preference made the editor show it as if the listener
        // had typed it, and offered a Remove button for a server they cannot see.
        assertEquals(
            "",
            ListenTogether.serverPreferenceForSwitch(ListenTogether.defaultServer),
        )
    }

    @Test
    fun `switching to a custom server keeps it verbatim`() {
        assertEquals(
            "https://party.example.com",
            ListenTogether.serverPreferenceForSwitch("https://party.example.com"),
        )
    }

    @Test
    fun `a trailing slash does not make the built-in server look custom`() {
        assertEquals(
            "",
            ListenTogether.serverPreferenceForSwitch("${ListenTogether.defaultServer}/"),
        )
    }

    @Test
    fun `a blank target clears the preference`() {
        assertEquals("", ListenTogether.serverPreferenceForSwitch(""))
    }

    @Test
    fun `a stale slot is released on the server that actually hosted it`() {
        // A party can be hosted on the built-in server while a custom server is
        // configured (creation fell back). After process death, the idle routing
        // must not be allowed to send the leave to the custom host.
        assertEquals(
            "https://party.example.com",
            ListenTogether.staleSlotReleaseServer(
                savedServer = "https://party.example.com",
                activeServer = null,
            ),
        )
        assertEquals(
            "https://party.example.com",
            ListenTogether.staleSlotReleaseServer(
                savedServer = null,
                activeServer = "https://party.example.com/",
            ),
        )
    }

    @Test
    fun `no saved or active server means there is nowhere to release`() {
        assertNull(ListenTogether.staleSlotReleaseServer(null, null))
        assertNull(ListenTogether.staleSlotReleaseServer("  ", ""))
        assertNotNull(ListenTogether.staleSlotReleaseServer("", "https://party.example.com"))
    }
}
