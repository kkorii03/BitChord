package com.music.bitchord.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.languageDisplayNameRes
import java.util.Locale

@Composable
internal fun SettingsLanguageSection(
    search: SettingsSearch,
    onAppLanguage: () -> Unit,
    onContentCountry: () -> Unit,
) {
    val contentCountry by AppSettings.contentCountry.collectAsStateWithLifecycle()

        SearchableSettingsGroup(search, header = stringResource(R.string.language)) {
            val appLanguageTitle = stringResource(R.string.app_language)
            row(appLanguageTitle, "locale", "translate") {
                val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
                    ?: Locale.getDefault().toLanguageTag()
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = appLanguageTitle,
                    subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                    onClick = onAppLanguage,
                )
            }
            val contentCountryTitle = stringResource(R.string.content_country)
            row(contentCountryTitle, "country", "region", "chart", "gl", "국가", "지역") {
                SettingsRow(
                    icon = Icons.Rounded.Public,
                    title = contentCountryTitle,
                    subtitle = countryDisplayName(contentCountry),
                    trailing = { Chevron() },
                    onClick = onContentCountry,
                )
            }
        }
}
