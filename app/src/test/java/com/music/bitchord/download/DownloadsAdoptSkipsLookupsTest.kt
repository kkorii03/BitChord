package com.music.bitchord.download

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review D2: `transfer` runs inside `coroutineScope`, which does not return while
 * a child is running, and [MediaTagger.artworkFor] blocks (a cancel cannot stop
 * it). When the lyrics and cover lookups were started before the "already in
 * Music; adopting it" branch, every adopted track paid a full lyrics search and
 * a cover fetch before the worker moved on. The lookups must start after it.
 *
 * A source check: the branch needs a real context, media store and network.
 */
class DownloadsAdoptSkipsLookupsTest {

    @Test
    fun `transfer starts the lyrics and cover lookups only after the adopt branch`() {
        val source = File("src/main/java/com/music/bitchord/download/Downloads.kt").readText()
        val start = source.indexOf("private suspend fun transfer(")
        assertTrue("transfer not found", start >= 0)
        val end = source.indexOf("One resolved download", start).takeIf { it > start } ?: source.length
        val body = source.substring(start, end)

        val adopt = body.indexOf("is already in Music; adopting it")
        val lyrics = body.indexOf("lyrics = async")
        val artwork = body.indexOf("artwork = async")
        assertTrue("adopt branch not found", adopt >= 0)
        assertTrue("lyrics lookup not found", lyrics >= 0)
        assertTrue("cover lookup not found", artwork >= 0)
        assertTrue("lyrics lookup starts before the adopt branch", lyrics > adopt)
        assertTrue("cover lookup starts before the adopt branch", artwork > adopt)
    }
}
