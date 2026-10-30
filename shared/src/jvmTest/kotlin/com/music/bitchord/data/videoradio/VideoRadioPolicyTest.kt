package com.music.bitchord.data.videoradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRadioPolicyTest {

    private fun seed(
        title: String = "Dance Monkey (Cover)",
        artist: String = "Oohyo",
        channel: String = "Oohyo",
        isVideo: Boolean = true,
        officialArtistChannel: Boolean = false,
    ) = SeedVideoInfo(
        videoId = "seed",
        title = title,
        artist = artist,
        channelName = channel,
        isVideo = isVideo,
        isOfficialArtistChannel = officialArtistChannel,
    )

    private fun candidate(
        id: String,
        title: String = "Song (Cover)",
        singer: String = "Singer",
        channel: String = singer,
        duration: Int? = 200,
        description: String = "",
        categories: List<String> = emptyList(),
        isShort: Boolean = false,
        isVideoUpload: Boolean = true,
        isUserUpload: Boolean = false,
        songKey: String = "",
        source: VideoSource = VideoSource.MIX,
    ) = VideoCandidate(
        videoId = id,
        title = title,
        channelName = channel,
        singer = singer,
        durationSec = duration,
        description = description,
        categories = categories,
        isShort = isShort,
        isVideoUpload = isVideoUpload,
        isUserUpload = isUserUpload,
        songKey = songKey,
        source = source,
    )

    private fun filter(
        mode: AutoplayMode,
        candidates: List<VideoCandidate>,
        seed: SeedVideoInfo = seed(),
        options: VideoFilterOptions = VideoFilterOptions(),
    ) = filterVideoCandidates(mode, seed, candidates, options)

    @Test
    fun `a cover or live video on an unrelated channel selects video radio`() {
        assertEquals(
            AutoplayMode.VIDEO_RADIO,
            autoplayModeFor(seed(title = "Dance Monkey (Cover)", channel = "Oohyo")),
        )
        assertEquals(
            AutoplayMode.VIDEO_RADIO,
            autoplayModeFor(seed(title = "Dance Monkey (Live)", channel = "Oohyo")),
        )
    }

    @Test
    fun `a topic channel selects normal mode`() {
        assertEquals(
            AutoplayMode.NORMAL,
            autoplayModeFor(seed(channel = "Oohyo - Topic")),
        )
        assertTrue(isTopicChannel("Oohyo - Topic"))
        assertFalse(isTopicChannel("Oohyo"))
    }

    @Test
    fun `an official artist channel selects normal mode`() {
        assertEquals(
            AutoplayMode.NORMAL,
            autoplayModeFor(seed(officialArtistChannel = true)),
        )
    }

    @Test
    fun `an official MV on the artist channel selects normal mode`() {
        listOf(
            "Dance Monkey (Official MV)",
            "Dance Monkey M/V",
            "Dance Monkey MV",
            "Dance Monkey Official Music Video",
        ).forEach { title ->
            assertEquals(
                title,
                AutoplayMode.NORMAL,
                autoplayModeFor(seed(title = title, artist = "Tones and I", channel = "Tones And I")),
            )
        }
    }

    @Test
    fun `an official marker on a fan channel stays video radio`() {
        assertEquals(
            AutoplayMode.VIDEO_RADIO,
            autoplayModeFor(
                seed(
                    title = "Dance Monkey (Official MV)",
                    artist = "Tones and I",
                    channel = "Fan Uploads",
                ),
            ),
        )
    }

    @Test
    fun `an audio row selects normal mode`() {
        assertEquals(AutoplayMode.NORMAL, autoplayModeFor(seed(isVideo = false)))
    }

    @Test
    fun `the runtime window includes 90 seconds and 8 minutes`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("min", duration = 90),
                candidate("max", duration = 480),
                candidate("too-short", duration = 89),
                candidate("too-long", duration = 481),
                candidate("unknown", duration = null),
            ),
        )
        assertEquals(listOf("min", "max"), result.kept.map { it.videoId })
        assertEquals(3, result.dropped[VideoDropReason.DURATION])
    }

    @Test
    fun `shorts are dropped even inside the runtime window`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("flag", duration = 200, isShort = true),
                candidate("short-duration", duration = 60),
                candidate("hashtag", title = "Song (Cover) #shorts", duration = 200),
                candidate("kept", duration = 200),
            ),
        )
        assertEquals(listOf("kept"), result.kept.map { it.videoId })
        assertEquals(3, result.dropped[VideoDropReason.SHORTS])
    }

    @Test
    fun `video radio needs a song marker or a music category`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("plain", title = "A Day In My Life", singer = "Someone"),
                candidate("music-category", title = "A Day In My Life", categories = listOf("Music")),
                candidate("cover-marker", title = "Dance Monkey (Cover)"),
                candidate("korean-marker", title = "댄스 몽키 불러봤", singer = "Someone"),
                candidate("live-marker", title = "Dance Monkey Live"),
                candidate("sing-marker", title = "Sing Dance Monkey"),
                candidate("japanese-marker", title = "Dance Monkey 歌ってみた"),
                candidate("channel-marker", title = "Dance Monkey", channel = "노래 아카이브"),
            ),
        )
        assertEquals(
            listOf(
                "music-category",
                "cover-marker",
                "korean-marker",
                "live-marker",
                "sing-marker",
                "japanese-marker",
                "channel-marker",
            ),
            result.kept.map { it.videoId },
        )
        assertEquals(1, result.dropped[VideoDropReason.NOT_SONG])
    }

    @Test
    fun `a video row with no title or singer is dropped as no metadata`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("blank-title", title = ""),
                candidate("blank-singer", singer = "", channel = ""),
                candidate("kept", title = "Dance Monkey (Cover)"),
            ),
        )
        assertEquals(listOf("kept"), result.kept.map { it.videoId })
        assertEquals(2, result.dropped[VideoDropReason.NO_METADATA])
    }

    @Test
    fun `talk vlog reaction highlight and clip collections are dropped`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("talk", title = "Dance Monkey (Cover) Talk"),
                candidate("vlog", title = "Dance Monkey (Cover) Vlog"),
                candidate("reaction", title = "Dance Monkey (Cover) Reaction"),
                candidate("highlight", title = "Dance Monkey (Cover) Highlight"),
                candidate("clip-collection", title = "Dance Monkey (Cover) Clip Collection"),
                candidate("kept", title = "Dance Monkey (Cover)"),
            ),
        )
        assertEquals(listOf("kept"), result.kept.map { it.videoId })
        assertEquals(5, result.dropped[VideoDropReason.TALK])
    }

    @Test
    fun `recent videos and repeated rows are dropped`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("recent"),
                candidate("kept"),
                candidate("kept"),
            ),
            options = VideoFilterOptions(recentVideoIds = setOf("recent")),
        )
        assertEquals(listOf("kept"), result.kept.map { it.videoId })
        assertEquals(1, result.dropped[VideoDropReason.RECENT])
        assertEquals(1, result.dropped[VideoDropReason.DUPLICATE])
    }

    @Test
    fun `a different cover of the song just played is not queued next`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("cover", title = "Dance Monkey (Cover)"),
                candidate("live", title = "Dance Monkey (Live)"),
                candidate("other", title = "Other Song (Cover)"),
            ),
            options = VideoFilterOptions(previousSongKey = songKeyOf("Dance Monkey")),
        )
        assertEquals(listOf("other"), result.kept.map { it.videoId })
        assertEquals(2, result.dropped[VideoDropReason.SAME_SONG])
    }

    @Test
    fun `normal mode drops arrangements and keeps the original`() {
        val result = filter(
            AutoplayMode.NORMAL,
            seed = seed(title = "Dance Monkey", artist = "Tones and I", channel = "Tones And I - Topic"),
            candidates = listOf(
                candidate("cover", title = "Dance Monkey (Cover)", singer = "Someone"),
                candidate("piano", title = "Dance Monkey (Piano Version)", singer = "Piano Dream"),
                candidate("karaoke", title = "Dance Monkey (Karaoke Version)", singer = "Sing King"),
                candidate("instrumental", title = "Dance Monkey (Instrumental)", singer = "Studio"),
                candidate("mr", title = "Dance Monkey (MR)", singer = "Studio"),
                candidate("eight-bit", title = "Dance Monkey (8-bit)", singer = "Chip"),
                candidate("sixteen-bit", title = "Dance Monkey (16-bit)", singer = "Chip"),
                candidate("lofi", title = "Dance Monkey (Lofi)", singer = "Chill"),
                candidate("original", title = "Dance Monkey", singer = "Tones and I"),
            ),
        )
        assertEquals(listOf("original"), result.kept.map { it.videoId })
        assertEquals(8, result.dropped[VideoDropReason.ARRANGEMENT])
    }

    @Test
    fun `normal mode drops fan clips and user uploads`() {
        val result = filter(
            AutoplayMode.NORMAL,
            seed = seed(title = "Dance Monkey", artist = "Tones and I", channel = "Tones And I - Topic"),
            candidates = listOf(
                candidate("fancam", title = "Dance Monkey Fancam", singer = "Fan"),
                candidate("jikcam", title = "Dance Monkey 직캠", singer = "Fan"),
                candidate("user-upload-marker", title = "Dance Monkey User Upload", singer = "Fan"),
                candidate("user-upload-flag", title = "Dance Monkey", singer = "Fan", isUserUpload = true),
                candidate("original", title = "Dance Monkey", singer = "Tones and I"),
            ),
        )
        assertEquals(listOf("original"), result.kept.map { it.videoId })
        assertEquals(3, result.dropped[VideoDropReason.FAN_CLIP])
        assertEquals(1, result.dropped[VideoDropReason.USER_UPLOAD])
    }

    @Test
    fun `song keys treat cover and live uploads of one song as the same song`() {
        val cover = songKeyOf("Dance Monkey (Cover)")
        val live = songKeyOf("Dance Monkey - Live")
        val official = songKeyOf("Dance Monkey (Official MV)")
        assertEquals(cover, live)
        assertEquals(cover, official)
        assertEquals("dance monkey", cover)
    }

    @Test
    fun `ordering follows one seed singer to two other singers`() {
        val ordered = orderVideoCandidates(
            candidates = listOf(
                candidate("same-1", singer = "Oohyo"),
                candidate("a-1", singer = "A"),
                candidate("a-2", singer = "A"),
                candidate("same-2", singer = "Oohyo"),
                candidate("b-1", singer = "B"),
                candidate("b-2", singer = "B"),
            ),
            seedSinger = "Oohyo",
        )
        assertEquals(
            listOf("Oohyo", "A", "B", "Oohyo", "A", "B"),
            ordered.map { it.singer },
        )
    }

    @Test
    fun `ordering never runs one singer more than twice in a row`() {
        val ordered = orderVideoCandidates(
            candidates = listOf(
                candidate("a-1", title = "Song One (Cover)", singer = "A"),
                candidate("a-2", title = "Song Two (Cover)", singer = "A"),
                candidate("a-3", title = "Song Three (Cover)", singer = "A"),
            ),
            seedSinger = "Oohyo",
        )
        assertEquals(listOf("a-1", "a-2"), ordered.map { it.videoId })
    }

    @Test
    fun `ordering fills the rest from the other group when one runs out`() {
        val ordered = orderVideoCandidates(
            candidates = listOf(
                candidate("same-1", singer = "Oohyo"),
                candidate("same-2", singer = "Oohyo"),
                candidate("other", singer = "A"),
            ),
            seedSinger = "Oohyo",
        )
        assertEquals(listOf("same-1", "other", "same-2"), ordered.map { it.videoId })
    }

    @Test
    fun `ordering avoids putting two covers of one song next to each other`() {
        val ordered = orderVideoCandidates(
            candidates = listOf(
                candidate("cover-a", title = "Dance Monkey (Cover)", singer = "A"),
                candidate("cover-b", title = "Dance Monkey (Live)", singer = "B"),
                candidate("other", title = "Other Song (Cover)", singer = "C"),
            ),
            seedSinger = "Oohyo",
        )
        assertEquals(
            listOf("cover-a", "other", "cover-b"),
            ordered.map { it.videoId },
        )
    }
    @Test
    fun `video radio keeps covers but drops official catalogue rows`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("topic", title = "Dance Monkey", channel = "Tones and I - Topic"),
                candidate(
                    "official-mv",
                    title = "Dance Monkey (Official MV)",
                    singer = "Tones and I",
                    channel = "Tones and I",
                ),
                candidate("mapped-audio", title = "Dance Monkey", isVideoUpload = false),
                candidate("fan-cover", title = "Dance Monkey (Cover)", singer = "Oohyo"),
                candidate("fan-live", title = "Dance Monkey Live", singer = "Oohyo"),
            ),
        )
        assertEquals(listOf("fan-cover", "fan-live"), result.kept.map { it.videoId })
        assertEquals(3, result.dropped[VideoDropReason.OFFICIAL])
    }

    @Test
    fun `video radio still drops arrangements that are not a performance`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("karaoke", title = "Dance Monkey (Karaoke)"),
                candidate("eight-bit", title = "Dance Monkey (8-bit)"),
                candidate("piano", title = "Dance Monkey (Piano Version)"),
                candidate("inst", title = "Dance Monkey (Inst)"),
                candidate("mr", title = "Dance Monkey (MR)"),
                candidate("cover", title = "Dance Monkey (Cover)"),
                candidate("live", title = "Dance Monkey Live"),
            ),
        )
        assertEquals(listOf("cover", "live"), result.kept.map { it.videoId })
        assertEquals(5, result.dropped[VideoDropReason.ARRANGEMENT])
    }

    @Test
    fun `a recent song key drops another cover of the same song`() {
        val result = filter(
            AutoplayMode.VIDEO_RADIO,
            listOf(
                candidate("cover", title = "Dance Monkey (Cover)"),
                candidate("live", title = "Dance Monkey (Live)"),
                candidate("other", title = "Other Song (Cover)"),
            ),
            options = VideoFilterOptions(recentSongKeys = setOf("Dance Monkey")),
        )
        assertEquals(listOf("other"), result.kept.map { it.videoId })
        assertEquals(2, result.dropped[VideoDropReason.SAME_SONG])
    }

    @Test
    fun `a thin video batch falls through same mode then normal then empty`() {
        assertEquals(
            VideoRadioFallback.NONE,
            videoRadioFallbackStage(picked = 3, sameModeExhausted = true, normalAvailable = false),
        )
        assertEquals(
            VideoRadioFallback.SAME_MODE,
            videoRadioFallbackStage(picked = 2, sameModeExhausted = false, normalAvailable = true),
        )
        assertEquals(
            VideoRadioFallback.NORMAL,
            videoRadioFallbackStage(picked = 2, sameModeExhausted = true, normalAvailable = true),
        )
        assertEquals(
            VideoRadioFallback.EMPTY,
            videoRadioFallbackStage(picked = 0, sameModeExhausted = true, normalAvailable = false),
        )
    }

    @Test
    fun `a thin offline batch is reported as partial`() {
        assertEquals("partial", VideoRadioFallback.PARTIAL.wire)
    }

    @Test
    fun `a thin video pick is kept when the normal fallback cannot answer`() {
        // A broken or empty ordinary radio must not throw away the one or two
        // video rows that are already playable.
        assertEquals(
            VideoRadioFallback.PARTIAL,
            videoRadioFallbackStage(
                picked = 2,
                sameModeExhausted = true,
                normalAvailable = false,
                partialAvailable = true,
            ),
        )
        assertEquals(
            VideoRadioFallback.EMPTY,
            videoRadioFallbackStage(
                picked = 0,
                sameModeExhausted = true,
                normalAvailable = false,
                partialAvailable = true,
            ),
        )
    }

}
