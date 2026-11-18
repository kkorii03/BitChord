package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ⋮ menu's "이 채널을 커버 채널로 지정" row used to show for every video
 * row, the artist's own official MV included (phone 11-18: "What Do You
 * Mean?" DK_0jXPuIr0 on Justin Bieber's channel). The old rule was
 * `song.isVideo` alone, which every case below satisfies.
 */
class KoCoverChannelOfferTest {

    private fun video(title: String, channel: String, type: String? = null) = Song(
        videoId = "DK_0jXPuIr0",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        durationText = "3:27",
        artistId = "UCchannel",
        isVideo = true,
        musicVideoType = type,
    )

    @Test
    fun `the artist's official MV is not offered as a cover channel`() {
        // YouTube Music's row: no "official" word, typed OMV.
        assertFalse(offersCoverChannelMark(video("What Do You Mean?", "Justin Bieber", "MUSIC_VIDEO_TYPE_OMV"), designated = false))
        // youtube.com's row: no type, but "Official Music Video" on the artist's VEVO channel.
        assertFalse(
            offersCoverChannelMark(
                video("Justin Bieber - What Do You Mean? (Official Music Video)", "JustinBieberVEVO"),
                designated = false,
            ),
        )
        assertFalse(offersCoverChannelMark(video("What Do You Mean?", "Justin Bieber - Topic"), designated = false))
    }

    @Test
    fun `an upload that could be a cover is still offered`() {
        assertTrue(offersCoverChannelMark(video("Not a Dream", "아카네 리제", "MUSIC_VIDEO_TYPE_UGC"), designated = false))
        assertTrue(offersCoverChannelMark(video("Dance Monkey (Cover)", "오화요"), designated = false))
    }

    @Test
    fun `a channel already marked keeps the row so the mark can be undone`() {
        assertTrue(offersCoverChannelMark(video("What Do You Mean?", "Justin Bieber", "MUSIC_VIDEO_TYPE_OMV"), designated = true))
    }
}
