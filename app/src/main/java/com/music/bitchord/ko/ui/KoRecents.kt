package com.music.bitchord.ko.ui

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.MainViewModel
import java.util.Locale

/** The app's own Recents key, as the feed and the shelf-title localiser use it. */
private const val RECENTS_TITLE = "Recents"

private fun isRecentsTitle(title: String): Boolean =
    title.trim().equals(RECENTS_TITLE, ignoreCase = true)

/** The Recents trail, tagged so one exported log reads as a single sequence. */
internal fun MainViewModel.koRecentsLog(message: String) {
    DebugLog.i("BitChord", message)
}

/** Logs the Recents shelf a load, refresh, or the feed itself just published. */
internal fun MainViewModel.koLogRecentsPublished(source: String, shelves: List<HomeShelf>) {
    val shelf = shelves.firstOrNull { isRecentsTitle(it.title) } ?: return
    koRecentsLog("recents: published by $source top=${shelf.items.firstOrNull()?.title.orEmpty()}")
}

/** A counted local play: repaint Recents without a request or a timer. */
internal suspend fun MainViewModel.koOnLocalPlayChanged() {
    koRecentsLog("recents: event -> refresh")
    koRefreshRecentsFromLocal()
}

/** A feed answered: log its Recents shelf, then repaint from the local record. */
internal suspend fun MainViewModel.koAfterHomeFeed(shelves: List<HomeShelf>) {
    koLogRecentsPublished("feed", shelves)
    koRefreshRecentsFromLocal()
}

/**
 * Repaints Recents from the device's own record after a counted play.
 *
 * The shelf is rebuilt from local plays only, with whatever account rows
 * are already on screen kept after them: no feed request, no cover lookup,
 * no polling. A page that has not loaded yet is left for its own load.
 */
internal suspend fun MainViewModel.koRefreshRecentsFromLocal() {
    val state = _home.value
    if (state !is UiState.Success) {
        koRecentsLog("recents: refresh skipped, home not Success")
        return
    }
    val shown = state.data
    val index = shown.indexOfFirst { isRecentsTitle(it.title) }
    val previous = shown.getOrNull(index)?.items.orEmpty()
    val shelf = YtMusicRepository.localRecentShelf(previous)?.takeIf { it.items.isNotEmpty() }
    if (shelf == null) {
        koRecentsLog("recents: refresh skipped, empty local shelf")
        return
    }
    // Other shelves keep arriving while the page loads, so the page is
    // re-read rather than required to be unchanged: the local shelf does
    // not depend on them. Only a page that stopped being a page (sign-out,
    // a reload in progress) is left alone.
    val currentState = _home.value
    if (currentState !is UiState.Success) {
        koRecentsLog("recents: refresh skipped, page replaced")
        return
    }
    val current = currentState.data
    val top = shelf.items.firstOrNull()?.title.orEmpty()
    val currentIndex = current.indexOfFirst { isRecentsTitle(it.title) }
    if (currentIndex < 0) {
        homeSeenTitles.add(RECENTS_TITLE.lowercase(Locale.ROOT))
        _home.value = UiState.Success(listOf(shelf) + current)
        koRecentsLog("recents: shelf repainted top=$top index=0")
    } else {
        _home.value = UiState.Success(
            current.toMutableList().apply { set(currentIndex, shelf) },
        )
        koRecentsLog("recents: shelf repainted top=$top index=$currentIndex")
    }
}
