package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure decisions behind [CatalogSongMapper]: which credit is the same act,
 * which title is a cover, when two runtimes agree, and when a stored miss may
 * be asked again.
 */
class CatalogSongMapperTest {

    @Test
    fun `case brackets and Korean aliases fold to the same artist`() {
        assertTrue(CatalogSongMapper.artistsMatch("IU", "IU(아이유)"))
        assertTrue(CatalogSongMapper.artistsMatch("아이유", "IU"))
        assertTrue(CatalogSongMapper.artistsMatch("iu", "Album • IU(아이유) • 2024"))
        assertTrue(CatalogSongMapper.artistsMatch("BLACKPINK", "블랙핑크"))
        assertTrue(CatalogSongMapper.artistsMatch("헤이즈 (Heize)", "HEIZE"))
    }

    @Test
    fun `different artists never fold together`() {
        assertFalse(CatalogSongMapper.artistsMatch("IU", "BTS"))
        assertFalse(CatalogSongMapper.artistsMatch("아이유", "아이유즈"))
        assertFalse(CatalogSongMapper.artistsMatch("", "IU"))
        assertFalse(CatalogSongMapper.artistsMatch("IU", ""))
    }

    @Test
    fun `cover uploads and cover-tagged tracks are excluded`() {
        assertTrue(CatalogSongMapper.isCoverTitle("Shape of You (cover by Jimin)"))
        assertTrue(CatalogSongMapper.isCoverTitle("금요일에 만나요 - 커버"))
        assertTrue(CatalogSongMapper.isCoverTitle("Friday (Cover)"))
        assertFalse(CatalogSongMapper.isCoverTitle("Friday"))
        assertFalse(CatalogSongMapper.isCoverTitle("Take Cover"))

        val cover = Song(
            videoId = "cover",
            title = "Friday (Cover)",
            artist = "Some Singer",
            thumbnailUrl = null,
        )
        val original = cover.copy(title = "Friday", artist = "IU")
        assertTrue(CatalogSongMapper.isCover(cover))
        assertFalse(CatalogSongMapper.isCover(original))
    }

    @Test
    fun `run times agree only within ten seconds when both are stated`() {
        assertTrue(CatalogSongMapper.durationCompatible("3:30", "3:39"))
        assertTrue(CatalogSongMapper.durationCompatible("3:30", "3:40"))
        assertFalse(CatalogSongMapper.durationCompatible("3:30", "3:41"))
        assertFalse(CatalogSongMapper.durationCompatible("4:12", "3:45"))
        assertTrue(CatalogSongMapper.durationCompatible(null, "3:41"))
        assertTrue(CatalogSongMapper.durationCompatible("3:30", null))
        // A music video's visual intro can make it a minute longer than its
        // album track; the video-source window admits that (The Hills).
        assertFalse(CatalogSongMapper.durationCompatible("4:07", "3:55"))
        assertTrue(CatalogSongMapper.durationCompatible("4:07", "3:55", 90))
        // A video can be minutes longer than its song ("Born This Way" 7:19
        // video, 4:20 album) and still be the same recording; an album cut
        // much *longer* than the video is a different edit and stays refused.
        assertFalse(CatalogSongMapper.durationCompatible("7:19", "4:20", 90))
        assertTrue(
            CatalogSongMapper.durationCompatible(
                "7:19",
                "4:20",
                90,
                allowVideoLongerThanSong = true,
            ),
        )
        assertFalse(
            CatalogSongMapper.durationCompatible(
                "3:20",
                "5:20",
                90,
                allowVideoLongerThanSong = true,
            ),
        )
    }

    @Test
    fun `a stated but malformed run time fails closed`() {
        assertFalse(CatalogSongMapper.durationCompatible("3:20", "3:60"))
        assertFalse(CatalogSongMapper.durationCompatible("3:60", "3:20"))
        assertFalse(CatalogSongMapper.durationCompatible("3:20", "not-a-time"))
        assertTrue(CatalogSongMapper.durationCompatible("", "3:20"))
        assertTrue(CatalogSongMapper.durationCompatible("3:20", "  "))
    }

    @Test
    fun `a stored miss is retried after seven days`() {
        val now = 10_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        assertFalse(CatalogSongMapper.shouldRetryMiss(now - 6 * day, now))
        assertFalse(CatalogSongMapper.shouldRetryMiss(now - 7 * day + 1, now))
        assertTrue(CatalogSongMapper.shouldRetryMiss(now - 7 * day, now))
        assertTrue(CatalogSongMapper.shouldRetryMiss(now - 30 * day, now))
        assertTrue(CatalogSongMapper.shouldRetryMiss(0L, now))
    }

    @Test
    fun `a track title answers a query carrying the artist`() {
        assertTrue(CatalogSongMapper.trackMatchesQuery("IU 금요일에 만나요", "금요일에 만나요"))
        assertTrue(CatalogSongMapper.trackMatchesQuery("Friday", "Friday (feat. Jang Yi-jeong)"))
        assertFalse(CatalogSongMapper.trackMatchesQuery("IU 금요일에 만나요", "밤편지"))
        assertFalse(CatalogSongMapper.trackMatchesQuery("", "Friday"))
    }

    @Test
    fun `a bracketed Korean spelling is another title for the same song`() {
        assertEquals(
            listOf("금요일에 만나요"),
            CatalogSongMapper.alternateTitles("Friday(금요일에 만나요) (Feat. Jang Yi-jeong)"),
        )
        assertTrue(CatalogSongMapper.alternateTitles("Friday (Live)").isEmpty())
        assertTrue(CatalogSongMapper.alternateTitles("Friday").isEmpty())
    }

    @Test
    fun `a cached catalogue song survives encoding`() {
        val song = CatalogSongMapper.CatalogSong(
            videoId = "catalogue-id",
            title = "Friday",
            artist = "IU",
            albumTitle = "Modern Times",
            albumId = "MPREb_album",
            artworkUrl = "https://yt3.googleusercontent.com/cover",
            durationText = "3:30",
        )
        assertEquals(song, CatalogSongMapper.decodeForTest(CatalogSongMapper.encodeForTest(song)))
    }

    @Test
    fun `a malformed cache value is not a catalogue song`() {
        assertNull(CatalogSongMapper.decodeForTest("none"))
        assertNull(CatalogSongMapper.decodeForTest("only\u001Ffour\u001Ffields\u001Fhere"))
        assertNull(CatalogSongMapper.decodeForTest("\u001Ftitle\u001Fartist\u001Falbum\u001Fid"))
    }

    @Test
    fun `the mapped row takes the catalogue identity and becomes audio`() {
        val catalog = CatalogSongMapper.CatalogSong(
            videoId = "catalogue-id",
            title = "Friday",
            artist = "IU",
            albumTitle = "Modern Times",
            albumId = "MPREb_album",
            artworkUrl = "https://yt3.googleusercontent.com/cover",
            durationText = "3:30",
        )
        val source = Song(
            videoId = "video-id",
            title = "Friday (Official Video)",
            artist = "IU - Topic",
            thumbnailUrl = "https://i.ytimg.com/vi/video-id/hqdefault.jpg",
            durationText = "3:45",
            albumName = "Upload",
            albumId = "MPRE_upload",
            artistId = "UC_upload",
            setVideoId = "set",
            isVideo = true,
            typeUnknown = true,
            isVideoOrigin = true,
        )

        val mapped = catalog.toSong(source)
        assertEquals("catalogue-id", mapped.videoId)
        assertEquals("Friday", mapped.title)
        assertEquals("IU", mapped.artist)
        assertEquals("https://yt3.googleusercontent.com/cover", mapped.thumbnailUrl)
        assertEquals("3:30", mapped.durationText)
        assertEquals("MPREb_album", mapped.albumId)
        assertEquals("Modern Times", mapped.albumName)
        assertNull(mapped.artistId)
        assertTrue(mapped.artists.isEmpty())
        assertNull(mapped.setVideoId)
        assertFalse(mapped.isVideo)
        assertFalse(mapped.typeUnknown)
        assertFalse(mapped.isVideoOrigin)
    }

    @Test
    fun `a local video's catalogue row does not inherit the video file`() {
        val catalog = CatalogSongMapper.CatalogSong(
            videoId = "catalogue-id",
            title = "7 rings",
            artist = "Ariana Grande",
            albumTitle = "thank u, next",
            albumId = "MPREb_album",
            artworkUrl = null,
            durationText = "2:58",
        )
        val source = Song(
            videoId = "QYh6mYIJG2Y",
            title = "7 rings (Official Video)",
            artist = "Ariana Grande",
            thumbnailUrl = null,
            durationText = "3:04",
            isVideo = true,
            localUri = "content://media/external/audio/media/7",
            localPath = "/music/7 rings.webm",
        )

        val mapped = catalog.toSong(source)

        assertEquals("catalogue-id", mapped.videoId)
        assertNull(mapped.localUri)
        assertNull(mapped.localPath)
    }

    @Test
    fun `an unknown-type row is a video source`() {
        assertTrue(
            CatalogSongMapper.isVideoSource(
                Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null, typeUnknown = true),
            ),
        )
        assertTrue(
            CatalogSongMapper.isVideoSource(
                Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null, isVideo = true),
            ),
        )
        assertFalse(
            CatalogSongMapper.isVideoSource(
                Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null),
            ),
        )
    }

    @Test
    fun `the album subtitle names the artist for an album search`() {
        assertTrue(CatalogSongMapper.albumArtistMatches("IU", "Album • IU • 2023"))
        assertTrue(CatalogSongMapper.albumArtistMatches("아이유", "Album • IU(아이유) • 2023"))
        assertFalse(CatalogSongMapper.albumArtistMatches("IU", "Album • BTS • 2023"))
        assertFalse(CatalogSongMapper.albumArtistMatches("", "Album • IU • 2023"))
    }

    @Test
    fun `a different take does not answer a title query`() {
        assertFalse(CatalogSongMapper.trackMatchesQuery("Friday", "Friday (Live)"))
        assertFalse(CatalogSongMapper.trackMatchesQuery("Friday", "Friday (Remix)"))
        assertTrue(CatalogSongMapper.trackMatchesQuery("Friday", "Friday (Official Video)"))
    }

    @Test
    fun `only bracketed names of the song become alternate titles`() {
        assertEquals(
            listOf("금요일에 만나요", "Friday"),
            CatalogSongMapper.alternateTitles("Friday(금요일에 만나요)(Friday)"),
        )
        assertTrue(CatalogSongMapper.alternateTitles("Friday (2024)").isEmpty())
        assertTrue(CatalogSongMapper.alternateTitles("Friday (Official Video)").isEmpty())
        assertTrue(CatalogSongMapper.alternateTitles("Friday (Remix)").isEmpty())
    }
}
