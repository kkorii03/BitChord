package com.music.bitchord

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deferred launch work exists as [com.music.bitchord.ko.ui.StartupDeferredWork]
 * and is covered on its own, but the 1.9 merge dropped the call that ran it:
 * a launch never checked for updates and fetched Library/Playlists eagerly.
 * This checks the wiring, which no pure function test can see.
 */
class StartupLaunchWiringTest {

    @Test
    fun `launch runs the deferred account and update work`() {
        val source = mainViewModelSource()

        assertTrue(
            "launch must run startupDeferredWork so the update check and account load are not lost",
            source.contains("for (work in startupDeferredWork(_signedIn.value))"),
        )
        assertTrue(
            "the once-a-day update check must still run at launch",
            source.contains("AppUpdateChecker.checkDaily(getApplication())"),
        )
        assertTrue(
            "the deferred work belongs behind STARTUP_DEFER_MS",
            source.contains("delay(STARTUP_DEFER_MS)"),
        )
        assertTrue(
            "the deferred ACCOUNT work must actually load the account",
            source.contains("StartupDeferredWork.ACCOUNT -> loadAccount()"),
        )
        assertTrue(
            "the deferred UPDATE_CHECK work must stay wired to the update check",
            source.contains("StartupDeferredWork.UPDATE_CHECK ->"),
        )
    }

    /**
     * Review F2b-1: the launch seeds the liked ids itself, so a heart is right
     * before the Library tab is ever opened.
     */
    @Test
    fun `launch seeds the liked ids without loading the library feed`() {
        val source = mainViewModelSource()

        assertTrue(
            "the deferred LIKED_IDS work must be wired in the launch",
            source.contains("StartupDeferredWork.LIKED_IDS -> koLoadLikedIds()"),
        )
        val body = source.substringAfter("private fun koLoadLikedIds()").substringBefore("\n    }")
        assertTrue(
            "LIKED_IDS reads Liked Music only",
            body.contains("YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC)"),
        )
        assertTrue("LIKED_IDS seeds LikeState", body.contains("LikeState.seedLiked("))
        assertTrue("LIKED_IDS follows the rest of the collection", body.contains("syncLikedMusic(identity, token)"))
        assertTrue("LIKED_IDS stops for another listener", body.contains("identity != listenerKey()"))
        assertTrue("LIKED_IDS must not publish the Library feed", !body.contains("loadLibrary()") && !body.contains("_library.value ="))
    }

    @Test
    fun `library and playlists are still fetched, just when the tab is shown`() {
        val source = mainViewModelSource()
        val shown = source.substringAfter("fun onLibraryShown()").substringBefore("\n    }")

        assertTrue(
            "onLibraryShown must fetch the Library the launch no longer fetches eagerly",
            shown.contains("loadLibrary()"),
        )
        assertTrue(
            "onLibraryShown must fetch the playlists the launch no longer fetches eagerly",
            shown.contains("loadPlaylists()"),
        )
    }

    private fun mainViewModelSource(): String {
        val projectRoot = File(".").canonicalFile
        val path = if (File(projectRoot, "app/src/main").exists()) {
            "app/src/main/java/com/music/bitchord/ui/MainViewModel.kt"
        } else {
            "src/main/java/com/music/bitchord/ui/MainViewModel.kt"
        }
        return File(projectRoot, path).readText()
    }
}