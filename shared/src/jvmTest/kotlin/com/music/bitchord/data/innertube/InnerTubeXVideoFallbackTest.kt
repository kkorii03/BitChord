package com.music.bitchord.data.innertube

import com.metrolist.innertubex.extraction.StreamResolveException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * The picture normally follows the video ranking. When that finds nothing, it
 * asks the one client audio learned (WEB_CREATOR with its PoToken) instead of
 * leaving a music video with no picture.
 */
class InnerTubeXVideoFallbackTest {

    private fun stream(height: Int = 1080) = InnerTubeXResolver.VideoStream(
        videoId = "v",
        url = "https://example.test/video",
        width = height * 16 / 9,
        height = height,
        mimeType = "video/mp4",
        headers = emptyMap(),
        heights = listOf(height),
        profileId = "WEB_CREATOR__po",
    )

    @Test
    fun `a failed video ranking asks the learned audio client`() = runBlocking {
        val asked = mutableListOf<String?>()
        val preferred = stream()
        val found = InnerTubeXResolver.extractVideoWithFallback("WEB_CREATOR", emptySet()) { client ->
            asked += client
            if (client == null) {
                throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "no playable clients")
            }
            preferred
        }

        assertSame(preferred, found)
        assertEquals(listOf(null, "WEB_CREATOR"), asked)
        assertEquals("WEB_CREATOR", InnerTubeXResolver.manifestIdForProfile(found?.profileId))
    }

    @Test
    fun `an empty video ranking asks the learned audio client`() = runBlocking {
        val asked = mutableListOf<String?>()
        val preferred = stream()
        val found = InnerTubeXResolver.extractVideoWithFallback("WEB_CREATOR", emptySet()) { client ->
            asked += client
            if (client == null) null else preferred
        }

        assertSame(preferred, found)
        assertEquals(listOf(null, "WEB_CREATOR"), asked)
    }

    @Test
    fun `a ranking failure other than no playable stream is not retried`() = runBlocking {
        for (reason in listOf(
            StreamResolveException.Reason.NETWORK,
            StreamResolveException.Reason.AGE_RESTRICTED,
        )) {
            val asked = mutableListOf<String?>()
            val rankedFailure = StreamResolveException(reason, "ranking failed")
            val failure = assertFailsWith<StreamResolveException> {
                InnerTubeXResolver.extractVideoWithFallback("WEB_CREATOR", emptySet()) { client ->
                    asked += client
                    throw rankedFailure
                }
            }

            assertSame(rankedFailure, failure)
            assertEquals(reason, failure.reason)
            assertEquals(listOf<String?>(null), asked)
        }
    }

    @Test
    fun `a ranked picture is not asked again with the learned client`() = runBlocking {
        val asked = mutableListOf<String?>()
        val ranked = stream(720)
        val found = InnerTubeXResolver.extractVideoWithFallback("WEB_CREATOR", emptySet()) { client ->
            asked += client
            ranked
        }

        assertSame(ranked, found)
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun `no learned client keeps the one-shot failure`() = runBlocking {
        val asked = mutableListOf<String?>()
        val failure = assertFailsWith<StreamResolveException> {
            InnerTubeXResolver.extractVideoWithFallback(null, emptySet()) { client ->
                asked += client
                throw StreamResolveException(StreamResolveException.Reason.AGE_RESTRICTED, "age gate")
            }
        }

        assertEquals(StreamResolveException.Reason.AGE_RESTRICTED, failure.reason)
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun `a learned client this picture already refused is not asked again`() = runBlocking {
        val asked = mutableListOf<String?>()
        val rankedFailure = StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "no playable clients")
        val failure = assertFailsWith<StreamResolveException> {
            InnerTubeXResolver.extractVideoWithFallback(
                "WEB_CREATOR",
                setOf("WEB_CREATOR__po"),
            ) { client ->
                asked += client
                throw rankedFailure
            }
        }

        assertSame(rankedFailure, failure)
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun `a failed fallback still reports the ranking failure`() = runBlocking {
        val asked = mutableListOf<String?>()
        val rankedFailure = StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "no playable clients")
        val failure = assertFailsWith<StreamResolveException> {
            InnerTubeXResolver.extractVideoWithFallback("WEB_CREATOR", emptySet()) { client ->
                asked += client
                if (client == null) throw rankedFailure
                null
            }
        }

        assertSame(rankedFailure, failure)
        assertEquals(listOf(null, "WEB_CREATOR"), asked)
    }
}