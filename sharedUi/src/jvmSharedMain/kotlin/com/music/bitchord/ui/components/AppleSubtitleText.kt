package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.music.bitchord.data.model.AppleCardKind
import com.music.bitchord.data.model.appleSubtitleCredit
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.album
import com.music.bitchord.sharedui.resources.artist
import com.music.bitchord.sharedui.resources.playlist
import com.music.bitchord.sharedui.resources.song
import org.jetbrains.compose.resources.stringResource

/**
 * The second line of a card, in Apple Music's shape: the kind, then who made
 * it — never YouTube's play, view or subscriber tally.
 *
 * A song is "Song · Artist", an album "Album · Artist", a playlist
 * "Playlist · Maker"; an artist card names the kind alone, because its title
 * is already the name. A card the ids cannot classify keeps whatever subtitle
 * the server sent.
 */
@Composable
fun appleSubtitleText(subtitle: String, kind: AppleCardKind?): String {
    kind ?: return subtitle
    val credit = remember(subtitle) { appleSubtitleCredit(subtitle) }
    val label = when (kind) {
        AppleCardKind.SONG -> stringResource(Res.string.song)
        AppleCardKind.ALBUM -> stringResource(Res.string.album)
        AppleCardKind.PLAYLIST -> stringResource(Res.string.playlist)
            .replaceFirstChar { it.uppercaseChar() }
        AppleCardKind.ARTIST -> stringResource(Res.string.artist)
    }
    if (kind == AppleCardKind.ARTIST) return label
    return if (credit.isNullOrBlank()) label else "$label · $credit"
}
