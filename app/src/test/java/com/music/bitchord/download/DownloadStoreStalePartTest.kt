package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A download killed by process death leaves its `.part` file behind. The next
 * attempt for that name removes it, but a track never asked for again would
 * keep the bytes forever; [DownloadStore.begin] sweeps only parts old enough
 * that no live transfer can own them.
 */
class DownloadStoreStalePartTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `only parts older than the live-transfer window are swept`() {
        val now = 1_700_000_000_000L
        val old = File(tmp.root, "Artist - Old.flac.part").apply {
            writeText("old")
            setLastModified(now - 25L * 60 * 60 * 1000)
        }
        val live = File(tmp.root, "Artist - Live.flac.part").apply {
            writeText("live")
            setLastModified(now - 60_000)
        }
        val finished = File(tmp.root, "Artist - Song.flac").apply {
            writeText("finished")
            setLastModified(now - 48L * 60 * 60 * 1000)
        }

        val stale = DownloadStore.staleParts(tmp.root, now)

        assertEquals(listOf(old), stale)
        assertEquals(true, live.exists())
        assertEquals(true, finished.exists())
    }
}
