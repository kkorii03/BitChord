package com.music.bitchord

import com.music.bitchord.playback.StreamContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamContainerTest {

    @Test
    fun `dash reopen changes virtual uri while preserving playback identity`() {
        val original = "bitchord://watch?v=iQnRCdtECl8&n=Wait&a=M83&d=344"

        val reopened = StreamContainer.markedForManifestReopen(
            original,
            "application/dash+xml",
        )

        assertNotEquals(original, reopened)
        assertEquals("$original&manifest_reopen=dash", reopened)
    }

    @Test
    fun `hls source-track reopen preserves source parameters and fragment`() {
        val original = "bitchord://source?s=addon-1&t=track-2&n=Wait#part"

        assertEquals(
            "bitchord://source?s=addon-1&t=track-2&n=Wait&manifest_reopen=hls#part",
            StreamContainer.markedForManifestReopen(
                original,
                "application/x-mpegURL",
            ),
        )
    }

    @Test
    fun `non-manifest mime does not force a source rebuild`() {
        val original = "bitchord://watch?v=iQnRCdtECl8"

        assertEquals(
            original,
            StreamContainer.markedForManifestReopen(original, "audio/flac"),
        )
    }

    @Test
    fun `the same container and length is the same rendition`() {
        val original = "https://rr.googlevideo.com/a?mime=audio%2Fwebm&clen=7340032&c=WEB"
        val replacement = "https://rr.googlevideo.com/b?mime=audio%2Fwebm&clen=7340032&c=ANDROID"

        assertTrue(StreamContainer.sameRendition(original, replacement))
    }

    @Test
    fun `a different length is not the same rendition`() {
        val original = "https://rr.googlevideo.com/a?mime=audio%2Fwebm&clen=7340032"
        val replacement = "https://rr.googlevideo.com/b?mime=audio%2Fwebm&clen=6291456"

        assertFalse(StreamContainer.sameRendition(original, replacement))
    }

    @Test
    fun `a different container is not the same rendition`() {
        val original = "https://rr.googlevideo.com/a?mime=audio%2Fwebm&clen=7340032"
        val replacement = "https://rr.googlevideo.com/b?mime=audio%2Fmp4&clen=7340032"

        assertFalse(StreamContainer.sameRendition(original, replacement))
    }
}
