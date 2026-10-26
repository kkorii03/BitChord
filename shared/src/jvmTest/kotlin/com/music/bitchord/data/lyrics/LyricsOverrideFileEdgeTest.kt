package com.music.bitchord.data.lyrics

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The override file's format edges: corrupt data, unknown names, blank entries and exact line round-trips. */
class LyricsOverrideFileEdgeTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("lyrics-overrides-edge").toFile()
        return File(dir, LyricsOverrideFile.FILE_NAME).also { file ->
            file.deleteOnExit()
            file.parentFile?.deleteOnExit()
        }
    }

    @Test
    fun chooseTakesTheFirstNonNullLayer() {
        assertEquals("override", LyricsOverrideFile.choose("override", "file", "online"))
        assertEquals("file", LyricsOverrideFile.choose(null, "file", "online"))
        assertEquals("online", LyricsOverrideFile.choose(null, null, "online"))
        assertNull(LyricsOverrideFile.choose(null, null, null))
        assertEquals("", LyricsOverrideFile.choose("", "file", "online"))
    }

    @Test
    fun kindNamesAreExactAndUnknownValuesAreRechecks() {
        assertEquals(LyricsOverrideFile.Kind.USER, LyricsOverrideFile.Kind.of("user"))
        assertEquals(LyricsOverrideFile.Kind.RECHECK, LyricsOverrideFile.Kind.of("recheck"))
        assertEquals(LyricsOverrideFile.Kind.RECHECK, LyricsOverrideFile.Kind.of("USER"))
        assertEquals(LyricsOverrideFile.Kind.RECHECK, LyricsOverrideFile.Kind.of(""))
        assertEquals(LyricsOverrideFile.Kind.RECHECK, LyricsOverrideFile.Kind.of(null))
        assertEquals(LyricsOverrideFile.Kind.RECHECK, LyricsOverrideFile.Kind.of("alien"))
        assertEquals("recheck", LyricsOverrideFile.Kind.RECHECK.value)
        assertEquals("user", LyricsOverrideFile.Kind.USER.value)
    }

    @Test
    fun aMissingFileHasNoOverride() {
        assertNull(LyricsOverrideFile.read(tempFile(), "id"))
    }

    @Test
    fun aCorruptFileHasNoOverride() {
        val file = tempFile()
        file.writeText("not json")
        assertNull(LyricsOverrideFile.read(file, "id"))
    }

    @Test
    fun aFileFromAnotherVersionIsIgnored() {
        val file = tempFile()
        file.writeText("""{"version":2,"songs":{}}""")
        assertNull(LyricsOverrideFile.read(file, "id"))
    }

    @Test
    fun aBlankVideoIdIsNeverWritten() {
        val file = tempFile()
        assertFalse(LyricsOverrideFile.write(file, "", listOf(LyricLine(0L, "line")), LyricsSource.LRCLIB))
        assertFalse(LyricsOverrideFile.write(file, "   ", listOf(LyricLine(0L, "line")), LyricsSource.LRCLIB))
        assertFalse(file.exists())
    }

    @Test
    fun emptyAndAllBlankLinesAreNeverWritten() {
        val file = tempFile()
        assertFalse(LyricsOverrideFile.write(file, "id", emptyList(), LyricsSource.LRCLIB))
        assertFalse(
            LyricsOverrideFile.write(
                file,
                "id",
                listOf(LyricLine(0L, " "), LyricLine(1_000L, "\t")),
                LyricsSource.LRCLIB,
            ),
        )
        assertFalse(file.exists())
    }

    @Test
    fun aBlankLineAmongRealOnesIsKept() {
        val file = tempFile()
        val lines = listOf(LyricLine(0L, " "), LyricLine(1_000L, "real"))
        assertTrue(LyricsOverrideFile.write(file, "id", lines, LyricsSource.LRCLIB))
        assertEquals(listOf(" ", "real"), LyricsOverrideFile.read(file, "id")?.lines?.map { it.text })
    }

    @Test
    fun aNestedParentDirectoryIsCreated() {
        val dir = Files.createTempDirectory("lyrics-overrides-nested").toFile()
        val file = File(File(dir, "one/two"), LyricsOverrideFile.FILE_NAME)
        assertTrue(LyricsOverrideFile.write(file, "id", listOf(LyricLine(0L, "line")), LyricsSource.LRCLIB))
        assertEquals("line", LyricsOverrideFile.read(file, "id")?.lines?.single()?.text)
    }

    @Test
    fun anUnknownSourceNameReadsBackAsNoSource() {
        val file = tempFile()
        assertTrue(LyricsOverrideFile.write(file, "id", listOf(LyricLine(0L, "line")), LyricsSource.LRCLIB))
        file.writeText(file.readText().replace("\"LRCLIB\"", "\"NOT_A_SOURCE\""))
        val stored = LyricsOverrideFile.read(file, "id")
        assertEquals("line", stored?.lines?.single()?.text)
        assertNull(stored?.source)
    }

    @Test
    fun anUnknownKindReadsBackAsARecheck() {
        val file = tempFile()
        assertTrue(
            LyricsOverrideFile.write(
                file,
                "id",
                listOf(LyricLine(0L, "line")),
                LyricsSource.LRCLIB,
                kind = LyricsOverrideFile.Kind.USER,
            ),
        )
        file.writeText(file.readText().replace("\"user\"", "\"alien\""))
        assertEquals(LyricsOverrideFile.Kind.RECHECK, LyricsOverrideFile.read(file, "id")?.kind)
    }

    @Test
    fun aLaterWriteForTheSameSongReplacesTheEarlierOne() {
        val file = tempFile()
        assertTrue(LyricsOverrideFile.write(file, "id", listOf(LyricLine(0L, "old")), LyricsSource.LRCLIB, savedAt = 1L))
        assertTrue(
            LyricsOverrideFile.write(
                file,
                "id",
                listOf(LyricLine(5_000L, "new")),
                LyricsSource.MUSIXMATCH,
                savedAt = 2L,
                kind = LyricsOverrideFile.Kind.USER,
            ),
        )
        val stored = requireNotNull(LyricsOverrideFile.read(file, "id"))
        assertEquals("new", stored.lines.single().text)
        assertEquals(5_000L, stored.lines.single().timeMs)
        assertEquals(LyricsSource.MUSIXMATCH, stored.source)
        assertEquals(2L, stored.savedAt)
        assertEquals(LyricsOverrideFile.Kind.USER, stored.kind)
    }

    @Test
    fun removingAMissingSongDoesNothing() {
        val file = tempFile()
        assertFalse(LyricsOverrideFile.remove(file, "missing"))
        assertFalse(LyricsOverrideFile.remove(file, ""))
        assertFalse(file.exists())
    }

    @Test
    fun removingOneKindLeavesTheOtherKindsOverride() {
        val file = tempFile()
        val lines = listOf(LyricLine(0L, "line"))
        assertTrue(
            LyricsOverrideFile.write(
                file,
                "id",
                lines,
                LyricsSource.LRCLIB,
                kind = LyricsOverrideFile.Kind.USER,
            ),
        )
        // A replaced file cleans up the automatic answer only.
        assertFalse(LyricsOverrideFile.remove(file, "id", LyricsOverrideFile.Kind.RECHECK))
        assertEquals(LyricsOverrideFile.Kind.USER, LyricsOverrideFile.read(file, "id")?.kind)

        assertTrue(
            LyricsOverrideFile.write(
                file,
                "id",
                lines,
                LyricsSource.LRCLIB,
                kind = LyricsOverrideFile.Kind.RECHECK,
            ),
        )
        assertTrue(LyricsOverrideFile.remove(file, "id", LyricsOverrideFile.Kind.RECHECK))
        assertNull(LyricsOverrideFile.read(file, "id"))
    }

    @Test
    fun wordsSyllablesBackgroundAndAlignmentComeBackExactly() {
        val file = tempFile()
        val line = LyricLine(
            timeMs = 1_000L,
            text = "hello world",
            words = listOf(
                LyricWord(
                    1_000L,
                    1_500L,
                    "hello",
                    listOf(LyricSyllable(1_000L, 1_200L, 0, 2), LyricSyllable(1_200L, 1_500L, 2, 5)),
                ),
                LyricWord(1_600L, 2_200L, "world"),
            ),
            sungUntilMs = 2_300L,
            background = LyricLine(
                timeMs = 1_300L,
                text = "(ooh)",
                words = listOf(LyricWord(1_300L, 1_800L, "(ooh)")),
                sungUntilMs = 1_800L,
            ),
            alignment = LyricAlignment.End,
        )
        assertTrue(
            LyricsOverrideFile.write(
                file,
                "id",
                listOf(line),
                LyricsSource.BETTER_LYRICS,
                savedAt = 42L,
                kind = LyricsOverrideFile.Kind.USER,
            ),
        )
        val stored = requireNotNull(LyricsOverrideFile.read(file, "id"))
        assertEquals(listOf(line), stored.lines)
        assertEquals(LyricsSource.BETTER_LYRICS, stored.source)
        assertEquals(42L, stored.savedAt)
        assertEquals(LyricsOverrideFile.Kind.USER, stored.kind)
    }

    @Test
    fun theFileNameIsStable() {
        assertEquals("lyrics-overrides.json", LyricsOverrideFile.FILE_NAME)
    }
}
