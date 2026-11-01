package com.music.bitchord.ko

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.YouTubeFallback
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.ChartsPage

/** The content-country charts page kept out of the upstream file. */
internal object KoCharts {

    /** The charts page for [country] (a code the page offered), or the content country's. */
    suspend fun charts(country: String?): Result<ChartsPage> =
        YtMusicRepository.call("charts:${country.orEmpty()}") {
            if (KoRepoGate.ensureGateKnown()) return@call YouTubeFallback.charts(country)
            val response = Innertube.charts(country)
            val (shown, countries) = InnertubeParser.parseChartCountries(response)
            val shelves = InnertubeParser.parseHome(response)
            // A gate can begin between the verdict check and this call; an empty
            // Music chart is the same symptom the fallback exists to answer.
            if (shelves.isEmpty()) {
                val fallback = YouTubeFallback.charts(country)
                if (fallback.shelves.isNotEmpty()) return@call fallback
            }
            ChartsPage(
                country = shown ?: country,
                countries = countries,
                shelves = shelves,
            )
        }
}
