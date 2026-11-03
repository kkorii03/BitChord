package com.music.bitchord.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.9 merge rewrote the playlist picker and moved the download manager
 * from a sheet to a page. Two calls the old surfaces made did not come along:
 * the failure notice when YouTube refuses a new playlist, and the retry that
 * reuses a failed download's original plan (quality, pinned pair, replace
 * intent). Neither is visible to a pure function test, so the wiring is read.
 */
class LibraryRegressionWiringTest {

    @Test
    fun `creating a playlist still reports a refusal`() {
        val source = mainActivitySource()
        val onlineCreate = source
            .substringAfter("onCreate = { title, privacy ->")
            .substringBefore("// ---- Add to a device playlist")

        assertTrue(
            "the picker's create must pass onFailure so a refused playlist is not silent",
            onlineCreate.contains("createPlaylist(title, privacy, target)"),
        )
        assertTrue(
            "a refused create must say playlist_action_failed",
            onlineCreate.contains("playlist_action_failed"),
        )
    }

    @Test
    fun `the download manager retries with the original plan and shows HQ progress`() {
        val page = downloadManagerPageSource()

        assertTrue(
            "a failed row must retry through Downloads.retry so its quality/source plan survives",
            page.contains("Downloads.retry(context, item)"),
        )
        assertFalse(
            "retrying with enqueue(song) drops the recorded high-quality/source plan",
            page.contains("Downloads.enqueue(context, item.song"),
        )
        assertTrue(
            "the high-quality pass's progress line must stay on the download manager page",
            page.contains("hqProgress.visible"),
        )
    }

    private fun downloadManagerPageSource(): String {
        val source = projectFile(
            "app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt",
            "src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt",
        ).readText()
        return source
            .substringAfter("fun DownloadManagerPage(")
            .substringBefore("@Composable\nfun DownloadManagerSheet(")
    }

    private fun mainActivitySource(): String = projectFile(
        "app/src/main/java/com/music/bitchord/MainActivity.kt",
        "src/main/java/com/music/bitchord/MainActivity.kt",
    ).readText()

    private fun projectFile(withRoot: String, withoutRoot: String): File {
        val projectRoot = File(".").canonicalFile
        val path = if (File(projectRoot, "app/src/main").exists()) withRoot else withoutRoot
        return File(projectRoot, path)
    }
}
