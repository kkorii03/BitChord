package com.music.bitchord.data.lyrics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsOverrideFileTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("lyrics-overrides").toFile()
        return File(dir, LyricsOverrideFile.FILE_NAME).also { file ->
            file.deleteOnExit()
            file.parentFile?.deleteOnExit()
        }
    }

    @Test
    fun `an override round-trips its lines, source, time and kind`() {
        val file = tempFile()
        val lrc = "[00:01.20]first line here\n[00:04.50]second line here"

        assertTrue(
            LyricsOverrideFile.write(
                file = file,
                videoId = "PMivT7MJ41M",
                lines = LrcLib.parseLrc(lrc),
                source = LyricsSource.LRCLIB,
                savedAt = 1_700_000_000_000L,
                kind = LyricsOverrideFile.Kind.RECHECK,
            ),
        )

        val stored = requireNotNull(LyricsOverrideFile.read(file, "PMivT7MJ41M"))
        assertEquals(listOf("first line here", "second line here"), stored.lines.map { it.text })
        assertEquals(listOf(1_200L, 4_500L), stored.lines.map { it.timeMs })
        assertEquals(LyricsSource.LRCLIB, stored.source)
        assertEquals(1_700_000_000_000L, stored.savedAt)
        assertEquals(LyricsOverrideFile.Kind.RECHECK, stored.kind)
    }

    @Test
    fun `the exact lines and word timings come back`() {
        val file = tempFile()
        val lines = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "hello world",
                words = listOf(
                    LyricWord(1_000L, 1_500L, "hello"),
                    LyricWord(1_600L, 2_200L, "world"),
                ),
                sungUntilMs = 2_300L,
                alignment = LyricAlignment.End,
            ),
        )

        assertTrue(LyricsOverrideFile.write(file, "id", lines, LyricsSource.LRCLIB))

        assertEquals(lines, LyricsOverrideFile.read(file, "id")?.lines)
    }

    @Test
    fun `overrides are kept per video id`() {
        val file = tempFile()

        assertTrue(
            LyricsOverrideFile.write(
                file, "one", LrcLib.parseLrc("[00:01.00]one line"), LyricsSource.LRCLIB,
                kind = LyricsOverrideFile.Kind.RECHECK,
            ),
        )
        assertTrue(
            LyricsOverrideFile.write(
                file, "two", LrcLib.parseLrc("[00:02.00]two line"), LyricsSource.MUSIXMATCH,
                kind = LyricsOverrideFile.Kind.USER,
            ),
        )

        assertEquals("one line", LyricsOverrideFile.read(file, "one")?.lines?.single()?.text)
        assertEquals(LyricsSource.MUSIXMATCH, LyricsOverrideFile.read(file, "two")?.source)
        assertEquals(LyricsOverrideFile.Kind.USER, LyricsOverrideFile.read(file, "two")?.kind)
    }

    @Test
    fun `a user override stays marked as the listener's`() {
        val file = tempFile()

        LyricsOverrideFile.write(
            file, "id", LrcLib.parseLrc("[00:01.00]edited"), null,
            kind = LyricsOverrideFile.Kind.USER,
        )

        val stored = requireNotNull(LyricsOverrideFile.read(file, "id"))
        assertEquals(LyricsOverrideFile.Kind.USER, stored.kind)
        assertNull(stored.source)
    }

    @Test
    fun `missing, blank and corrupt entries are no override`() {
        val file = tempFile()

        assertNull(LyricsOverrideFile.read(file, "missing"))

        LyricsOverrideFile.write(file, "blank", listOf(LyricLine(0L, "   ")), LyricsSource.LRCLIB)
        assertNull(LyricsOverrideFile.read(file, "blank"))

        file.writeText("not json")
        assertNull(LyricsOverrideFile.read(file, "blank"))
    }

    @Test
    fun `removing one override leaves the others`() {
        val file = tempFile()
        LyricsOverrideFile.write(file, "one", LrcLib.parseLrc("[00:01.00]one"), LyricsSource.LRCLIB)
        LyricsOverrideFile.write(file, "two", LrcLib.parseLrc("[00:02.00]two"), LyricsSource.LRCLIB)

        assertTrue(LyricsOverrideFile.remove(file, "one"))
        assertNull(LyricsOverrideFile.read(file, "one"))
        assertEquals("two", LyricsOverrideFile.read(file, "two")?.lines?.single()?.text)
        assertFalse(LyricsOverrideFile.remove(file, "one"))
    }

    @Test
    fun `the override comes before the file and an online answer`() {
        assertEquals("override", LyricsOverrideFile.choose("override", "file", "online"))
        assertEquals("file", LyricsOverrideFile.choose(null, "file", "online"))
        assertEquals("online", LyricsOverrideFile.choose(null, null, "online"))
        assertNull(LyricsOverrideFile.choose(null, null, null))
    }
}

class LyricsOverrideFileResetTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("lyrics-overrides-reset").toFile()
        return File(dir, LyricsOverrideFile.FILE_NAME).also { file ->
            file.deleteOnExit()
            file.parentFile?.deleteOnExit()
        }
    }

    @Test
    fun `a replaced file drops the recheck override`() {
        val file = tempFile()
        LyricsOverrideFile.write(
            file, "id", LrcLib.parseLrc("[00:01.00]old recheck"), LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.RECHECK,
        )

        assertTrue(LyricsOverrideFile.removeRecheck(file, "id"))
        assertNull(LyricsOverrideFile.read(file, "id"))
    }

    @Test
    fun `a replaced file keeps the listener's own override`() {
        val file = tempFile()
        LyricsOverrideFile.write(
            file, "id", LrcLib.parseLrc("[00:01.00]mine"), LyricsSource.LRCLIB,
            kind = LyricsOverrideFile.Kind.USER,
        )

        assertFalse(LyricsOverrideFile.removeRecheck(file, "id"))
        assertEquals("mine", LyricsOverrideFile.read(file, "id")?.lines?.single()?.text)
        assertEquals(LyricsOverrideFile.Kind.USER, LyricsOverrideFile.read(file, "id")?.kind)
    }

    @Test
    fun `override lyrics are never treated as the file's own timing`() {
        assertFalse(LyricsOverrideFile.timingIsFilesOwn(hasOverride = true, localUri = true))
        assertTrue(LyricsOverrideFile.timingIsFilesOwn(hasOverride = false, localUri = true))
        assertFalse(LyricsOverrideFile.timingIsFilesOwn(hasOverride = false, localUri = false))
    }
}
