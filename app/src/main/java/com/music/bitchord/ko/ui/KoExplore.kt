package com.music.bitchord.ko.ui

import androidx.lifecycle.viewModelScope
import com.music.bitchord.R
import com.music.bitchord.data.ExploreCategoryFilter
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * Applies the hidden-category filter to the last answer and appends the
 * charts card. Kept separate from the fetch so a change in Settings takes
 * effect at once without another network request.
 */
internal fun MainViewModel.koPublishExplore() {
    val visible = ExploreCategoryFilter.visible(
        rawExploreSections,
        AppSettings.hiddenExploreCategories.value,
    )
    // The Music channel's own card closes the grid, after the curated
    // moods and genres, and stays even when everything above it is hidden.
    val charts = MoodGenre(
        title = text(R.string.charts),
        browseId = Innertube.CHARTS_BROWSE_ID,
        params = null,
        stripeColor = 0xFFE8304CL,
    )
    _explore.value = UiState.Success(
        visible + MoodGenreSection(text(R.string.charts), listOf(charts)),
    )
}

/**
 * Resolves the cover of one tile the grid has scrolled into view. A tile
 * already on screen keeps its one request, and the repository caps the
 * three-at-a-time budget; a settled cover is persisted for a day.
 */
internal fun MainViewModel.koLoadMoodGenreArtwork(item: MoodGenre) {
    if (item.thumbnailUrl != null) return
    val key = "${item.browseId}:${item.params.orEmpty()}"
    if (moodGenreArtworkJobs[key]?.isCompleted == false) return
    val job = viewModelScope.launch {
        val artwork = YtMusicRepository.moodGenreArtwork(item.browseId, item.params).getOrNull()
            ?: return@launch
        if (rawExploreSections.isEmpty()) return@launch
        rawExploreSections = rawExploreSections.map { section ->
            section.copy(items = section.items.map { currentItem ->
                if (currentItem.browseId == item.browseId && currentItem.params == item.params) {
                    currentItem.copy(thumbnailUrl = artwork)
                } else {
                    currentItem
                }
            })
        }
        koPublishExplore()
    }
    moodGenreArtworkJobs[key] = job
    job.invokeOnCompletion { moodGenreArtworkJobs.remove(key, job) }
}
