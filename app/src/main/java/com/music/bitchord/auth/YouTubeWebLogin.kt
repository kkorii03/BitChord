package com.music.bitchord.auth

import java.net.URLEncoder

/**
 * The pure parts of the in-app Google sign-in: where it starts, what user
 * agent it claims, and when the cookie jar is complete enough to finish.
 *
 * Kept out of the Compose screen so the two decisions a real Korean
 * non-Premium sign-in tripped over can be pinned by unit tests without a
 * device. See [YtMusicLoginScreen] for the browser itself.
 */
object YouTubeWebLogin {

    /**
     * A desktop Chrome user agent.
     *
     * Google serves the free YouTube Music desktop web player to a desktop
     * browser in regions where the *mobile* Music site is replaced by a
     * "Get Music Premium" paywall. The WebView is there to obtain a Google
     * session, not to render the mobile app, so it should not announce itself
     * as one.
     */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /**
     * Where a completed sign-in lands.
     *
     * A desktop youtube.com URL rather than music.youtube.com: the mobile
     * Music page is the one Google paywalls for non-Premium accounts, and the
     * paywall publishes no signed-in `ytcfg` for the completion step to read.
     * The identity is fetched afterwards with [Innertube] and the same cookies.
     */
    const val CONTINUE_URL =
        "https://www.youtube.com/?app=desktop&persist_app=1&noapp=1"

    /** The Google login page, told to return to [continueUrl] when it is done. */
    fun loginUrl(continueUrl: String = CONTINUE_URL): String =
        "https://accounts.google.com/ServiceLogin" +
            "?ltmpl=music&service=youtube&passive=true" +
            "&continue=" + URLEncoder.encode(continueUrl, "UTF-8")

    /**
     * Whether [cookieHeader] is a completed YouTube login.
     *
     * The test is the same one [AuthStore.isSignedIn] makes: a signing secret
     * (`SAPISID` or one of the `__Secure-*PAPISID` forms) is present and
     * non-empty. Google only issues those to a signed-in jar, which is why
     * `SID` / `LOGIN_INFO` alone are not enough — the app could not sign a
     * single request with them.
     *
     * This deliberately says nothing about the page the cookies were read
     * from. The page is not the credential, and a paywall, a desktop home page
     * and m.youtube.com can all sit on top of the same complete jar.
     */
    fun isSessionComplete(cookieHeader: String?): Boolean =
        !cookieHeader.isNullOrBlank() && AuthStore.hasApiSid(cookieHeader)
}
