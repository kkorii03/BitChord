package com.music.bitchord.ko

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.MusicGateFallback
import io.ktor.client.plugins.ResponseException

/**
 * Korean-fork gate helpers that lived in YtMusicRepository before the v1.9
 * merge. Kept outside the upstream file so the next merge does not lose them.
 */
internal object KoRepoGate {

    /**
     * Whether the Music gate is active, settling an UNKNOWN verdict first.
     *
     * Called by entry points that can be the first thing opened in a process
     * (Explore, Library, a shared link). It costs one browse only while the
     * verdict is unknown and not backing off, or when a stored verdict is due
     * for re-check; after that the persisted answer decides.
     */
    suspend fun ensureGateKnown(): Boolean {
        ensureSessionForGate()
        val key = Innertube.sessionKey()
        if (MusicGateFallback.needsRecheck(key)) {
            // Signed for the same reason as Home's re-check.
            runCatching { Innertube.browse("FEmusic_home", forceSigned = true) }
                // A failure is still a check that happened: move checkedAt so
                // every entry point does not repeat the same failing browse
                // until the window opens again.
                .onFailure { MusicGateFallback.touch(key) }
        }
        return MusicGateFallback.isActive(key)
    }

    /** The gate's own refusal, as opposed to a network or proxy failure. */
    fun isMusicGateSignal(failure: Exception): Boolean {
        val status = (failure as? ResponseException)?.response?.status?.value
        return status == 401 || status == 403
    }

    /**
     * Resolves the account identity before reading the persisted verdict.
     *
     * Without this a restored cookie is keyed by its hash until the shell
     * answers, and the verdict saved under the account's `dataSyncId` would be
     * missed for the first request of every process.
     */
    private suspend fun ensureSessionForGate() {
        if (Innertube.cookie != null) runCatching { Innertube.ensureSessionScope() }
    }

    suspend fun fallbackActive(): Boolean {
        ensureSessionForGate()
        return MusicGateFallback.isActive()
    }

    fun isYouTubePlaylistId(browseId: String): Boolean =
        browseId.startsWith("VL") || browseId.startsWith("RD")
}
