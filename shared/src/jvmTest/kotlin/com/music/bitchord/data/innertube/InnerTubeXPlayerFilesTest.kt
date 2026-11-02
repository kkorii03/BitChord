package com.music.bitchord.data.innertube

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 11-02 heap report: cipher caches held more than one player version. The
 * persistent preprocessed players keep only the newest one now.
 */
class InnerTubeXPlayerFilesTest {

    @Test
    fun onlyTheNewestPreprocessedPlayerSurvives() {
        val dir = Files.createTempDirectory("bitchord-itx-players").toFile()
        try {
            val old = File(dir, "old").apply {
                writeText("old player")
                setLastModified(1_000L)
            }
            val newest = File(dir, "new").apply {
                writeText("new player")
                setLastModified(2_000L)
            }
            val temp = File(dir, "new.tmp").apply { writeText("writing") }

            trimPlayerFiles(dir, keep = 1)

            assertFalse(old.exists())
            assertTrue(newest.exists())
            assertTrue(temp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
