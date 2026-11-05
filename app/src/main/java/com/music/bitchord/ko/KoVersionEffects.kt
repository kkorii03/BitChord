package com.music.bitchord.ko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.session.MediaController
import com.music.bitchord.autoMusicOnlyEffectKey
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.videoRadioEnabledFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The before-audio music-only fallback for a video row that reached the player
 * unconverted. Moved out of MainActivity so the upstream player code stays
 * untouched — see docs/ko-fork-layout.md.
 */
@Composable
internal fun KoAutoMusicOnlyEffect(
    player: PlayerState,
    controller: MediaController?,
    preferMusicOnly: Boolean,
    videoRadio: Boolean,
    partyInParty: Boolean,
    keepVideoId: String?,
    switchToMusicOnly: suspend (Song, Boolean, Boolean, Boolean) -> Boolean,
) {
    LaunchedEffect(autoMusicOnlyEffectKey(player.song?.videoId, preferMusicOnly, videoRadio)) {
        val song = player.song ?: return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        if (preferMusicOnly && !partyInParty && !videoRadioEnabledFor(song) &&
            SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown) &&
            (song.localUri == null || !AudioCache.isOffline()) &&
            song.videoId != keepVideoId && song.videoId !in musicOnlyTried &&
            SongVersionPolicy.mayAutoSwapBeforeAudio(
                positionMs = c.currentPosition,
                isPlaying = c.isPlaying,
                alreadyAudible = false,
            )
        ) {
            val settled = switchToMusicOnly(
                song,
                false,
                false,
                true,
            )
            if (settled) musicOnlyTried.add(song.videoId)
        }
    }
}

/**
 * Whether an alternate video/audio version exists, looked up only while the
 * player's track menu is open on this track. Moved out of MainActivity so the
 * upstream player code stays untouched — see docs/ko-fork-layout.md.
 */
@Composable
internal fun KoAlternateVersionEffect(
    player: PlayerState,
    songActions: Song?,
    menuFromPlayer: Boolean,
    showNowPlaying: Boolean,
    convertedAudioId: String?,
    convertedVideoId: String?,
    convertedFromVideo: Song?,
    convertedFromAudio: Song?,
    partyInParty: Boolean,
    alternateVersionCache: MutableMap<String, Boolean>,
    onHasAlternateVersion: (Boolean) -> Unit,
) {
    LaunchedEffect(
        player.song?.videoId,
        songActions?.videoId,
        menuFromPlayer,
        showNowPlaying,
        convertedAudioId,
        convertedVideoId,
        partyInParty,
    ) {
        val song = player.song
        val sheetIsOpenForThisTrack = song != null &&
            showNowPlaying &&
            menuFromPlayer &&
            songActions?.videoId == song.videoId
        if (song == null ||
            SongVersionPolicy.keepsLocalFile(
                SongOrigins.isLocalFile(song),
                song.isVideo,
                song.typeUnknown,
            ) ||
            partyInParty || !sheetIsOpenForThisTrack
        ) {
            onHasAlternateVersion(false)
            return@LaunchedEffect
        }
        if ((convertedFromVideo != null && convertedAudioId == song.videoId) ||
            (convertedFromAudio != null && convertedVideoId == song.videoId)
        ) {
            onHasAlternateVersion(true)
            return@LaunchedEffect
        }
        alternateVersionCache[song.videoId]?.let { known ->
            onHasAlternateVersion(known)
            return@LaunchedEffect
        }
        onHasAlternateVersion(false)
        val exists = withContext(Dispatchers.IO) {
            if (SongVersionPolicy.wantsSongVersion(song.isVideo, song.typeUnknown)) {
                runCatching {
                    val resolved = YtMusicRepository.songVersion(song)
                    SongVersionPolicy.isSongVersionOf(song, resolved)
                }.getOrDefault(false)
            } else {
                runCatching {
                    YtMusicRepository.resolveVideo(song) != null
                }.getOrDefault(false)
            }
        }
        alternateVersionCache[song.videoId] = exists
        if (player.song?.videoId == song.videoId && songActions?.videoId == song.videoId) {
            onHasAlternateVersion(exists)
        }
    }
}

/**
 * Undoes a version swap the lyrics check has proved wrong.
 *
 * The audio switch may adopt a row on its credit and runtime alone when the
 * title is filed under another script, and the sound is what settles whether
 * that was right. That verdict already exists: the lyrics lookup throws a
 * sheet away when what is being sung is not in it, and two providers failing
 * the same sound is the sound — see `LyricsMismatch.wrongSound`. This reads it
 * and nothing else: no request, no decode, no listening of its own.
 *
 * Only a swap made from the player is undone, because [original] — the row the
 * listener swapped away from — is the only copy of it. shortcut: a version the
 * queue resolved before playback has no such row and is left to the next ask,
 * which the recorded verdict then refuses.
 */
@Composable
internal fun KoWrongAudioRevertEffect(
    playingVideoId: String?,
    convertedFromVideo: Song?,
    convertedAudioId: String?,
    onRevert: (Song) -> Unit,
) {
    val playing = rememberUpdatedState(playingVideoId)
    val original = rememberUpdatedState(convertedFromVideo)
    val swapped = rememberUpdatedState(convertedAudioId)
    val revert = rememberUpdatedState(onRevert)
    LaunchedEffect(Unit) {
        LyricsMismatch.wrongSounds.collect { videoId ->
            val source = original.value ?: return@collect
            if (swapped.value != videoId || playing.value != videoId) return@collect
            val note = LyricsMismatch.wrongSoundNote(videoId) ?: return@collect
            TrackLog.i("BitChord", note, videoId)
            revert.value(source)
        }
    }
}
