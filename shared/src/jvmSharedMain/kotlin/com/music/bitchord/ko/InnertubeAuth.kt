package com.music.bitchord.ko

import java.security.MessageDigest
import java.util.Locale

/** [KO] authorization */
internal object KoAuth {
    /**
     * The `Authorization` header youtube.com's own pages send for [cookieHeader].
     *
     * One hash per signing cookie the jar holds: `SAPISIDHASH`, then
     * `SAPISID1PHASH` (`__Secure-1PAPISID`) and `SAPISID3PHASH`
     * (`__Secure-3PAPISID`). A current Google login can carry only the
     * `__Secure-` forms; signing those with a lone `SAPISIDHASH` is answered
     * 401 "missing required authentication credential" — what the S24 log
     * showed for every youtube.com request after a fresh sign-in.
     *
     * Public so the desktop module signs its own youtube.com page fetches with
     * the same scheme instead of keeping a second, weaker copy.
     */
    internal fun authorizationFor(
        cookieHeader: String,
        origin: String,
        timestamp: Long = nowSeconds(),
    ): String? {
        val jar = cookieHeader.split(';')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
        val plain = jar["SAPISID"] ?: jar["__Secure-3PAPISID"] ?: jar["__Secure-1PAPISID"] ?: return null
        return buildList {
            add(hashPart("SAPISIDHASH", plain, origin, timestamp))
            jar["__Secure-1PAPISID"]?.let { add(hashPart("SAPISID1PHASH", it, origin, timestamp)) }
            jar["__Secure-3PAPISID"]?.let { add(hashPart("SAPISID3PHASH", it, origin, timestamp)) }
        }.joinToString(" ")
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun hashPart(label: String, secret: String, origin: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $secret $origin".toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "$label ${timestamp}_$digest"
    }
}

