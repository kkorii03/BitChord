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
    fun `a region or bot answer survives a later timeout for the proxy decision`() {
        val region = "This video is not available in your country"
        val bot = "Sign in to confirm you're not a bot"
        val timeout = "SocketTimeoutException: Read timed out"

        assertEquals(region, UnplayableReason.keepProxyWorthy(region, timeout))
        assertEquals(bot, UnplayableReason.keepProxyWorthy(bot, timeout))
        assertEquals(region, UnplayableReason.keepProxyWorthy(region, null))
        assertEquals(timeout, UnplayableReason.keepProxyWorthy(null, timeout))
        assertEquals(
            "a later region refusal still replaces an earlier timeout",
            region,
            UnplayableReason.keepProxyWorthy(timeout, region),
        )
        assertEquals(
            "a takedown is the stronger verdict",
            "This video has been removed by the uploader",
            UnplayableReason.keepProxyWorthy(region, "This video has been removed by the uploader"),
        )
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
    fun `a bot check is a network verdict, never a track verdict`() {
        val message = "Sign in to confirm you're not a bot"
        assertFalse(UnplayableReason.isDefinitive(message))
        assertFalse(UnplayableReason.isTrackSpecific(message))
        assertFalse(UnplayableReason.isDefinitelyGone(message))
        assertTrue(UnplayableReason.isBotChallenge(message))
        assertTrue(
            UnplayableReason.isBotChallenge("YouTube가 이 네트워크에서 봇 확인을 요구합니다"),
        )
        assertTrue(UnplayableReason.isBotChallenge("로그인하여 봇이 아님을 확인하세요."))
        assertFalse(UnplayableReason.isBotChallenge("This track is unavailable"))
    }

    @Test
    fun `region and premium gates are worth a proxy walk, takedowns are not`() {
        assertTrue(UnplayableReason.isRegionGate("not available in your country"))
        assertTrue(UnplayableReason.isRegionGate("이 국가에서는 이용할 수 없는 트랙입니다"))
        assertTrue(UnplayableReason.isRegionGate("YouTube Music Premium이 필요한 트랙입니다"))
        assertTrue(UnplayableReason.isRegionGate("Sign in to confirm you're not a bot"))
        assertFalse(UnplayableReason.isRegionGate("This video has been removed by the uploader"))
        assertFalse(UnplayableReason.isRegionGate("Private video"))
        assertFalse(UnplayableReason.isRegionGate("Read timed out"))
    }

    @Test
    fun `a bot challenge dressed as LOGIN_REQUIRED is still not definitive`() {
        val message = "LOGIN_REQUIRED: Sign in to confirm you're not a bot"
        assertNull(UnplayableReason.fromMessage(message))
        assertFalse(UnplayableReason.isDefinitive(message))
        assertTrue(UnplayableReason.isBotChallenge(message))
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
