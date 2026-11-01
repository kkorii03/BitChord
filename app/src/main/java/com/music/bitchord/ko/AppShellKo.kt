package com.music.bitchord.ko

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.net.ProxyHealth
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.navigation.AppRoute

/** The Home notice for a proxy that stopped answering — at most once per outage. */
@Composable
internal fun KoProxyHealthNotice(context: Context) {
    val proxyHealth by ProxyHealthMonitor.state.collectAsStateWithLifecycle()
    var proxyHealthNotified by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(proxyHealth) {
        if (proxyHealth == ProxyHealth.UNREACHABLE) {
            if (!proxyHealthNotified) {
                proxyHealthNotified = true
                Toast.makeText(context, R.string.proxy_health_home_notice, Toast.LENGTH_LONG).show()
            }
        } else {
            // Recovered (or never known): a later outage is news again. Still
            // at most one notice per outage.
            proxyHealthNotified = false
        }
    }
}

/** Asks for the playback notification permission on the first play, once per install. */
@Composable
internal fun KoFirstPlayNotificationPermission(
    context: Context,
    isPlaying: Boolean,
    requestPermission: () -> Unit,
) {
    LaunchedEffect(isPlaying) {
        if (!isPlaying || AppSettings.notificationPermissionAsked.value) {
            return@LaunchedEffect
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return@LaunchedEffect
        }
        AppSettings.markNotificationPermissionAsked()
        requestPermission()
    }
}
/** The label the export header reports for the screen currently on top. */
internal fun koScreenLabel(
    showNowPlaying: Boolean,
    route: AppRoute?,
    detail: DetailPage?,
    selectedMoodGenreTitle: String?,
    selectedTab: Int,
): String = when {
    showNowPlaying -> "Player"
    route == AppRoute.Replay -> "Replay"
    route == AppRoute.Settings -> "Settings"
    route == AppRoute.Lyrics -> "Settings/lyrics"
    route == AppRoute.AccountScrobbling -> "Settings/account_scrobbling"
    route == AppRoute.Telegram -> "Settings/telegram"
    route == AppRoute.Discord -> "Settings/discord"
    route == AppRoute.Spotify -> "Settings/spotify"
    route == AppRoute.Sources -> "Settings/sources"
    route == AppRoute.ListenTogether -> "Settings/listen_together"
    route == AppRoute.WearOs -> "Settings/wear_os"
    route == AppRoute.Equalizer -> "Settings/equalizer"
    route == AppRoute.History -> "History"
    route is AppRoute.LibraryShowAll -> "Library/show_all"
    detail != null -> "Detail ${detail.type.name.lowercase()}"
    selectedMoodGenreTitle != null -> "Explore/$selectedMoodGenreTitle"
    selectedTab == 0 -> "Home"
    selectedTab == 1 -> "Explore"
    selectedTab == 2 -> "Library"
    selectedTab == 3 -> "Search"
    else -> "tab$selectedTab"
}
