package com.music.bitchord.data.innertube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which container a download may keep.
 *
 * An app-private download prefers Opus-in-WebM but accepts either container,
 * so an InnerTubeX answer that came back as AAC-in-MP4 must not be discarded
 * just because Opus was preferred. Only an export to the system Music
 * collection insists on MP4. Before this rule was shared, the non-export path
 * required WebM here while the download walk accepted both, so an MP4 answer
 * was thrown away and extraction was asked for an Opus rendition the track
 * might not have.
 */
class DownloadContainerPolicyTest {

    @Test
    fun `an app private download keeps either container`() {
        assertTrue(StreamResolver.downloadContainerAllowed("m4a", requireM4a = false))
        assertTrue(StreamResolver.downloadContainerAllowed("webm", requireM4a = false))
    }

    @Test
    fun `an exported download keeps only mp4`() {
        assertTrue(StreamResolver.downloadContainerAllowed("m4a", requireM4a = true))
        assertFalse(StreamResolver.downloadContainerAllowed("webm", requireM4a = true))
    }

    @Test
    fun `the container names match what the stream reports`() {
        val mp4 = StreamResolver.Stream(
            url = "https://rr1.googlevideo.com/videoplayback?mime=audio%2Fmp4",
            kbps = 128,
            mimeType = "audio/mp4",
        )
        val webm = StreamResolver.Stream(
            url = "https://rr1.googlevideo.com/videoplayback?mime=audio%2Fwebm",
            kbps = 160,
            mimeType = "audio/webm",
        )

        assertTrue(StreamResolver.downloadContainerAllowed(mp4.downloadExtension, false))
        assertTrue(StreamResolver.downloadContainerAllowed(webm.downloadExtension, false))
        assertTrue(StreamResolver.downloadContainerAllowed(mp4.downloadExtension, true))
        assertFalse(StreamResolver.downloadContainerAllowed(webm.downloadExtension, true))
    }

    /**
     * A mid-download re-resolve may keep the same container only. The length
     * check beside this one cannot catch a container switch: an MP4 and a WebM
     * rendition frequently report the same total length, and appending one to
     * the other leaves a file of exactly the right size that no extractor can
     * read.
     */
    @Test
    fun `a resumed download may not switch container`() {
        val original = StreamResolver.Stream(
            url = "https://rr1.googlevideo.com/videoplayback?mime=audio%2Fwebm",
            kbps = 160,
            mimeType = "audio/webm",
        )
        val same = StreamResolver.Stream(
            url = "https://rr2.googlevideo.com/videoplayback?mime=audio%2Fwebm",
            kbps = 160,
            mimeType = "audio/webm",
        )
        val other = StreamResolver.Stream(
            url = "https://rr3.googlevideo.com/videoplayback?mime=audio%2Fmp4",
            kbps = 128,
            mimeType = "audio/mp4",
        )

        assertTrue(StreamResolver.downloadResumeAllowed(original, same))
        assertFalse(StreamResolver.downloadResumeAllowed(original, other))
    }
}
