package com.music.bitchord.ko.ui

import androidx.lifecycle.viewModelScope
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.MainViewModel
import kotlinx.coroutines.launch

/** Shows the charts for [country] (a code the page offered) and remembers the pick. */
internal fun MainViewModel.koSelectChartsCountry(country: String) {
    if (_selectedMoodGenre.value?.browseId != Innertube.CHARTS_BROWSE_ID) return
    AppSettings.setChartsCountry(country)
    _moodGenreShelves.value = UiState.Loading
    koLoadCharts(country)
}

internal fun MainViewModel.koLoadCharts(country: String?) {
    chartsJob?.cancel()
    chartsJob = viewModelScope.launch {
        val result = YtMusicRepository.charts(country)
        result.getOrNull()?.let { page ->
            // Keep a picked country selected while the page leaves it unsaid.
            _chartCountries.value = (page.country ?: country) to page.countries
        }
        _moodGenreShelves.value = result.fold(
            onSuccess = { page ->
                if (page.shelves.isEmpty()) UiState.Error(text(R.string.nothing_to_explore))
                else UiState.Success(page.shelves)
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }
}
