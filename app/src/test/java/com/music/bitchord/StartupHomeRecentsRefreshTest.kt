package com.music.bitchord

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recents refreshes for a guest too. Its local half is the device's own plays
 * ([com.music.bitchord.data.YtMusicRepository.recentlyPlayed] reads them for
 * any listener), and 1.8 always asked for it on a Home refresh. The v1.9 merge
 * put that request behind `_signedIn`, so a guest's pull-to-refresh left the
 * shelf alone even though [com.music.bitchord.ui.MainViewModel.loadHome] still
 * fetches it for a guest.
 */
class StartupHomeRecentsRefreshTest {

    @Test
    fun `a home refresh fetches recents without an account`() {
        val refresh = refreshHomeSource()

        assertTrue(
            "refreshHome must ask for recents so the local shelf follows the device",
            refresh.contains("val recent = async { YtMusicRepository.homeRecentlyPlayed() }"),
        )
        assertFalse(
            "recents is local-first and must not be gated on the account",
            refresh.contains("if (_signedIn.value) async"),
        )
        assertTrue(
            "the recents answer must still be awaited",
            refresh.contains("recent.await().onSuccess"),
        )
    }

    private fun refreshHomeSource(): String {
        val source = mainViewModelSource()
        return source.substringAfter("private suspend fun refreshHome(")
            .substringBefore("fun loadMoreHome()")
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
