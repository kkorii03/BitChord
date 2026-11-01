package com.music.bitchord.ko

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FIXLIST 80: one track's own refusal must not bench the direct path for the
 * next, playable track. Only a refusal about the path itself may count.
 */
class KoDirectBenchTest {

    @Test
    fun `a copyright claim does not count against the path`() {
        assertFalse(
            KoDirectBench.countsAgainstPath(
                "Got error UNPLAYABLE: It was blocked due to the claimed content by SME",
            ),
        )
        assertFalse(
            KoDirectBench.countsAgainstPath(
                "This video contains content from SME, who has blocked it on copyright grounds",
            ),
        )
        assertFalse(
            KoDirectBench.countsAgainstPath(
                "SME님이 소유권 주장을 제기한 콘텐츠로 인해 차단되었습니다",
            ),
        )
    }

    @Test
    fun `a deleted or private upload does not count against the path`() {
        assertFalse(
            KoDirectBench.countsAgainstPath("This video has been removed by the uploader"),
        )
        assertFalse(KoDirectBench.countsAgainstPath("삭제된 동영상입니다"))
        assertFalse(KoDirectBench.countsAgainstPath("Private video"))
    }

    @Test
    fun `a bare UNPLAYABLE or track unavailable answer does not count`() {
        assertFalse(
            KoDirectBench.countsAgainstPath("Got error UNPLAYABLE: This track is unavailable"),
        )
        assertFalse(KoDirectBench.countsAgainstPath("UNPLAYABLE"))
        assertFalse(KoDirectBench.countsAgainstPath("StreamResolveException: This track is unavailable"))
        assertFalse(KoDirectBench.countsAgainstPath("Video unavailable"))
    }

    @Test
    fun `a region or premium gate still counts against the path`() {
        assertTrue(KoDirectBench.countsAgainstPath("이 국가에서는 이용할 수 없는 트랙입니다"))
        assertTrue(KoDirectBench.countsAgainstPath("This video is not available in your country"))
        assertTrue(KoDirectBench.countsAgainstPath("YouTube Music Premium이 필요한 트랙입니다"))
    }

    @Test
    fun `a bot challenge or a network failure still counts`() {
        assertTrue(KoDirectBench.countsAgainstPath("Sign in to confirm you're not a bot"))
        assertTrue(KoDirectBench.countsAgainstPath("HTTP 429 Too Many Requests"))
        assertTrue(KoDirectBench.countsAgainstPath("Read timed out"))
        assertTrue(KoDirectBench.countsAgainstPath("HTTP 503 Service Unavailable"))
        assertTrue(KoDirectBench.countsAgainstPath(null))
    }
}
