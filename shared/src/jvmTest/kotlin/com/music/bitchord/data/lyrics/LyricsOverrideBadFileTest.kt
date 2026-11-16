package com.music.bitchord.data.lyrics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L9: an unreadable override file read as empty and the next write replaced
 * it, erasing every stored sheet (the listener's own picks too) with no trace.
 */
class LyricsOverrideBadFileTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("lyrics-overrides-bad").toFile()
        return File(dir, LyricsOverrideFile.FILE_NAME)
    }

    private val lines = LrcLib.parseLrc("[00:01.00]first line\n[00:02.00]second line")

    @Test
    fun aBrokenFileIsSetAsideBeforeTheNextWrite() {
        val file = tempFile()
        assertTrue(LyricsOverrideFile.write(file, "A", lines, LyricsSource.LRCLIB, kind = LyricsOverrideFile.Kind.USER))
        val good = file.readText()
        file.writeText(good.take(good.length / 2))
        val broken = file.readText()

        assertTrue(LyricsOverrideFile.write(file, "B", lines, LyricsSource.LRCLIB))

        val aside = File(file.parentFile, "${file.name}.bad")
        assertTrue(aside.isFile)
        assertEquals(broken, aside.readText())
        assertTrue(LyricsOverrideFile.read(file, "B") != null)
    }

    @Test
    fun aReadableFileLeavesNothingAside() {
        val file = tempFile()
        assertTrue(LyricsOverrideFile.write(file, "A", lines, LyricsSource.LRCLIB))
        assertTrue(LyricsOverrideFile.write(file, "B", lines, LyricsSource.LRCLIB))
        assertFalse(File(file.parentFile, "${file.name}.bad").exists())
        assertTrue(LyricsOverrideFile.read(file, "A") != null)
    }

    @Test
    fun aRestoreOfAnotherVersionIsRefused() {
        val file = tempFile()
        assertFalse(LyricsOverrideFile.restore(file, """{"version":99,"songs":{}}"""))
        assertFalse(file.exists())
    }
}
