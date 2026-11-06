package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.ko.KoSongActionsSheet

/** The menu entry point; the Korean fork keeps the rows in [KoSongActionsSheet]. */
@Composable
fun SongActionsMenu(

    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onDownload: () -> Unit,
    /**
     * Copies this track to the WebDAV server. Null hides the row ??the caller
     * passes one only for a readable device file while a server is
     * configured. Uploading while another upload of the same track runs just
     * re-runs it; the conflict dialog keeps that idempotent.
     */
    onUploadToWebDav: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    /**
     * Saves this song to the account's library, or takes it out. Null hides
     * the row ??a guest or a local file has no library entry to write.
     */
    onToggleLibrary: (() -> Unit)? = null,
    /** Whether [onToggleLibrary] would remove rather than add. */
    inLibrary: Boolean = false,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /**
     * Takes this track out of the song cache. Null hides the row ??the caller
     * passes one only while the cache actually holds some of it.
     */
    onRemoveFromCache: (() -> Unit)? = null,
    /**
     * Removes this track from the account's listening history. Null hides the
     * row ??the History screen passes one only when the menu is opened there.
     */
    onRemoveFromHistory: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    showPlaybackSpeed: Boolean = false,
    /**
     * The player's quick incognito switch. Null outside the player, where the
     * switch belongs to Settings rather than to one row in a list.
     */
    privateListening: Boolean = false,
    onTogglePrivateListening: (() -> Unit)? = null,
    /**
     * Sends the playing track back to YouTube's own upload, and keeps it there.
     * Offered for any player item that has one behind it ??a substituted copy
     * can be the wrong recording from the first second, not only after a
     * visible upgrade. See
     * [com.music.bitchord.playback.hasYouTubeOriginal].
     */
    onRollbackToOriginal: (() -> Unit)? = null,
    /**
     * The way back from a revert: available only for a player item the listener
     * has pinned to YouTube's own upload, where nothing will go looking for a
     * better copy again until they ask.
     * See [com.music.bitchord.playback.OriginalVersion].
     */
    onUpgradeQuality: (() -> Unit)? = null,
    /** Keeps the upgrade row visible but untappable while its lookup is running. */
    upgradeQualityInProgress: Boolean = false,
    /**
     * Fetches this downloaded track again at a higher grade and replaces the
     * file, rather than swapping the stream that is playing. Null everywhere
     * except a finished download with a YouTube id behind it.
     */
    onRedownloadQuality: (() -> Unit)? = null,
    /** True when nothing higher was verified, so the row says it is already best. */
    redownloadQualityAtBest: Boolean = false,
    /**
     * Switches the playing track between its video and audio-only cuts. Null
     * hides the row ??offered only from the player, and only once an
     * alternate cut is known to exist. See
     * [com.music.bitchord.playback.PlaybackService.smoothSwapCurrentTrackVersion].
     */
    onToggleAudioVersion: (() -> Unit)? = null,
    /** Which cut is playing now, so the row can offer the other one. */
    isAudioVersion: Boolean = false,
    /** The other cut was already looked for and does not exist, so the row says so. */
    audioVersionMissing: Boolean = false,
    onShare: (() -> Unit)? = null,
    /**
     * Copies what the app logged while starting this track. Null everywhere
     * except the player, where "this track" means something.
     */
    onCopyLog: (() -> Unit)? = null,
    /**
     * Writes this moment into the diagnostic log so a problem the listener can
     * see can be found again afterwards. Null everywhere except the player.
     */
    onReportProblem: (() -> Unit)? = null,
    /** Opens the timing control offered only by the main player's menu. */
    onLyricsOffset: (() -> Unit)? = null,
    /**
     * Opens the Automix mix popup on the next transition. Null hides the row ??
     * the player passes one only while Automix is on.
     */
    onAutomixOverview: (() -> Unit)? = null,
    /**
     * True while a lookup for this track's album/artist ids is still in
     * flight, so it isn't yet known whether "Open album" and "Open artist"
     * belong on this sheet at all. Only the player ever opens a sheet before
     * it knows; everywhere else this is simply false, and the two rows behave
     * as before ??present when the id is there, absent when it never was.
     */
    resolvingLinks: Boolean = false,
    /**
     * Where the rows are drawn. [SongActionsPresentation.Sheet] is the tinted
     * bottom sheet with its own track header; [SongActionsPresentation.Menu]
     * is only the rows, compact and in the theme's own colours, for the
     * popup a held row lifts into ??see [HeldContextMenu], which draws the
     * held row itself above them.
     */
    menu: Boolean = false,
) {
    KoSongActionsSheet(
        song = song,
        signedIn = signedIn,
        likeStatus = likeStatus,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onStartRadio = onStartRadio,
        onDownload = onDownload,
        onUploadToWebDav = onUploadToWebDav,
        onToggleLike = onToggleLike,
        onToggleDislike = onToggleDislike,
        onToggleLibrary = onToggleLibrary,
        inLibrary = inLibrary,
        onAddToPlaylist = onAddToPlaylist,
        onOpenAlbum = onOpenAlbum,
        onOpenArtist = onOpenArtist,
        modifier = modifier,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onRemoveFromCache = onRemoveFromCache,
        onRemoveFromHistory = onRemoveFromHistory,
        showSleepTimer = showSleepTimer,
        showPlaybackSpeed = showPlaybackSpeed,
        privateListening = privateListening,
        onTogglePrivateListening = onTogglePrivateListening,
        onRollbackToOriginal = onRollbackToOriginal,
        onUpgradeQuality = onUpgradeQuality,
        upgradeQualityInProgress = upgradeQualityInProgress,
        onRedownloadQuality = onRedownloadQuality,
        redownloadQualityAtBest = redownloadQualityAtBest,
        onToggleAudioVersion = onToggleAudioVersion,
        isAudioVersion = isAudioVersion,
        audioVersionMissing = audioVersionMissing,
        onShare = onShare,
        onCopyLog = onCopyLog,
        onReportProblem = onReportProblem,
        onLyricsOffset = onLyricsOffset,
        onAutomixOverview = onAutomixOverview,
        resolvingLinks = resolvingLinks,
        menu = menu,
    )
}
