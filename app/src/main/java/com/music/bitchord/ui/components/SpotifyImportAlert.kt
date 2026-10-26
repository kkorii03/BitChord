package com.music.bitchord.ui.components

import com.music.bitchord.ui.theme.AppType

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImportTrack
import com.music.bitchord.data.spotify.SpotifyImporter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MANUAL_LANGUAGE = "Manual / Other"

private const val NO_PREFERENCE_LANGUAGE = "No Preference"

/**
 * A language chip: what it reads as, and the value the matcher is asked for.
 *
 * The label is translated, the value is not: it is a search hint, and Spotify
 * only recognises its own English names.
 */
private data class ImportLanguage(val labelRes: Int, val value: String)

private val IMPORT_LANGUAGES = listOf(
    ImportLanguage(R.string.spotify_language_tamil, "Tamil"),
    ImportLanguage(R.string.spotify_language_telugu, "Telugu"),
    ImportLanguage(R.string.spotify_language_kannada, "Kannada"),
    ImportLanguage(R.string.spotify_language_malayalam, "Malayalam"),
    ImportLanguage(R.string.spotify_language_hindi, "Hindi"),
    ImportLanguage(R.string.spotify_language_english, "English"),
    ImportLanguage(R.string.spotify_language_multilingual, "Multilingual"),
    ImportLanguage(R.string.spotify_language_no_preference, NO_PREFERENCE_LANGUAGE),
    ImportLanguage(R.string.spotify_language_manual, MANUAL_LANGUAGE),
)

/**
 * Paste a Spotify playlist link, match its songs on YouTube Music, keep the result.
 *
 * The same frosted card as the addon editor, rather than a Material dialog.
 * The "who can see it" row exists only for [signedIn]: it is a YouTube Music
 * setting, and a playlist kept on this device has no audience to choose.
 * Songs that matched nothing are listed once the playlist is made, instead of
 * being dropped without a word.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    /** Called once the songs are matched; [privacy] is meaningful only when signed in. */
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var selectedLanguage by remember { mutableStateOf(NO_PREFERENCE_LANGUAGE) }
    var customLanguageInput by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>?>(null) }

    fun start() {
        val id = SpotifyImporter.extractPlaylistId(link)
        if (id == null) {
            status = context.getString(R.string.spotify_import_invalid)
            failed = true
            return
        }
        val effectiveLanguage = if (selectedLanguage == MANUAL_LANGUAGE) {
            customLanguageInput.trim().ifBlank { null }
        } else {
            selectedLanguage
        }
        working = true
        failed = false
        status = context.getString(R.string.spotify_import_fetching)
        scope.launch {
            try {
                val (name, tracks) = SpotifyImporter.fetchPlaylistTracks(id)
                val matched = SpotifyImporter.resolveToSongs(tracks, effectiveLanguage) { done, total ->
                    status = context.getString(R.string.spotify_import_matching, done, total)
                }
                val songs = matched.songs
                val missed = matched.unmatched
                if (songs.isEmpty()) error(context.getString(R.string.spotify_import_none))
                if (matched.looselyMatched > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.spotify_import_loose, matched.looselyMatched),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                onImported(name, privacy, songs)
                if (missed.isEmpty()) onDismiss() else unmatched = missed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = context.getString(R.string.spotify_import_failed, e.message.orEmpty())
                failed = true
            }
            working = false
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.spotify_import_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = AppType.Body, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            val missed = unmatched
            if (missed != null) {
                Text(
                    text = stringResource(R.string.spotify_import_unmatched, missed.size),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = AppType.Caption, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    missed.forEach { track ->
                        Text(
                            text = if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = AppType.Caption),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    text = status ?: stringResource(
                        if (signedIn) R.string.spotify_import_description
                        else R.string.spotify_import_description_local,
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = AppType.Caption, lineHeight = 17.sp),
                    color = when {
                        status == null -> MaterialTheme.colorScheme.onSurface
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
                )
                PillTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        if (failed) {
                            status = null
                            failed = false
                        }
                    },
                    placeholder = stringResource(R.string.spotify_import_hint),
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank() && !working) start() }),
                )
                Text(
                    text = stringResource(R.string.spotify_import_language_preference),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IMPORT_LANGUAGES.forEach { lang ->
                        val selected = lang.value == selectedLanguage
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable(enabled = !working) {
                                    selectedLanguage = lang.value
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(lang.labelRes),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = AppType.Micro),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (selectedLanguage == MANUAL_LANGUAGE) {
                    Spacer(Modifier.height(8.dp))
                    PillTextField(
                        value = customLanguageInput,
                        onValueChange = { customLanguageInput = it },
                        placeholder = stringResource(R.string.spotify_import_language_hint),
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    )
                }
                if (signedIn) {
                    Text(
                        text = stringResource(R.string.who_can_see_it),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PlaylistPrivacy.entries.forEach { option ->
                            val selected = option == privacy
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .clickable(enabled = !working) { privacy = option }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = AppType.Caption),
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        AlertRule()
        if (unmatched != null) {
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        } else {
            AlertAction(
                label = stringResource(R.string.spotify_import_button),
                emphasised = true,
                onClick = ::start,
                enabled = link.isNotBlank() && !working,
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.cancel),
                emphasised = false,
                onClick = onDismiss,
                enabled = !working,
            )
        }
    }
}
