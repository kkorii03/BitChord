package com.music.bitchord.data

import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * 134d/135c/136b (2026-11-07): 280 `catalogue mapping: no matching track`
     * lines and no `(catalogue)` hit, and the line named neither the row's id
     * nor why the albums it opened held nothing. The fork's log rule allows no
     * typed text, so the query is reported as a length and a hash, and the row
     * only as the video id the log can be joined back to.
     */
    @Test
    fun `a catalogue miss names its exit and counts without any typed text`() {
        val query = "Bruno Mars When I Was Your Man"
        val line = CatalogSongMapper.missLine(
            sourceVideoId = "ekzHIouo8Q4",
            sourceIsVideo = true,
            reason = "no-album-artist",
            query = query,
            albums = 4,
            matching = 0,
            pages = 0,
            tracks = 0,
            candidates = 0,
        )
        assertTrue(line.startsWith("catalogue mapping: no matching track videoId=ekzHIouo8Q4"), line)
        assertTrue(line.contains("(video source)"), line)
        assertTrue(line.contains("reason=no-album-artist"), line)
        for (field in listOf("albums=4", "matching=0", "pages=0", "tracks=0", "candidates=0")) {
            assertTrue(line.contains(field), line)
        }
        assertTrue(line.contains("queryLength=${query.length}"), line)
        // Nothing the listener typed, and no display text from the row.
        for (text in listOf("When I Was Your Man", "Bruno Mars", query)) {
            assertFalse(line.contains(text, ignoreCase = true), "$text leaked into: $line")
        }
        // Not the old "'title' / 'artist'" shape either.
        assertFalse(line.contains(" for '"), line)
        // The hash is the line's last field, so no count before it can be read
        // as part of it, and it is the query's own hash.
        assertEquals(Integer.toHexString(query.hashCode()), queryHashOf(line))
        assertEquals(
            queryHashOf(line),
            queryHashOf(
                CatalogSongMapper.missLine("id", false, "no-track", query, 1, 1, 1, 1, 1),
            ),
        )
    }

    /** The `queryHash=` value, which must close the line. */
    private fun queryHashOf(line: String): String =
        Regex("""queryHash=([0-9a-fA-F]+)$""").find(line)?.groupValues?.get(1)
            ?: fail("no trailing queryHash field in: $line")

    private fun album(browseId: String, title: String, subtitle: String) = BrowseItem(
        browseId = browseId,
        title = title,
        subtitle = subtitle,
        thumbnailUrl = null,
        type = BrowseType.ALBUM,
    )

    /**
     * The album filter answers a song's *releases* by relevance: a sped-up
     * take and the artist's other albums are listed above the album that holds
     * the song. Taking the first three rows opened three pages that cannot
     * answer the lookup and logged a miss — the 2026-11-08 run's 280 misses,
     * none of them a hit. Rows below are the live album-filter answer for
     * `Sabrina Carpenter - Feather` (title, subtitle and browse id verbatim).
     */
    @Test
    fun `an album is opened ahead of the singles and EPs that outrank it`() {
        val albums = listOf(
            album("MPREb_VwIvkLTnuVy", "Sabrina Carpenter - Feather - Piano Cover", "싱글 • Piano Covers • 2024년"),
            album("MPREb_Vs4cQnUGPVT", "Feather (Sped Up)", "싱글 • Sabrina Carpenter • 2023년"),
            album("MPREb_FN2r5jrFXVm", "Short n' Sweet", "앨범 • Sabrina Carpenter • 2024년"),
            album(
                "MPREb_eBfNDFBZaai",
                "Feather (Originally Performed by Sabrina Carpenter) (Karaoke Version)",
                "싱글 • karaoke SESH • 2024년",
            ),
            album("MPREb_msfRVJDqlXJ", "Man’s Best Friend", "앨범 • Sabrina Carpenter • 2025년"),
            album("MPREb_fruitcake", "fruitcake", "EP • Sabrina Carpenter • 2023년"),
            album("MPREb_fwd", "emails i can’t send fwd:", "앨범 • Sabrina Carpenter • 2023년"),
        )

        assertEquals(
            listOf("MPREb_FN2r5jrFXVm", "MPREb_msfRVJDqlXJ", "MPREb_fwd"),
            CatalogSongMapper.albumPagesFor(albums, "Sabrina Carpenter").map { it.browseId },
        )
    }

    /** The same shape for `Bruno Mars - Treasure`: the album is the fifth row. */
    @Test
    fun `the release that holds the song is opened even when singles outrank it`() {
        val albums = listOf(
            album("MPREb_ThyGRhfjSbZ", "The Romantic", "앨범 • Bruno Mars • 2026년"),
            album("MPREb_YfEwkO1JDBG", "Treasure: Tribute to Bruno Mars", "싱글 • Nick Down • 2014년"),
            album("MPREb_wl7Cx4CHeez", "Treasure Remix EP", "EP • Bruno Mars • 2013년"),
            album("MPREb_mW7gTIMDkSl", "24K Magic", "앨범 • Bruno Mars • 2016년"),
            album("MPREb_HvNTLCfP5Jk", "Unorthodox Jukebox", "앨범 • Bruno Mars • 2012년"),
        )

        assertEquals(
            listOf("MPREb_ThyGRhfjSbZ", "MPREb_mW7gTIMDkSl", "MPREb_HvNTLCfP5Jk"),
            CatalogSongMapper.albumPagesFor(albums, "Bruno Mars").map { it.browseId },
        )
    }

    /** A song released only as a single must still open that single. */
    @Test
    fun `a song released only as a single still opens its single`() {
        val albums = listOf(album("MPREb_vgko26UcRX3", "Flowers", "싱글 • Miley Cyrus • 2023년"))

        assertEquals(
            listOf("MPREb_vgko26UcRX3"),
            CatalogSongMapper.albumPagesFor(albums, "Miley Cyrus").map { it.browseId },
        )
    }

    /**
     * Review H3: the album subtitle parser already splits `•`, `·` and `,`,
     * but the short-release rule only read the bullet shape, so a comma or
     * middle-dot single/EP was ranked as a full album.
     */
    @Test
    fun `single and EP subtitles split by comma or middle dot are short releases`() {
        assertTrue(CatalogSongMapper.isShortRelease("싱글 • Miley Cyrus • 2023"))
        assertTrue(CatalogSongMapper.isShortRelease("Miley Cyrus, 싱글, 2023"))
        assertTrue(CatalogSongMapper.isShortRelease("EP · Bruno Mars · 2013"))
        assertTrue(CatalogSongMapper.isShortRelease("Bruno Mars, EP, 2013"))
        assertFalse(CatalogSongMapper.isShortRelease("앨범 • Bruno Mars • 2012"))
        assertFalse(CatalogSongMapper.isShortRelease("Bruno Mars, 앨범, 2026년"))
    }

    /**
     * Review M3: the search shortcut took the raw relevance order, so the
     * same album-after-singles miss came back on that path.
     */
    @Test
    fun `a search shortcut opens the album ahead of a single ranked above it`() {
        val albums = listOf(
            album("single", "Feather (Sped Up)", "싱글 • Sabrina Carpenter • 2023년"),
            album("album", "Short n' Sweet", "앨범 • Sabrina Carpenter • 2024년"),
            album("ep", "Treasure Remix EP", "Bruno Mars, EP, 2013"),
        )

        assertEquals(
            listOf("album", "single"),
            CatalogSongMapper.searchAlbumsFor(albums).map { it.browseId },
        )
    }

    /**
     * Review M2: the priority lane is a separate single permit, so the
     * process-wide ceiling is MAX_CONCURRENT + 1, not MAX_CONCURRENT.
     */
    @Test
    fun `the shared ceiling plus the priority lane is the stated total`() {
        assertEquals(2, CatalogSongMapper.MAX_CONCURRENT)
        assertEquals(3, CatalogSongMapper.MAX_CONCURRENT_TOTAL)
    }

    /** Other artists' pages are never opened, and the page cap is unchanged. */
    @Test
    fun `pages of other artists and pages past the cap are not opened`() {
        val albums = listOf(
            album("a", "A", "앨범 • Bruno Mars • 2024년"),
            album("cover", "When I Was Your Man (Bruno Mars)", "싱글 • Fabio Rodrigues • 2021년"),
            album("b", "B", "앨범 • Bruno Mars • 2024년"),
            album("c", "C", "앨범 • Bruno Mars • 2024년"),
            album("d", "D", "앨범 • Bruno Mars • 2024년"),
        )

        assertEquals(
            listOf("a", "b", "c"),
            CatalogSongMapper.albumPagesFor(albums, "Bruno Mars").map { it.browseId },
        )
    }

    /**
     * The Songs filter answers no rows from a Korean IP, so the youtube.com
     * uploads are mapped instead — twenty unrelated uploads, each its own
     * album search. A song-version search reads the row it was given, so its
     * page must not spend that (triage 2026-11-08).
     */
    @Test
    fun `the song version search maps no unrelated upload rows`() {
        assertFalse(CatalogSongMapper.mapsPageRows(SearchKind.AUDIO_SWITCH))
        assertTrue(CatalogSongMapper.mapsPageRows(SearchKind.TYPED))
        assertTrue(CatalogSongMapper.mapsPageRows(SearchKind.CATALOG))
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

    @Test
    fun `a mapped row keeps the upload title it was mapped from`() {
        val catalog = CatalogSongMapper.CatalogSong(
            videoId = "qsqvJpOgrWw",
            title = "체포하겠어",
            artist = "ILLIT",
            albumTitle = "BREAK EVEN",
            albumId = "MPREb_album",
            artworkUrl = null,
            durationText = "3:05",
        )
        // The fanchant upload is the only row that prints both names of the
        // song; replacing its title with the release's lost the other one.
        val upload = Song(
            videoId = "V2V9-9VhYJo",
            title = "ILLIT (아일릿) '체포하겠어 (You're Under Arrest)'",
            artist = "ILLIT",
            thumbnailUrl = null,
            isVideo = true,
        )

        val mapped = catalog.toSong(upload)
        assertEquals("체포하겠어", mapped.title)
        assertEquals(upload.title, mapped.uploadTitle)
        // Kept for matching only: the row is not a cover, so nothing that
        // reads a cover out of a row may see it.
        assertNull(mapped.originalTitle)
        assertFalse(CatalogSongMapper.isCover(mapped))
        // A source that already names the release adds no second name.
        assertNull(catalog.toSong(upload.copy(title = "체포하겠어")).uploadTitle)
    }
}
