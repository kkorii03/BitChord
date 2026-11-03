package com.music.bitchord.data.stats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row must stay a video through the local history read and its file. */
class ListeningStatsVideoRoundTripTest {

    @Test
    fun `a video row stays a video after the recent read`() {
        val entry = TrackEntry(
            id = "vid_video",
            title = "Video Song",
            artist = "Video Artist",
            isVideo = true,
        )

        assertTrue(entry.asSong().isVideo)
    }

    @Test
    fun `a video row stays a video after serialization`() {
        val bucket = StoredBucket(
            month = "2026-11",
            tracks = listOf(
                TrackEntry(
                    id = "vid_video",
                    title = "Video Song",
                    artist = "Video Artist",
                    isVideo = true,
                ),
            ),
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val decoded = json.decodeFromString(
            StoredBucket.serializer(),
            json.encodeToString(StoredBucket.serializer(), bucket),
        )

        assertEquals(true, decoded.tracks.single().isVideo)
    }

    @Test
    fun `merging a row without a flag keeps a known video flag`() {
        val unknown = TrackEntry(id = "vid_video", title = "Video Song", artist = "Video Artist")
        val known = TrackEntry(id = "vid_video", title = "Video Song", artist = "Video Artist", isVideo = true)

        unknown.absorb(known)

        assertEquals(true, unknown.isVideo)
    }
}
