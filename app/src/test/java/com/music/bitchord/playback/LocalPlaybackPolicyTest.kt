package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackPolicyTest {

    @Test
    fun `a streamed song with a saved file under the same id is not local`() {
        assertFalse(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = "bitchord://watch?v=song",
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = "content://downloads/song.m4a",
            ),
        )
        assertFalse(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = "https://example.test/song",
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = "content://downloads/song.m4a",
            ),
        )
    }

    @Test
    fun `a file item is local even without a download record`() {
        assertTrue(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = "file:///storage/song.flac",
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = null,
            ),
        )
        assertTrue(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = null,
                requestMetadataUri = "content://media/song.flac",
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = null,
            ),
        )
    }

    @Test
    fun `an item with no source of its own falls back to the saved file`() {
        assertTrue(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = null,
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = "content://downloads/song.m4a",
            ),
        )
        assertFalse(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = null,
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = null,
            ),
        )
    }

    @Test
    fun `file media ids and extra local paths are local`() {
        assertTrue(
            LocalPlaybackPolicy.isLocal(
                mediaId = "content://media/song",
                localConfigurationUri = "https://example.test/song",
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = null,
                savedUri = null,
            ),
        )
        assertTrue(
            LocalPlaybackPolicy.isLocal(
                mediaId = "song",
                localConfigurationUri = "https://example.test/song",
                requestMetadataUri = null,
                extraLocalUri = null,
                extraLocalPath = "/storage/song.flac",
                savedUri = null,
            ),
        )
    }
}
