package com.music.bitchord.ko

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.Innertube
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Locale

/** [KO] visitor-persistence */
internal object KoVisitor {
    private const val TAG = "BitChord"
    /** A visitor id minted without the session, for anonymous-request calls. */
    @Volatile
    internal var anonymousVisitorData: String? = null

    private val anonymousVisitorLock = Mutex()

    /** Where a visitor id is kept between process starts. */
    @Volatile
    private var visitorStore: Innertube.VisitorStore? = null

    @Volatile
    private var persistedVisitorKey: String? = null

    @Volatile
    private var persistedVisitorValue: String? = null

    /** Installs (or clears) the between-process home for the visitor id. */
    internal fun initVisitorStore(store: Innertube.VisitorStore?) {
        visitorStore = store
        persistedVisitorKey = null
        persistedVisitorValue = null
    }

    /**
     * Reads back the id left by the previous process when it was minted under
     * this exact cookie. A visitor id (and the PoTokens derived from it) is
     * bound to the cookie that asked for it, so a rotated cookie for the same
     * account gets a different slot and cannot inherit the old id.
     */
    internal fun restorePersistedVisitor() {
        val store = visitorStore ?: return
        val key = visitorPersistenceKey(Innertube.cookie)
        val value = store.getString(key)?.takeIf { VISITOR_DATA.matches(it) } ?: return
        Innertube.visitorData = value
        Innertube.visitorDataIsSessionBound = store.getString(visitorBoundStorageKey(key)) == "1"
        persistedVisitorKey = key
        persistedVisitorValue = value
    }

    /** Writes the id only when the cookie or the value actually changed. */
    internal fun persistVisitor() {
        val store = visitorStore ?: return
        val value = Innertube.visitorData ?: return
        val key = visitorPersistenceKey(Innertube.cookie)
        if (key == persistedVisitorKey && value == persistedVisitorValue) return
        store.putString(key, value)
        store.putString(
            visitorBoundStorageKey(key),
            if (Innertube.visitorDataIsSessionBound) "1" else "0",
        )
        persistedVisitorKey = key
        persistedVisitorValue = value
    }

    /**
     * A visitor id minted without the session, for requests that must not be
     * attributable to the listener.
     *
     * Kept apart from the session id rather than reset when a login happens: an
     * anonymous request must never borrow the signed-in shell's id, and the
     * anonymous id stays anonymous no matter how many sign-ins follow.
     */
    internal suspend fun ensureAnonymousVisitorData(): String? {
        anonymousVisitorData?.let { return it }
        return anonymousVisitorLock.withLock {
            anonymousVisitorData?.let { return@withLock it }
            runCatching { Innertube.fetchVisitorData() }
                .onFailure {
                    Log.w(TAG, "could not mint an anonymous visitor id: ${it.message}")
                }
                .getOrNull()
                ?.also { anonymousVisitorData = it }
        }
    }
}

/** Keep a minted visitor id out of another cookie's slot. */
internal fun visitorStorageKey(sessionKey: String): String = "innertube_visitor:$sessionKey"

/** Hash of the cookie that minted a visitor id; anonymous has one shared slot. */
internal fun visitorCookieKey(cookie: String?): String {
    if (cookie == null) return "anonymous"
    return "c:" + MessageDigest.getInstance("SHA-256")
        .digest(cookie.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
        .take(16)
}

/**
 * The store slot for a visitor id.
 *
 * Keyed by the cookie, not the session key: the same account can rotate its
 * cookie, and a visitor id (and the PoTokens minted from it) stays bound to
 * the cookie that asked for it.
 */
internal fun visitorPersistenceKey(cookie: String?): String =
    visitorStorageKey(visitorCookieKey(cookie))

/** Whether the stored id came from the signed-in shell rather than an anonymous mint. */
private fun visitorBoundStorageKey(key: String): String = "$key:bound"

/** Protobuf-in-base64; always this shape, and nothing else in there is. */
private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")
