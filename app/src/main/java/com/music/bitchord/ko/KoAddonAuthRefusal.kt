package com.music.bitchord.ko

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.TrackLog

/**
 * [KO] auth-refusal
 *
 * The addon verification service refuses this fork outright: "This app is not
 * signed with the official BitChord key". That answer is about the APK's
 * signature, so it cannot change until a different build is installed. Upstream
 * remembers any refusal for 15 minutes in memory only, so the listener's phone
 * asked again 14 times between 11-07 22:17 and 11-08 08:39 (a key generation in
 * the secure hardware plus two round trips each), and every resolve that ran
 * while it was not remembered counted Lossless Bridge as a failed source, which
 * left the upgrade look inconclusive and searched the song again a minute later.
 *
 * This keeps that one refusal for the installed build. The signature check is
 * not touched or worked around: the addons that need verification simply stay
 * out of the walks (see [com.music.bitchord.data.sources.SourceRegistry.active])
 * until the app's version or the verification service changes, or a tester code
 * is saved. Every other refusal keeps upstream's 15-minute backoff.
 */
internal object KoAddonAuthRefusal {

    private const val PREFS = "bitchord_addon_auth_refusal"
    private const val KEY_BUILD = "build"
    private const val KEY_MESSAGE = "message"

    /** The service's own words for a signature it will never accept. */
    private const val SIGNATURE_REFUSAL = "not signed with the official BitChord key"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Whether [message] is the refusal no retry on this build can change. */
    fun isPermanent(message: String?): Boolean =
        message?.contains(SIGNATURE_REFUSAL, ignoreCase = true) == true

    /** What a stored refusal is valid for: this build talking to this service. */
    fun buildKey(versionCode: Int, authUrl: String): String = "$versionCode|$authUrl"

    /** The stored refusal [message] when it was recorded for [currentBuild], else null. */
    fun standing(storedBuild: String?, message: String?, currentBuild: String): String? =
        message?.takeIf { storedBuild == currentBuild && isPermanent(it) }

    private fun currentBuild(): String = buildKey(BuildConfig.VERSION_CODE, BuildConfig.ADDON_AUTH_URL)

    /** At startup: the refusal this build already got, so the first walk does not ask again. */
    fun restore(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val message = standing(p.getString(KEY_BUILD, null), p.getString(KEY_MESSAGE, null), currentBuild())
        if (message == null) {
            // Another build, or nothing stored: that build gets its own one try.
            if (p.contains(KEY_BUILD)) p.edit().clear().apply()
            return null
        }
        TrackLog.i("BitChord", "addon auth: kept from this build's last try: $message; verified addons stay out until the app updates")
        return message
    }

    /** Records [message] when it is the permanent kind. True when it was. */
    fun note(message: String?): Boolean {
        if (!isPermanent(message)) return false
        prefs?.edit()?.putString(KEY_BUILD, currentBuild())?.putString(KEY_MESSAGE, message)?.apply()
        TrackLog.i("BitChord", "addon auth: this build is refused for good ($message); not asking again until the app updates")
        return true
    }

    /** A tester code changes what the service may answer, so the stored refusal goes. */
    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
