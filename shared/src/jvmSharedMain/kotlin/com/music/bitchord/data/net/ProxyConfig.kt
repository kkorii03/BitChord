package com.music.bitchord.data.net

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

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
 * the safe default — it moves the browse traffic that the gate is decided on,
 * and leaves ordinary youtube.com pages, stream/player negotiation and
 * googlevideo media downloads on whatever connection the phone already has.
 */
enum class ProxyScope {
    /**
     * music.youtube.com and youtubei.googleapis.com, minus any `/player`
     * request. Stream resolution always uses the direct connection.
     */
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
                isMusicOrigin(target) && !isStreamResolution(requestPath)
        }
    }

    /**
     * The two hosts the gate is decided on. `www.youtube.com` and
     * `m.youtube.com` are deliberately absent: their API calls (search, next,
     * player) must stay on the direct connection, both because the Korean
     * fallbacks already work there and because a player response minted
     * through the proxy cannot be fetched from Korea.
     */
    private fun isMusicOrigin(host: String): Boolean =
        host == "music.youtube.com" || host == "youtubei.googleapis.com"

    /**
     * googlevideo binds a stream URL to the IP of the `player` request that
     * minted it, so a player response fetched through the overseas proxy
     * returns URLs the phone then cannot use from Korea. Player negotiation
     * therefore never goes through the proxy, on any host, in MUSIC_ONLY.
     */
    private fun isStreamResolution(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith("/player") ||
            "/youtubei/v1/player" in lower ||
            "/get_watch" in lower
    }

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

/**
 * Reads the single-string form used by the debug launch extra.
 *
 * `http://user:pass@host:port` and `socks5://user:pass@host:port` are the
 * shapes a proxy is usually handed around in. The result is always Music-only:
 * the debug extra configures the Korea workaround, not a general-purpose
 * tunnel. A missing port falls back to the scheme's usual one, and anything
 * the settings dialog itself would refuse is rejected rather than half-applied.
 */
object ProxyUrlParser {

    fun parse(raw: String?): ProxyConfig? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val separator = text.indexOf("://")
        if (separator <= 0) return null
        val mode = when (text.substring(0, separator).lowercase()) {
            "http", "https" -> ProxyMode.HTTP
            "socks5", "socks5h" -> ProxyMode.SOCKS5
            else -> return null
        }
        var authority = text.substring(separator + 3)
        // A trailing slash is how URLs are often pasted; it is not part of
        // the authority and does not change what the proxy is.
        while (authority.endsWith('/')) authority = authority.dropLast(1)
        if (authority.isEmpty()) return null
        if ('/' in authority || '?' in authority || '#' in authority) return null

        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = authority.substring(at + 1)
        if (hostPort.isEmpty()) return null

        val host: String
        val portText: String?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close <= 1) return null
            host = hostPort.substring(1, close)
            val rest = hostPort.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            val first = hostPort.indexOf(':')
            val last = hostPort.lastIndexOf(':')
            when {
                first < 0 -> {
                    host = hostPort
                    portText = null
                }
                // Several colons without brackets is an IPv6 literal, and its
                // last group is not a port.
                first != last -> {
                    host = hostPort
                    portText = null
                }
                else -> {
                    host = hostPort.substring(0, first)
                    portText = hostPort.substring(first + 1)
                }
            }
        }

        val port = when (portText) {
            null -> when (mode) {
                ProxyMode.HTTP -> 80
                ProxyMode.SOCKS5 -> 1080
                ProxyMode.OFF -> return null
            }
            else -> portText.toIntOrNull() ?: return null
        }

        val colon = userInfo.indexOf(':')
        val username = percentDecode(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
        val password = if (colon >= 0) percentDecode(userInfo.substring(colon + 1)) else ""

        val config = ProxyConfig(
            mode = mode,
            host = host,
            port = port,
            username = username,
            password = password,
            scope = ProxyScope.MUSIC_ONLY,
        ).normalized()
        return config.takeIf { ProxyValidation.validate(it) == null }
    }

    /**
     * `%40` and friends, without treating `+` as a space: in a password a plus
     * is a plus, unlike in a form body.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val high = hex(value[index + 1])
                val low = hex(value[index + 2])
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low)
                    index += 3
                    continue
                }
            }
            out.write(char.toString().toByteArray(StandardCharsets.UTF_8))
            index++
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun hex(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
