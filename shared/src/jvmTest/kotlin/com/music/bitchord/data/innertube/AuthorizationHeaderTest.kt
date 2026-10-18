package com.music.bitchord.data.innertube

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `Authorization` header sent with a signed-in request.
 *
 * Pinned because a fresh Google login can carry only the `__Secure-1P/3P`
 * signing cookies, and a lone `SAPISIDHASH` for those was answered 401 by
 * youtube.com for every request (S24, Korea, no Premium).
 */
class AuthorizationHeaderTest {

    private val origin = "https://www.youtube.com"
    private val ts = 1_700_000_000L

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test
    fun `secure-only jar sends all three hashes`() {
        val header = Innertube.authorizationFor(
            "__Secure-1PAPISID=one; __Secure-3PAPISID=three; SID=x",
            origin,
            ts,
        )
        assertEquals(
            "SAPISIDHASH ${ts}_${sha1("$ts three $origin")} " +
                "SAPISID1PHASH ${ts}_${sha1("$ts one $origin")} " +
                "SAPISID3PHASH ${ts}_${sha1("$ts three $origin")}",
            header,
        )
    }

    @Test
    fun `plain SAPISID signs the first hash`() {
        val header = Innertube.authorizationFor("SAPISID=plain; __Secure-3PAPISID=three", origin, ts)
        assertEquals(
            "SAPISIDHASH ${ts}_${sha1("$ts plain $origin")} " +
                "SAPISID3PHASH ${ts}_${sha1("$ts three $origin")}",
            header,
        )
    }

    @Test
    fun `jar without a signing cookie sends nothing`() {
        assertNull(Innertube.authorizationFor("PREF=a; YSC=b", origin, ts))
    }
}
