package com.music.bitchord.ko

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.screens.Chevron
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow

@Stable
internal class KoHiddenExploreState {
    var show by mutableStateOf(false)
}

@Composable
internal fun rememberKoHiddenExploreState(): KoHiddenExploreState =
    remember { KoHiddenExploreState() }

@Composable
internal fun SettingsGroupScope.KoHiddenExploreRow(state: KoHiddenExploreState) {
    val hiddenExploreCategories by AppSettings.hiddenExploreCategories.collectAsStateWithLifecycle()
    val hiddenCategoriesTitle = stringResource(R.string.hidden_explore_categories)
    row(hiddenCategoriesTitle, "explore", "category", "filter", "hide") {
        SettingsRow(
            icon = Icons.Rounded.VisibilityOff,
            title = hiddenCategoriesTitle,
            subtitle = if (hiddenExploreCategories.isEmpty()) {
                stringResource(R.string.hidden_explore_categories_none)
            } else {
                stringResource(
                    R.string.hidden_explore_categories_count,
                    hiddenExploreCategories.size,
                )
            },
            trailing = { Chevron() },
            onClick = { state.show = true },
        )
    }
}

@Composable
internal fun KoHiddenExploreDialog(state: KoHiddenExploreState) {
    if (!state.show) return
    val hiddenExploreCategories by AppSettings.hiddenExploreCategories.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { state.show = false },
        title = { Text(stringResource(R.string.hidden_explore_categories)) },
        text = {
            if (hiddenExploreCategories.isEmpty()) {
                Text(stringResource(R.string.hidden_explore_categories_none))
            } else {
                Column {
                    hiddenExploreCategories.sorted().forEach { title ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { AppSettings.showExploreCategory(title) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.show_again),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { AppSettings.clearHiddenExploreCategories() },
                enabled = hiddenExploreCategories.isNotEmpty(),
            ) {
                Text(stringResource(R.string.show_all_again))
            }
        },
        dismissButton = {
            TextButton(onClick = { state.show = false }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
