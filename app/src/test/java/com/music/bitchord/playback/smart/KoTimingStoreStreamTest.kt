package com.music.bitchord.playback.smart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cold1119 (heap-0359 holder 4): the timing store is saved by streaming the
 * JSON into the file instead of building it as one String first. The file is
 * sync storage, so the bytes must be exactly what the old
 * `encodeToString(...)` + `writeText` (UTF-8) wrote. The seam does not exist on
 * the old code, so this class fails there.
 */
class KoTimingStoreStreamTest {
    private fun word(i: Int, text: String) = LyricsTiming.SavedWord(text, i * 37L, i * 37L + 20)

    private fun sample(count: Int): Map<String, LyricsTiming.Entry> {
        // Synthetic words only: Hangul, emoji (surrogate pairs), quotes,
        // backslashes, control characters and broken surrogates, which the
        // two UTF-8 writers must all render the same way.
        val odd = listOf("가나다", "xéy", "😀🎵", "\"q\"", "back\\slash", "tab\tnl\n", "\u0001", "\uD800x", "y\uDC00", "end\uD83D")
        val map = LinkedHashMap<String, LyricsTiming.Entry>()
        for (i in 0 until count) {
            map["vid${i}_-AbC"] = LyricsTiming.Entry(
                clockVersion = 1,
                aiShiftMs = if (i % 3 == 0) null else i * 11L - 500,
                aiStarts = if (i % 5 == 0) listOf(0L, 1_234L, -7L) else null,
                auto = LyricsTiming.SavedMap(
                    knots = listOf(0L, 1_000L * i, 2_000L * i + 3),
                    headRate = 1.0 + i / 1e5,
                    tailRate = 0.999_9,
                    rate = 1.000_123_456_789,
                    match = if (i % 2 == 0) 87 else null,
                ),
                autoClock = if (i % 4 == 0) "youtube" else "alt:src/mono:$i~1",
                heardWords = List(i % 7) { k -> word(k, odd[(i + k) % odd.size] + k) },
            )
        }
        return map
    }

    @Test
    fun streamedBytesEqualTheOldStringBytesForASmallStore() {
        val entries = sample(40)
        val old = LyricsTiming.storeTextForTest(entries).toByteArray(Charsets.UTF_8)
        assertArrayEquals(old, LyricsTiming.storeBytesForTest(entries))
    }

    @Test
    fun streamedBytesEqualTheOldStringBytesForAFullStore() {
        // Big enough to cross the stream writer's internal buffers many times,
        // with multi-byte characters landing on every boundary.
        val entries = sample(3_000)
        val old = LyricsTiming.storeTextForTest(entries).toByteArray(Charsets.UTF_8)
        assertTrue(old.size > 256 * 1024)
        assertArrayEquals(old, LyricsTiming.storeBytesForTest(entries))
    }

    @Test
    fun anEmptyStoreIsIdenticalToo() {
        val old = LyricsTiming.storeTextForTest(emptyMap()).toByteArray(Charsets.UTF_8)
        assertArrayEquals(old, LyricsTiming.storeBytesForTest(emptyMap()))
    }
}
