package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import com.music.bitchord.data.TrackLog

/**
 * Encrypted-at-rest storage for credentials.
 *
 * Two live here: the YouTube Music session cookie, and — if the user turns on
 * the Discord integration — that account's own bearer token. Neither is a
 * password: the Google one is typed into accounts.google.com inside a WebView,
 * and the Discord one is read out of a completed login session. But both grant
 * full access to their account, so they don't go in the plain prefs the
 * scrobbler tokens use.
 *
 * A restored file whose keyset this device can't unwrap is recreated, and a
 * Keystore that can't be used at all degrades to plain prefs rather than
 * crashing on launch; see [EncryptedPrefs]. Kept out of backups for the first
 * reason, in backup_rules.xml and data_extraction_rules.xml.
 */
class AuthStore private constructor(
    private val appContext: Context?,
    private val openPreferences: (() -> SharedPreferences)?,
) {

    constructor(context: Context) : this(context.applicationContext, null)

    /** Test seam: supplies the store without a Keystore or an Android context. */
    internal constructor(openPreferences: () -> SharedPreferences) : this(null, openPreferences)

    /**
     * Opened once, on whichever thread asks first: the startup thread prewarms
     * it, and an earlier credential read waits for that same open instead of
     * opening a second store or seeing an empty one.
     */
    @Volatile
    private var prefs: SharedPreferences? = null

    private val openLock = Any()

    /** Work that may read credentials, run once the same open has finished. */
    private val readyCallbacks = ArrayList<() -> Unit>()

    /** Whether the encrypted store has finished opening. */
    val isReady: Boolean get() = prefs != null

    /**
     * Opens the encrypted store now, or waits for the open already running.
     *
     * Called by the startup prewarm and by every credential accessor, so a
     * first read before the prewarm finishes joins that one open rather than
     * starting another.
     */
    fun ensureReady() {
        readyPrefs()
    }

    /**
     * Runs [block] once credentials can be read.
     *
     * If the store is already open it runs on this thread; otherwise it is
     * queued behind the one open and runs on the thread that finishes it. It
     * always runs after the store is visible, so the callback can read it.
     */
    fun onReady(block: () -> Unit) {
        if (prefs != null) {
            runCatching(block)
            return
        }
        val runNow = synchronized(openLock) {
            if (prefs != null) {
                true
            } else {
                readyCallbacks += block
                false
            }
        }
        if (runNow) runCatching(block)
    }

    private fun readyPrefs(): SharedPreferences {
        prefs?.let { return it }
        val callbacks: List<() -> Unit>
        val opened: SharedPreferences
        synchronized(openLock) {
            prefs?.let { return it }
            opened = openPreferences?.invoke()
                ?: EncryptedPrefs.open(
                    checkNotNull(appContext) { "AuthStore was created without a Context" },
                    "bitchord_auth",
                    "bitchord_auth_plain",
                )
            prefs = opened
            TrackLog.i(
                "BitChord",
                "startup: authstore ready in ${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()}ms " +
                    "(thread=${Thread.currentThread().name})",
            )
            callbacks = readyCallbacks.toList()
            readyCallbacks.clear()
        }
        callbacks.forEach { callback ->
            runCatching(callback).onFailure {
                TrackLog.w("BitChord", "startup auth-store callback failed", it)
            }
        }
        return opened
    }

    var cookie: String?
        get() = readyPrefs().getString(KEY_COOKIE, null)
        set(value) = readyPrefs().edit().putString(KEY_COOKIE, value).apply()

    /**
     * The durable account registry. Credentials remain in this encrypted store;
     * the old single-cookie entry is migrated lazily so an update never logs a
     * listener out.
     */
    var sessions: List<GoogleAccountSession>
        get() {
            val saved = sessionsFromJson(readyPrefs().getString(KEY_SESSIONS, null))
            if (saved.isNotEmpty()) return saved
            val legacy = cookie ?: return emptyList()
            val profile = YouTubeProfile(
                profileId = profileId(channelPageId, channelDataSyncId, channelName ?: "Personal"),
                name = channelName ?: "Personal",
                pageId = channelPageId,
                dataSyncId = channelDataSyncId,
                authUser = channelAuthUser,
                isBrandAccount = channelPageId != null,
            )
            return listOf(GoogleAccountSession(
                accountId = sessionId(legacy, channelDataSyncId), cookie = legacy,
                profiles = listOf(profile), activeProfileId = profile.profileId,
            )).also { replaceSessions(it) }
        }
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = readyPrefs().getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) = readyPrefs().edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    var activeProfileId: String?
        get() = readyPrefs().getString(KEY_ACTIVE_PROFILE, null)
        set(value) = readyPrefs().edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        readyPrefs().edit().putString(KEY_SESSIONS, value.toJson()).apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val existing = sessions
        val index = existing.indexOfFirst { it.accountId == session.accountId }
        val next = if (index >= 0) {
            existing.toMutableList().also { it[index] = session }
        } else {
            existing + session
        }
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    /**
     * Removes one Google login and reports the session that is active after it.
     *
     * Only a removal of the *active* login switches identities: deleting another
     * account from the list must not make that account's neighbour current.
     */
    fun removeAccount(accountId: String): GoogleAccountSession? {
        val active = activeAccountId
        val removedActive = active == accountId
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        if (!removedActive) {
            return remaining.firstOrNull { it.accountId == active } ?: remaining.firstOrNull()
        }
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        readyPrefs().edit().putString(KEY_COOKIE, fallback?.cookie).apply()
        BrowserSession.clearGoogleCookies()
        return fallback
    }

    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = readyPrefs().getString(KEY_DISCORD_TOKEN, null)
        set(value) = readyPrefs().edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /**
     * The WebDAV server password. Encrypted like every other credential here:
     * a backup export carries the server URL and username in plain prefs, but
     * never this.
     */
    var webdavPassword: String?
        get() = readyPrefs().getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = readyPrefs().edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    /**
     * The SMB share password. Encrypted like every other credential here:
     * exports carry the server, share and username in plain prefs, but never
     * this.
     */
    var smbPassword: String?
        get() = readyPrefs().getString(KEY_SMB_PASSWORD, null)
        set(value) = readyPrefs().edit().putString(KEY_SMB_PASSWORD, value).apply()

    /**
     * The network proxy's password. Encrypted like every other credential
     * here: the host, port and username are ordinary settings, but this must
     * never be written to plain prefs or carried by a backup export.
     */
    var proxyPassword: String?
        get() = readyPrefs().getString(KEY_PROXY_PASSWORD, null)
        set(value) = readyPrefs().edit().putString(KEY_PROXY_PASSWORD, value).apply()

    /**
     * A named credential from the settings that used to live in plain
     * preferences: API keys, scrobbler session keys and the Spotify cookie.
     *
     * They are here for the same reason as the proxy password. The plain
     * settings file is carried by Android auto-backup, so a key written there
     * leaves the phone in a cloud copy; this encrypted file is excluded from
     * both backup rule sets. Empty removes the entry rather than storing a
     * blank credential.
     */
    fun secret(name: String): String? = readyPrefs().getString(name, null)

    fun setSecret(name: String, value: String?) {
        readyPrefs().edit().apply {
            if (value.isNullOrEmpty()) remove(name) else putString(name, value)
        }.apply()
    }

    /**
     * The channel the listener chose to act as, if they chose one.
     *
     * Stored beside the cookie rather than in the plain settings because it is
     * only meaningful with that cookie and must not outlive it: a `dataSyncId`
     * from one login, replayed under another, is answered with 401 on every
     * request. [signOut] and [onNewSession] both clear it for that reason.
     *
     * A null [channelPageId] with a [channelDataSyncId] set is a real state,
     * not an absent one — it is the account's own channel, deliberately chosen
     * over a brand channel the web player would otherwise default to.
     */
    val channelPageId: String? get() = readyPrefs().getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = readyPrefs().getString(KEY_CHANNEL_DATASYNC_ID, null)

    /** The chosen channel's display name, for the settings row. */
    val channelName: String? get() = readyPrefs().getString(KEY_CHANNEL_NAME, null)

    /** Which Google account in the jar it belongs to; null means "as the shell says". */
    val channelAuthUser: String? get() = readyPrefs().getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) = readyPrefs().edit()
        .putString(KEY_CHANNEL_PAGE_ID, pageId)
        .putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
        .putString(KEY_CHANNEL_NAME, name)
        .putString(KEY_CHANNEL_AUTH_USER, authUser)
        .apply()

    /**
     * The chosen channel's name, once something knows it.
     *
     * Separate from [selectChannel] because the two are learned at different
     * times: a channel picked in the in-app browser is identified by ids the
     * moment it is picked, and named only after the next account fetch comes
     * back to say what it is called.
     */
    fun setChannelName(name: String?) =
        readyPrefs().edit().putString(KEY_CHANNEL_NAME, name).apply()

    /** Back to whichever channel YouTube Music serves by default. */
    fun clearChannel() = readyPrefs().edit()
        .remove(KEY_CHANNEL_PAGE_ID)
        .remove(KEY_CHANNEL_DATASYNC_ID)
        .remove(KEY_CHANNEL_NAME)
        .remove(KEY_CHANNEL_AUTH_USER)
        .apply()

    /**
     * A fresh login lands here. The cookie is new, so any channel chosen under
     * the old one names an identity this session cannot act as.
     */
    fun onNewSession(cookie: String) {
        this.cookie = cookie
        clearChannel()
    }

    /**
     * Signs out of YouTube Music only — the Discord login is a separate account.
     */
    fun signOut() {
        readyPrefs().edit().remove(KEY_COOKIE).remove(KEY_SESSIONS)
            .remove(KEY_ACTIVE_ACCOUNT).remove(KEY_ACTIVE_PROFILE).apply()
        clearChannel()
        // The in-app browser keeps its own copy of the Google login, and a
        // sign-out that leaves it in place is not one: the next sign-in is
        // waved straight through as the account just signed out of, with no
        // opportunity to choose another. See [BrowserSession].
        BrowserSession.clearGoogleCookies()
    }

    companion object {
        /**
         * Whether a cookie header carries a secret Innertube requests can be
         * signed with.
         *
         * Matched on the cookie *name*, which reads as pedantry and is not. The
         * test used to be `cookie.contains("SAPISID")`, and `__Secure-3PAPISID`
         * contains "SAPISID" — so a jar holding only the `__Secure-` forms, which
         * is what a partitioned-cookie login produces, passed a check for a
         * cookie it did not have. The app then declared itself signed in and made
         * every request unsigned, which Google answers as a stranger. Library
         * reads degraded quietly and history was never written at all.
         */
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_COOKIE = "cookie"
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_SMB_PASSWORD = "smb_password"
        private const val KEY_PROXY_PASSWORD = "proxy_password"
    }
}
