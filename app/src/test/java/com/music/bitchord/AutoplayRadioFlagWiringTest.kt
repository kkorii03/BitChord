package com.music.bitchord

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.9 merge kept [com.music.bitchord.data.YtMusicRepository.radio]'s
 * `preferVideo` flag but the Korean AutoPlay path never passed it, so a video
 * station's radio rows were paired back to audio and the flag did nothing.
 * Upstream's own AutoPlay passed it for a video seed; this keeps that wiring
 * from being dropped again.
 */
class AutoplayRadioFlagWiringTest {
    private val source = listOf(
        "app/src/main/java/com/music/bitchord/ko/KoAutoplayTopUp.kt",
        "../app/src/main/java/com/music/bitchord/ko/KoAutoplayTopUp.kt",
    ).map(::File).first { it.isFile }.readText()

    @Test
    fun `a video station asks the radio for its video rows`() {
        assertTrue(
            "video-radio lost preferVideo = true",
            "YtMusicRepository.radio(seed, preferVideo = true)" in source,
        )
    }

    @Test
    fun `a video seed keeps videos through the normal path`() {
        assertTrue(
            "normal autoplay lost the video seed; see upstream loadAutoplayTracks",
            "val videos = seedSong.isVideo && seed == seedSong.videoId" in source,
        )
        assertTrue(
            "normal autoplay lost preferVideo",
            "YtMusicRepository.radio(seed, preferVideo = videos)" in source,
        )
        assertTrue(
            "queue build lost preferVideos",
            "preferVideos = videos" in source,
        )
    }

    @Test
    fun `the personalised session radio asks for the same video pairing`() {
        assertTrue(
            "session radio lost the queue's preferVideos; see rankedExtensions",
            "sessionRadioFor(seedSong, sessionSong, preferVideo = preferVideos)" in source,
        )
    }
}
