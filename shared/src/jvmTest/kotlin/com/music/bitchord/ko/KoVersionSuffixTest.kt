package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 11-19: the private Hi-Res addon learned to refuse "another take" whose
 * length equals the original's (Whiplash English Version 183 s, Almond
 * Chocolate Korean Ver. 202 s, Rock & Roll Part II 185 s vs Part 1 184 s), so
 * no duration check can separate them. The app's own HQ row gates
 * ([TrackMatcher.score] for the ranking, [TrackMatcher.isOriginalRecording]
 * for the replacement, [KoTrackMatcher.namesUnaskedTake] for the audio switch)
 * have to read the suffix instead: refuse it against the plain original, and
 * accept it when the requested track carries the same suffix.
 */
class KoVersionSuffixTest {

    private data class Case(
        val wanted: String,
        val got: String,
        val same: Boolean,
        val wantedSec: Int = 200,
        val gotSec: Int = 200,
        val artist: String = "Artist",
    )

    private fun row(title: String, artist: String, sec: Int) = Song(
        videoId = "row-${title.hashCode()}",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}",
        albumId = "album",
    )

    /** Every gate that disagrees with [Case.same], one line each. */
    private fun wrong(cases: List<Case>): List<String> = cases.flatMap { c ->
        val target = TrackMatcher.Target(c.wanted, c.artist, durationSec = c.wantedSec)
        val candidate = row(c.got, c.artist, c.gotSec)
        val scored = TrackMatcher.score(candidate, target) != null
        val original = TrackMatcher.isOriginalRecording(candidate, target)
        val unasked = KoTrackMatcher.namesUnaskedTake(target, candidate)
        buildList {
            // A refusal has to hold in every gate; an accept needs the ranking
            // and the replacement gate to agree and no unasked take.
            if (c.same) {
                if (!scored) add("score refused  '${c.got}' for '${c.wanted}'")
                if (!original) add("original refused '${c.got}' for '${c.wanted}'")
                if (unasked) add("unasked take on '${c.got}' for '${c.wanted}'")
            } else {
                if (scored) add("score accepted '${c.got}' for '${c.wanted}'")
                if (original) add("original accepted '${c.got}' for '${c.wanted}'")
            }
        }
    }

    private fun assertAllRight(cases: List<Case>) {
        val bad = wrong(cases)
        assertTrue(bad.isEmpty(), "\n" + bad.joinToString("\n"))
    }

    @Test
    fun `a language version is not the original`() = assertAllRight(
        listOf(
            Case("Whiplash", "Whiplash (English Version)", false, 183, 183, "aespa"),
            Case("Almond Chocolate", "Almond Chocolate (Korean Ver.)", false, 202, 202, "ILLIT"),
            Case("HOT", "HOT (English ver.)", false, 190, 190, "SEVENTEEN"),
            Case("Song", "Song (Japanese ver.)", false),
            Case("Song", "Song - Japanese Version", false),
            Case("Song", "Song (Chinese Ver.)", false),
            Case("Song", "Song (Eng Ver.)", false),
            Case("Song", "Song (Jpn ver)", false),
            Case("Song", "Song (JP Ver.)", false),
            Case("Song", "Song (KR Ver.)", false),
            Case("Song", "Song (Spanish Version)", false),
            Case("Song", "Song (일본어 버전)", false),
            // A language take after a soundtrack label keeps its language.
            Case("Song", "Song | Joker OST Jpn Ver.", false),
            Case("밤양갱", "밤양갱 (English Ver.)", false, artist = "비비"),
            Case("밤양갱", "밤양갱 (일본어 버전)", false, artist = "비비"),
        ),
    )

    @Test
    fun `a language version matches the same language version`() = assertAllRight(
        listOf(
            Case("Whiplash (English Version)", "Whiplash (English Ver.)", true, 183, 183, "aespa"),
            Case("Almond Chocolate (Korean Ver.)", "Almond Chocolate (Korean Version)", true, 202, 202, "ILLIT"),
            Case("Song (Jpn ver)", "Song (Japanese Version)", true),
            Case("Song (Eng Ver.)", "Song - English Version", true),
            Case("Song (JP Ver.)", "Song (Japanese ver.)", true),
            Case("Song (KR Ver.)", "Song (Korean Version)", true),
            Case("Song (한국어 버전)", "Song (Korean Ver.)", true),
            // A language word that only names the listing is not a take.
            Case("Song", "Song (English Translation)", true),
            Case("Song", "Song (Korean Drama OST)", true),
        ),
    )

    @Test
    fun `numbered parts never match another part`() = assertAllRight(
        listOf(
            Case("Rock & Roll Part II", "Rock and Roll Part 1", false, 185, 184, "Gary Glitter"),
            Case("Rock and Roll Part 1", "Rock & Roll Part II", false, 184, 185, "Gary Glitter"),
            Case("Song (Part 1)", "Song (Part 2)", false),
            Case("Song (Part I)", "Song (Part II)", false),
            Case("Song (Pt. 1)", "Song (Pt. 2)", false),
            Case("Song Part I", "Song Part 2", false),
            Case("Song Pt. 1", "Song Part II", false),
            Case("Song - Part I", "Song - Part II", false),
            Case("사랑 Part.1", "사랑 Part.2", false, artist = "가수"),
        ),
    )

    @Test
    fun `the same part in another spelling is the same part`() = assertAllRight(
        listOf(
            Case("Rock & Roll Part II", "Rock and Roll Part 2", true, 185, 185, "Gary Glitter"),
            Case("Song (Part II)", "Song (Part 2)", true),
            Case("Song (Pt. 1)", "Song (Part I)", true),
            Case("Song Part I", "Song Pt. 1", true),
            Case("Song - Part II", "Song (Pt. 2)", true),
            Case("사랑 Part II", "사랑 Part.2", true, artist = "가수"),
            // A soundtrack part numbers the release, not the recording.
            Case("Song", "Song (OST Part II)", true),
            Case("Song", "Song - OST Part 3", true),
        ),
    )

    @Test
    fun `a dated version is a different recording, a remaster is not`() = assertAllRight(
        listOf(
            Case("Song", "Song (2023 Version)", false),
            Case("Song", "Song - 2023 Version", false),
            Case("Song", "Song (2023 Ver.)", false),
            Case("Song (2019 Version)", "Song (2023 Version)", false),
            Case("Song (2023 Version)", "Song (2023 Ver.)", true),
            Case("Song", "Song (2009 Remaster)", true),
            Case("Song", "Song (Remastered 2011 Version)", true),
            Case("Song", "Song (2011 Remastered Version)", true),
        ),
    )

    @Test
    fun `mixes, stripped, commentary and instrument versions are other takes`() = assertAllRight(
        listOf(
            Case("Song", "Song (Club Mix)", false),
            Case("Song", "Song (Dub)", false),
            Case("Song", "Song (Stripped)", false),
            Case("Song", "Song - Stripped", false),
            Case("Song", "Song (Stripped Version)", false),
            Case("Song", "Song (Commentary)", false),
            Case("Song", "Song (Track Commentary)", false),
            Case("Song", "Song (Cello Version)", false),
            Case("Song", "Song (Violin Ver.)", false),
            Case("Song", "Song (Guitar Version)", false),
            Case("Song", "Song (Piano Version)", false),
            Case("Song", "Song (Acoustic Version)", false),
            Case("Song", "Song (Orchestral Version)", false),
            Case("Song", "Song (Jazz Version)", false),
            Case("밤양갱", "밤양갱 (피아노 버전)", false, artist = "비비"),
            Case("Song (Stripped)", "Song - Stripped Version", true),
            Case("Song (Cello Version)", "Song (Cello Ver.)", true),
            Case("밤양갱 (Piano Ver.)", "밤양갱 (피아노 버전)", true, artist = "비비"),
        ),
    )

    @Test
    fun `titles that only contain the words are left alone`() = assertAllRight(
        listOf(
            Case("Part of Me", "Part of Me", true, artist = "Katy Perry"),
            Case("The Best Part", "The Best Part", true, artist = "Daniel Caesar"),
            Case("Stripped", "Stripped", true, artist = "Depeche Mode"),
            Case("Piano Man", "Piano Man", true, artist = "Billy Joel"),
            Case("2002", "2002", true, artist = "Anne-Marie"),
            Case("1999", "1999 (2019 Remaster)", true, artist = "Prince"),
            Case("Song", "Song (Version)", true),
            Case("Song", "Song (Album Version)", true),
            Case("O Mundo É Mal (part. Clara Lima)", "O Mundo É Mal", true, artist = "Froid"),
        ),
    )

    @Test
    fun `the version keys are one per take`() {
        fun versions(title: String) = TrackMatcher.parseTitle(title).versions
        // A part number printed in the title stays in the title, one spelling for all.
        assertEquals(TrackMatcher.titleCores("Rock and Roll Part 2"), TrackMatcher.titleCores("Rock & Roll Part II"))
        assertEquals(TrackMatcher.titleCores("Song Part 1"), TrackMatcher.titleCores("Song Pt. I"))
        // ...and keeps its words for the search.
        assertEquals(listOf("rock roll part ii"), TrackMatcher.titleVariants("Rock & Roll Part II"))
        assertEquals(setOf("part2"), versions("Song (Part II)"))
        assertEquals(setOf("part1"), versions("Song (Pt. I)"))
        assertEquals(setOf("japanese"), versions("Song (Jpn ver)"))
        assertEquals(setOf("2023 version"), versions("Song (2023 Version)"))
        assertEquals(setOf("cello"), versions("Song (Cello Version)"))
        assertEquals(setOf("piano"), versions("밤양갱 (피아노 버전)"))
        // The query keeps the existing key spelling (SourcesTest: "song part1").
        assertEquals("song part1", TrackMatcher.queries(TrackMatcher.Target("Song (Part 1)", "")).single())
    }
}
