package com.music.bitchord.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file half of the diagnostic log: rotation is bounded, and the export
 * reads the files in the order they happened rather than newest-first.
 */
class TrackLogPersistenceTest {

    @Test
    fun `rotates at the cap and reads oldest first`() {
        val directory = Files.createTempDirectory("bitchord-trail-test").toFile()
        try {
            val store = TrackLogPersistence(directory, maxBytes = 80L, fileCount = 3)
            repeat(40) { index ->
                store.appendSync("line-" + index.toString().padStart(2, '0'))
            }

            val lines = store.readOldestFirst()

            // Old lines are gone, and the newest line is the last one read:
            // trail-2 -> trail-1 -> trail-0 is chronological.
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.size < 40, "rotation should have dropped old lines")
            assertEquals("line-39", lines.last())
            assertTrue(
                lines.indexOf("line-39") > lines.indexOf(lines.first()),
                "the last written line should come after the oldest surviving one",
            )

            val trailFiles = directory.listFiles().orEmpty().filter { it.name.startsWith("trail-") }
            assertTrue(trailFiles.size <= 3, "at most three trail files should exist")
            store.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a line larger than the cap still lands before the next rotation`() {
        val directory = Files.createTempDirectory("bitchord-trail-big").toFile()
        try {
            val store = TrackLogPersistence(directory, maxBytes = 20L, fileCount = 2)
            store.appendSync("first")
            store.appendSync("a line that is much longer than the cap")
            store.appendSync("last")

            val lines = store.readOldestFirst()
            assertTrue(lines.contains("first"))
            assertEquals("last", lines.last())
            store.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `queued lines are batched and all reach disk on flush`() {
        val directory = Files.createTempDirectory("bitchord-trail-batch").toFile()
        try {
            val store = TrackLogPersistence(directory)
            repeat(100) { index -> store.append("line-" + index.toString().padStart(3, '0')) }
            store.flush()

            val lines = store.readOldestFirst()
            assertEquals(100, lines.size)
            assertEquals("line-099", lines.last())
            store.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `flush policy waits for the interval or the buffer cap`() {
        // Nothing to flush yet.
        assertTrue(!TrackLogFlushPolicy.shouldFlush(nowMs = 1_000, lastFlushMs = 1_000, bufferedChars = 0))
        // A line inside the interval stays buffered.
        assertTrue(!TrackLogFlushPolicy.shouldFlush(nowMs = 3_999, lastFlushMs = 1_000, bufferedChars = 10))
        // The interval forces it out even if the stream never goes quiet.
        assertTrue(TrackLogFlushPolicy.shouldFlush(nowMs = 4_000, lastFlushMs = 1_000, bufferedChars = 10))
        // So does a burst big enough to matter on its own.
        assertTrue(
            TrackLogFlushPolicy.shouldFlush(
                nowMs = 1_000,
                lastFlushMs = 1_000,
                bufferedChars = TrackLogFlushPolicy.MAX_BUFFERED_CHARS,
            ),
        )
        // The wait is the quiet window, shortened near the interval cap.
        assertEquals(TrackLogFlushPolicy.IDLE_MS, TrackLogFlushPolicy.waitMs(nowMs = 1_000, lastFlushMs = 1_000))
        assertEquals(500L, TrackLogFlushPolicy.waitMs(nowMs = 3_500, lastFlushMs = 1_000))
        assertEquals(1L, TrackLogFlushPolicy.waitMs(nowMs = 5_000, lastFlushMs = 1_000))
    }

    @Test
    fun `debug javascript and read-ahead brackets are dropped but warnings are kept`() {
        assertTrue(!TrackLogNoise.keep('D', "PoTokenWebView", "JS: [createPoTokenMinter] ENTER"))
        assertTrue(TrackLogNoise.keep('W', "PoTokenWebView", "JS: Uncaught TypeError"))
        assertTrue(TrackLogNoise.keep('E', "PoTokenWebView", "JS: failed"))
        assertTrue(!TrackLogNoise.keep('D', "AudioCache", "read-ahead fetching key [0, 10)"))
        assertTrue(!TrackLogNoise.keep('D', "AudioCache", "read-ahead fetched key [0, 10) in 3ms"))
        assertTrue(TrackLogNoise.keep('D', "AudioCache", "read-ahead key: 2 probes, 1.2 MB in 30ms"))
        assertTrue(TrackLogNoise.keep('D', "AudioCache", "read-ahead key: 1 probes, 4 KB not kept (entry in use)"))
        assertTrue(TrackLogNoise.keep('D', "BitChord", "play: -> Song - Artist (id)"))
    }
}
