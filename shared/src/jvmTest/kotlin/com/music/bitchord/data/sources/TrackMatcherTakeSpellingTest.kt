package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Take spellings that must not let a different recording stand in for the
 * original: qualifier "Ver.", Korean take names, and sped-up/slowed edits.
 * Neutral packaging ("Remastered", "Radio Edit") stays neutral in both
 * directions.
 */
class TrackMatcherTakeSpellingTest {

    private fun song(title: String, artist: String, duration: String) = Song(
        videoId = "$title|$artist|$duration",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private fun accepted(target: TrackMatcher.Target, candidate: Song): Boolean =
        TrackMatcher.ranked(listOf(candidate), target).isNotEmpty()

    private fun assertTakeSeparated(
        takeTitle: String,
        plainTitle: String = "Song",
        artist: String = "Artist",
    ) {
        val take = TrackMatcher.Target(takeTitle, artist, durationSec = 200)
        val original = TrackMatcher.Target(plainTitle, artist, durationSec = 200)
        val takeRow = song(takeTitle, artist, "3:20")
        val plainRow = song(plainTitle, artist, "3:20")
        assertFalse("$plainTitle must not accept $takeTitle", accepted(original, takeRow))
        assertFalse("$takeTitle must not accept $plainTitle", accepted(take, plainRow))
        assertTrue("$takeTitle must accept itself", accepted(take, takeRow))
    }

    @Test
    fun `a qualifier before Ver is a take`() {
        assertTakeSeparated("Song (Band Ver.)")
        assertTakeSeparated("Song (Piano Ver.)")
        assertTakeSeparated("Song (Acoustic Ver.)")
        assertTakeSeparated("Song (Japanese Ver.)")
    }

    @Test
    fun `Ver and Version spellings of one take agree`() {
        val pairs = listOf(
            "Song (Acoustic Ver.)" to "Song (Acoustic Version)",
            "Song (Band Ver.)" to "Song (Band Version)",
            "Song (Piano Ver.)" to "Song (Piano Version)",
        )
        pairs.forEach { (short, long) ->
            val target = TrackMatcher.Target(short, "Artist", durationSec = 200)
            assertTrue("$short must accept $long", accepted(target, song(long, "Artist", "3:20")))
            val reverse = TrackMatcher.Target(long, "Artist", durationSec = 200)
            assertTrue("$long must accept $short", accepted(reverse, song(short, "Artist", "3:20")))
        }
    }

    @Test
    fun `qualifier words inside a longer bracket are not takes`() {
        val original = TrackMatcher.Target("Song", "Artist", durationSec = 200)
        listOf(
            "Song (From Band of Brothers)",
            "Song (Mr. Jones)",
        ).forEach { neutral ->
            assertTrue(
                "$neutral must match Song",
                accepted(original, song(neutral, "Artist", "3:20")),
            )
            assertTrue(
                "Song must match $neutral",
                accepted(TrackMatcher.Target(neutral, "Artist", durationSec = 200), song("Song", "Artist", "3:20")),
            )
        }
        assertFalse(
            "Song (Mr. Jones) must not match Song (Instrumental)",
            accepted(
                TrackMatcher.Target("Song (Instrumental)", "Artist", durationSec = 200),
                song("Song (Mr. Jones)", "Artist", "3:20"),
            ),
        )
        assertFalse(
            "Song (From Band of Brothers) must not match Song (Band Version)",
            accepted(
                TrackMatcher.Target("Song (Band Version)", "Artist", durationSec = 200),
                song("Song (From Band of Brothers)", "Artist", "3:20"),
            ),
        )
    }

    @Test
    fun `bare version and ver stay neutral`() {
        val original = TrackMatcher.Target("Song", "Artist", durationSec = 200)
        listOf("Song (Ver.)", "Song (Version)", "Song Ver", "Song Version").forEach { neutral ->
            assertTrue(
                "$neutral must match Song",
                accepted(original, song(neutral, "Artist", "3:20")),
            )
            assertTrue(
                "Song must match $neutral",
                accepted(TrackMatcher.Target(neutral, "Artist", durationSec = 200), song("Song", "Artist", "3:20")),
            )
        }
    }

    @Test
    fun `qualified ver strips its qualifier as one take`() {
        val short = TrackMatcher.Target("Song Band Ver.", "Artist", durationSec = 200)
        val long = TrackMatcher.Target("Song (Band Version)", "Artist", durationSec = 200)
        assertTrue(accepted(short, song("Song (Band Version)", "Artist", "3:20")))
        assertTrue(accepted(long, song("Song Band Ver.", "Artist", "3:20")))
        assertFalse(accepted(TrackMatcher.Target("Song", "Artist", durationSec = 200),
            song("Song Band Ver.", "Artist", "3:20")))
    }

    @Test
    fun `korean take names are takes`() {
        listOf(
            "Song (어쿠스틱)",
            "Song (피아노)",
            "Song (밴드)",
            "Song (리메이크)",
            "Song (MR)",
            "Song (반주)",
            "Song (인스트)",
            "Song (일본어 버전)",
            "Song (영어 버전)",
        ).forEach { assertTakeSeparated(it) }
    }

    @Test
    fun `korean takes agree with their english spelling`() {
        val pairs = listOf(
            "Song (어쿠스틱)" to "Song (Acoustic)",
            "Song (피아노)" to "Song (Piano)",
            "Song (밴드)" to "Song (Band)",
            "Song (리메이크)" to "Song (Remake)",
            "Song (인스트)" to "Song (Instrumental)",
            "Song (반주)" to "Song (Instrumental)",
            "Song (MR)" to "Song (Instrumental)",
            "Song (일본어 버전)" to "Song (Japanese Version)",
            "Song (영어 버전)" to "Song (English Version)",
        )
        pairs.forEach { (korean, english) ->
            val koreanTarget = TrackMatcher.Target(korean, "Artist", durationSec = 200)
            assertTrue("$korean must accept $english", accepted(koreanTarget, song(english, "Artist", "3:20")))
            val englishTarget = TrackMatcher.Target(english, "Artist", durationSec = 200)
            assertTrue("$english must accept $korean", accepted(englishTarget, song(korean, "Artist", "3:20")))
        }
    }

    @Test
    fun `sped up and slowed variants are takes`() {
        listOf(
            "Song (Sped Up)",
            "Song (Sped-Up)",
            "Song (Speed Up)",
            "Song (Slowed Down)",
            "Song (Slowed-Down)",
            "Song (Slow Down)",
            "Song (Slowed + Reverb)",
        ).forEach { assertTakeSeparated(it) }
    }

    @Test
    fun `unbracketed trailing speed edits are takes`() {
        listOf(
            "Song Sped Up",
            "Song Speed Up",
            "Song Sped-Up",
            "Song Slowed Down",
            "Song Slow Down",
        ).forEach { assertTakeSeparated(it) }
    }

    @Test
    fun `korean and japanese take spellings agree with their english names`() {
        val pairs = listOf(
            "Song (리믹스)" to "Song (Remix)",
            "Song (라이브)" to "Song (Live)",
            "Song (커버)" to "Song (Cover)",
            "Song (인스트)" to "Song (Instrumental)",
            "Song (リミックス)" to "Song (Remix)",
            "Song (ライブ)" to "Song (Live)",
            "Song (カバー)" to "Song (Cover)",
            "Song (インスト)" to "Song (Instrumental)",
        )
        pairs.forEach { (localized, english) ->
            val localizedTarget = TrackMatcher.Target(localized, "Artist", durationSec = 200)
            assertTrue(
                "$localized must accept $english",
                accepted(localizedTarget, song(english, "Artist", "3:20")),
            )
            val englishTarget = TrackMatcher.Target(english, "Artist", durationSec = 200)
            assertTrue(
                "$english must accept $localized",
                accepted(englishTarget, song(localized, "Artist", "3:20")),
            )
        }
    }

    @Test
    fun `a live recording at a venue is a take`() {
        assertTakeSeparated("Song (Live at Wembley)")
    }

    @Test
    fun `a sped up or slowed tail after a soundtrack label stays a take`() {
        val fast = TrackMatcher.parseTitle("Dynamite - Speed Up | Joker OST")
        assertEquals("dynamite", fast.core)
        assertEquals(setOf("sped"), fast.versions)

        val slow = TrackMatcher.parseTitle("Dynamite - Slow Down | Joker OST")
        assertEquals("dynamite", slow.core)
        assertEquals(setOf("slowed"), slow.versions)
    }

    @Test
    fun `remastered stays neutral in both directions`() {
        val remastered = TrackMatcher.Target("Song (Remastered 2011)", "Artist", durationSec = 200)
        val original = TrackMatcher.Target("Song", "Artist", durationSec = 200)
        assertTrue(accepted(remastered, song("Song", "Artist", "3:20")))
        assertTrue(accepted(original, song("Song (Remastered 2011)", "Artist", "3:20")))
    }

    @Test
    fun `radio edit stays neutral in both directions`() {
        val radio = TrackMatcher.Target("Song (Radio Edit)", "Artist", durationSec = 200)
        val original = TrackMatcher.Target("Song", "Artist", durationSec = 200)
        assertTrue(accepted(radio, song("Song", "Artist", "3:20")))
        assertTrue(accepted(original, song("Song (Radio Edit)", "Artist", "3:20")))
    }
}