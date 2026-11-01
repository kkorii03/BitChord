package com.music.bitchord.ko

import com.music.bitchord.data.innertube.Innertube

/** [KO] session-key */
internal object KoSession {
    /**
     * The identity a per-session answer belongs to — the music-gate verdict,
     * and anything else cached across a switch (Auto's Recents, Quick Picks,
     * Home).
     *
     * A channel override wins because the same Google login can act as several
     * listeners, and one of them being gated says nothing about another. Before
     * the shell has answered, the cookie is hashed so the entry still cannot
     * leak across accounts; an anonymous session has one shared entry.
     */
    internal fun sessionKey(): String {
        val chosen = Innertube.channelOverride
        val id = chosen?.dataSyncId?.takeIf { it.isNotBlank() }
            ?: chosen?.pageId?.takeIf { it.isNotBlank() }
            ?: Innertube.scope?.dataSyncId?.takeIf { it.isNotBlank() }
            ?: Innertube.scope?.pageId?.takeIf { it.isNotBlank() }
        if (id != null) return "u:$id"
        val current = Innertube.cookie ?: return "anonymous"
        return visitorCookieKey(current)
    }

    /**
     * The identity currently resolved from the cookie, or null if none is yet.
     */
    internal fun currentSession(): Innertube.ResolvedSession? = Innertube.scope?.let {
        Innertube.ResolvedSession(
            pageId = it.pageId,
            dataSyncId = it.dataSyncId,
            authUser = it.authUser,
            visitorData = Innertube.visitorData,
            clientVersion = it.clientVersion,
        )
    }
}

