package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKIP7 (phone 11-17, vc208test): two covers by one singer in an autoplay
 * queue played her other song '여로' (K7h1Wjg38uI) instead, until the sound
 * check took it off mid-play ("is another song; back to ..."). Both were
 * adopted by the music-only audio switch on the title-unverified grade, whose
 * "one title Latin, the other another script" test read digits as Latin: the
 * cover '8282' and the date stamp of '타비,히나-붉은노을(07.22)' made those
 * Hangul titles look like the English filing of the Hangul '여로'.
 */
class KoTitleUnverifiedDigitsTest {

    private val singer = "아라하시 타비"

    /** The singer's own song the switch adopted, as the Songs page listed it. */
    private val yeoro = Song(
        videoId = "K7h1Wjg38uI",
        title = "여로",
        artist = singer,
        thumbnailUrl = null,
        durationText = "3:23",
    )

    private fun video(id: String, title: String) = Song(
        videoId = id,
        title = title,
        artist = singer,
        thumbnailUrl = null,
        durationText = "3:30",
        isVideo = true,
    )

    private fun videoTarget(title: String) =
        TrackMatcher.Target(title = title, artist = singer, durationSec = 210, isVideo = true)

    @Test
    fun `a number or a date stamp is not a latin filing of a hangul title`() {
        for ((wanted, got) in listOf(
            "8282" to "여로",
            "8282 - 스텔라이브 2기생" to "여로",
            "타비,히나-붉은노을(07.22)" to "여로",
            "1004" to "천사",
        )) {
            assertFalse(KoTrackMatcher.latinAgainstOtherScript(wanted, got), "$wanted / $got")
            assertFalse(KoTrackMatcher.latinAgainstOtherScript(got, wanted), "$got / $wanted")
        }
    }

    @Test
    fun `the covers are not adopted as the singer's other song`() {
        for (title in listOf("8282", "8282 - 스텔라이브 2기생", "타비,히나-붉은노을(07.22)")) {
            val target = videoTarget(title)
            assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(yeoro, target), title)
            assertNull(TrackMatcher.titleUnverifiedAdoption(listOf(yeoro), target), title)
        }
    }

    @Test
    fun `the phone's two video rows find no song version`() {
        for (video in listOf(
            video("rrhrV8RcSVE", "타비,히나-붉은노을(07.22)"),
            video("Vez6XEhdBy0", "8282 - 스텔라이브 2기생"),
        )) {
            val row = KoAudioSwitch.audioSwitchRow(video)
            val target = TrackMatcher.audioSwitchTargetOf(row).copy(isVideo = true)
            val page = listOf(yeoro)
            assertNull(KoAudioSwitch.adoptBest(page, page, target, video, 10), video.videoId)
        }
    }

    @Test
    fun `a latin word still files a title and nothing is newly admitted`() {
        // The real shape keeps passing: a Latin title with a number in it.
        assertTrue(KoTrackMatcher.latinAgainstOtherScript("4 Seasons", "사계"))
        assertTrue(KoTrackMatcher.latinAgainstOtherScript("BREAK EVEN", "체포하겠어"))
        // Packaging with digits is still no filing.
        assertFalse(KoTrackMatcher.latinAgainstOtherScript("여로 4K", "붉은노을"))
        // Only narrowed: a date-stamped Hangul title was never the other-script
        // name of a Latin one, and it does not become one now.
        assertFalse(KoTrackMatcher.latinAgainstOtherScript("붉은노을(07.22)", "Red Sunset"))
        assertFalse(KoTrackMatcher.latinAgainstOtherScript("Red Sunset", "붉은노을(07.22)"))
    }

    // Review round: digits were only one way in. One Latin word in the upload's
    // packaging (an agency name, a joining 'x', 'ver', '#shorts', '1080p') still
    // made the same singer's Hangul covers the "English filing" of '여로'. The
    // song's own name, the parsed core, is Hangul or Hangul and Latin together.

    private val packagedCovers = listOf(
        "8282 - StelLive 2기생",
        "타비 x 히나-붉은노을(07.22)",
        "붉은노을 (Full ver.)",
        "붉은노을 #shorts",
        "붉은노을 1080p",
    )

    @Test
    fun `a latin packaging word does not make a hangul cover another song's latin name`() {
        for (title in packagedCovers) {
            // The audio switch of a video row (-30/+3 s window).
            val video = videoTarget(title)
            assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(yeoro, video), title)
            assertNull(TrackMatcher.titleUnverifiedAdoption(listOf(yeoro), video), title)
            // The quality upgrade grade (3 s window).
            val row = TrackMatcher.Target(title = title, artist = singer, durationSec = 204)
            assertFalse(TrackMatcher.isTitleUnverifiedOriginal(yeoro, row), title)
        }
    }

    @Test
    fun `the core test reads the letters of the song's name`() {
        // The catalogue filing shape: Latin letters only against another script only.
        assertTrue(KoTrackMatcher.coresLatinAgainstOtherScript("youreunderarrest", "체포하겠어"))
        assertTrue(KoTrackMatcher.coresLatinAgainstOtherScript("체포하겠어", "youreunderarrest"))
        // Hangul and Latin in one core, digits alone, a packaging word alone,
        // and two Latin names are no such pair.
        assertFalse(KoTrackMatcher.coresLatinAgainstOtherScript("stellive2기생", "여로"))
        assertFalse(KoTrackMatcher.coresLatinAgainstOtherScript("타비x히나붉은노을", "여로"))
        assertFalse(KoTrackMatcher.coresLatinAgainstOtherScript("8282", "여로"))
        assertFalse(KoTrackMatcher.coresLatinAgainstOtherScript("4k", "여로"))
        assertFalse(KoTrackMatcher.coresLatinAgainstOtherScript("lemonade", "camouflage"))
    }
}
