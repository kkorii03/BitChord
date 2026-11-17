package com.music.bitchord.ko

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review VER6 (phone 11-17): wrong versions picked for, or saved as, a song.
 * Titles, credits and lengths are the reported rows'; ids other than the
 * reported ones are placeholders.
 */
class StoredVersionFixTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String?,
        album: String? = null,
        isVideo: Boolean = false,
        type: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        albumName = album,
        isVideo = isVideo,
        musicVideoType = type,
    )

    private val omv = "MUSIC_VIDEO_TYPE_OMV"

    // ── Can't Feel My Face: another act's version that keeps the singer in its credit ──

    private val cantFeelVideo = TrackMatcher.Target(
        title = "Can't Feel My Face",
        artist = "The Weeknd",
        durationSec = 238,
        isVideo = true,
    )
    private val madjesla = song("VFKrvI6NsjA", "Can't Feel My Face", "MADJESLA, The Weeknd", "3:35")
    private val weeknd = song("CFMFALBUM01", "Can't Feel My Face", "The Weeknd", "3:35", album = "Beauty Behind The Madness")

    @Test
    fun `another act billed first is not the song even with the singer credited`() {
        assertTrue(KoTrackMatcher.billsForeignLead(cantFeelVideo, madjesla))
        assertNull(TrackMatcher.bestSameArtist(listOf(madjesla), cantFeelVideo))
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(madjesla), cantFeelVideo))
        assertEquals("artist(lead)", TrackMatcher.songVersionRejectReason(madjesla, cantFeelVideo))
        // The real release on the same page is still taken.
        assertEquals("CFMFALBUM01", TrackMatcher.bestSameArtist(listOf(madjesla, weeknd), cantFeelVideo)?.videoId)
    }

    @Test
    fun `a saved alias of another act no longer plays for the video`() {
        val row = song("KEI4qSrkPAs", "Can't Feel My Face", "The Weeknd", "3:58", isVideo = true)
        assertEquals("artist", KoStoredVersionFix.aliasRefusal(row, madjesla))
        assertNull(KoStoredVersionFix.aliasRefusal(row, weeknd))
    }

    // ── a featuring credit on the official release is not another version ──

    @Test
    fun `an official track with a featured guest is left alone`() {
        val starboy = song("STARBOYALB1", "Starboy", "The Weeknd, Daft Punk", "3:50", album = "Starboy")
        val target = TrackMatcher.Target(title = "Starboy", artist = "The Weeknd", durationSec = 274, isVideo = true)
        assertFalse(KoTrackMatcher.billsForeignLead(target, starboy))
        assertEquals("STARBOYALB1", TrackMatcher.bestSameArtist(listOf(starboy), target)?.videoId)
        // Saved, liked or downloaded, the album row is never a suspect.
        assertNull(KoStoredVersionFix.suspicion(starboy, downloaded = true))
        assertNull(KoStoredVersionFix.suspicion(starboy.copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV"), downloaded = false))
        // A stored MV of it may still point at the featured release.
        val mv = song("STARBOYMV01", "Starboy", "The Weeknd", "4:34", type = omv)
        assertTrue(KoStoredVersionFix.judge(mv, starboy, "type").accepted)
    }

    // ── music videos saved as song rows ──

    @Test
    fun `bad guy saved as a song is pointed at the album cut`() {
        val row = song("DyDfgMOUjCI", "bad guy", "Billie Eilish", "3:26", type = omv)
        val album = song("BADGUYALB01", "bad guy", "Billie Eilish", "3:14", album = "WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?")
        assertEquals("type", KoStoredVersionFix.suspicion(row, downloaded = true))
        val verdict = KoStoredVersionFix.judge(row, album, "type")
        assertTrue(verdict.accepted, verdict.evidence)
        assertEquals("mv", verdict.reason)
        assertEquals(12_000L, verdict.overhangMs)
        assertEquals(
            "version-fix: DyDfgMOUjCI -> BADGUYALB01 reason=mv download=pending ${verdict.evidence}",
            KoStoredVersionFix.fixLine("DyDfgMOUjCI", "BADGUYALB01", verdict.reason, "pending", verdict.evidence),
        )
    }

    @Test
    fun `greedy downloaded from its video is pointed at the album cut`() {
        val row = song("To4SWGZkEPk", "greedy", "Tate McRae", "2:35")
        val album = song("GREEDYALB01", "greedy", "Tate McRae", "2:11", album = "THINK LATER")
        assertEquals("no-album", KoStoredVersionFix.suspicion(row, downloaded = true))
        // Streamed, a row without an album is far too common to look up.
        assertNull(KoStoredVersionFix.suspicion(row, downloaded = false))
        val verdict = KoStoredVersionFix.judge(row, album, "no-album")
        assertTrue(verdict.accepted, verdict.evidence)
        assertEquals("length", verdict.reason)
        assertEquals(24_000L, verdict.overhangMs)
    }

    @Test
    fun `love yourself with its video series suffix is pointed at the album cut`() {
        val row = song("oyEuk8j8imI", "Love Yourself (PURPOSE : The Movement)", "Justin Bieber", "4:33")
        val album = song("LOVEYOURS01", "Love Yourself", "Justin Bieber", "3:53", album = "Purpose")
        val why = assertNotNull(KoStoredVersionFix.suspicion(row, downloaded = true))
        val verdict = KoStoredVersionFix.judge(row, album, why)
        assertTrue(verdict.accepted, verdict.evidence)
        assertEquals(40_000L, verdict.overhangMs)
    }

    @Test
    fun `a saved row is kept when the found version is not a safe replacement`() {
        val row = song("DyDfgMOUjCI", "bad guy", "Billie Eilish", "3:26", type = omv)
        // The same length: the row is probably that song already.
        val same = KoStoredVersionFix.judge(row, song("BADGUYDUP01", "bad guy", "Billie Eilish", "3:25"), "type")
        assertFalse(same.accepted)
        assertEquals("length", same.reason)
        assertFalse(same.dropsVersion)
        // The lookup naming the row itself changes nothing.
        assertFalse(KoStoredVersionFix.judge(row, row, "type").accepted)
        // Another take, a cover, another act and a video are never the song.
        val live = KoStoredVersionFix.judge(row, song("BADGUYLIVE1", "bad guy (Live)", "Billie Eilish", "3:20"), "type")
        assertEquals("cover", live.reason)
        assertTrue(live.dropsVersion)
        val other = KoStoredVersionFix.judge(
            song("CFMFMV00001", "Can't Feel My Face", "The Weeknd", "3:58", type = omv),
            madjesla,
            "type",
        )
        assertEquals("artist", other.reason)
        assertTrue(other.dropsVersion)
        assertEquals("mv", KoStoredVersionFix.judge(row, row.copy(videoId = "BADGUYVID01", isVideo = true), "type").reason)
    }

    @Test
    fun `rows that are not saved songs are not suspects`() {
        val video = song("DyDfgMOUjCI", "bad guy", "Billie Eilish", "3:26", isVideo = true, type = omv)
        assertNull(KoStoredVersionFix.suspicion(video, downloaded = true))
        assertNull(KoStoredVersionFix.suspicion(video.copy(isVideo = false, typeUnknown = true), downloaded = true))
        val cover = song("COVERROW001", "bad guy (Piano Cover)", "Some Pianist", "3:30", type = omv)
        assertNull(KoStoredVersionFix.suspicion(cover, downloaded = true))
        val titled = song("MVTITLE0001", "bad guy (Official Music Video)", "Billie Eilish", "3:26")
        assertEquals("mv-title", KoStoredVersionFix.suspicion(titled, downloaded = false))
    }

    // ── a playlist row of an official video with square art ──

    @Test
    fun `a responsive row keeps its music video type`() {
        val json = """
        { "contents": [ {
          "musicResponsiveListItemRenderer": {
            "playlistItemData": { "videoId": "DyDfgMOUjCI" },
            "overlay": { "musicItemThumbnailOverlayRenderer": { "content": { "musicPlayButtonRenderer": {
              "playNavigationEndpoint": { "watchEndpoint": { "videoId": "DyDfgMOUjCI",
                "watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "$omv" } } } }
            } } } },
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "bad guy" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Billie Eilish" } ] } } }
            ]
          }
        } ] }"""
        val row = InnertubeParser.collectSongsDeep(Json.parseToJsonElement(json)).single()
        assertFalse(row.isVideo)
        assertEquals(omv, row.musicVideoType)
        assertEquals("type", KoStoredVersionFix.suspicion(row, downloaded = false))
    }

    // ── the page's own runtime consensus ──

    @Test
    fun `a candidate far from the runtime its page agrees on is another take`() {
        val target = TrackMatcher.Target(title = "Love Yourself", artist = "Justin Bieber", durationSec = 273, isVideo = true)
        val long = song("LOVEYOULONG", "Love Yourself", "Justin Bieber", "5:30")
        val album = song("LOVEYOURS01", "Love Yourself", "Justin Bieber", "3:53", album = "Purpose")
        val single = song("LOVEYOURS02", "Love Yourself", "Justin Bieber", "3:54")
        val page = listOf(long, album, single)
        assertTrue(KoTrackMatcher.runtimeDisagreesWithPage(target, long, page))
        assertFalse(KoTrackMatcher.runtimeDisagreesWithPage(target, album, page))
        assertEquals("duration(page)", TrackMatcher.songVersionRejectReason(long, target, page))
        assertTrue(TrackMatcher.bestSameArtist(page, target)?.videoId in setOf("LOVEYOURS01", "LOVEYOURS02"))
        // Two rows are no consensus, and a song row target has its own window.
        assertFalse(KoTrackMatcher.runtimeDisagreesWithPage(target, long, listOf(long, album)))
        assertFalse(KoTrackMatcher.runtimeDisagreesWithPage(target.copy(isVideo = false), long, page))
    }
}
