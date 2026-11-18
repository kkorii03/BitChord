package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.RecordingMatch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phone 11-18: the audio switch swapped music videos for another recording of
 * the same song. "This Is How We Do It" -> "(Re-Recorded)" (the lyrics then
 * had to move 10 s), "No Diggity" -> "(Re-Recorded)", "Shake It Off" ->
 * "(Taylor's Version)", "we can't be friends" -> "– string version". None of
 * those asides counted as a take, so the re-recording read as the video's own song.
 */
class KoRerecordTakeTest {

    private fun mv(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = true,
    )

    private fun track(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        albumId = "album-$id",
    )

    private fun pick(video: Song, page: List<Song>): String? {
        val row = KoAudioSwitch.audioSwitchRow(video)
        val target = TrackMatcher.audioSwitchTargetOf(row).copy(isVideo = true)
        return KoAudioSwitch.adoptBest(page, page.filterNot { it.isVideo }, target, video, 20)?.videoId
    }

    private fun versions(title: String, artist: String = "") = TrackMatcher.parseTitle(title, artist).versions

    @Test
    fun `a re-recording is not the video's song`() {
        val video = mv("BC_ui0KqOWY", "This Is How We Do It", "Montell Jordan", "4:20")
        val rerecord = track("rerec01", "This Is How We Do It (Re-Recorded)", "Montell Jordan", "3:56")
        assertNull(pick(video, listOf(rerecord)))

        val original = track("orig01", "This Is How We Do It", "Montell Jordan", "3:56")
        assertEquals("orig01", pick(video, listOf(rerecord, original)))
    }

    @Test
    fun `a featured credit on the video does not let the re-recording through`() {
        val video = mv("diggity01", "No Diggity (feat. Dr. Dre, Queen Pen)", "Blackstreet", "5:20")
        val rerecord = track("rerec02", "No Diggity (Re-Recorded)", "Blackstreet", "5:05")
        assertNull(pick(video, listOf(rerecord)))
        assertEquals(versions("No Diggity (Re-Recorded)"), versions("No Diggity [Rerecorded]"))
    }

    @Test
    fun `the owner's re-recording is not the original video`() {
        val video = mv("nfWlot6h_JM", "Shake It Off", "Taylor Swift", "4:02")
        val taylors = track("H59xVMF4zxE", "Shake It Off (Taylor's Version)", "Taylor Swift", "3:39")
        assertNull(pick(video, listOf(taylors)))
        assertTrue(KoTrackMatcher.namesUnaskedTake(TrackMatcher.targetOf(video), taylors))
        // Curly apostrophe and the bare spelling are the same take.
        assertEquals(versions("Shake It Off (Taylor's Version)"), versions("Shake It Off (Taylor’s Version)"))
        assertEquals(versions("Shake It Off (Taylor's Version)"), versions("Shake It Off (Taylors Version)"))
    }

    @Test
    fun `a string version is its own recording, dashed or bracketed`() {
        val video = mv("wcbf01", "we can't be friends (wait for your love)", "Ariana Grande", "3:50")
        val dashed = track("str01", "we can't be friends (wait for your love) – string version", "Ariana Grande", "3:48")
        val bracketed = track("str02", "we can't be friends (wait for your love) (String Version)", "Ariana Grande", "3:48")
        assertNull(pick(video, listOf(dashed)))
        assertNull(pick(video, listOf(bracketed)))
        assertEquals(versions("Song (Strings)"), versions("Song - String Version"))
        assertTrue(versions("Song (Orchestra Version)").isNotEmpty())
    }

    @Test
    fun `a row that says the take still matches its own take`() {
        val rerecordVideo = mv("rv01", "No Diggity (Re-Recorded) (Official Video)", "Blackstreet", "5:20")
        val rerecord = track("rerec03", "No Diggity (Re-Record)", "Blackstreet", "5:05")
        assertEquals("rerec03", pick(rerecordVideo, listOf(rerecord)))

        val taylorsVideo = mv("tv01", "Shake It Off (Taylor's Version) (Lyric Video)", "Taylor Swift", "3:45")
        val taylors = track("tv02", "Shake It Off (Taylor's Version)", "Taylor Swift", "3:39")
        assertEquals("tv02", pick(taylorsVideo, listOf(taylors)))
        assertFalse(KoTrackMatcher.namesUnaskedTake(TrackMatcher.targetOf(taylorsVideo), taylors))

        val stringVideo = mv("sv01", "we can't be friends (wait for your love) (String Version)", "Ariana Grande", "3:55")
        val dashed = track("str03", "we can't be friends (wait for your love) – string version", "Ariana Grande", "3:48")
        assertEquals("str03", pick(stringVideo, listOf(dashed)))
    }

    @Test
    fun `packaging and remasters are still the same recording`() {
        // A remaster is the same performance re-mastered: same words on the same clock.
        assertEquals(emptySet(), versions("Shake It Off (Official Audio)"))
        assertEquals(emptySet(), versions("No Diggity (feat. Dr. Dre, Queen Pen)"))
        assertEquals(emptySet(), versions("This Is How We Do It (Remastered 2009)"))
        assertEquals(emptySet(), versions("This Is How We Do It (Album Version)"))
        assertEquals(emptySet(), versions("Piano Man (Version)"))
        val video = mv("nd02", "No Diggity (feat. Dr. Dre, Queen Pen)", "Blackstreet", "5:20")
        val remaster = track("rm01", "No Diggity (2009 Remaster)", "Blackstreet", "5:05")
        assertEquals("rm01", pick(video, listOf(remaster)))
    }

    @Test
    fun `the lyrics name match refuses a re-record too`() {
        val rerecord = RecordingMatch.Candidate(
            value = "rerecord",
            title = "Stay With Me (Re-record)",
            artist = "Sam Smith",
            seconds = 173.0,
        )
        assertNull(RecordingMatch.best(listOf(rerecord), "Stay With Me", "Sam Smith", 173_000))
        assertEquals("rerecord", RecordingMatch.best(listOf(rerecord), "Stay With Me (Re-record)", "Sam Smith", 173_000))
    }
}
