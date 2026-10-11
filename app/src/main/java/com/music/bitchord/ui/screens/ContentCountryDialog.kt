package com.music.bitchord.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.ui.components.ACTION_HEIGHT
import com.music.bitchord.ui.components.PAGE_GUTTER
import java.text.Collator
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

/** "전 세계" for the charts' global choice, the country's name otherwise. */
@Composable
internal fun chartCountryName(code: String): String =
    if (code.equals("ZZ", ignoreCase = true)) stringResource(R.string.charts_global) else countryDisplayName(code)

/**
 * The charts page's country picker, YouTube-Music style: a single pill showing
 * the current country, which opens a bottom sheet listing every country the
 * page offers. A long horizontal chip rail was hard to scan and to find a
 * country in; this shows one line and puts the full list one tap away.
 * Nothing when the page offered no choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartCountryChips(
    selected: String?,
    countries: List<String>,
    onSelect: (String) -> Unit,
) {
    if (countries.isEmpty()) return
    var sheetOpen by remember { mutableStateOf(false) }

    // The current pick and the global/home choices stay pinned to the top so
    // they're always one glance away; the rest of the list is sorted by the
    // country's name in the app's language so it reads like a phone book.
    val (pinned, rest) = remember(countries, selected) {
        val collator = Collator.getInstance(
            AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
        )
        val pins = (listOfNotNull(selected?.uppercase()) + listOf("KR", "ZZ"))
            .filter { it in countries }
            .distinct()
        val others = (countries - pins.toSet())
            .sortedWith(compareBy(collator) { chartCountryDisplayName(it) })
        pins to others
    }

    Surface(
        onClick = { sheetOpen = true },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(start = PAGE_GUTTER, bottom = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        ) {
            Text(
                text = selected?.let { chartCountryName(it) } ?: stringResource(R.string.select_country),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp).size(22.dp),
            )
        }
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.select_country),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { sheetOpen = false }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(max = 480.dp),
            ) {
                items(pinned, key = { "pin:$it" }) { code ->
                    ChartCountryRow(code, code.equals(selected, ignoreCase = true)) {
                        if (!code.equals(selected, ignoreCase = true)) onSelect(code)
                        sheetOpen = false
                    }
                }
                if (pinned.isNotEmpty() && rest.isNotEmpty()) {
                    item("divider") {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                items(rest, key = { it }) { code ->
                    ChartCountryRow(code, code.equals(selected, ignoreCase = true)) {
                        onSelect(code)
                        sheetOpen = false
                    }
                }
            }
        }
    }
}

/** [code]'s name as shown in the picker: "전 세계" for global, the country name otherwise. Non-composable, for sorting. */
private fun chartCountryDisplayName(code: String): String =
    if (code.equals("ZZ", ignoreCase = true)) "" else countryDisplayName(code)

@Composable
private fun ChartCountryRow(code: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(indication = null, interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = chartCountryName(code),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}
