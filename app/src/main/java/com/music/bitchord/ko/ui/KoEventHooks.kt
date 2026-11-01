package com.music.bitchord.ko.ui

import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.LocalPlayHistory
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.MainViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Event-driven fork hooks that must run for the lifetime of the view model. */
internal fun MainViewModel.koInstallEventHooks() {
    viewModelScope.launch {
        // Recents is the device's own record first, so a counted play
        // repaints the shelf from that record — no request and no polling.
        LocalPlayHistory.changes().collect {
            koOnLocalPlayChanged()
        }
    }
    viewModelScope.launch {
        // AppSettings has already reset the gate when the route changed;
        // ask once more through the new route and repaint both feeds
        // rather than leaving yesterday's GATED answer on screen.
        AppSettings.proxyRevision.drop(1).collect {
            // A failing re-check (proxy unreachable, wrong password) must
            // show as an error on Home, never take the app down.
            try {
                YtMusicRepository.recheckGateNow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                DebugLog.w("BitChord", "proxy re-check failed: ${failure.message}")
            }
            // The answers this route gave before may be the ones it gated.
            invalidateSearchCaches()
            refresh(MainViewModel.Feed.HOME, force = true, replaceShelves = true, reason = "proxy")
            refresh(MainViewModel.Feed.EXPLORE)
        }
    }
    viewModelScope.launch {
        // Moods and charts are the country's; a new one means a new page.
        AppSettings.contentCountry.drop(1).collect {
            if (_explore.value !is UiState.Loading) loadExplore()
        }
    }
    viewModelScope.launch {
        // Hiding or restoring a category repaints the grid from the last
        // answer rather than spending another network request.
        AppSettings.hiddenExploreCategories.drop(1).collect {
            if (rawExploreSections.isNotEmpty()) koPublishExplore()
        }
    }
}
