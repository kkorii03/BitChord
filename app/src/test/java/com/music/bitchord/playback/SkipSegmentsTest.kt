package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsTest {

    private val music = SkipSegment(10_000, 20_000, "music_offtopic", "music")
    private val sponsor = SkipSegment(30_000, 35_000, "sponsor", "sponsor")

    private fun decide(
        positionMs: Long,
        segments: List<SkipSegment> = listOf(music, sponsor),
        categories: Set<String> = setOf(MUSIC_OFF_TOPIC),
        durationMs: Long = 240_000,
        skipped: Set<String> = emptySet(),
        lastManualSeekAtMs: Long = 0L,
        lastManualSeekPositionMs: Long = 0L,
        nowMs: Long = 100_000L,
    ): SkipAction = decideSkip(
        segments = segments,
        positionMs = positionMs,
        durationMs = durationMs,
        categories = categories,
        skippedIds = skipped,
        lastManualSeekAtMs = lastManualSeekAtMs,
        lastManualSeekPositionMs = lastManualSeekPositionMs,
        nowMs = nowMs,
    )

    @Test
    fun `position inside a music off topic segment is skipped to its end`() {
        val action = decide(12_000)
        assertEquals(SkipAction.Skip(music), action)
    }

    @Test
    fun `position at the start of a segment is inside it`() {
        assertEquals(SkipAction.Skip(music), decide(10_000))
    }

    @Test
    fun `position at the end of a segment is outside it`() {
        assertEquals(SkipAction.None, decide(20_000))
    }

    @Test
    fun `position outside every segment is left alone`() {
        assertEquals(SkipAction.None, decide(25_000))
    }

    @Test
    fun `extra categories are only used when switched on`() {
        assertEquals(SkipAction.None, decide(32_000))
        assertEquals(
            SkipAction.Skip(sponsor),
            decide(32_000, categories = skipCategories(includeExtras = true)),
        )
    }

    @Test
    fun `music off topic is still asked for when the extras are also on`() {
        val extras = skipCategories(includeExtras = true)
        assertEquals(SkipAction.Skip(music), decide(12_000, categories = extras))
        assertTrue(MUSIC_OFF_TOPIC in extras)
        assertTrue("intro" in extras)
        assertFalse("poi" in extras)
    }

    @Test
    fun `a segment already handled this playback is not skipped again`() {
        assertEquals(SkipAction.None, decide(12_000, skipped = setOf("music")))
    }

    @Test
    fun `the most recently started overlapping segment wins`() {
        val early = SkipSegment(10_000, 40_000, "music_offtopic", "early")
        val late = SkipSegment(12_000, 15_000, "music_offtopic", "late")
        assertEquals(
            SkipAction.Skip(late),
            decide(13_000, segments = listOf(early, late)),
        )
    }

    @Test
    fun `a manual seek into the segment is left alone within the grace period`() {
        assertEquals(
            SkipAction.Leave(music),
            decide(
                12_000,
                lastManualSeekAtMs = 99_000,
                lastManualSeekPositionMs = 11_000,
            ),
        )
    }

    @Test
    fun `a manual seek is forgotten once the grace period has passed`() {
        assertEquals(
            SkipAction.Skip(music),
            decide(
                12_000,
                lastManualSeekAtMs = 97_000,
                lastManualSeekPositionMs = 11_000,
            ),
        )
    }

    @Test
    fun `a manual seek that landed elsewhere does not protect the segment`() {
        assertEquals(
            SkipAction.Skip(music),
            decide(
                12_000,
                lastManualSeekAtMs = 99_500,
                lastManualSeekPositionMs = 5_000,
            ),
        )
    }

    @Test
    fun `a segment covering more than eighty percent is never skipped`() {
        val huge = SkipSegment(0, 200_000, "music_offtopic", "huge")
        assertEquals(SkipAction.None, decide(1_000, segments = listOf(huge), durationMs = 240_000))
    }

    @Test
    fun `a segment ending at the very edge of the track is never skipped`() {
        val tail = SkipSegment(100_000, 239_500, "music_offtopic", "tail")
        assertEquals(SkipAction.None, decide(101_000, segments = listOf(tail), durationMs = 240_000))
    }

    @Test
    fun `eighty percent exactly is still allowed when it ends well before the track does`() {
        val long = SkipSegment(10_000, 250_000, "music_offtopic", "long")
        assertEquals(SkipAction.Skip(long), decide(20_000, segments = listOf(long), durationMs = 300_000))
    }

    @Test
    fun `an unknown duration does not block the guards that need it`() {
        assertEquals(SkipAction.Skip(music), decide(12_000, durationMs = 0L))
    }

    @Test
    fun `direct segments are parsed and non-skip actions dropped`() {
        val body = """
            [
              {"segment":[10.5,20.25],"category":"music_offtopic","actionType":"skip","UUID":"u1","videoDuration":240.0,"votes":3},
              {"segment":[30,35],"category":"sponsor","actionType":"mute","UUID":"u2"},
              {"segment":[40],"category":"intro","actionType":"skip","UUID":"u3"},
              {"segment":[50,50],"category":"outro","actionType":"skip","UUID":"u4"}
            ]
        """.trimIndent()
        val parsed = parseSkipSegments(body)
        assertEquals(1, parsed.size)
        assertEquals(SkipSegment(10_500, 20_250, "music_offtopic", "u1"), parsed.single())
    }

    @Test
    fun `hashed segments are picked by video id`() {
        val body = """
            [
              {"videoID":"AAAAAAAAAAA","segments":[{"segment":[1,2],"category":"intro","actionType":"skip","UUID":"x"}]},
              {"videoID":"dQw4w9WgXcQ","segments":[{"segment":[5,8],"category":"music_offtopic","actionType":"skip","UUID":"y"}]}
            ]
        """.trimIndent()
        val parsed = parseHashedSkipSegments(body, "dQw4w9WgXcQ")
        assertEquals(listOf(SkipSegment(5_000, 8_000, "music_offtopic", "y")), parsed)
        assertTrue(parseHashedSkipSegments(body, "ZZZZZZZZZZZ").isEmpty())
    }

    @Test
    fun `unreadable answers read as no segments`() {
        assertTrue(parseSkipSegments("").isEmpty())
        assertTrue(parseSkipSegments("not json").isEmpty())
        assertTrue(parseHashedSkipSegments("{}", "dQw4w9WgXcQ").isEmpty())
    }

    @Test
    fun `the hash prefix is the first four hex characters of sha256`() {
        assertEquals("5f6b", videoIdHashPrefix("dQw4w9WgXcQ"))
    }

    @Test
    fun `only eleven character ids are looked up`() {
        assertTrue(isYouTubeVideoId("dQw4w9WgXcQ"))
        assertFalse(isYouTubeVideoId("content://media/1"))
        assertFalse(isYouTubeVideoId("webdav:http://example/a.flac"))
        assertFalse(isYouTubeVideoId("short"))
    }
}
