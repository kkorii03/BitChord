package com.music.bitchord.data.sources

import com.music.bitchord.data.CatalogSongMapper
import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.cover.YouTubeCoverResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoAudioSwitch
import com.music.bitchord.ko.KoTrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The song-version search now reads its Songs page before the iTunes/Deezer
 * cover pass, and reads the covered page only when the plain one has no
 * answer (opus-mvswap, 11-09). The cover pass is not only artwork: a
 * confirmed upload also takes the store's title and credit, so this checks
 * that the plain page picks what the covered page picked.
 *
 * The pages are the real ones the MV rows of rs18-rs20 searched: the
 * youtube.com rows of `godzilla eminem`, `hero mariah carey` and
 * `좋은 날 아이유` (www.youtube.com search, hl=ko gl=KR, 2026-11-09), and the
 * album tracks forSearch puts in front of them (music.youtube.com album search
 * + page, same day). Every youtube.com row there is a video row (no Topic
 * channel, a 16:9 frame), so the candidates the matcher can adopt are the
 * album tracks in both passes. The covered rows use Deezer's real answer for
 * each song (iTunes' KR store answers no songs), renamed the way
 * [YouTubeCoverResolver.enrich] renames: only when [YouTubeCoverResolver.accept]
 * confirms the recording, and never a cover's credit.
 */
class AudioSwitchCoverPassTest {

    private fun mv(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hq720.jpg",
        durationText = duration,
        isVideo = true,
    )

    /** A youtube.com search row as forSearch keeps it: cleaned, not mapped. */
    private fun upload(id: String, title: String, owner: String, duration: String) =
        YouTubeDisplayCleaner.clean(mv(id, title, owner, duration))

    private fun track(id: String, title: String, artist: String, duration: String, album: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = "https://lh3.googleusercontent.com/$album=w544-h544",
        durationText = duration,
        albumId = album,
    )

    /** [rows] after the cover pass, given the store's answers for the song. */
    private fun covered(rows: List<Song>, store: List<Pair<String, String>>): List<Song> = rows.map { row ->
        if (!YouTubeCoverResolver.isVideoThumbnail(row.thumbnailUrl)) return@map row
        val target = TrackMatcher.targetOf(row)
        val artist = YouTubeCoverResolver.cleanArtist(target.artist)
        val match = store.firstOrNull { (title, credit) ->
            YouTubeCoverResolver.accept(target.title, artist, title, credit)
        } ?: return@map row
        val keepCredit = CoverTitles.isCover(row)
        row.copy(
            title = if (keepCredit) row.title else match.first,
            artist = if (keepCredit) row.artist else match.second,
            thumbnailUrl = "https://cdn-images.dzcdn.net/images/cover/x/1000x1000.jpg",
        )
    }

    private fun pick(video: Song, page: List<Song>): String? {
        val row = KoAudioSwitch.audioSwitchRow(video)
        val target = TrackMatcher.audioSwitchTargetOf(row).copy(isVideo = true)
        return KoAudioSwitch.adoptBest(page, page.filterNot { it.isVideo }, target, video, 15)?.videoId
    }

    private fun assertSamePick(
        expected: String,
        video: Song,
        page: List<Song>,
        store: List<Pair<String, String>>,
    ) {
        val withCovers = covered(page, store)
        assertEquals(
            page.filterNot { it.isVideo }.map { it.videoId },
            withCovers.filterNot { it.isVideo }.map { it.videoId },
            "the cover pass changed which rows are candidates",
        )
        assertEquals(expected, pick(video, page), "plain page")
        assertEquals(expected, pick(video, withCovers), "covered page")
    }

    @Test
    fun `godzilla picks the same album track with or without covers`() {
        val page = listOf(
            track("5UpETVVdRaA", "Godzilla", "Eminem", "3:31", "MPREb_WALokSukf0y"),
            upload("s1WC62y07Ak", "Eminem - Godzilla (Feat. Juice WRLD)ㅣ Lyrics / 가사", "음악 자막 채널", "3:33"),
            upload("r_0JjYUe5jo", "Eminem - Godzilla ft. Juice WRLD (Official Music Video)", "Lyrical Lemonade", "4:27"),
            upload("9XvXF1LrWgA", "Godzilla (feat. Juice WRLD) [Official Audio]", "EminemMusic", "3:31"),
            upload("3qFvCPmee8U", "Eminem - Godzilla (Lyric Video) ft. Juice WRLD", "EminemMusic", "3:33"),
            upload("Su-VBZRd5iQ", "Eminem - Godzilla ft. Juice WRLD [가사/해석/lyrics]", "아이스티", "3:32"),
            upload("OwONpOi6RYE", "Godzilla", "EminemMusic", "3:31"),
            upload("-na_XhgtPRM", "Eminem ft. Juice WRLD - Godzilla (LYRICS) — Uproxx Music", "UPROXX Music", "3:31"),
            upload("WuKhh7n9eBM", "EMINEM - GODZILLA (FT. JUICE WRLD) COVER", "YNL", "3:33"),
            upload("49OY0kV0n_M", "Eminem - Godzilla ft. Juice WRLD [Animated Music Video]", "Meraki United by Randy Chriz", "3:57"),
            upload("TtxuIf9siU4", "Karaoke♬ Godzilla (ft. Juice WRLD)  - Eminem 【No Guide Melody】 Instrumental", "EdKara", "3:40"),
            upload("SmBzqkgdH9I", "Eminem - Godzilla (Lyrics) ft. Juice WRLD", "FutureHype", "3:31"),
            upload("u4xZpzNUBsc", "Eminem (ft Juice Wrld) Godzilla (Slowed + Reverb)", "LegendaryEditz", "4:16"),
        )
        assertSamePick(
            "5UpETVVdRaA",
            mv("r_0JjYUe5jo", "Godzilla (feat. Juice WRLD)", "Eminem", "4:27"),
            page,
            store = listOf("Godzilla" to "Eminem", "Godzilla (Eminem) Versión Español" to "DIZI OU"),
        )
    }

    @Test
    fun `hero picks the same album track with or without covers`() {
        val page = listOf(
            track("aVQKVqYvpHs", "Hero", "Mariah Carey", "4:18", "MPREb_UoxHwhB2Shb"),
            track("l7xckikGl1o", "Hero (Mariah Carey)", "Karolina Protsenko", "4:06", "MPREb_f5bl4lkWivX"),
            upload("fDb0tKHcZhg", "Mariah Carey - Hero (Official Lyric Video)", "Mariah Carey", "4:19"),
            upload("0IA3ZvCkRkQ", "Mariah Carey - Hero (Live) (Official HD Video)", "Mariah Carey", "4:17"),
            upload("Cd9rQ5pu3m8", "Mariah Carey - Hero (from Fantasy: Live at Madison Square Garden)", "Mariah Carey", "4:17"),
            upload("--02D71FV0g", "Hero - Mariah Carey (Lyric Video) Musicarmonix", "Musicarmonix", "4:21"),
            upload("q9BN0zyXAVQ", "Hero - Mariah Carey (Lyrics) 🎵", "Pillow", "4:27"),
            upload("rjvTzFlHVRs", "Mariah Carey, Luciano Pavarotti - Hero (Live)", "Luciano Pavarotti", "4:31"),
            upload("lZe8niZY7KA", "Hero (2009 Version)", "Mariah Carey", "4:19"),
            upload("P3iGU3sFM5k", "Mariah Carey- Hero", "MariahMusicChannel", "4:20"),
            upload("JA-AeiultFg", "The Version Sessions - Hero - Mariah Carey (Cover by Andre Valdez)", "The Version Sessions", "4:23"),
        )
        assertSamePick(
            "aVQKVqYvpHs",
            mv("fDb0tKHcZhg", "Hero", "Mariah Carey", "4:19"),
            page,
            store = listOf(
                "Hero" to "Mariah Carey",
                "Hero (2009 Version)" to "Mariah Carey",
                "Hero (Live)" to "Luciano Pavarotti",
            ),
        )
    }

    @Test
    fun `good day picks the same album track with or without covers`() {
        val page = listOf(
            track("OIHlzvEKncQ", "좋은 날", "아이유", "3:56", "MPREb_nOAywq2fgio"),
            track("ccqycCN5JTI", "좋은 날 (inst)", "아이유", "3:54", "MPREb_nOAywq2fgio"),
            upload("V6WWJNpIJN4", "아이유(IU) - 좋은 날 [가사/Lyrics]", "웅키", "3:56"),
            upload("jeqdYqsrsA0", "IU (아이유) _ Good Day (좋은 날) _ MV", "1theK (원더케이)", "5:58"),
            upload("LYm7CIn1K58", "아이유 - 좋은 날 [유희열의 스케치북/You Heeyeol’s Sketchbook] | KBS 200918 방송", "KBS Kpop", "4:16"),
            upload("7bPMyLV-vk0", "[뮤뱅] 아이유 - 좋은날 20120831", "KBS Kpop", "3:56"),
            upload("NbmtT4n2hyk", "[TJ노래방] 좋은날 - IU / TJ Karaoke", "TJ노래방 공식 유튜브채널", "3:58"),
            upload("9X5Kcr8hXmI", "[Full HD Audio]IU - 좋은 날 (Good Day)", "KpopLoversChannel", "3:54"),
        )
        assertSamePick(
            "OIHlzvEKncQ",
            mv("V6WWJNpIJN4", "좋은 날 [가사/Lyrics]", "아이유", "3:56"),
            page,
            store = listOf("좋은 날 : Originally Performed By 아이유 Karaoke Verison" to "코케"),
        )
    }

    /**
     * NPZtKA6343o: the bridge is a video row printing both names. A cover pass
     * that files it under the store's one name would take that evidence away;
     * the plain page's bridges are collected first, so the switch keeps it.
     */
    @Test
    fun `the plain page keeps the both-names bridge a cover rename would drop`() {
        val target = TrackMatcher.Target(
            title = "You’re Under Arrest",
            artist = "ILLIT",
            durationSec = 192,
            isVideo = true,
        )
        val bridge = Song(
            videoId = "V2V9-9VhYJo",
            title = "체포하겠어 (You’re Under Arrest)",
            artist = "ILLIT",
            thumbnailUrl = "https://i.ytimg.com/vi/V2V9-9VhYJo/hq720.jpg",
            isVideo = true,
        )
        val renamed = bridge.copy(title = "체포하겠어")
        assertTrue(KoTrackMatcher.titleBridges(listOf(renamed), target).isEmpty())

        val bridges = LinkedHashMap<String, Song>()
        for (rows in listOf(listOf(bridge), listOf(renamed))) {
            KoTrackMatcher.titleBridges(rows, target).forEach { bridges.getOrPut(it.videoId) { it } }
        }
        val breakEven = listOf("AQUrMavKqVg" to "Super in Love", "oSJX4sCLFfo" to "More", "qsqvJpOgrWw" to "체포하겠어")
            .map { (id, title) -> Song(videoId = id, title = title, artist = "ILLIT", thumbnailUrl = null, durationText = "3:02") }
        assertEquals(
            "qsqvJpOgrWw",
            CatalogSongMapper.bridgedAlbumTrack(breakEven, target, bridges.values.toList())?.videoId,
        )
    }
}
