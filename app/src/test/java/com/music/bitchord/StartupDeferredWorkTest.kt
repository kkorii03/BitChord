package com.music.bitchord

import com.music.bitchord.ui.StartupDeferredWork
import com.music.bitchord.ui.startupDeferredWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The launch timer may do the small account/update work, but never a feed:
 * Explore and Library belong to the tab that shows them.
 */
class StartupDeferredWorkTest {

    @Test
    fun signedInLaunchDefersOnlyAccountAndUpdateCheck() {
        assertEquals(
            listOf(StartupDeferredWork.ACCOUNT, StartupDeferredWork.UPDATE_CHECK),
            startupDeferredWork(signedIn = true),
        )
    }

    @Test
    fun guestLaunchDoesNotAskForAnAccount() {
        assertEquals(
            listOf(StartupDeferredWork.UPDATE_CHECK),
            startupDeferredWork(signedIn = false),
        )
    }

    @Test
    fun feedsAreNeverPartOfTheLaunchTimer() {
        val names = startupDeferredWork(signedIn = true).map { it.name }
        assertFalse("Home", "HOME" in names)
        assertFalse("Explore", "EXPLORE" in names)
        assertFalse("Library", "LIBRARY" in names)
    }
}
