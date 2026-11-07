package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SyncLRC answers one row per request: a karaoke LRC for the recording it
 * matched, a synced LRC, plain text, or nothing.
 *
 * The live answers this provider was added for are in `ship\lanes\aggregators.md`
 * (2026-11-07). These fixtures are synthetic: the names and the times are the
 * ones the checks care about, and the words are placeholders.
 */
class SyncLrcTest {

    /** A karaoke answer for a 323 s track: two lines, word stamps on both. */
    private val karaoke = """
        {"id":"0123456789abcdef0123456789abcdef","track":"Lose Yourself","artist":"Eminem",
        "album":"8 Mile","duration":323.3,"instrumental":false,
        "lyrics":"[00:01.00]<00:01.00>one <00:01.40>two <00:01.80>three\n[00:02.50]<00:02.50>four <00:02.90>five",
        "type":"karaoke","format":"lrc"}
    """.trimIndent().replace("\n", "")

    @Test
    fun `a karaoke answer is word timed`() {
        val lines = assertNotNull(SyncLrc.linesOf(karaoke, "Lose Yourself", "Eminem", 327_500))
        val sung = lines.filterNot { it.isGap }
        assertEquals(2, sung.size)
        assertEquals(1_000L, sung[0].timeMs)
        assertEquals(3, sung[0].words.size)
        assertTrue(sung.all { it.isWordSynced })
    }

    @Test
    fun `a sheet of another recording is refused`() {
        // A shorter edit: 323 s is past the 45 s overhang for a 250 s song.
        assertNull(SyncLrc.linesOf(karaoke, "Lose Yourself", "Eminem", 250_000))
        // Another version word.
        assertNull(SyncLrc.linesOf(karaoke, "Lose Yourself (Live)", "Eminem", 327_500))
        // Another artist.
        assertNull(SyncLrc.linesOf(karaoke, "Lose Yourself", "Someone Else", 327_500))
    }

    @Test
    fun `a line-only answer is refused`() {
        // The requested type is karaoke; a synced LRC is a line sheet, which the
        // line-only sources already cover.
        val synced = karaoke
            .replace("\"type\":\"karaoke\"", "\"type\":\"synced\"")
            .replace("""<00:01.00>""", "").replace("""<00:01.40>""", "")
            .replace("""<00:01.80>""", "").replace("""<00:02.50>""", "")
            .replace("""<00:02.90>""", "")
        assertNull(SyncLrc.linesOf(synced, "Lose Yourself", "Eminem", 327_500))
        // Even under the karaoke type, a body with no word stamps has nothing to
        // sweep; EnhancedLrc comes back empty and the caller falls through.
        val unstamped = karaoke
            .replace("""<00:01.00>""", "").replace("""<00:01.40>""", "")
            .replace("""<00:01.80>""", "").replace("""<00:02.50>""", "")
            .replace("""<00:02.90>""", "")
        assertNull(SyncLrc.linesOf(unstamped, "Lose Yourself", "Eminem", 327_500))
    }

    @Test
    fun `an empty answer is refused`() {
        assertNull(SyncLrc.linesOf("""{"track":"Lose Yourself","artist":"Eminem","duration":323.3,"lyrics":""}""",
            "Lose Yourself", "Eminem", 327_500))
        assertNull(SyncLrc.linesOf("""{"track":"Lose Yourself","artist":"Eminem","duration":323.3}""",
            "Lose Yourself", "Eminem", 327_500))
    }

    @Test
    fun `a malformed answer is refused`() {
        assertNull(SyncLrc.linesOf("{not json", "Lose Yourself", "Eminem", 327_500))
        assertNull(SyncLrc.linesOf("", "Lose Yourself", "Eminem", 327_500))
    }

    @Test
    fun `the queue keeps the calls under thirty a minute`() {
        // 2.1 s apart = 28.6 a minute, inside the documented 30.
        assertEquals(0L, SyncLrc.waitMs(nowMs = 10_000, lastAtMs = 0))
        assertEquals(1_100L, SyncLrc.waitMs(nowMs = 11_000, lastAtMs = 10_000))
        assertEquals(0L, SyncLrc.waitMs(nowMs = 20_000, lastAtMs = 17_900))
    }
}