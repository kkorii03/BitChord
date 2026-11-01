package com.music.bitchord

import com.music.bitchord.data.lyrics.BugsLyrics
import com.music.bitchord.data.lyrics.KuGou
import com.music.bitchord.data.lyrics.LrcRed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * A provider with more chances behind it must keep them when one request dies.
 *
 * [com.music.bitchord.data.lyrics.lyricsGet] surfaces transport failures so the
 * provider sheet can say NETWORK/TIMEOUT, but the providers used to treat the
 * first thrown IOException as their final answer, skipping the hash/keyword,
 * sibling-release and candidate fallbacks that a plain null used to allow.
 */
class LyricsProviderFallbackTest {

    @Test
    fun `lrc red searches a sibling release when the known isrc document fails`() = runBlocking {
        val search = """
            {"hits":[{"isrc":"USRC17607839","title":"Song","artist":"Artist","duration":200.0}]}
        """.trimIndent()
        val ttml = """<tt><body><div><p begin="1.0" end="2.0">sibling line</p></div></body></tt>"""

        val get: suspend (String) -> String? = { url ->
            when {
                url.endsWith("/GBUM71029604.ttml") -> throw IOException("known document is down")
                url.contains("search.json") -> search
                url.endsWith("/USRC17607839.ttml") -> ttml
                else -> null
            }
        }

        val lines = LrcRed.lyrics("Song", "Artist", 200_000L, isrc = "GBUM71029604", get = get)

        assertNotNull("the sibling release was never asked", lines)
        assertEquals("sibling line", lines!!.first().text)
    }

    @Test
    fun `kugou falls back to the keyword search when a hash search fails`() = runBlocking {
        val lrc = "[00:01.00]first line\n[00:05.00]second line"
        val encoded = Base64.getEncoder().encodeToString(lrc.toByteArray(Charsets.UTF_8))

        val get: (String) -> String? = { url ->
            when {
                url.contains("/search/song") ->
                    """{"data":{"info":[{"hash":"h1","duration":200,"songname":"Song","filename":"Song"}]}}"""
                url.contains("/search") && url.contains("hash=h1") ->
                    throw IOException("hash search is down")
                url.contains("/search") && url.contains("keyword=") ->
                    """{"candidates":[{"id":"c1","accesskey":"a1","song":"Song","singer":"Artist"}]}"""
                url.contains("/download") -> """{"content":"$encoded"}"""
                else -> null
            }
        }

        val lines = KuGou.lyrics("Song", "Artist", 200_000L, get = get)

        assertNotNull("the keyword fallback was never asked", lines)
        assertEquals("first line", lines!!.first().text)
    }

    @Test
    fun `bugs keeps the other candidate ids when one track detail fails`() = runBlocking {
        val search = """
            <tr trackId="1" rowType="track"></tr>
            <tr trackId="2" rowType="track"></tr>
        """.trimIndent()
        val track = """{"track":{"track_title":"Song","artist_nm":"Artist","len":"03:20"}}"""
        val lyrics = """{"lyrics":"1.0|line one＃2.0|line two"}"""

        val get: (String) -> String? = { url ->
            when {
                url.contains("/search/track") -> search
                url.endsWith("/player/track/1") -> throw IOException("first candidate is down")
                url.endsWith("/player/track/2") -> track
                url.contains("/player/lyrics/T/2") -> lyrics
                url.contains("/player/lyrics/N/2") -> lyrics
                else -> null
            }
        }

        val lines = BugsLyrics.lyrics("Song", "Artist", 200_000L, get = get)

        assertNotNull("the second candidate was never asked", lines)
        assertEquals("line one", lines!!.first().text)
    }
}
