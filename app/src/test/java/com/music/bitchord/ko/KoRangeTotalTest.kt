package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] AUD-LEN, 11-18 16:00 WKZO-CWeOVA: a bounded 1 MiB warm left the cache
 * entry without a length, and a FLAC with no SEEKTABLE could not seek
 * ("seekable=false read=1048576B"). The 206 answer's own total is recorded.
 */
class KoRangeTotalTest {

    @Test
    fun `the total of a 206 Content-Range is read`() {
        assertEquals(28_883_400L, KoRangeTotal.parse("bytes 0-1048575/28883400"))
        assertEquals(28_883_400L, KoRangeTotal.parse("Bytes 1048576-2097151/28883400 "))
    }

    @Test
    fun `an unknown or malformed total is never guessed`() {
        assertNull(KoRangeTotal.parse(null))
        assertNull(KoRangeTotal.parse("bytes 0-1048575/*"))
        assertNull(KoRangeTotal.parse("bytes */28883400"))
        assertNull(KoRangeTotal.parse("bytes 0-0/0"))
        assertNull(KoRangeTotal.parse("garbage"))
    }

    @Test
    fun `the header is found whatever case the server used`() {
        assertEquals(500L, KoRangeTotal.of(mapOf("content-range" to listOf("bytes 0-99/500"))))
        assertEquals(500L, KoRangeTotal.of(mapOf("Content-Type" to listOf("audio/flac"), "CONTENT-RANGE" to listOf("bytes 0-99/500"))))
        assertNull(KoRangeTotal.of(mapOf("Content-Length" to listOf("100"))))
    }

    @Test
    fun `the bounded warm records the server's total under the read's own key`() {
        val root = File(".").canonicalFile
        val cache = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/AudioCache.kt") }
            .first { it.exists() }.readText()
        assertTrue(cache.contains("readAheadCacheFactory(com.music.bitchord.ko.KoRangeTotal.noting(upstream) { rangeTotal = it })"))
        assertTrue(
            cache.contains(
                "recordContentLength(if (pinKey) cacheKey else keyFactory.buildCacheKey(DataSpec(uri)), rangeTotal)",
            ),
        )
    }
}
