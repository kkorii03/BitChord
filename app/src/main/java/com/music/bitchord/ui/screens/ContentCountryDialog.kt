package com.music.bitchord.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import java.util.Locale

/** Countries offered for the content country, most likely first. */
internal val CONTENT_COUNTRIES = listOf(
    "KR", "US", "JP", "GB", "CA", "AU", "DE", "FR", "TW", "HK", "SG", "PH",
    "ID", "TH", "VN", "MY", "IN", "BR", "MX", "ES", "IT", "NL", "SE",
)

/** [code]'s name in the app's language: "대한민국" for KR in Korean. */
internal fun countryDisplayName(code: String): String {
    val locale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
    return Locale("", code).getDisplayCountry(locale).ifBlank { code }
}

@Composable
internal fun ContentCountryDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.content_country)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        text = stringResource(R.string.content_country_description),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(CONTENT_COUNTRIES) { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = code == selected, onClick = { onSelect(code) })
                        Text(countryDisplayName(code), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** "전 세계" for the charts' global choice, the country's name otherwise. */
@Composable
internal fun chartCountryName(code: String): String =
    if (code.equals("ZZ", ignoreCase = true)) stringResource(R.string.charts_global) else countryDisplayName(code)

/**
 * The charts page's country picker: one chip per country the page offers,
 * the shown one selected. Nothing when the page offered no choice.
 */
@Composable
internal fun ChartCountryChips(
    selected: String?,
    countries: List<String>,
    onSelect: (String) -> Unit,
) {
    if (countries.isEmpty()) return
    // The selected chip first is easier to find than scrolling for it; the
    // rest keep the page's order, with the likely picks pulled forward.
    val ordered = androidx.compose.runtime.remember(countries, selected) {
        val preferred = listOfNotNull(selected) + listOf("KR", "ZZ", "US", "JP", "GB")
        (preferred.filter { it in countries } + countries).distinct()
    }
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = com.music.bitchord.ui.components.PAGE_GUTTER),
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        items(ordered, key = { it }) { code ->
            androidx.compose.material3.FilterChip(
                selected = code.equals(selected, ignoreCase = true),
                onClick = { if (!code.equals(selected, ignoreCase = true)) onSelect(code) },
                label = { Text(chartCountryName(code)) },
            )
        }
    }
}
