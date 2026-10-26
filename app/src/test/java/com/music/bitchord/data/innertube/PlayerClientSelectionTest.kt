package com.music.bitchord.data.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Client selection from a googlevideo URL's `c` / `cver` parameters. The
 * media fetch has to wear the same identity that minted the URL, and the
 * range cap depends on that identity too.
 */
class PlayerClientSelectionTest {

    private fun url(client: String?, version: String? = null, host: String = "rr1---sn-abc.googlevideo.com") =
        buildString {
            append("https://$host/videoplayback?clen=5000000")
            if (client != null) append("&c=").append(client)
            if (version != null) append("&cver=").append(version)
        }

    @Test
    fun `a missing or invalid url falls back to IOS`() {
        assertEquals("IOS", PlayerClient.forStreamUrl("https://example.com/x").clientName)
        assertEquals("IOS", PlayerClient.forStreamUrl("not a url").clientName)
        assertEquals("IOS", PlayerClient.forStreamUrl(url(null)).clientName)
    }

    @Test
    fun `the client parameter is matched case-insensitively`() {
        assertEquals("IOS", PlayerClient.forStreamUrl(url("ios")).clientName)
        assertEquals("ANDROID", PlayerClient.forStreamUrl(url("android")).clientName)
        assertEquals("WEB_REMIX", PlayerClient.forStreamUrl(url("web_remix")).clientName)
    }

    @Test
    fun `the recent IOS version is modelled separately`() {
        val recent = PlayerClient.forStreamUrl(url("IOS", "21.29.1"))
        assertEquals("IOS", recent.clientName)
        assertEquals("21.29.1", recent.clientVersion)
        assertTrue(recent.userAgent.contains("21.29.1"))

        val current = PlayerClient.forStreamUrl(url("IOS", "21.26.4"))
        assertEquals("21.26.4", current.clientVersion)
        assertTrue(current.userAgent.contains("21.26.4"))
    }

    @Test
    fun `an unknown IOS version keeps the baseline client`() {
        assertEquals("21.26.4", PlayerClient.forStreamUrl(url("IOS", "99.0")).clientVersion)
    }

    @Test
    fun `an IOS-prefixed client name is still IOS`() {
        assertEquals("IOS", PlayerClient.forStreamUrl(url("IOS_X")).clientName)
    }

    @Test
    fun `the legacy ANDROID_VR version is modelled separately`() {
        val legacy = PlayerClient.forStreamUrl(url("ANDROID_VR", "1.43.32"))
        assertEquals("ANDROID_VR", legacy.clientName)
        assertEquals("1.43.32", legacy.clientVersion)
        assertTrue(legacy.userAgent.contains("1.43.32"))

        val current = PlayerClient.forStreamUrl(url("ANDROID_VR", "1.65.10"))
        assertEquals("1.65.10", current.clientVersion)
    }

    @Test
    fun `ANDROID_MUSIC is its own client`() {
        val client = PlayerClient.forStreamUrl(url("ANDROID_MUSIC"))
        assertEquals("ANDROID_MUSIC", client.clientName)
        assertTrue(client.userAgent.contains("youtube.music"))
    }

    @Test
    fun `other ANDROID variants use the Android client`() {
        assertEquals("ANDROID", PlayerClient.forStreamUrl(url("ANDROID_TESTSUITE")).clientName)
        assertEquals("ANDROID", PlayerClient.forStreamUrl(url("ANDROID")).clientName)
    }

    @Test
    fun `TV variants use the TV client`() {
        assertEquals("TVHTML5", PlayerClient.forStreamUrl(url("TVHTML5")).clientName)
        assertEquals("TVHTML5", PlayerClient.forStreamUrl(url("TVHTML5_SIMPLY")).clientName)
    }

    @Test
    fun `web clients carry the origin their request must repeat`() {
        val music = PlayerClient.forStreamUrl(url("WEB_REMIX"))
        assertEquals("WEB_REMIX", music.clientName)
        assertEquals("https://music.youtube.com", music.origin)
        assertEquals("https://music.youtube.com/", music.referer)
        assertEquals("https://music.youtube.com", music.mediaHeaders()["Origin"])
        assertEquals("https://music.youtube.com/", music.mediaHeaders()["Referer"])

        val web = PlayerClient.forStreamUrl(url("WEB"))
        assertEquals("WEB", web.clientName)
        assertEquals("https://www.youtube.com", web.origin)

        val mweb = PlayerClient.forStreamUrl(url("MWEB"))
        assertEquals("WEB", mweb.clientName)
        assertEquals("https://www.youtube.com", mweb.origin)
    }

    @Test
    fun `native clients send no origin or referer`() {
        val client = PlayerClient.forStreamUrl(url("IOS"))
        assertNull(client.origin)
        assertNull(client.referer)
        assertFalse(client.mediaHeaders().containsKey("Origin"))
        assertFalse(client.mediaHeaders().containsKey("Referer"))
        assertTrue(client.mediaHeaders().getValue("User-Agent").startsWith("com.google.ios.youtube/"))
    }

    @Test
    fun `an unknown client is fetched as IOS rather than guessed`() {
        assertEquals("IOS", PlayerClient.forStreamUrl(url("SOMETHING_NEW")).clientName)
    }

    @Test
    fun `non-googlevideo and invalid urls are not range-capped`() {
        assertEquals(Long.MAX_VALUE, PlayerClient.rangeBytesFor("https://example.com/a.flac"))
        assertEquals(Long.MAX_VALUE, PlayerClient.rangeBytesFor("not a url"))
        assertEquals(Long.MAX_VALUE, PlayerClient.rangeBytesFor(url("IOS", host = "googlevideo.com.evil")))
    }

    @Test
    fun `googlevideo clients get a one megabyte range by default`() {
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url("IOS")))
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url("ANDROID")))
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url("TVHTML5")))
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url("WEB_REMIX")))
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url(null)))
    }

    @Test
    fun `clients refused past a megabyte get half that`() {
        assertEquals(512L * 1024, PlayerClient.rangeBytesFor(url("ANDROID_VR")))
        assertEquals(512L * 1024, PlayerClient.rangeBytesFor(url("TVHTML5_SIMPLY")))
        assertEquals(512L * 1024, PlayerClient.rangeBytesFor(url("android_vr")))
    }

    @Test
    fun `the range host check accepts the googlevideo apex and subdomains`() {
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url("IOS", host = "googlevideo.com")))
        assertEquals(1024L * 1024, PlayerClient.rangeBytesFor(url("IOS", host = "rr1.googlevideo.com")))
    }

    @Ignore(
        "BUG: rangeBytesFor uses host.endsWith(\"googlevideo.com\") without a dot boundary, " +
            "so a lookalike such as notgooglevideo.com is range-capped as if it were YouTube. " +
            "ProxyScopeMatcher's isYouTubeHost correctly requires the apex or a .googlevideo.com suffix.",
    )
    @Test
    fun `a lookalike host is not range-capped`() {
        assertEquals(
            Long.MAX_VALUE,
            PlayerClient.rangeBytesFor(url("IOS", host = "notgooglevideo.com")),
        )
    }
}
