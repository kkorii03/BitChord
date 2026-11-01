package com.music.bitchord

import com.music.bitchord.ui.screens.libraryOffersNewPlaylist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A signed-out Library used to draw a New playlist tile that could not create
 * anything, because the write needs an account. The import tile is different:
 * it saves to this device, so it stays.
 */
class SignedOutLibraryActionsTest {

    @Test
    fun `signed out library does not offer the new playlist tile`() {
        assertFalse(libraryOffersNewPlaylist(signedIn = false))
    }

    @Test
    fun `signed in library still offers the new playlist tile`() {
        assertTrue(libraryOffersNewPlaylist(signedIn = true))
    }
}
