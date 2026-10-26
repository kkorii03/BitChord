package com.music.bitchord.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.ui.components.*

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onWearOs: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    /** Opens the content-country picker, hosted by the activity above the tab bar. */
    onContentCountry: () -> Unit,
    /** Opens the Gemini API key alert, hosted by the activity above the tab bar. */
    onGeminiKey: () -> Unit,
    /** Opens the Deepgram API key alert (AI lyric timing), hosted the same way. */
    onDeepgramKey: () -> Unit = {},
    /** Forgets downloaded songs' sync-check versions; each is checked again on its next play. */
    onResetDownloadSync: () -> Unit = {},
    onEditSignatureApi: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Filters the rows below — see [SettingsSearch]. Blank shows everything,
    // exactly as if the field weren't there.
    // Saveable so a search survives a trip into one of the pages it found.
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // Made fresh on every pass rather than remembered: it tallies the rows
    // that answered to the query, and that tally has to start from zero each
    // time the query changes.
    val search = SettingsSearch(searchQuery)

    // What is left after a keystroke is a different list, and the offset the
    // last one was scrolled to means nothing in it.
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var isFirstSearchComposition by remember { mutableStateOf(true) }
    LaunchedEffect(searchQuery) {
        if (isFirstSearchComposition) {
            // Skip the initial composition — on Activity recreation, and on
            // coming back from a page opened here: rememberSaveable already
            // restored the scroll position and the query it belongs to.
            isFirstSearchComposition = false
        } else {
            isFirstSearchComposition = false
            scrollState.scrollTo(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )
        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSubmit = {},
            placeholder = stringResource(R.string.settings_search_hint),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )

        SettingsAccountSection(
            search = search,
            signedIn = signedIn,
            account = account,
            onAccountScrobbling = onAccountScrobbling,
            onListenTogether = onListenTogether,
        )
        SettingsAudioSection(
            search = search,
            onSources = onSources,
            onEqualizer = onEqualizer,
        )
        SettingsAppearanceSection(
            search = search,
            windowWidth = windowWidth,
            onSpotifyCanvasAuth = onSpotifyCanvasAuth,
            onEditSignatureApi = onEditSignatureApi,
            onLyricsSources = onLyricsSources,
            onTranslationLanguage = onTranslationLanguage,
            onGeminiKey = onGeminiKey,
            onDeepgramKey = onDeepgramKey,
            onResetDownloadSync = onResetDownloadSync,
        )

        SettingsPerformanceSection(search = search)
        SettingsLibrarySection(
            search = search,
            onWearOs = onWearOs,
        )
        SettingsDataSection(
            search = search,
            onOpenReplay = onOpenReplay,
        )

        SettingsMiscSection(search = search)

        SettingsLanguageSection(
            search = search,
            onAppLanguage = onAppLanguage,
            onContentCountry = onContentCountry,
        )

        SettingsAdvancedSection(search = search)

        // Read after every group above has had its turn at the query, which is
        // what makes this an accurate "nothing here" rather than a guess.
        if (!search.anyMatch) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }

        // The version line is the page's colophon, not a setting: it belongs to
        // the whole list, so it goes when the list is narrowed to a few rows.
        if (searchQuery.isBlank()) {
        Text(
            text = buildAnnotatedString {
                append("bitchord $version  ")
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/BitChord", linkStyles)) {
                    append("GitHub")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                    append("개발자")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://discord.gg/pDdKfrdHY6", linkStyles)) {
                    append("Discord")
                }
                append("  ")
                withLink(LinkAnnotation.Url("https://bitchord.kushagrasingh.in/", linkStyles)) {
                    append("웹사이트")
                }
                append("\n~YouTube Music & Listen Together Backend")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        }
    }
}
