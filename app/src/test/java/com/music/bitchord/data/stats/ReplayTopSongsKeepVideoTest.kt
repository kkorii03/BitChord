package com.music.bitchord.data.stats

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review T2: Replay's Top songs rebuilt each row as a bare Song, dropping the
 * video flag, so a kept video or a cover played from there as a plain audio
 * row (the MV soundtrack, never the song version). Recents used asSong().
 */
class ReplayTopSongsKeepVideoTest {

    @Test
    fun `top songs rows are built with asSong`() {
        val source = File("src/main/java/com/music/bitchord/data/stats/ListeningStats.kt").readText()
        val start = source.indexOf("fun toSummary(")
        assertTrue("toSummary not found", start >= 0)
        val body = source.substring(start, source.indexOf("// The artist's own picture", start))
        assertTrue("week branch must use entry.asSong()", "song = entry.asSong()," in body)
        assertTrue("all-time branch must use it.asSong()", "song = it.asSong()," in body)
        assertFalse("a bare Song( rebuild drops isVideo", Regex("""\bSong\(\s*videoId""").containsMatchIn(body))
    }
}
