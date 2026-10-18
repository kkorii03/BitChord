package com.music.bitchord.data.net

/**
 * Which transport the optional outbound proxy speaks.
 *
 * Off is the default and the state of every install that never asked for one.
 */
enum class ProxyMode { OFF, HTTP, SOCKS5 }

/**
 * How much of YouTube goes through the proxy.
 *
 * Korea without YouTube Music Premium is the case this exists for: the Music
 * origin is gated while youtube.com still answers. [MUSIC_ONLY] is therefore
 * the safe default — it moves the browse/player traffic that the gate is
 * decided on, and leaves ordinary youtube.com pages and googlevideo media
 * downloads on whatever connection the phone already has.
 */
enum class ProxyScope {
    /** music.youtube.com and the youtubei API calls only. */
    MUSIC_ONLY,

    /** Every youtube.com host plus googlevideo.com media downloads. */
    ALL_YOUTUBE,
}

/** What was wrong with a proposed configuration, or null when it is usable. */
enum class ProxyConfigError {
    HOST_MISSING,
    HOST_INVALID,
    PORT_INVALID,
}

/**
 * A proxy configuration, held in memory only.
 *
 * The password is here because OkHttp needs it per request; the persisted copy
 * lives in the encrypted credential store (see AuthStore), and this type's
 * [toString] redacts it so the object can never spill a credential into a log
 * line through an accidental interpolation.
 */
data class ProxyConfig(
    val mode: ProxyMode = ProxyMode.OFF,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val scope: ProxyScope = ProxyScope.MUSIC_ONLY,
) {
    /** Whether a proxy was asked for at all, configured or not. */
    val enabled: Boolean get() = mode != ProxyMode.OFF

    /** Whether this configuration can actually be handed to the network layer. */
    val usable: Boolean get() = enabled && ProxyValidation.validate(this) == null

    fun normalized(): ProxyConfig = copy(
        host = host.trim().removeSurrounding("[", "]"),
        username = username.trim(),
    )

    override fun toString(): String =
        "ProxyConfig(mode=$mode, host='$host', port=$port, username='$username', " +
            "password=${if (password.isEmpty()) "''" else "'***'"}, scope=$scope)"
}

/**
 * Decides, from a request's host (and path), whether it belongs on the proxy.
 *
 * Kept a pure function so the one rule that must be right — `EVIL-youtube.com`
 * is not YouTube — is tested without a proxy, a socket, or a network.
 */
object ProxyScopeMatcher {

    fun matches(host: String?, path: String?, scope: ProxyScope): Boolean {
        val target = host?.trim()?.lowercase() ?: return false
        if (target.isEmpty()) return false
        val requestPath = path.orEmpty()
        return when (scope) {
            ProxyScope.ALL_YOUTUBE -> isYouTubeHost(target)
            ProxyScope.MUSIC_ONLY ->
                target == "music.youtube.com" ||
                    target == "youtubei.googleapis.com" ||
                    (isYouTubeApiHost(target) && isYouTubeApiPath(requestPath))
        }
    }

    /**
     * The hosts that serve youtubei calls. `music.youtube.com` is deliberately
     * absent here: it is handled as a whole-origin match above, while these
     * three only earn the proxy for their API paths.
     */
    private fun isYouTubeApiHost(host: String): Boolean =
        host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com"

    private fun isYouTubeApiPath(path: String): Boolean =
        path.startsWith("/youtubei/", ignoreCase = true)

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "googlevideo.com" ||
            host.endsWith(".googlevideo.com") ||
            host == "youtubei.googleapis.com"
}

/**
 * The validation behind both the settings dialog and [ProxyConfig.usable].
 *
 * Only the fields a proxy cannot work without are rejected. Credentials stay
 * optional: an open proxy is a real configuration, and a SOCKS5 proxy may
 * authenticate a user with no password.
 */
object ProxyValidation {

    fun validate(config: ProxyConfig): ProxyConfigError? {
        if (!config.enabled) return null
        val host = config.host.trim()
        when {
            host.isEmpty() -> return ProxyConfigError.HOST_MISSING
            host.any { it.isWhitespace() } -> return ProxyConfigError.HOST_INVALID
            "://" in host || '/' in host || '\\' in host || '@' in host ->
                return ProxyConfigError.HOST_INVALID
            // One colon means a host:port pair was pasted into the host field.
            // Several means an IPv6 literal, which is legitimate.
            host.count { it == ':' } == 1 -> return ProxyConfigError.HOST_INVALID
        }
        if (config.port !in 1..65535) return ProxyConfigError.PORT_INVALID
        return null
    }
}
