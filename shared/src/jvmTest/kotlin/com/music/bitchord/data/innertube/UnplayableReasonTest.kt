package com.music.bitchord.data.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UnplayableReasonTest {

    @Test
    fun `a copyright claim is a definitive verdict`() {
        val message = "Got error UNPLAYABLE: It was blocked due to the claimed content by SME"
        assertTrue(UnplayableReason.isDefinitive(message))
        assertTrue(UnplayableReason.isDefinitelyGone(message))
        assertTrue(UnplayableReason.isTrackSpecific(message))
    }

    @Test
    fun `a korean ownership claim is a definitive verdict`() {
        val message = "SME님이 소유권 주장을 제기한 콘텐츠로 인해 차단되었습니다"
        assertTrue(UnplayableReason.isDefinitive(message))
        assertTrue(UnplayableReason.isDefinitelyGone(message))
        assertTrue(UnplayableReason.isTrackSpecific(message))
    }

    @Test
    fun `an english copyright grounds block is a definitive verdict`() {
        val message = "This video contains content from SME, who has blocked it on copyright grounds"
        assertTrue(UnplayableReason.isDefinitive(message))
        assertTrue(UnplayableReason.isDefinitelyGone(message))
        assertTrue(UnplayableReason.isTrackSpecific(message))
    }

    @Test
    fun `a copyright block scoped to a country is not a gone recording`() {
        val english =
            "This video contains content from SME, who has blocked it in your country on copyright grounds"
        val korean = "이 국가에서는 저작권 문제로 차단되었습니다"
        assertTrue(UnplayableReason.isDefinitive(english))
        assertFalse(UnplayableReason.isDefinitelyGone(english))
        assertTrue(UnplayableReason.isDefinitive(korean))
        assertFalse(UnplayableReason.isDefinitelyGone(korean))
    }

    @Test
    fun `a bot check or a region block is not a gone recording`() {
        val bot = "Sign in to confirm you are not a bot"
        val region = "이 국가에서는 이용할 수 없는 트랙입니다"
        assertFalse(UnplayableReason.isDefinitelyGone(bot))
        assertFalse(UnplayableReason.isDefinitelyGone(region))
        assertFalse(UnplayableReason.isTrackSpecific(bot))
        assertFalse(UnplayableReason.isTrackSpecific(region))
    }

    @Test
    fun `the generic unavailable answer waits for the proxy retry`() {
        val message = "StreamResolveException: This track is unavailable"
        assertTrue(UnplayableReason.isDefinitive(message))
        assertFalse(UnplayableReason.isDefinitelyGone(message))
        assertFalse(UnplayableReason.isTrackSpecific(message))
    }

    @Test
    fun `a resolver unavailable answer is definitive`() {
        assertTrue(UnplayableReason.isDefinitive("StreamResolveException: unavailable"))
        assertTrue(UnplayableReason.isDefinitive("Video unavailable"))
    }

    @Test
    fun `a deleted or private upload is definitive`() {
        assertTrue(UnplayableReason.isDefinitive("Private video"))
        assertTrue(UnplayableReason.isDefinitelyGone("Private video"))
        assertTrue(UnplayableReason.isDefinitive("This video has been removed by the uploader"))
        assertTrue(UnplayableReason.isDefinitelyGone("This video has been removed by the uploader"))
        assertTrue(UnplayableReason.isDefinitive("비공개 트랙입니다"))
        assertTrue(UnplayableReason.isDefinitelyGone("비공개 트랙입니다"))
        assertTrue(UnplayableReason.isDefinitive("삭제된 동영상입니다"))
        assertTrue(UnplayableReason.isDefinitelyGone("삭제된 동영상입니다"))
    }

    @Test
    fun `rate limits are retryable`() {
        assertFalse(UnplayableReason.isDefinitive("HTTP 429 Too Many Requests"))
        assertFalse(UnplayableReason.isDefinitive("429"))
        assertFalse(UnplayableReason.isDefinitelyGone("HTTP 429 Too Many Requests"))
    }

    @Test
    fun `timeouts and dropped sockets are retryable`() {
        assertFalse(UnplayableReason.isDefinitive("Read timed out"))
        assertFalse(UnplayableReason.isDefinitive("timeout"))
        assertFalse(UnplayableReason.isDefinitive("Connection reset by peer"))
        assertFalse(UnplayableReason.isDefinitive(IOException("connection reset").message))
    }

    @Test
    fun `a service unavailable is not a takedown`() {
        assertFalse(UnplayableReason.isDefinitive("HTTP 503 Service Unavailable"))
        assertFalse(UnplayableReason.isDefinitive("temporarily unavailable"))
    }

    @Test
    fun `blank or unknown failures are retryable`() {
        assertNull(UnplayableReason.fromMessage(null))
        assertNull(UnplayableReason.fromMessage(""))
        assertNull(UnplayableReason.fromMessage("   "))
        assertFalse(UnplayableReason.isDefinitive("something odd happened"))
    }

    @Test
    fun `a track specific reason is not replaced by a generic failure`() {
        val claimed = "Got error UNPLAYABLE: It was blocked due to the claimed content by SME"
        val generic = "StreamResolveException: This track is unavailable"
        assertEquals(claimed, UnplayableReason.keepTrackSpecific(claimed, generic))
        assertEquals(claimed, UnplayableReason.keepTrackSpecific(claimed, null))
        assertEquals(claimed, UnplayableReason.keepTrackSpecific(generic, claimed))
        assertEquals(generic, UnplayableReason.keepTrackSpecific(null, generic))
    }

    @Test
    fun `age restricted and paid tracks are track specific but not gone`() {
        assertTrue(UnplayableReason.isTrackSpecific("This video is age-restricted"))
        assertFalse(UnplayableReason.isDefinitelyGone("This video is age-restricted"))
        assertTrue(UnplayableReason.isTrackSpecific("Paid content"))
        assertFalse(UnplayableReason.isDefinitelyGone("Paid content"))
    }

    @Test
    fun `a region or premium gate is definitive but not track specific`() {
        assertTrue(UnplayableReason.isDefinitive("not available in your country"))
        assertFalse(UnplayableReason.isTrackSpecific("not available in your country"))
        assertFalse(UnplayableReason.isDefinitelyGone("not available in your country"))
    }

    @Test
    fun `unrelated addon and module errors are not track verdicts`() {
        assertFalse(UnplayableReason.isDefinitive("AddonClient: 사용할 수 없는 주소입니다"))
        assertFalse(UnplayableReason.isDefinitive("모듈을 이용할 수 없습니다"))
        assertFalse(UnplayableReason.isDefinitive("not available"))
        assertFalse(UnplayableReason.isTrackSpecific("AddonClient: 사용할 수 없는 주소입니다"))
    }

    @Test
    fun `a bare unavailable from unrelated modules is not a track verdict`() {
        assertFalse(UnplayableReason.isDefinitive("unavailable"))
        assertFalse(UnplayableReason.isDefinitive("AddonClient: unavailable"))
        assertFalse(UnplayableReason.isDefinitive("Media3: source unavailable"))
        assertFalse(UnplayableReason.isDefinitive("module unavailable"))
    }

    @Test
    fun `UNPLAYABLE is definitive but needs the explicit reason to be track specific`() {
        assertTrue(UnplayableReason.isDefinitive("Got error UNPLAYABLE: This track is unavailable"))
        assertFalse(UnplayableReason.isDefinitelyGone("Got error UNPLAYABLE: This track is unavailable"))
        assertFalse(UnplayableReason.isTrackSpecific("Got error UNPLAYABLE: This track is unavailable"))
    }
}
