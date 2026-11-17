package com.music.bitchord

import com.music.bitchord.ko.ui.StartupDeferredWork
import com.music.bitchord.ko.ui.startupDeferredWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch timer may do the small account/update work, but never a feed:
 * Explore and Library belong to the tab that shows them.
 */
class StartupDeferredWorkTest {

    @Test
    fun signedInLaunchDefersAccountLikedIdsAndUpdateCheck() {
        assertEquals(
            listOf(
                StartupDeferredWork.ACCOUNT,
                StartupDeferredWork.LIKED_IDS,
                StartupDeferredWork.UPDATE_CHECK,
            ),
            startupDeferredWork(signedIn = true),
        )
    }

    /**
     * Review F2b-1: the Library tab was the only thing that told the hearts
     * which songs are liked, so until it was opened a liked song showed an
     * empty heart and the first tap sent LIKE again instead of unliking.
     */
    @Test
    fun signedInLaunchSeedsLikedIdsWithoutTheLibraryTab() {
        assertTrue(StartupDeferredWork.LIKED_IDS in startupDeferredWork(signedIn = true))
        assertFalse(StartupDeferredWork.LIKED_IDS in startupDeferredWork(signedIn = false))
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
