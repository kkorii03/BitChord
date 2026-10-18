package com.music.bitchord

import com.music.bitchord.auth.YouTubeWebLogin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the sign-in WebView's cookie jar is a completed YouTube login.
 *
 * This is the check that replaced "the page URL starts with music.youtube.com":
 * a Korean non-Premium account is redirected to a Premium paywall after a
 * successful Google login, so the page-based check never finished even though
 * the jar was complete. The decision is pinned here because getting it wrong
 * either blocks a real login (too strict) or saves an unusable session (too
 * loose).
 */
class YouTubeLoginCookieTest {

    @Test
    fun `a complete signed-in jar is accepted`() {
        assertTrue(
            YouTubeWebLogin.isSessionComplete(
                "SID=abc; HSID=def; SAPISID=secret; LOGIN_INFO=music",
            ),
        )
    }

    @Test
    fun `partitioned secure cookies are complete on their own`() {
        assertTrue(
            YouTubeWebLogin.isSessionComplete(
                "__Secure-3PSID=abc; __Secure-3PAPISID=secret",
            ),
        )
        assertTrue(YouTubeWebLogin.isSessionComplete("__Secure-1PAPISID=secret"))
    }

    @Test
    fun `sign-in cookies without a signing secret are not complete`() {
        // The app could send these but could not sign a single Innertube
        // request with them, which Google answers as a stranger.
        assertFalse(YouTubeWebLogin.isSessionComplete("SID=abc; LOGIN_INFO=music"))
    }

    @Test
    fun `a blank jar is not complete`() {
        assertFalse(YouTubeWebLogin.isSessionComplete(null))
        assertFalse(YouTubeWebLogin.isSessionComplete(""))
        assertFalse(YouTubeWebLogin.isSessionComplete("SID=; SAPISID=; LOGIN_INFO="))
    }

    @Test
    fun `the cookie name has to be the whole name`() {
        assertFalse(YouTubeWebLogin.isSessionComplete("APISID=secret"))
        assertFalse(YouTubeWebLogin.isSessionComplete("NOT-SAPISID=secret"))
        assertFalse(YouTubeWebLogin.isSessionComplete("PREF=tz=SAPISID"))
    }
}
