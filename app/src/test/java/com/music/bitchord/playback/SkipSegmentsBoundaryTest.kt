package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsBoundaryTest {

    private val music = SkipSegment(10_000, 20_000, MUSIC_OFF_TOPIC, "music")
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
    fun segmentLengthIsTheDistanceBetweenItsEdges() {
        assertEquals(10_000L, music.lengthMs)
        assertEquals(0L, SkipSegment(5_000, 5_000, "x", "u").lengthMs)
        assertEquals(-1L, SkipSegment(5_001, 5_000, "x", "u").lengthMs)
    }

    @Test
    fun aSegmentIdPrefersItsUuidAndFallsBackToItsEdges() {
        assertEquals("music", music.id)
        assertEquals(
            "sponsor:10000:20000",
            SkipSegment(10_000, 20_000, "sponsor", "").id,
        )
        assertEquals(":10000:20000", SkipSegment(10_000, 20_000, "", "").id)
    }

    @Test
    fun theStartEdgeIsInsideAndTheEndEdgeIsOutside() {
        assertEquals(SkipAction.Skip(music), decide(10_000))
        assertEquals(SkipAction.None, decide(9_999))
        assertEquals(SkipAction.Skip(music), decide(19_999))
        assertEquals(SkipAction.None, decide(20_000))
        assertEquals(SkipAction.None, decide(20_001))
    }

    @Test
    fun noSegmentsOrNoCategoriesMeansNothingHappens() {
        assertEquals(SkipAction.None, decide(12_000, segments = emptyList()))
        assertEquals(SkipAction.None, decide(12_000, categories = emptySet()))
        assertEquals(SkipAction.None, decide(32_000, categories = setOf(MUSIC_OFF_TOPIC)))
    }

    @Test
    fun aSegmentAlreadyHandledIsNotHandledAgain() {
        assertEquals(SkipAction.None, decide(12_000, skipped = setOf("music")))
        assertEquals(
            SkipAction.None,
            decide(
                12_000,
                segments = listOf(SkipSegment(10_000, 20_000, MUSIC_OFF_TOPIC, "")),
                skipped = setOf("$MUSIC_OFF_TOPIC:10000:20000"),
            ),
        )
    }

    @Test
    fun theSecondarySwitchOnlyAddsItsOwnCategories() {
        assertEquals(setOf(MUSIC_OFF_TOPIC), skipCategories(includeExtras = false))
        val extras = skipCategories(includeExtras = true)
        assertEquals(REQUESTED_SKIP_CATEGORIES.toSet(), extras)
        assertTrue(MUSIC_OFF_TOPIC in extras)
        assertTrue("sponsor" in extras)
        assertTrue("selfpromo" in extras)
        assertTrue("intro" in extras)
        assertTrue("outro" in extras)
        assertFalse("mute" in extras)
        assertFalse("poi" in extras)
        assertFalse("interaction" in extras)
    }

    @Test
    fun aManualSeekIsProtectedForExactlyTheGracePeriod() {
        assertEquals(
            SkipAction.Leave(music),
            decide(12_000, lastManualSeekAtMs = 98_000, lastManualSeekPositionMs = 11_000),
        )
        assertEquals(
            SkipAction.Skip(music),
            decide(12_000, lastManualSeekAtMs = 97_999, lastManualSeekPositionMs = 11_000),
        )
    }

    @Test
    fun aManualSeekAtTheStartEdgeProtectsAndAtTheEndEdgeDoesNot() {
        assertEquals(
            SkipAction.Leave(music),
            decide(12_000, lastManualSeekAtMs = 99_500, lastManualSeekPositionMs = 10_000),
        )
        assertEquals(
            SkipAction.Leave(music),
            decide(12_000, lastManualSeekAtMs = 99_500, lastManualSeekPositionMs = 19_999),
        )
        assertEquals(
            SkipAction.Skip(music),
            decide(12_000, lastManualSeekAtMs = 99_500, lastManualSeekPositionMs = 20_000),
        )
    }

    @Test
    fun aZeroTimestampMeansNoManualSeekAtAll() {
        assertEquals(
            SkipAction.Skip(music),
            decide(
                12_000,
                lastManualSeekAtMs = 0L,
                lastManualSeekPositionMs = 11_000,
                nowMs = 100_000L,
            ),
        )
    }

    @Test
    fun aClockThatMovedBackwardsStillTreatsTheSeekAsRecent() {
        assertEquals(
            SkipAction.Leave(music),
            decide(
                12_000,
                lastManualSeekAtMs = 100_001L,
                lastManualSeekPositionMs = 11_000,
                nowMs = 100_000L,
            ),
        )
    }

    @Test
    fun anUnknownOrNonPositiveDurationNeverBlocksTheSkip() {
        assertEquals(SkipAction.Skip(music), decide(12_000, durationMs = 0L))
        assertEquals(SkipAction.Skip(music), decide(12_000, durationMs = -1L))
        assertFalse(isTooMuchOfTheVideo(music, 0L))
        assertFalse(isTooMuchOfTheVideo(music, -1L))
    }

    @Test
    fun aSegmentEndingAtTheVeryEdgeIsNeverSkipped() {
        val duration = 100_000L
        assertTrue(isTooMuchOfTheVideo(SkipSegment(90_000, 99_000, "x", "u"), duration))
        assertTrue(isTooMuchOfTheVideo(SkipSegment(90_000, 99_001, "x", "u"), duration))
        assertFalse(isTooMuchOfTheVideo(SkipSegment(90_000, 98_999, "x", "u"), duration))
    }

    @Test
    fun eightyPercentExactlyIsAllowedAndOneMillisecondMoreIsNot() {
        val duration = 100_000L
        assertFalse(isTooMuchOfTheVideo(SkipSegment(1_000, 81_000, "x", "u"), duration))
        assertTrue(isTooMuchOfTheVideo(SkipSegment(1_000, 81_001, "x", "u"), duration))
    }

    @Test
    fun aShortVideoHasNoSegmentWorthSkippingNearItsEnd() {
        val duration = 1_500L
        assertFalse(isTooMuchOfTheVideo(SkipSegment(0, 400, "x", "u"), duration))
        assertTrue(isTooMuchOfTheVideo(SkipSegment(0, 500, "x", "u"), duration))
        assertTrue(isTooMuchOfTheVideo(SkipSegment(0, 600, "x", "u"), duration))
    }

    @Test
    fun directParsingAcceptsUnknownFieldsAndKeepsSkipSegments() {
        val body = """
            [
              {"segment":[10.5,20.25],"category":"music_offtopic","actionType":"skip","UUID":"u1","videoDuration":240.0,"votes":3}
            ]
        """.trimIndent()
        assertEquals(
            listOf(SkipSegment(10_500, 20_250, MUSIC_OFF_TOPIC, "u1")),
            parseSkipSegments(body),
        )
    }

    @Test
    fun aMissingActionTypeDefaultsToSkip() {
        val body = """[{"segment":[1,2],"category":"intro","UUID":"u"}]"""
        assertEquals(listOf(SkipSegment(1_000, 2_000, "intro", "u")), parseSkipSegments(body))
    }

    @Test
    fun actionTypesOtherThanLowercaseSkipAreDropped() {
        val body = """
            [
              {"segment":[1,2],"category":"intro","actionType":"SKIP","UUID":"upper"},
              {"segment":[3,4],"category":"intro","actionType":"mute","UUID":"mute"},
              {"segment":[5,6],"category":"intro","actionType":"poi","UUID":"poi"},
              {"segment":[7,8],"category":"intro","actionType":"skip","UUID":"keep"}
            ]
        """.trimIndent()
        assertEquals(listOf(SkipSegment(7_000, 8_000, "intro", "keep")), parseSkipSegments(body))
    }

    @Test
    fun malformedSegmentsAreDroppedOneByOne() {
        val body = """
            [
              {"segment":[-1,5],"category":"intro","UUID":"negative"},
              {"segment":[5,5],"category":"intro","UUID":"empty"},
              {"segment":[6,5],"category":"intro","UUID":"backwards"},
              {"segment":[40],"category":"intro","UUID":"short"},
              {"segment":[9,10],"category":"intro","UUID":"keep"}
            ]
        """.trimIndent()
        assertEquals(listOf(SkipSegment(9_000, 10_000, "intro", "keep")), parseSkipSegments(body))
    }

    @Test
    fun extraElementsInTheSegmentArrayAreIgnored() {
        val body = """[{"segment":[1,2,3],"category":"intro","UUID":"u"}]"""
        assertEquals(listOf(SkipSegment(1_000, 2_000, "intro", "u")), parseSkipSegments(body))
    }

    @Test
    fun millisecondRoundingUsesTheNearestMillisecond() {
        val body = """[{"segment":[10.0004,20.0006],"category":"x","UUID":"u"}]"""
        assertEquals(listOf(SkipSegment(10_000, 20_001, "x", "u")), parseSkipSegments(body))
    }

    @Test
    fun missingCategoryAndUuidDecodeToBlankValues() {
        val body = """[{"segment":[1,2]}]"""
        assertEquals(listOf(SkipSegment(1_000, 2_000, "", "")), parseSkipSegments(body))
    }

    @Test
    fun anUnreadableAnswerIsTreatedAsNoSegments() {
        assertTrue(parseSkipSegments("").isEmpty())
        assertTrue(parseSkipSegments("not json").isEmpty())
        assertTrue(parseSkipSegments("null").isEmpty())
        assertTrue(parseSkipSegments("[]").isEmpty())
        assertTrue(parseSkipSegments("""[{"segment":"oops"}]""").isEmpty())
        assertTrue(parseHashedSkipSegments("{}", "dQw4w9WgXcQ").isEmpty())
        assertTrue(parseHashedSkipSegments("not json", "dQw4w9WgXcQ").isEmpty())
    }

    @Test
    fun hashedParsingPicksOnlyTheRequestedVideo() {
        val body = """
            [
              {"videoID":"AAAAAAAAAAA","segments":[{"segment":[1,2],"category":"intro","actionType":"skip","UUID":"x"}]},
              {"videoID":"dQw4w9WgXcQ","segments":[{"segment":[5,8],"category":"music_offtopic","actionType":"skip","UUID":"y"}]}
            ]
        """.trimIndent()
        assertEquals(
            listOf(SkipSegment(5_000, 8_000, MUSIC_OFF_TOPIC, "y")),
            parseHashedSkipSegments(body, "dQw4w9WgXcQ"),
        )
        assertTrue(parseHashedSkipSegments(body, "ZZZZZZZZZZZ").isEmpty())
    }

    @Test
    fun aRepeatedVideoIdUsesTheFirstEntry() {
        val body = """
            [
              {"videoID":"dQw4w9WgXcQ","segments":[{"segment":[1,2],"category":"first","actionType":"skip","UUID":"a"}]},
              {"videoID":"dQw4w9WgXcQ","segments":[{"segment":[3,4],"category":"second","actionType":"skip","UUID":"b"}]}
            ]
        """.trimIndent()
        assertEquals(
            listOf(SkipSegment(1_000, 2_000, "first", "a")),
            parseHashedSkipSegments(body, "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun theHashPrefixIsTheFirstFourHexCharactersOfSha256() {
        assertEquals("5f6b", videoIdHashPrefix("dQw4w9WgXcQ"))
        assertEquals("e3b0", videoIdHashPrefix(""))
        assertEquals("ca97", videoIdHashPrefix("a"))
        assertEquals("ba78", videoIdHashPrefix("abc"))
        assertEquals(4, videoIdHashPrefix("dQw4w9WgXcQ").length)
    }

    @Test
    fun onlyExactlyElevenUrlSafeCharactersAreVideoIds() {
        assertTrue(isYouTubeVideoId("dQw4w9WgXcQ"))
        assertTrue(isYouTubeVideoId("___________"))
        assertTrue(isYouTubeVideoId("-----------"))
        assertFalse(isYouTubeVideoId(""))
        assertFalse(isYouTubeVideoId("dQw4w9WgXc"))
        assertFalse(isYouTubeVideoId("dQw4w9WgXcQQ"))
        assertFalse(isYouTubeVideoId("dQw4w9WgXc "))
        assertFalse(isYouTubeVideoId("dQw4w9WgXcQ\n"))
        assertFalse(isYouTubeVideoId("content://media/1"))
        assertFalse(isYouTubeVideoId("webdav:http://example/a.flac"))
    }

    @Test
    fun theGuardConstantsKeepTheirBudget() {
        assertEquals(2_000L, MANUAL_SEEK_GRACE_MS)
        assertEquals(1_000L, EDGE_OF_VIDEO_MS)
        assertEquals(80, MAX_SEGMENT_PERCENT)
        assertEquals("music_offtopic", MUSIC_OFF_TOPIC)
        assertEquals(
            listOf("sponsor", "selfpromo", "intro", "outro"),
            EXTRA_SKIP_CATEGORIES,
        )
    }
}
