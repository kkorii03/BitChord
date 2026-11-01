package com.music.bitchord.ko

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * [KO] offline-policy
 *
 * Offline is decided from the active network's capabilities without polling:
 * a captive portal and "no active network" both mean offline.
 */
object KoNetworkPolicy {
    fun isOffline(context: Context?): Boolean {
        val context = context ?: return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }
            .getOrNull()
        // A captive portal advertises INTERNET but only serves its sign-in
        // page. Treating that as online sent every lookup into a timeout; the
        // portal's own capability is the event-free way to tell them apart.
        // (A proxy-only network can be unvalidated but is not a portal, so
        // VALIDATED is deliberately not required here.)
        //
        // No capabilities means there is no active network — airplane mode, or
        // a moment after Wi-Fi and cellular have both dropped. Reporting that
        // as online skipped the cached copy and sent every lookup into a
        // timeout instead.
        return offlineFromCapabilities(
            hasCapabilities = capabilities != null,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
    }
}

/**
 * Offline decision from the capabilities of the active network.
 *
 * [hasCapabilities] is false when the platform has no active network at all.
 * That is offline, not unknown: reporting it as online skipped cached playback
 * and sent lookups into timeouts.
 */
internal fun offlineFromCapabilities(
    hasCapabilities: Boolean,
    hasInternet: Boolean,
    captivePortal: Boolean,
): Boolean = !hasCapabilities || !hasInternet || captivePortal
