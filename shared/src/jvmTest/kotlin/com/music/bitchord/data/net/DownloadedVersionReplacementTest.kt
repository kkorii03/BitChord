package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exact rows and downloaded versions from the 10-26 phone log.
 *
 * A cover row is saved as the player shows it — its song title and its byline
 * ("원곡자 · 부른사람 커버") — and keeps `isVideo = true` because it is an
 * upload. Those are the fields the download record holds, so these are the
 * values the replacement decision actually sees.
 */
class DownloadedVersionReplacementTest {

    private fun row(
        id: String,
        title: String,
        artist: String,
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    private fun cover(id: String, title: String, artist: String) =
        row(id, title, artist, isVideo = true)

    private fun downloaded(id: String, title: String, artist: String) =
        row(id, title, artist)

    @Test
    fun `ponyo cover is not replaced by the downloaded original`() {
        val cover = cover("v59g4HRNIkc", "벼랑위의 포뇨 ost", "오화요 커버")
        val original = downloaded("Zs4zLIgpqfQ", "벼랑위의 포뇨 ost", "오화요")

        assertFalse(SongVersionPolicy.mayInstallSongVersion(cover))
        assertTrue(original.videoId != cover.videoId)
    }

    @Test
    fun `dance hall cover is not replaced by the downloaded original`() {
        val cover = cover(
            "etyHAg3sADA",
            "ダンスホール - Dance Hall",
            "Mrs. GREEN APPLE · 오화요 커버",
        )
        val original = downloaded("xhoKTYHaQXU", "ダンスホール", "Mrs. GREEN APPLE")

        assertFalse(SongVersionPolicy.mayInstallSongVersion(cover))
        assertTrue(original.videoId != cover.videoId)
    }

    @Test
    fun `night flower cover is not replaced by the downloaded original`() {
        val cover = cover("vqskFsBLeUs", "야화", "안예은 · 오화요 커버")
        val original = downloaded("4rsheRU_bdQ", "Night Flower (야화)", "안예은")

        assertFalse(SongVersionPolicy.mayInstallSongVersion(cover))
        assertTrue(original.videoId != cover.videoId)
    }

    @Test
    fun `weigh anchor cover is not replaced by the downloaded original`() {
        val cover = cover(
            "weigh-anchor-cover",
            "抜錨 - weigh anchor",
            "Nanahoshi Orchestra · 오화요 커버",
        )
        val original = downloaded("weigh-anchor", "抜錨", "Nanahoshi Orchestra")

        assertFalse(SongVersionPolicy.mayInstallSongVersion(cover))
        assertTrue(original.videoId != cover.videoId)
    }

    @Test
    fun `youngblood mv is replaced by its downloaded song`() {
        val mv = row(
            "youngblood-mv",
            "Youngblood (Official Video)",
            "5 Seconds of Summer",
            isVideo = true,
        )
        val song = downloaded("youngblood", "Youngblood", "5 Seconds of Summer")

        assertTrue(SongVersionPolicy.mayInstallSongVersion(mv))
        assertTrue(song.title != mv.title)
    }

    @Test
    fun `7 rings mv is replaced by its downloaded song`() {
        val mv = row(
            "7-rings-mv",
            "7 rings (Official Video)",
            "Ariana Grande",
            isVideo = true,
        )
        val song = downloaded("7-rings", "7 rings", "Ariana Grande")

        assertTrue(SongVersionPolicy.mayInstallSongVersion(mv))
        assertTrue(song.title != mv.title)
    }

    @Test
    fun `when i was your man mv is replaced by its downloaded song`() {
        val mv = row(
            "when-i-was-your-man-mv",
            "When I Was Your Man (Official Music Video)",
            "Bruno Mars",
            isVideo = true,
        )
        val song = downloaded("when-i-was-your-man", "When I Was Your Man", "Bruno Mars")

        assertTrue(SongVersionPolicy.mayInstallSongVersion(mv))
        assertTrue(song.title != mv.title)
    }

    @Test
    fun `dancing with a stranger mv is replaced by its bonus track song`() {
        val mv = row(
            "dancing-with-a-stranger-mv",
            "Dancing With A Stranger (Official Video)",
            "Sam Smith, Normani",
            isVideo = true,
        )
        val song = downloaded(
            "dancing-with-a-stranger",
            "Dancing With A Stranger (with Normani) [Bonus Track]",
            "Sam Smith, Normani",
        )

        assertTrue(SongVersionPolicy.mayInstallSongVersion(mv))
        assertTrue(song.title != mv.title)
    }

    @Test
    fun `sucker mv is replaced by its downloaded song`() {
        val mv = row(
            "sucker-mv",
            "Sucker (Official Video)",
            "Jonas Brothers",
            isVideo = true,
        )
        val song = downloaded("sucker", "Sucker", "Jonas Brothers")

        assertTrue(SongVersionPolicy.mayInstallSongVersion(mv))
        assertTrue(song.title != mv.title)
    }
}
