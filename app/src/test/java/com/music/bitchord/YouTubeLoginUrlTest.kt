package com.music.bitchord

import com.music.bitchord.auth.YouTubeWebLogin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Where the in-app Google sign-in sends the listener.
 *
 * Pinned because the old URL returned to music.youtube.com, whose *mobile*
 * page Google replaces with a Premium paywall for some regions and accounts.
 * The login must return to the desktop YouTube site so the completed cookie
 * jar is not hidden behind that wall, and must announce desktop Chrome for the
 * same reason.
 */
class YouTubeLoginUrlTest {

    private val url = YouTubeWebLogin.loginUrl()

    @Test
    fun `login starts at Google service login`() {
        assertTrue(url.startsWith("https://accounts.google.com/ServiceLogin?"))
    }

    @Test
    fun `completion returns to desktop YouTube rather than mobile Music`() {
        val expected = URLEncoder.encode(YouTubeWebLogin.CONTINUE_URL, "UTF-8")
        assertTrue(url.contains("continue=$expected"))
        assertTrue(YouTubeWebLogin.CONTINUE_URL.startsWith("https://www.youtube.com/"))
        assertFalse(YouTubeWebLogin.CONTINUE_URL.contains("music.youtube.com"))
    }

    @Test
    fun `a custom continue target is URL encoded`() {
        val custom = YouTubeWebLogin.loginUrl("https://www.youtube.com/watch?v=a b")
        assertTrue(custom.endsWith("continue=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Da+b"))
    }

    @Test
    fun `completion does not force the desktop site`() {
        // Google refuses a sign-in from a WebView posing as desktop Chrome, so
        // the WebView keeps its own UA and nothing here asks for desktop.
        assertFalse(YouTubeWebLogin.CONTINUE_URL.contains("app=desktop"))
    }
}
