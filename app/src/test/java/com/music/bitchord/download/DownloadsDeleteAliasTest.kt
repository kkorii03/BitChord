package com.music.bitchord.download

import java.io.File
import org.junit.Assert.assertEquals
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
