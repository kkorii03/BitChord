package com.music.bitchord.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review R2: an MV download is filed under the video id and its song id. Deleting
 * from the song sheet forgot only the asked id, so the other stayed "downloaded"
 * and playlist sync and liked auto-download never fetched it again.
 */
class DownloadsDeleteAliasTest {

    @Test
    fun `both ids of one file are forgotten together`() {
        val saved = mapOf("mv" to "file:/x.m4a", "song" to "file:/x.m4a", "other" to "file:/y.m4a")
        assertEquals(setOf("mv", "song"), Downloads.idsSharingFile(saved, "mv"))
        assertEquals(setOf("song", "mv"), Downloads.idsSharingFile(saved, "song"))
        assertEquals(setOf("other"), Downloads.idsSharingFile(saved, "other"))
        assertEquals(setOf("gone"), Downloads.idsSharingFile(saved, "gone"))
    }

    /**
     * u1121 LIBRARY-1: a replacement landed on a new uri and deleted the old
     * file; a queued item still on the old uri failed to open it. Old code
     * forgot the record by id, which named the new, valid file.
     */
    @Test
    fun `a failed read of the old uri keeps the record of the replacement`() {
        val id = "u1121-lib1"
        val old = "content://media/external/audio/media/11"
        val new = "content://media/external/audio/media/12"
        Downloads.record(saved = { it + (id to new) }, meta = { it })
        try {
            assertFalse(Downloads.forgetMissing(id, old))
            assertEquals(new, Downloads.savedValue()[id])
            assertFalse("no failed uri, nothing proven", Downloads.forgetMissing(id, null))
            assertEquals(new, Downloads.savedValue()[id])
            assertTrue(Downloads.forgetMissing(id, new))
            assertNull(Downloads.savedValue()[id])
        } finally {
            Downloads.record(saved = { it - id }, meta = { it - id })
        }
    }

    @Test
    fun `the forget rule asks for the recorded file itself`() {
        assertTrue(Downloads.forgetsMissing("file:///a.m4a", "file:///a.m4a"))
        assertFalse(Downloads.forgetsMissing("file:///a.flac", "file:///a.m4a"))
        assertFalse(Downloads.forgetsMissing("file:///a.flac", null))
    }

    @Test
    fun `delete forgets every id sharing the file`() {
        val source = File("src/main/java/com/music/bitchord/download/Downloads.kt").readText()
        val start = source.indexOf("suspend fun delete(context: Context, videoId: String)")
        assertTrue("delete not found", start >= 0)
        val body = source.substring(start, source.indexOf("private fun forget(", start))
        assertTrue(body.contains("idsSharingFile(_saved.value, videoId)"))
        assertTrue("delete must not forget the asked id alone", !Regex("""^\s*forget\(videoId\)""", RegexOption.MULTILINE).containsMatchIn(body))
    }
}
