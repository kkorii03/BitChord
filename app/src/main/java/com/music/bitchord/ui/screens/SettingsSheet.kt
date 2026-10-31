package com.music.bitchord.ui.screens

import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.wear.WatchInstaller
import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WebAssetOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.data.lyrics.translationLanguageName
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.music.bitchord.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.download.DownloadHealth
import com.music.bitchord.download.HqLibraryUpgrade
import com.music.bitchord.download.MvSoundRedownload
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.AppIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.PillSegmentedControl
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SettingsTransfer
import com.music.bitchord.data.settings.SettingsTransferInbox
import com.music.bitchord.data.settings.TransferCategory
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.BitPerfectMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.R
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.LowPowerMode
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.settings.AppFont
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.data.spotify.PlaylistImportMode
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

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
    /**
     * Asks before [mode] is turned on: it hands volume to the DAC at full
     * scale. Shown in the app's frosted alert, which needs the app's blur.
     */
    onConfirmBitPerfect: (BitPerfectMode) -> Unit,
    /** Asks before a DAC with no settable volume may play at full scale. */
    onConfirmFixedVolume: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /**
     * The category this page is, or null for the main page — which lists the
     * categories rather than their rows, until something is typed into its
     * search field.
     */
    section: SettingsCategory? = null,
    /** Opens one category as a page of its own. */
    onOpenSection: (SettingsCategory) -> Unit = {},
    /** Opens a choice with more options than its row can show, as a page. */
    onOpenChoice: (SettingsChoiceKind) -> Unit = {},
) {
    val context = LocalContext.current

    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixVideos by AppSettings.automixVideos.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val privateListening by AppSettings.privateListening.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    val directSourceStream by AppSettings.directSourceStream.collectAsStateWithLifecycle()
    // A property of the hardware, so it is read once rather than remembered
    // against a key that can never change — see [DeviceCodecs.playsDolbyAtmos],
    // which caches the codec-list walk for the life of the process.
    val dolbyAtmosSupported = DeviceCodecs.playsDolbyAtmos
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val listenTogetherButton by AppSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val lowPowerMode by AppSettings.lowPowerMode.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val videoCanvas by AppSettings.videoCanvas.collectAsStateWithLifecycle()
    val signatureApiEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureApiUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val geminiTranslation by AppSettings.geminiTranslation.collectAsStateWithLifecycle()
    val geminiApiKey by AppSettings.geminiApiKey.collectAsStateWithLifecycle()
    val deepgramApiKey by AppSettings.deepgramApiKey.collectAsStateWithLifecycle()
    val deepgramKeyRejected by com.music.bitchord.data.lyrics.Deepgram.keyRejected.collectAsStateWithLifecycle()
    val prefetchTranslation by AppSettings.prefetchTranslation.collectAsStateWithLifecycle()
    val autoTranslateLyrics by AppSettings.autoTranslateLyrics.collectAsStateWithLifecycle()
    val autoLyricsTiming by AppSettings.autoLyricsTiming.collectAsStateWithLifecycle()
    val aiLyrics by AppSettings.aiLyrics.collectAsStateWithLifecycle()
    val aiTimingAhead by AppSettings.aiTimingAhead.collectAsStateWithLifecycle()
    val syncSafetyNextPrep by AppSettings.syncSafetyNextPrep.collectAsStateWithLifecycle()
    val syncSafetyDownloadRecheck by AppSettings.syncSafetyDownloadRecheck.collectAsStateWithLifecycle()
    val syncSafetyHqSync by AppSettings.syncSafetyHqSync.collectAsStateWithLifecycle()
    val keepDownloadTranslation by AppSettings.keepDownloadTranslation.collectAsStateWithLifecycle()
    val lockScreenLyrics by AppSettings.lockScreenLyrics.collectAsStateWithLifecycle()
    val contentCountry by AppSettings.contentCountry.collectAsStateWithLifecycle()
    val resumeOnHeadphones by AppSettings.resumeOnHeadphones.collectAsStateWithLifecycle()
    val personalisedAutoplay by AppSettings.personalisedAutoplay.collectAsStateWithLifecycle()
    val videoRadio by AppSettings.videoRadio.collectAsStateWithLifecycle()
    // Read again whenever the page comes back, which is how a grant made in
    // the system's own screen shows up here.
    val settingsForeground = com.music.bitchord.ui.rememberIsForeground()
    val canDrawOverlays = remember(settingsForeground) {
        com.music.bitchord.lockscreen.LockScreenLyrics.canShow(context)
    }
    val openOverlayPermission: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val geminiKeyRejected by com.music.bitchord.data.lyrics.LyricsTranslation.geminiKeyRejected
        .collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val bitPerfectMode by AppSettings.bitPerfectMode.collectAsStateWithLifecycle()
    val bitPerfectStatus by BitPerfectOutput.status.collectAsStateWithLifecycle()
    val bitPerfectReady by BitPerfectOutput.ready.collectAsStateWithLifecycle()
    val dacVolume by BitPerfectOutput.hardwareVolume.collectAsStateWithLifecycle()
    val usbAllowFixedVolume by AppSettings.usbAllowFixedVolume.collectAsStateWithLifecycle()
    val convertForLimitedDac by AppSettings.convertForLimitedDac.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
    val playingDolbyAtmos = playingFormat?.isDolbyAtmos == true
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val autoRedownloadMvSound by AppSettings.autoRedownloadMvSound.collectAsStateWithLifecycle()
    val autoReplaceHqDownloads by AppSettings.autoReplaceHqDownloads.collectAsStateWithLifecycle()
    val autoPrefetchHq by AppSettings.autoPrefetchHq.collectAsStateWithLifecycle()
    val hqPrefetchLimit by AppSettings.hqPrefetchLimit.collectAsStateWithLifecycle()
    val autoDownloadLiked by AppSettings.autoDownloadLiked.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
    val syncDownloadedPlaylists by AppSettings.syncDownloadedPlaylists.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val widgetOpensPlayer by AppSettings.widgetOpensPlayer.collectAsStateWithLifecycle()
    val hiddenExploreCategories by AppSettings.hiddenExploreCategories.collectAsStateWithLifecycle()
    val showQueueHistory by AppSettings.showQueueHistory.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }

    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    // Scrobbling states
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()

    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()
    val lastfmApiKey by AppSettings.lastfmApiKey.collectAsStateWithLifecycle()

    // Read here so the row can say "In a party · ABC123" rather than making
    // somebody open the screen to find out whether they are still in one.
    val party by ListenTogether.state.collectAsStateWithLifecycle()

    // Filters the rows below — see [SettingsSearch]. Blank shows everything,
    // exactly as if the field weren't there.
    // Saveable so a search survives a trip into one of the pages it found.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    /** The file chosen, waiting for the replace-or-merge choice. */
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var confirmClearSongCache by remember { mutableStateOf(false) }
    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    var showHiddenCategories by remember { mutableStateOf(false) }
    val backupScope = rememberCoroutineScope()

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showPerformanceConfirmation = true
    }
    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, FriendlyError.message(context, it))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        // Which file is separate from how its playlists should be applied, and
        // the second question is the one the user has to answer.
        pendingImport = source
    }
    val applyImport: (Uri, PlaylistImportMode) -> Unit = { source, mode ->
        pendingImport = null
        backupScope.launch {
            importStatus = Backup.importFrom(context, source, mode).fold(
                onSuccess = { summary ->
                    if (summary.playlists > 0) {
                        context.getString(
                            R.string.import_succeeded_with_library,
                            context.countOfMonths(summary.months),
                            summary.from,
                            summary.playlists,
                        )
                    } else {
                        context.getString(
                            R.string.import_succeeded,
                            context.countOfMonths(summary.months),
                            summary.from,
                        )
                    }
                },
                onFailure = {
                    context.getString(R.string.import_failed, FriendlyError.message(context, it))
                },
            )
        }
    }
    // Settings transfer: a short file of proxy, sources and sound/screen
    // choices. Separate from the backup above, which replaces history too.
    var settingsSendStatus by remember { mutableStateOf<String?>(null) }
    var settingsImportStatus by remember { mutableStateOf<String?>(null) }
    var confirmSendSettings by remember { mutableStateOf(false) }
    var settingsPreview by remember { mutableStateOf<SettingsTransfer.Preview?>(null) }

    val settingsImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            SettingsTransfer.importFrom(context, source).fold(
                onSuccess = { settingsPreview = it },
                onFailure = {
                    settingsImportStatus = context.getString(
                        R.string.settings_transfer_import_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

    // A file shared to BitChord from a chat app or file manager. The activity
    // has already brought Settings forward; reading it here is the other half.
    val incomingSettingsFile by SettingsTransferInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(incomingSettingsFile) {
        val source = incomingSettingsFile ?: return@LaunchedEffect
        SettingsTransferInbox.handled()
        SettingsTransfer.importFrom(context, source).fold(
            onSuccess = { settingsPreview = it },
            onFailure = {
                settingsImportStatus = context.getString(
                    R.string.settings_transfer_import_failed,
                    FriendlyError.message(context, it),
                )
            },
        )
    }

    val sendSettings: () -> Unit = {
        confirmSendSettings = false
        backupScope.launch {
            SettingsTransfer.share(context).fold(
                onSuccess = { intent ->
                    settingsSendStatus = context.getString(R.string.settings_transfer_share_opened)
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.settings_transfer_send))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onFailure = {
                    settingsSendStatus = context.getString(
                        R.string.settings_transfer_send_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

    val applySettingsImport: (SettingsTransfer.Preview) -> Unit = { preview ->
        settingsPreview = null
        backupScope.launch {
            SettingsTransfer.apply(preview).fold(
                onSuccess = { summary ->
                    val parts = mutableListOf(
                        context.getString(
                            R.string.settings_transfer_applied,
                            summary.settings,
                            summary.sources,
                        ),
                    )
                    if (summary.proxy) {
                        parts += context.getString(R.string.settings_transfer_applied_proxy)
                    }
                    if (summary.skipped > 0) {
                        parts += context.getString(
                            R.string.settings_transfer_skipped,
                            summary.skipped,
                        )
                    }
                    settingsImportStatus = parts.joinToString(" ")
                },
                onFailure = {
                    settingsImportStatus = context.getString(
                        R.string.settings_transfer_import_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }
    var showLastfmLoginDialog by remember { mutableStateOf(false) }
    val scrobbleScope = rememberCoroutineScope()

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // Made fresh on every pass rather than remembered: it tallies the rows
    // that answered to the query, and that tally has to start from zero each
    // time the query changes.
    //
    // A category page shows all of its rows, so it searches for nothing. The
    // main page searches every category at once: typed into, it lays out the
    // rows that answer from all of them, as the single page Settings once was.
    val search = SettingsSearch(if (section == null) searchQuery else "", inPage = section != null)
    val searching = section == null && searchQuery.isNotBlank()
    fun shows(category: SettingsCategory) = section == category || searching
    val watchAvailable = remember { WatchInstaller.available(context) }

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
        SettingsPageTitle(stringResource(section?.title ?: R.string.settings))
        if (section == null) {
            SearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSubmit = {},
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )

            SearchableSettingsGroup(search) {
                val accountTitle = stringResource(R.string.account_integrations)
                // What the row opens is the scrobbling screen, so the services it
                // signs into are worth typing at this field even though none of
                // them is named on the row itself.
                row(accountTitle, "scrobbling", "last.fm", "listenbrainz") {
                    SettingsRow(
                        icon = SfIcons.Person,
                        title = accountTitle,
                        subtitle = account?.email?.takeIf { it.isNotBlank() }
                            ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                        onClick = onAccountScrobbling,
                    )
                }
                // Sits with the account rather than with Playback: a party is up to
                // five signed-in people, and being signed in is the whole of what
                // the row needs before it will do anything.
                val listenTogetherTitle = stringResource(R.string.listen_together)
                row(listenTogetherTitle, "jam", "party", "sync", "friends") {
                    SettingsRow(
                        icon = SfIcons.Groups,
                        title = listenTogetherTitle,
                        subtitle = party.code?.let {
                            stringResource(R.string.listen_together_in_party, it)
                        } ?: stringResource(R.string.listen_together_subtitle),
                        badge = party.members.size.takeIf { party.inParty && it > 1 }?.toString(),
                        onClick = onListenTogether,
                    )
                }
            }

            // The row that used to sit at the top of this group was called
            // "Lossless / HQ Audio" and toggled `SourceRegistry.setModuleEnabled` —
            // it switched the *module source* on and off, not lossless. Sources
            // above lists that as the module's own row now. Lossless itself is no
            // longer a setting at all — see
            // [SourceResolver.requestForNow][com.music.bitchord.data.sources.SourceResolver.requestForNow].
            SearchableSettingsGroup(search, header = stringResource(R.string.audio_quality)) {
                val sourceTitle = stringResource(R.string.source)
                val sourceSubtitle = stringResource(R.string.sources_subtitle)
                row(sourceTitle, sourceSubtitle, "addon", "lossless") {
                    SettingsRow(
                        icon = SfIcons.Extension,
                        title = sourceTitle,
                        subtitle = sourceSubtitle,
                        onClick = onSources,
                    )
                }
                // One line on purpose: it qualifies the row above rather than
                // being a setting of its own, so it reads as part of Sources.
                val directSourceTitle = stringResource(R.string.direct_source_stream)
                // Drawn like the animated-cover cellular line: no icon, no divider.
                row(directSourceTitle, "addon", "direct", "prefetch", "read-ahead", divided = false) {
                    // Off for the length of a party: holding every fetch until a
                    // track is about to play would leave this phone behind the
                    // others at each sync — see [DirectSourceGate.isActive].
                    SettingsSubRow(
                        title = directSourceTitle,
                        checked = directSourceStream && !party.inParty,
                        onCheckedChange = AppSettings::setDirectSourceStream,
                        subtitle = stringResource(R.string.direct_source_stream_in_party).takeIf { party.inParty },
                        enabled = !party.inParty,
                    )
                }
                val onWifiTitle = stringResource(R.string.on_wifi)
                row(onWifiTitle, "wi-fi", "streaming quality") {
                    SettingsRow(
                        icon = SfIcons.Wifi,
                        title = onWifiTitle,
                        badge = stringResource(R.string.in_use).takeIf { metered == false },
                        value = wifiQuality.localizedLabel(),
                        onClick = { onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_WIFI) },
                    )
                }
                val onMobileDataTitle = stringResource(R.string.on_mobile_data)
                row(onMobileDataTitle, "cellular", "streaming quality") {
                    SettingsRow(
                        icon = SfIcons.SignalCellularAlt,
                        title = onMobileDataTitle,
                        badge = stringResource(R.string.in_use).takeIf { metered == true },
                        value = cellularQuality.localizedLabel(),
                        onClick = { onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_CELLULAR) },
                    )
                }
                // Sits with the quality ceilings rather than with Playback: it
                // decides which version of a track gets fetched, the same question
                // the two rows above answer, and not how one is played back.
                //
                // Greyed rather than hidden where the device can't decode E-AC-3.
                // A missing row reads as a feature the app doesn't have; a disabled
                // one with a reason under it is the difference between "BitChord
                // has no Atmos" and "this phone has no Dolby decoder", and only the
                // second is true. The stored preference is left untouched either
                // way — see [AppSettings.dolbyAtmos].
                val dolbyAtmosTitle = stringResource(R.string.dolby_atmos)
                row(dolbyAtmosTitle, "surround", "e-ac-3") {
                    BypassedByBitPerfect(bitPerfectReady) {
                        SettingsRow(
                            iconPainter = painterResource(R.drawable.ic_dolby_atmos),
                            title = dolbyAtmosTitle,
                            subtitle = stringResource(
                                if (dolbyAtmosSupported) {
                                    R.string.dolby_atmos_subtitle
                                } else {
                                    R.string.dolby_atmos_unavailable
                                },
                            ),
                            enabled = dolbyAtmosSupported,
                            trailing = {
                                IosSwitch(
                                    checked = dolbyAtmos && dolbyAtmosSupported,
                                    onCheckedChange = AppSettings::setDolbyAtmos,
                                    enabled = dolbyAtmosSupported,
                                )
                            },
                            onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
                        )
                    }
                }
            }

            // The categories, each a page of its own — until there is a query,
            // when their rows answer here instead (below).
            if (!searching) {
                SettingsCategoryList(onOpen = onOpenSection)
                if (watchAvailable) {
                    SettingsGroup {
                        WearOsSettingsRow(onClick = onWearOs)
                    }
                }
            }
        }

        // Its own group rather than rows bolted onto the two above, because a
        // download is not a third kind of connection. The ceilings answer "what
        // does this minute cost"; these answer "what am I keeping, and when may
        // it be fetched" — and those two questions only make sense read
        // together, which is what puts them side by side here.
        if (shows(SettingsCategory.DOWNLOADS)) SearchableSettingsGroup(search, header = stringResource(R.string.downloads)) {
            val downloadQualityTitle = stringResource(R.string.download_quality)
            row(downloadQualityTitle, "offline", "lossless") {
                SettingsRow(
                    icon = SfIcons.Download,
                    title = downloadQualityTitle,
                    subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                    value = downloadQuality.localizedLabel(),
                    onClick = { onOpenChoice(SettingsChoiceKind.DOWNLOAD_QUALITY) },
                )
            }
            // Reads as part of Download quality above it, not as a setting
            // of its own — same treatment as Play animated cover over
            // cellular gets under Animated cover art.
            val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
            row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
                SettingsSubRow(
                    title = downloadWifiOnlyTitle,
                    checked = wifiOnlyDownloads,
                    onCheckedChange = AppSettings::setWifiOnlyDownloads,
                )
            }
            val autoRedownloadMvSoundTitle = stringResource(R.string.auto_redownload_mv_sound)
            val autoRedownloadMvSoundSubtitle =
                stringResource(R.string.auto_redownload_mv_sound_subtitle)
            row(autoRedownloadMvSoundTitle, "mv", "video", "download", "automatic", divided = false) {
                SettingsSubRow(
                    title = autoRedownloadMvSoundTitle,
                    subtitle = autoRedownloadMvSoundSubtitle,
                    checked = autoRedownloadMvSound,
                    onCheckedChange = { checked ->
                        AppSettings.setAutoRedownloadMvSound(checked)
                        if (checked) {
                            MvSoundRedownload.scheduleIfEnabled(context)
                        } else {
                            MvSoundRedownload.cancel(context)
                        }
                    },
                )
            }
            val autoReplaceHqTitle = stringResource(R.string.auto_replace_hq_downloads)
            val autoReplaceHqSubtitle =
                stringResource(R.string.auto_replace_hq_downloads_subtitle)
            row(autoReplaceHqTitle, "hq", "high quality", "lossless", "replace", "고음질", divided = false) {
                SettingsSubRow(
                    title = autoReplaceHqTitle,
                    subtitle = autoReplaceHqSubtitle,
                    checked = autoReplaceHqDownloads,
                    onCheckedChange = { checked ->
                        AppSettings.setAutoReplaceHqDownloads(checked)
                        HqLibraryUpgrade.onSettingsChanged(context)
                    },
                )
            }
            val autoPrefetchHqTitle = stringResource(R.string.auto_prefetch_hq)
            val autoPrefetchHqSubtitle =
                stringResource(R.string.auto_prefetch_hq_subtitle, hqPrefetchLimit)
            row(autoPrefetchHqTitle, "hq", "high quality", "prefetch", "offline", "고음질", divided = false) {
                SettingsSubRow(
                    title = autoPrefetchHqTitle,
                    subtitle = autoPrefetchHqSubtitle,
                    checked = autoPrefetchHq,
                    onCheckedChange = { checked ->
                        AppSettings.setAutoPrefetchHq(checked)
                        HqLibraryUpgrade.onSettingsChanged(context)
                    },
                )
            }
            val recheckDownloadsTitle = stringResource(R.string.download_health_recheck)
            val recheckDownloadsSubtitle =
                stringResource(R.string.download_health_recheck_subtitle)
            row(recheckDownloadsTitle, "download", "recheck", "verify", "다운로드", "점검", divided = false) {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = recheckDownloadsTitle,
                    subtitle = recheckDownloadsSubtitle,
                    trailing = { Chevron() },
                    onClick = { DownloadHealth.resetChecks(context) },
                )
            }
            val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
            row(exportDownloadsTitle, "music folder") {
                SettingsSubRow(
                    title = exportDownloadsTitle,
                    checked = exportDownloads,
                    onCheckedChange = AppSettings::setExportDownloads,
                    subtitle = "Music/BitChord".takeIf { exportDownloads },
                )
            }
            val syncPlaylistsTitle = stringResource(R.string.sync_downloaded_playlists)
            row(syncPlaylistsTitle, "playlist", "new songs", "background", "automatic") {
                SettingsSubRow(
                    title = syncPlaylistsTitle,
                    checked = syncDownloadedPlaylists,
                    onCheckedChange = AppSettings::setSyncDownloadedPlaylists,
                    subtitle = stringResource(R.string.sync_downloaded_playlists_subtitle),
                )
            }
        }

        if (shows(SettingsCategory.PLAYBACK)) SearchableSettingsGroup(
            search,
            header = stringResource(R.string.playback),
            footer = stringResource(R.string.bit_perfect_bypassed_footer).takeIf { bitPerfectReady },
        ) {
            val outputPrecisionTitle = stringResource(R.string.output_precision)
            val outputRouteLabel = stringResource(
                when (outputStatus.routeKind) {
                    AudioRouting.Kind.PHONE -> R.string.route_phone
                    AudioRouting.Kind.WIRED -> R.string.route_wired
                    AudioRouting.Kind.USB -> R.string.route_usb
                    AudioRouting.Kind.BLUETOOTH -> R.string.route_bluetooth
                    AudioRouting.Kind.HDMI -> R.string.route_hdmi
                    AudioRouting.Kind.OTHER -> R.string.route_other
                },
            )
            row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
                SettingsRow(
                    icon = SfIcons.GraphicEq,
                    title = outputPrecisionTitle,
                    subtitle = buildString {
                        append(outputRouteLabel)
                        (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                            ?.let { append(" · ${it / 1000.0} kHz") }
                    },
                )
                SettingsSegmentedControl(
                    options = OutputPcmMode.entries.map {
                        stringResource(
                            when (it) {
                                OutputPcmMode.PCM_16 -> R.string.standard
                                OutputPcmMode.FLOAT_32 -> R.string.high
                            },
                        )
                    },
                    selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                    onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                    modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
            row(preferUsbDacTitle, "headphone", "output") {
                SettingsSubRow(
                    title = preferUsbDacTitle,
                    checked = preferUsbDac,
                    onCheckedChange = AppSettings::setPreferUsbDac,
                )
            }
            val bitPerfectTitle = stringResource(R.string.bit_perfect_output)
            row(bitPerfectTitle, "usb", "dac", "exclusive", "hi-res", "bitperfect") {
                // The mode and the device it names can both be long, so they
                // go under the title rather than beside it, where they left
                // the title and status no room.
                SettingsRow(
                    icon = SfIcons.Usb,
                    title = bitPerfectTitle,
                    subtitleContent = {
                        Column {
                            Text(
                                text = bitPerfectSubtitle(bitPerfectMode, bitPerfectStatus),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = bitPerfectMode.localizedLabel(),
                                modifier = Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = { onOpenChoice(SettingsChoiceKind.BIT_PERFECT) },
                )
            }
            // The DAC's own attenuator: the only volume direct USB has, since
            // nothing upstream may scale a sample. It and the row after it
            // belong to Bit-perfect output, so neither is divided from it.
            dacVolume?.let { volume ->
                val dacVolumeTitle = stringResource(R.string.dac_volume)
                row(dacVolumeTitle, "volume", "usb", "dac", divided = false) {
                    SliderRow(
                        icon = SfIcons.VolumeUp,
                        title = dacVolumeTitle,
                        subtitle = stringResource(R.string.dac_volume_subtitle),
                        value = "%.1f dB".format(volume.currentDb),
                        sliderValue = volume.currentDb.toFloat(),
                        onSliderValue = { BitPerfectOutput.setHardwareVolume(it.toDouble()) },
                        valueRange = volume.minDb.toFloat()..volume.maxDb.toFloat(),
                        steps = 0,
                    )
                }
            }
            if (bitPerfectMode == BitPerfectMode.USB_DIRECT) {
                val fixedVolumeTitle = stringResource(R.string.usb_allow_fixed_volume)
                row(fixedVolumeTitle, "volume", "usb", "dac", divided = false) {
                    SettingsSubRow(
                        title = fixedVolumeTitle,
                        subtitle = stringResource(R.string.usb_allow_fixed_volume_subtitle),
                        checked = usbAllowFixedVolume,
                        onCheckedChange = { on ->
                            if (on) onConfirmFixedVolume() else AppSettings.setUsbAllowFixedVolume(false)
                        },
                    )
                }
            }
            if (bitPerfectMode != BitPerfectMode.OFF && bitPerfectMode.isSupported) {
                val convertTitle = stringResource(R.string.convert_for_limited_dac)
                row(convertTitle, "convert", "resample", "usb", "dac", "bitperfect", divided = false) {
                    SettingsSubRow(
                        title = convertTitle,
                        subtitle = stringResource(R.string.convert_for_limited_dac_subtitle),
                        checked = convertForLimitedDac,
                        onCheckedChange = AppSettings::setConvertForLimitedDac,
                    )
                }
            }
            val loudnessTitle = stringResource(R.string.loudness_normalization)
            row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsRow(
                        icon = SfIcons.VolumeUp,
                        title = loudnessTitle,
                        subtitle = stringResource(R.string.loudness_normalization_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = loudnessNormalization,
                                onCheckedChange = AppSettings::setLoudnessNormalization,
                            )
                        },
                        onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                    )
                }
            }
            val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
            row(loudnessSpeakerTitle, "loudness", "speaker", "normalize") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsSubRow(
                        title = loudnessSpeakerTitle,
                        checked = loudnessOffOnSpeaker,
                        onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                    )
                }
            }
            // Automix decides its own length from each pair of tracks —
            // tempo, key, structure — so it replaces the manual slider rather
            // than needing it set to anything first.
            if (!smartFade) {
                val crossfadeTitle = stringResource(R.string.crossfade)
                row(crossfadeTitle, "fade", "gapless") {
                    BypassedByBitPerfect(bitPerfectReady) {
                        SliderRow(
                            icon = SfIcons.Waves,
                            title = crossfadeTitle,
                            subtitle = stringResource(R.string.crossfade_subtitle),
                            value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                            sliderValue = crossfade.toFloat(),
                            onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                            valueRange = 0f..12f,
                            steps = 11,
                        )
                    }
                }
            }
            val automixTitle = stringResource(R.string.automix)
            row(automixTitle, "crossfade", "smart fade", "mix") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsRow(
                        icon = SfIcons.AutoAwesome,
                        title = automixTitle,
                        subtitle = if (smartFade) {
                            stringResource(R.string.automix_enabled_subtitle)
                        } else {
                            stringResource(R.string.automix_disabled_subtitle)
                        },
                        trailing = {
                            IosSwitch(
                                checked = smartFade,
                                onCheckedChange = AppSettings::setSmartFadeEnabled,
                            )
                        },
                        onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                    )
                }
            }
            if (smartFade) {
                val automixVideosTitle = stringResource(R.string.automix_videos)
                row(automixVideosTitle, "video", "automix", "mix") {
                    SettingsSubRow(
                        title = automixVideosTitle,
                        subtitle = stringResource(R.string.automix_videos_subtitle),
                        checked = automixVideos,
                        onCheckedChange = AppSettings::setAutomixVideos,
                    )
                }
            }
            val automixPerformanceTitle = stringResource(R.string.automix_performance)
            row(automixPerformanceTitle, "cpu", "battery") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsRow(
                        icon = SfIcons.Tune,
                        title = automixPerformanceTitle,
                        subtitle = stringResource(R.string.automix_performance_subtitle),
                        value = automixPerformance.localizedLabel(),
                        onClick = { onOpenChoice(SettingsChoiceKind.AUTOMIX_PERFORMANCE) },
                    )
                }
            }
            val skipSilenceTitle = stringResource(R.string.skip_silence)
            row(skipSilenceTitle, "silence") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsRow(
                        icon = SfIcons.VolumeOff,
                        title = skipSilenceTitle,
                        // Silence skipping is Media3's own processor, and
                        // `DefaultAudioSink.configure` appends that processor list
                        // only on its 16-bit branch — the float branch gets the
                        // format converter and nothing else. So on a float track
                        // this switch really does nothing, and the row says so
                        // rather than letting someone toggle it and wonder. The
                        // equaliser is unaffected: it runs upstream of that sink.
                        subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            stringResource(R.string.skip_silence_float_subtitle)
                        } else {
                            stringResource(R.string.skip_silence_subtitle)
                        },
                        trailing = {
                            IosSwitch(
                                checked = skipSilence,
                                onCheckedChange = AppSettings::setSkipSilence,
                            )
                        },
                        onClick = { AppSettings.setSkipSilence(!skipSilence) },
                    )
                }
            }
            val privateListeningTitle = stringResource(R.string.private_listening)
            row(privateListeningTitle, "private", "incognito", "history", "privacy") {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = privateListeningTitle,
                    subtitle = stringResource(R.string.private_listening_subtitle),
                    trailing = {
                        Switch(
                            checked = privateListening,
                            onCheckedChange = {
                                AppSettings.setPrivateListening(it, PrivateListeningSource.SETTINGS)
                            },
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = {
                        AppSettings.setPrivateListening(
                            !privateListening,
                            PrivateListeningSource.SETTINGS,
                        )
                    },
                )
            }
            val widgetOpensPlayerTitle = stringResource(R.string.widget_opens_player)
            row(widgetOpensPlayerTitle, "widget", "home screen", "player") {
                SettingsRow(
                    icon = Icons.Rounded.Widgets,
                    title = widgetOpensPlayerTitle,
                    subtitle = stringResource(R.string.widget_opens_player_subtitle),
                    trailing = {
                        Switch(
                            checked = widgetOpensPlayer,
                            onCheckedChange = AppSettings::setWidgetOpensPlayer,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setWidgetOpensPlayer(!widgetOpensPlayer) },
                )
            }
            val spatialAudioTitle = stringResource(R.string.spatial_audio)
            row(spatialAudioTitle, "surround", "3d") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsRow(
                        icon = SfIcons.SurroundSound,
                        title = spatialAudioTitle,
                        // Widening a JOC stream would fight the object-based mix
                        // Dolby has already spatialized, so the service holds this
                        // off for the duration of an Atmos track — see
                        // `PlaybackService.applySpatialAudioEnabled`. Said on the
                        // row because the switch stays where the listener left it,
                        // and a switch reading "on" over an effect that is not
                        // running is the same silent lie the equaliser screen used
                        // to tell.
                        subtitle = if (playingDolbyAtmos) {
                            stringResource(R.string.spatial_audio_atmos_subtitle)
                        } else {
                            stringResource(R.string.spatial_audio_subtitle)
                        },
                        trailing = {
                            IosSwitch(
                                checked = spatialAudio,
                                onCheckedChange = AppSettings::setSpatialAudio,
                            )
                        },
                        onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                    )
                }
            }
            // The system panel is not listed here as well. A device with a
            // Dolby or Dirac panel has something BitChord cannot reproduce and
            // keeps its row — but one level in, at the foot of the equaliser
            // screen, rather than as a second equaliser entry alongside ours.
            val equalizerTitle = stringResource(R.string.equalizer)
            row(equalizerTitle, "eq", "bass", "treble") {
                BypassedByBitPerfect(bitPerfectReady) {
                    SettingsRow(
                        icon = SfIcons.Tune,
                        title = equalizerTitle,
                        subtitle = stringResource(R.string.equalizer_subtitle),
                        onClick = onEqualizer,
                    )
                }
            }
        }

        if (shows(SettingsCategory.APPEARANCE)) SearchableSettingsGroup(search, header = stringResource(R.string.appearance)) {
            val themeTitle = stringResource(R.string.theme)
            row(themeTitle, "dark mode", "light mode") {
                SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                SettingsSegmentedControl(
                    options = ThemeMode.entries.map { it.localizedLabel() },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val fontTitle = stringResource(R.string.font)
            row(fontTitle, "typeface", "sf pro", "legacy") {
                SettingsRow(
                    icon = SfIcons.Font,
                    title = fontTitle,
                    subtitle = appFont.description(),
                    value = appFont.localizedLabel(),
                    onClick = { onOpenChoice(SettingsChoiceKind.FONT) },
                )
            }
            val reduceAnimationTitle = stringResource(R.string.reduce_animation)
            row(reduceAnimationTitle, "motion") {
                SettingsRow(
                    icon = SfIcons.MotionPhotosOff,
                    title = reduceAnimationTitle,
                    subtitle = stringResource(R.string.reduce_animation_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = reduceAnimation,
                            onCheckedChange = AppSettings::setReduceAnimation,
                        )
                    },
                    onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                )
            }
            val lowPowerTitle = stringResource(R.string.low_power_mode)
            row(lowPowerTitle, "battery", "heat", "power saving") {
                SettingsRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = lowPowerTitle,
                    subtitle = stringResource(R.string.low_power_mode_subtitle),
                )
                SettingsSegmentedControl(
                    options = LowPowerMode.entries.map { it.localizedLabel() },
                    selectedIndex = LowPowerMode.entries.indexOf(lowPowerMode),
                    onSelect = { AppSettings.setLowPowerMode(LowPowerMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
            row(reduceDynamicBlurTitle, "blur", "performance") {
                SettingsRow(
                    icon = SfIcons.BlurOff,
                    title = reduceDynamicBlurTitle,
                    subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = reduceDynamicBlur,
                            onCheckedChange = AppSettings::setReduceDynamicBlur,
                        )
                    },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                )
            }
            val liquidGlassTitle = stringResource(R.string.liquid_glass)
            row(liquidGlassTitle, "glass", "blur") {
                SettingsRow(
                    icon = SfIcons.AutoAwesome,
                    title = liquidGlassTitle,
                    subtitle = stringResource(
                        if (liquidGlassSupported) {
                            R.string.liquid_glass_subtitle
                        } else {
                            R.string.liquid_glass_unavailable
                        },
                    ),
                    enabled = liquidGlassSupported,
                    trailing = {
                        IosSwitch(
                            checked = liquidGlass,
                            onCheckedChange = AppSettings::setLiquidGlass,
                            enabled = liquidGlassSupported,
                        )
                    },
                    onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                )
            }
            // One line, like Direct Source Stream under Sources, and only while
            // Liquid Glass is on: it qualifies the glass rather than standing on
            // its own. Its own switch because it costs frames of its own. Reduce
            // dynamic blur turns it off.
            if (liquidGlass && liquidGlassSupported) {
                val progressiveBlurTitle = stringResource(R.string.progressive_blur)
                row(progressiveBlurTitle, "blur", "status bar", "top", divided = false) {
                    SettingsSubRow(
                        title = progressiveBlurTitle,
                        checked = progressiveBlur,
                        onCheckedChange = AppSettings::setProgressiveBlur,
                    )
                }
            }
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                row(fullScreenCoverArtTitle, "artwork", "player") {
                    SettingsRow(
                        icon = SfIcons.Fullscreen,
                        title = fullScreenCoverArtTitle,
                        subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = fullBleedArtwork,
                                onCheckedChange = AppSettings::setFullBleedArtwork,
                            )
                        },
                        onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                    )
                }
            }
            val legacyMeshGradientTitle = stringResource(R.string.legacy_mesh_gradient)
            row(legacyMeshGradientTitle, "background", "player") {
                SettingsRow(
                    icon = SfIcons.Gradient,
                    title = legacyMeshGradientTitle,
                    subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = legacyMeshGradient,
                            onCheckedChange = AppSettings::setLegacyMeshGradient,
                        )
                    },
                    onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
                )
            }
            val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
            // Carries the keywords of the rows that only appear once it is on,
            // so searching "spotify" with animated covers switched off turns up
            // the switch that brings the Spotify row back rather than nothing.
            row(animatedCoverArtTitle, "canvas", "video", "spotify") {
                SettingsRow(
                    icon = SfIcons.Animation,
                    title = animatedCoverArtTitle,
                    subtitle = stringResource(R.string.animated_cover_art_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = animatedCanvas,
                            onCheckedChange = AppSettings::setAnimatedCanvas,
                        )
                    },
                    onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
                )
            }
            // Reads as part of the Animated cover art option above it, not
            // as a separate setting. Nothing to narrow while the clip itself
            // is off. Defaults to off: a clip loops for as long as its track
            // plays, so on cellular this is not a one-time video cost but
            // that cost repeated on every loop — see AppSettings.canvasOverCellular.
            if (animatedCanvas) {
                val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
                row(coverCellularTitle, "canvas", "cellular", divided = false) {
                    SettingsSubRow(
                        title = coverCellularTitle,
                        checked = canvasOverCellular,
                        onCheckedChange = AppSettings::setCanvasOverCellular,
                    )
                }
                val videoCanvasTitle = stringResource(R.string.video_canvas)
                row(videoCanvasTitle, "canvas", "video", "music video", divided = false) {
                    SettingsSubRow(
                        title = videoCanvasTitle,
                        checked = videoCanvas,
                        onCheckedChange = AppSettings::setVideoCanvas,
                    )
                }
                val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
                row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSpotifyCanvasAuth)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spotifyCanvasTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
            val artistSignaturesTitle = stringResource(R.string.artist_signatures)
            row(artistSignaturesTitle, "signature", "artist", "logo") {
                SettingsRow(
                    icon = SfIcons.Draw,
                    title = artistSignaturesTitle,
                    subtitle = stringResource(R.string.artist_signatures_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = signatureApiEnabled,
                            onCheckedChange = AppSettings::setSignatureApiEnabled,
                        )
                    },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
                )
            }
            if (signatureApiEnabled) {
                val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
                row(signatureUrlTitle, "signature", "api", "url", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onEditSignatureApi)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = signatureApiUrl.ifBlank {
                                stringResource(R.string.artist_signatures_unconfigured)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
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
                    onClick = { showHiddenCategories = true },
                )
            }
        }

        SearchableSettingsGroup(search, header = stringResource(R.string.settings_lyrics)) {
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art above: the lyrics source and
            // translation rows are only here while this is on.
            row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = SfIcons.Notes,
                    title = syncedLyricsTitle,
                    subtitle = stringResource(R.string.synced_lyrics_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = syncedLyrics,
                            onCheckedChange = AppSettings::setSyncedLyrics,
                        )
                    },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
                row(lyricsBlurTitle, "lyrics", "blur") {
                    SettingsRow(
                        icon = SfIcons.BlurOn,
                        title = lyricsBlurTitle,
                        subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lyricsBlur,
                                onCheckedChange = AppSettings::setLyricsBlur,
                            )
                        },
                        onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                    )
                }
                val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
                row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                    SettingsRow(
                        icon = SfIcons.Language,
                        title = lyricsSourcesTitle,
                        subtitle = lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                            .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                        trailing = { Chevron() },
                        onClick = onLyricsSources,
                    )
                }
                val translationLanguageTitle = stringResource(R.string.translation_language)
                row(translationLanguageTitle, "lyrics", "translate") {
                    SettingsRow(
                        icon = SfIcons.Translate,
                        title = translationLanguageTitle,
                        subtitle = if (translationLanguage.isBlank()) {
                            stringResource(R.string.translation_language_subtitle)
                        } else {
                            translationLanguageName(
                                translationLanguage,
                                AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                            )
                        },
                        trailing = { Chevron() },
                        onClick = onTranslationLanguage,
                    )
                }
                val geminiTranslationTitle = stringResource(R.string.gemini_translation)
                row(geminiTranslationTitle, "lyrics", "translate", "gemini", "ai") {
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = geminiTranslationTitle,
                        subtitle = stringResource(
                            when {
                                geminiApiKey.isBlank() -> R.string.gemini_translation_subtitle_missing
                                geminiKeyRejected -> R.string.gemini_translation_subtitle_rejected
                                else -> R.string.gemini_translation_subtitle_ready
                            },
                        ),
                        trailing = {
                            Switch(
                                checked = geminiTranslation && geminiApiKey.isNotBlank(),
                                onCheckedChange = { on ->
                                    if (on && geminiApiKey.isBlank()) onGeminiKey()
                                    else AppSettings.setGeminiTranslation(on)
                                },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = onGeminiKey,
                    )
                }
                val autoTranslateTitle = stringResource(R.string.auto_translate_lyrics)
                row(autoTranslateTitle, "lyrics", "translate", "auto") {
                    SettingsRow(
                        icon = Icons.Rounded.Translate,
                        title = autoTranslateTitle,
                        subtitle = stringResource(R.string.auto_translate_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = autoTranslateLyrics,
                                onCheckedChange = { AppSettings.setAutoTranslateLyrics(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAutoTranslateLyrics(!autoTranslateLyrics) },
                    )
                }
                val autoLyricsTimingTitle = stringResource(R.string.auto_lyrics_timing)
                row(autoLyricsTimingTitle, "lyrics", "timing", "sync", "video", "ai", "gemini") {
                    SettingsRow(
                        icon = Icons.Rounded.Timer,
                        title = autoLyricsTimingTitle,
                        subtitle = stringResource(R.string.auto_lyrics_timing_subtitle),
                        trailing = {
                            Switch(
                                checked = autoLyricsTiming,
                                onCheckedChange = { AppSettings.setAutoLyricsTiming(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAutoLyricsTiming(!autoLyricsTiming) },
                    )
                }
                val aiTimingAheadTitle = stringResource(R.string.ai_timing_ahead)
                row(aiTimingAheadTitle, "lyrics", "timing", "sync", "ai", "gemini", "ahead") {
                    SettingsRow(
                        icon = Icons.Rounded.Timer,
                        title = aiTimingAheadTitle,
                        subtitle = stringResource(R.string.ai_timing_ahead_subtitle),
                        trailing = {
                            Switch(
                                checked = aiTimingAhead,
                                onCheckedChange = { AppSettings.setAiTimingAhead(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAiTimingAhead(!aiTimingAhead) },
                    )
                }
                val resetDownloadSyncTitle = stringResource(R.string.reset_download_sync)
                row(resetDownloadSyncTitle, "lyrics", "timing", "sync", "download", "recheck") {
                    SettingsRow(
                        icon = Icons.Rounded.Cached,
                        title = resetDownloadSyncTitle,
                        subtitle = stringResource(R.string.reset_download_sync_subtitle),
                        trailing = { Chevron() },
                        onClick = onResetDownloadSync,
                    )
                }
                val deepgramTitle = stringResource(R.string.deepgram_api_key)
                row(deepgramTitle, "lyrics", "timing", "sync", "ai", "deepgram", "whisper", "key") {
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = deepgramTitle,
                        subtitle = stringResource(
                            when {
                                deepgramApiKey.isBlank() -> R.string.deepgram_subtitle_missing
                                deepgramKeyRejected -> R.string.deepgram_subtitle_rejected
                                AppSettings.deepgramKeyIsBundled -> R.string.deepgram_subtitle_bundled
                                else -> R.string.deepgram_subtitle_ready
                            },
                        ) + if (deepgramApiKey.length >= 8) " (••••${deepgramApiKey.takeLast(4)})" else "",
                        trailing = { Chevron() },
                        onClick = onDeepgramKey,
                    )
                }
                val aiLyricsTitle = stringResource(R.string.ai_lyrics)
                row(aiLyricsTitle, "lyrics", "ai", "gemini", "transcribe") {
                    SettingsRow(
                        icon = Icons.Rounded.Timer,
                        title = aiLyricsTitle,
                        subtitle = stringResource(R.string.ai_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = aiLyrics,
                                onCheckedChange = { AppSettings.setAiLyrics(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAiLyrics(!aiLyrics) },
                    )
                }
                val prefetchTranslationTitle = stringResource(R.string.prefetch_translation)
                row(prefetchTranslationTitle, "lyrics", "translate", "next", "prefetch") {
                    SettingsRow(
                        icon = Icons.Rounded.Cached,
                        title = prefetchTranslationTitle,
                        subtitle = stringResource(R.string.prefetch_translation_subtitle),
                        trailing = {
                            Switch(
                                checked = prefetchTranslation,
                                onCheckedChange = { AppSettings.setPrefetchTranslation(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setPrefetchTranslation(!prefetchTranslation) },
                    )
                }
                val keepDownloadTranslationTitle = stringResource(R.string.keep_download_translation)
                row(keepDownloadTranslationTitle, "lyrics", "translate", "download", "offline") {
                    SettingsRow(
                        icon = Icons.Rounded.Download,
                        title = keepDownloadTranslationTitle,
                        subtitle = stringResource(R.string.keep_download_translation_subtitle),
                        trailing = {
                            Switch(
                                checked = keepDownloadTranslation,
                                onCheckedChange = { AppSettings.setKeepDownloadTranslation(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setKeepDownloadTranslation(!keepDownloadTranslation) },
                    )
                }
                val lockScreenLyricsTitle = stringResource(R.string.lock_screen_lyrics)
                row(lockScreenLyricsTitle, "lyrics", "lock screen", "lockscreen") {
                    SettingsRow(
                        icon = Icons.Rounded.Lock,
                        title = lockScreenLyricsTitle,
                        subtitle = stringResource(
                            if (lockScreenLyrics && !canDrawOverlays) {
                                R.string.lock_screen_lyrics_needs_permission
                            } else {
                                R.string.lock_screen_lyrics_subtitle
                            },
                        ),
                        trailing = {
                            Switch(
                                checked = lockScreenLyrics && canDrawOverlays,
                                onCheckedChange = { on ->
                                    AppSettings.setLockScreenLyrics(on)
                                    if (on && !canDrawOverlays) openOverlayPermission()
                                },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = {
                            if (lockScreenLyrics && !canDrawOverlays) {
                                openOverlayPermission()
                            } else {
                                val on = !lockScreenLyrics
                                AppSettings.setLockScreenLyrics(on)
                                if (on && !canDrawOverlays) openOverlayPermission()
                            }
                        },
                    )
                }
            }
        }

        if (shows(SettingsCategory.PERFORMANCE)) SearchableSettingsGroup(search, header = stringResource(R.string.performance)) {
            val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
            row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
                SettingsRow(
                    icon = SfIcons.Performance,
                    title = highPerformanceModeTitle,
                    subtitle = if (highPerformanceMode) {
                        stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                    } else {
                        stringResource(R.string.high_performance_subtitle)
                    },
                    trailing = {
                        IosSwitch(
                            checked = highPerformanceMode,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showPerformanceWarning = true
                                } else {
                                    AppSettings.setHighPerformanceMode(false)
                                }
                            },
                        )
                    },
                    onClick = {
                        if (highPerformanceMode) {
                            AppSettings.setHighPerformanceMode(false)
                        } else {
                            showPerformanceWarning = true
                        }
                    },
                )
            }
            if (highPerformanceMode) {
                val refreshRateTitle = stringResource(R.string.refresh_rate)
                row(refreshRateTitle, "hz", "frame rate") {
                    SettingsRow(
                        icon = SfIcons.FrameRate,
                        title = refreshRateTitle,
                    )
                    SettingsSegmentedControl(
                        options = supportedRefreshRates.map { "$it Hz" },
                        selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                        onSelect = { index ->
                            AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                        },
                        modifier = Modifier.padding(
                            start = TEXT_INSET,
                            end = ROW_INSET,
                            bottom = 14.dp,
                        ),
                    )
                }
            }
        }

        if (shows(SettingsCategory.LOCAL_MUSIC)) SearchableSettingsGroup(search, header = stringResource(R.string.local_music)) {
            val localMusicFolderTitle = stringResource(R.string.local_music_folder)
            row(localMusicFolderTitle, "folder", "offline", "files") {
                SettingsRow(
                    icon = SfIcons.Folder,
                    title = localMusicFolderTitle,
                    subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                        ?: stringResource(R.string.all_audio_folders),
                    onClick = { localMusicFolderPicker.launch(null) },
                )
            }
            if (localMusicFolderUri.isNotBlank()) {
                val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
                row(useAllAudioFoldersTitle, "folder") {
                    SettingsRow(
                        icon = SfIcons.LibraryMusic,
                        title = useAllAudioFoldersTitle,
                        subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                        onClick = { AppSettings.setLocalMusicFolderUri("") },
                    )
                }
            }
            val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
            row(filterNonMusicAudioTitle, "podcast", "recording") {
                SettingsRow(
                    icon = SfIcons.FilterAlt,
                    title = filterNonMusicAudioTitle,
                    subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = filterNonMusicAudio,
                            onCheckedChange = AppSettings::setFilterNonMusicAudio,
                        )
                    },
                    onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
                )
            }
        }

        val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
        // The slider's last stop, one step past the largest fixed size, is
        // "Unlimited" — see [AppSettings.setAudioCacheLimitBytes].
        val cacheLimitMb = if (cacheUnlimited) CACHE_UNLIMITED_STOP_MB else (cacheLimitBytes / (1024 * 1024)).toInt()
        val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
        val cacheLimitLabel = if (cacheUnlimited) unlimitedLabel else formatCacheSize(cacheLimitMb)
        if (shows(SettingsCategory.STORAGE)) SearchableSettingsGroup(search, header = stringResource(R.string.storage)) {
            val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
            row(showCacheFolderTitle, "cache", "cached songs", "library", "folder") {
                SettingsRow(
                    icon = SfIcons.Cached,
                    title = showCacheFolderTitle,
                    trailing = {
                        IosSwitch(
                            checked = showCacheFolder,
                            onCheckedChange = AppSettings::setShowCacheFolder,
                        )
                    },
                    onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
                )
            }
            val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
            row(songCacheLimitTitle, "cache", "space", "unlimited") {
                SliderRow(
                    icon = SfIcons.Storage,
                    title = songCacheLimitTitle,
                    subtitle = when {
                        cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                        cacheLimitMb > CACHE_WARNING_MB ->
                            stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                        else -> stringResource(R.string.song_cache_limit_subtitle)
                    },
                    value = cacheLimitLabel,
                    sliderValue = cacheLimitMb.toFloat(),
                    onSliderValue = {
                        AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                    },
                    valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                        CACHE_UNLIMITED_STOP_MB.toFloat(),
                    steps = 19,
                )
            }
            val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
            row(clearSongCacheTitle, "cache", "free space") {
                SettingsRow(
                    icon = SfIcons.DeleteSweep,
                    title = clearSongCacheTitle,
                    subtitle = stringResource(R.string.clear_song_cache_subtitle),
                    onClick = { confirmClearSongCache = true },
                )
            }
            val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
            row(clearImageCacheTitle, "cache", "artwork", "free space") {
                SettingsRow(
                    icon = SfIcons.DeleteSweep,
                    title = clearImageCacheTitle,
                    subtitle = stringResource(R.string.clear_image_cache_subtitle),
                    onClick = {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // Only in a build that carries the watch app — see app/build.gradle.kts.
        // On the main page it is listed with the categories; here it answers
        // a search.
        if (searching && watchAvailable) {
            SearchableSettingsGroup(search) {
                row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                    WearOsSettingsRow(onClick = onWearOs)
                }
            }
        }

        if (shows(SettingsCategory.YOUR_DATA)) SearchableSettingsGroup(search, header = stringResource(R.string.your_data)) {
            val replayTitle = stringResource(R.string.replay)
            row(replayTitle, "stats", "history", "wrapped") {
                SettingsRow(
                    icon = SfIcons.BarChart,
                    title = replayTitle,
                    subtitle = stringResource(R.string.replay_subtitle),
                    onClick = onOpenReplay,
                )
            }
            val workOutGenresTitle = stringResource(R.string.work_out_genres)
            row(workOutGenresTitle, "genre", "replay") {
                val genresSupported = lastfmApiKey.isNotBlank()
                SettingsRow(
                    icon = SfIcons.LocalOffer,
                    title = workOutGenresTitle,
                    subtitle = stringResource(
                        when {
                            !genresSupported -> R.string.replay_genres_missing_key_subtitle
                            replayGenres -> R.string.replay_genres_enabled_subtitle
                            else -> R.string.replay_genres_disabled_subtitle
                        },
                    ),
                    enabled = genresSupported,
                    trailing = {
                        IosSwitch(
                            checked = replayGenres,
                            onCheckedChange = AppSettings::setReplayGenres,
                        )
                    },
                    onClick = { AppSettings.setReplayGenres(!replayGenres) },
                )
            }
            val exportDataTitle = stringResource(R.string.export_data)
            row(exportDataTitle, "backup") {
                SettingsRow(
                    icon = SfIcons.FileUpload,
                    title = exportDataTitle,
                    subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                    onClick = { exportPicker.launch(Backup.suggestedName()) },
                )
            }
            val importDataTitle = stringResource(R.string.import_data)
            row(importDataTitle, "backup", "restore") {
                SettingsRow(
                    icon = SfIcons.FileDownload,
                    title = importDataTitle,
                    subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                    onClick = { confirmImport = true },
                )
            }
            val sendSettingsTitle = stringResource(R.string.settings_transfer_send)
            row(sendSettingsTitle, "settings", "transfer", "share", "proxy") {
                SettingsRow(
                    icon = Icons.Rounded.IosShare,
                    title = sendSettingsTitle,
                    subtitle = settingsSendStatus
                        ?: stringResource(R.string.settings_transfer_send_subtitle),
                    onClick = { confirmSendSettings = true },
                )
            }
            val importSettingsTitle = stringResource(R.string.settings_transfer_import)
            row(importSettingsTitle, "settings", "transfer", "import", "proxy") {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = importSettingsTitle,
                    subtitle = settingsImportStatus
                        ?: stringResource(R.string.settings_transfer_import_subtitle),
                    onClick = {
                        settingsImportPicker.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"),
                        )
                    },
                )
            }
        }

        // No footer: it only restated the stop-on-close row's own subtitle,
        // which sits four rows above it and says the same thing in fewer words.
        if (shows(SettingsCategory.MISCELLANEOUS)) SearchableSettingsGroup(search, header = stringResource(R.string.miscellaneous)) {
            val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
            row(playNextOnSwipeTitle, "swipe", "queue") {
                SettingsRow(
                    icon = SfIcons.PlaylistPlay,
                    title = playNextOnSwipeTitle,
                    subtitle = if (swipeToPlayNext) {
                        stringResource(R.string.swipe_plays_next)
                    } else {
                        stringResource(R.string.swipe_adds_to_queue)
                    },
                    trailing = {
                        IosSwitch(
                            checked = swipeToPlayNext,
                            onCheckedChange = AppSettings::setSwipeToPlayNext,
                        )
                    },
                    onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
                )
            }
            val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
            row(dontRepeatSongsTitle, "radio", "suggestions") {
                SettingsRow(
                    icon = SfIcons.History,
                    title = dontRepeatSongsTitle,
                    subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = dontRepeatSuggestions,
                            onCheckedChange = AppSettings::setDontRepeatSuggestions,
                        )
                    },
                    onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
                )
            }
            val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
            row(stopMusicOnCloseTitle, "notification", "background") {
                SettingsRow(
                    icon = SfIcons.MusicOff,
                    title = stopMusicOnCloseTitle,
                    subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = stopOnTaskRemoved,
                            onCheckedChange = AppSettings::setStopOnTaskRemoved,
                        )
                    },
                    onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
                )
            }
            val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
            row(hideVolumeBarTitle, "volume", "player") {
                SettingsRow(
                    icon = SfIcons.VolumeOff,
                    title = hideVolumeBarTitle,
                    subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = hideVolumeBar,
                            onCheckedChange = AppSettings::setHideVolumeBar,
                        )
                    },
                    onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
                )
            }
            val listenTogetherButtonTitle = stringResource(R.string.show_listen_together_button)
            row(listenTogetherButtonTitle, "party", "jam", "output", "headphones", "player") {
                SettingsRow(
                    icon = SfIcons.Groups,
                    title = listenTogetherButtonTitle,
                    subtitle = stringResource(R.string.show_listen_together_button_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = listenTogetherButton,
                            onCheckedChange = AppSettings::setShowListenTogetherButton,
                        )
                    },
                    onClick = { AppSettings.setShowListenTogetherButton(!listenTogetherButton) },
                )
            }
            val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
            row(hideStatusBarTitle, "status bar", "immersive", "fullscreen", "player") {
                SettingsRow(
                    icon = SfIcons.WebAssetOff,
                    title = hideStatusBarTitle,
                    subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = hideStatusBarInPlayer,
                            onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                        )
                    },
                    onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
                )
            }
            val showQueueHistoryTitle = stringResource(R.string.show_queue_history)
            row(showQueueHistoryTitle, "player", "queue", "history", "previously played") {
                SettingsRow(
                    icon = SfIcons.History,
                    title = showQueueHistoryTitle,
                    subtitle = stringResource(R.string.show_queue_history_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = showQueueHistory,
                            onCheckedChange = AppSettings::setShowQueueHistory,
                        )
                    },
                    onClick = { AppSettings.setShowQueueHistory(!showQueueHistory) },
                )
            }
            val hideSongStatusTitle = stringResource(R.string.hide_song_status)
            row(hideSongStatusTitle, "player", "playing from", "played by") {
                SettingsRow(
                    icon = SfIcons.VisibilityOff,
                    title = hideSongStatusTitle,
                    subtitle = stringResource(R.string.hide_song_status_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = hideSongStatus,
                            onCheckedChange = AppSettings::setHideSongStatus,
                        )
                    },
                    onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
                )
            }
        }

        if (shows(SettingsCategory.LANGUAGE)) SearchableSettingsGroup(search, header = stringResource(R.string.language)) {
            val appLanguageTitle = stringResource(R.string.app_language)
            row(appLanguageTitle, "locale", "translate") {
                val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
                    ?: Locale.getDefault().toLanguageTag()
                SettingsRow(
                    icon = SfIcons.Language,
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

        if (shows(SettingsCategory.ADVANCED)) SearchableSettingsGroup(search, header = stringResource(R.string.advanced_options)) {
            val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
            row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
                SettingsRow(
                    icon = SfIcons.GraphicEq,
                    title = smartAlignmentTitle,
                    subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = smartVersionAlignment,
                            onCheckedChange = AppSettings::setSmartVersionAlignment,
                        )
                    },
                    onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
                )
            }
            val syncSafetyNextPrepTitle = stringResource(R.string.sync_safety_next_prep)
            row(syncSafetyNextPrepTitle, "lyrics", "sync", "next", "developer", "experimental") {
                SettingsRow(
                    icon = Icons.Rounded.Timer,
                    title = syncSafetyNextPrepTitle,
                    trailing = {
                        Switch(
                            checked = syncSafetyNextPrep,
                            onCheckedChange = AppSettings::setSyncSafetyNextPrep,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSyncSafetyNextPrep(!syncSafetyNextPrep) },
                )
            }
            val syncSafetyDownloadRecheckTitle = stringResource(R.string.sync_safety_download_recheck)
            row(syncSafetyDownloadRecheckTitle, "lyrics", "sync", "download", "recheck", "developer", "experimental") {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = syncSafetyDownloadRecheckTitle,
                    trailing = {
                        Switch(
                            checked = syncSafetyDownloadRecheck,
                            onCheckedChange = AppSettings::setSyncSafetyDownloadRecheck,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSyncSafetyDownloadRecheck(!syncSafetyDownloadRecheck) },
                )
            }
            val syncSafetyHqSyncTitle = stringResource(R.string.sync_safety_hq_sync)
            row(syncSafetyHqSyncTitle, "lyrics", "sync", "hq", "developer", "experimental") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = syncSafetyHqSyncTitle,
                    trailing = {
                        Switch(
                            checked = syncSafetyHqSync,
                            onCheckedChange = AppSettings::setSyncSafetyHqSync,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSyncSafetyHqSync(!syncSafetyHqSync) },
                )
            }
            val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
            row(showNerdStatsTitle, "debug", "bitrate", "codec") {
                SettingsRow(
                    icon = SfIcons.BarChart,
                    title = showNerdStatsTitle,
                    subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = nerdStats,
                            onCheckedChange = AppSettings::setShowNerdStats,
                        )
                    },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
            val appIconTitle = stringResource(R.string.app_icon)
            row(appIconTitle, "icon", "launcher", "logo") {
                var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
                SettingsRow(icon = SfIcons.Apps, title = appIconTitle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon.entries.forEach { option ->
                        val selected = option == selectedIcon
                        Column(
                            modifier = Modifier
                                .width(84.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedIcon = option
                                    AppIcon.apply(context, option)
                                }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // A glass foreground holds the finished icon in its own shape on the
                            // central 72 of 108 dp: scaled 1.5× it fills the tile as designed.
                            // Only the default goes through an adaptive mask.
                            val preview = option.previewRes
                            if (preview == null) {
                                AndroidView(
                                    factory = { ctx ->
                                        android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) }
                                    },
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(22.dp)),
                                )
                            } else {
                                Image(
                                    painter = painterResource(preview),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .scale(1.5f),
                                )
                            }
                            Text(
                                text = stringResource(option.labelRes),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Read after every group above has had its turn at the query, which is
        // what makes this an accurate "nothing here" rather than a guess.
        if (searching && !search.anyMatch) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }

        // The version line is the page's colophon, not a setting: it belongs to
        // the whole list, so it goes when the list is narrowed to a few rows.
        if (section == null && searchQuery.isBlank()) {
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

    // Everything in the Cached songs folder goes with it, so say so first.
    if (confirmClearSongCache) {
        AlertDialog(
            onDismissRequest = { confirmClearSongCache = false },
            title = { Text(stringResource(R.string.clear_song_cache_title)) },
            text = { Text(stringResource(R.string.clear_song_cache_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearSongCache = false
                    AudioCache.clear {
                        Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSongCache = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Asked before the picker opens rather than after a file is chosen: the
    // thing being confirmed is that this device's own history is about to be
    // thrown away, and that is true whichever file gets picked.
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = {
                Text(stringResource(R.string.import_backup_warning))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) {
                    Text(stringResource(R.string.choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // The password and any token in a source URL leave the phone in this file,
    // so say so before the share sheet opens rather than after somebody has
    // already sent it.
    if (confirmSendSettings) {
        AlertDialog(
            onDismissRequest = { confirmSendSettings = false },
            title = { Text(stringResource(R.string.settings_transfer_send)) },
            text = { Text(stringResource(R.string.settings_transfer_send_warning)) },
            confirmButton = {
                TextButton(onClick = sendSettings) {
                    Text(stringResource(R.string.settings_transfer_send_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSendSettings = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Nothing has been written yet: this is the list of what would change, and
    // the only button that writes is the one that says so.
    settingsPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { settingsPreview = null },
            title = { Text(stringResource(R.string.settings_transfer_preview_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.settings_transfer_preview_body))
                    if (!preview.hasChanges) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.settings_transfer_preview_none))
                    }
                    if (preview.sourcesChanged) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.settings_transfer_preview_sources,
                                preview.sources.size,
                                preview.sourceNames.joinToString(", "),
                            ),
                        )
                    }
                    if (preview.proxyChanged) {
                        Spacer(Modifier.height(12.dp))
                        val proxy = preview.proxy
                        Text(
                            stringResource(
                                R.string.settings_transfer_preview_proxy,
                                if (proxy == null || proxy.host.isBlank()) {
                                    stringResource(R.string.off)
                                } else {
                                    "${proxy.host}:${proxy.port}"
                                },
                            ),
                        )
                    }
                    if (preview.changedCategoryCounts.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        preview.changedCategoryCounts.forEach { (category, count) ->
                            Text(
                                stringResource(
                                    when (category) {
                                        TransferCategory.QUALITY -> R.string.settings_transfer_preview_quality
                                        TransferCategory.AUTOMIX -> R.string.settings_transfer_preview_automix
                                        TransferCategory.LYRICS -> R.string.settings_transfer_preview_lyrics
                                        TransferCategory.PLAYBACK -> R.string.settings_transfer_preview_playback
                                        TransferCategory.DISPLAY -> R.string.settings_transfer_preview_display
                                    },
                                    count,
                                ),
                            )
                        }
                    }
                    if (preview.skipped > 0) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.settings_transfer_preview_skipped,
                                preview.skipped,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { applySettingsImport(preview) }) {
                    Text(stringResource(R.string.settings_transfer_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { settingsPreview = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // The file is already chosen; the remaining question is only what happens
    // to the local playlists already on this device. Settings, listening and
    // search history are replaced either way.
    pendingImport?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_playlists_title)) },
            text = { Text(stringResource(R.string.import_playlists_choice)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = { applyImport(source, PlaylistImportMode.REPLACE) }) {
                        Text(stringResource(R.string.import_playlists_replace))
                    }
                    TextButton(onClick = { applyImport(source, PlaylistImportMode.MERGE) }) {
                        Text(stringResource(R.string.import_playlists_merge))
                    }
                }
            },
        )
    }

    if (showPerformanceWarning) {
        AlertDialog(
            onDismissRequest = { showPerformanceWarning = false },
            title = { Text(stringResource(R.string.high_performance_before_enabling)) },
            text = { Text(stringResource(R.string.high_performance_battery_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceWarning = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        val settingsIntent = intent.takeIf {
                            it.resolveActivity(context.packageManager) != null
                        } ?: Intent(Settings.ACTION_SETTINGS)
                        batterySettingsLauncher.launch(settingsIntent)
                    },
                ) {
                    Text(stringResource(R.string.open_battery_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPerformanceConfirmation) {
        AlertDialog(
            onDismissRequest = { showPerformanceConfirmation = false },
            title = { Text(stringResource(R.string.enable_high_performance_title)) },
            text = { Text(stringResource(R.string.enable_high_performance_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceConfirmation = false
                        AppSettings.setHighPerformanceMode(true)
                    },
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceConfirmation = false }) {
                    Text(stringResource(R.string.not_yet))
                }
            },
        )
    }

    if (showListenBrainzTokenDialog) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        AlertDialog(
            onDismissRequest = { showListenBrainzTokenDialog = false },
            title = { Text(stringResource(R.string.listenbrainz_token)) },
            text = {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text(stringResource(R.string.api_token)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzTokenDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showListenBrainzTokenDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLastfmLoginDialog) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!lastfmLoading) showLastfmLoginDialog = false },
            title = { Text(stringResource(R.string.lastfm_login)) },
            text = {
                Column {
                    if (lastfmError != null) {
                        Text(
                            text = lastfmError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lastfmLoading = true
                        lastfmError = null
                        scrobbleScope.launch {
                            try {
                                // Use the credentials supplied for this build.
                                LastFM.initialize(
                                    apiKey = AppSettings.lastfmApiKey.value,
                                    secret = AppSettings.lastfmSecret.value,
                                )
                                LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                    .onSuccess { auth ->
                                        AppSettings.setLastfmSessionKey(auth.session.key)
                                        AppSettings.setLastfmUsername(auth.session.name)
                                        AppSettings.setLastfmEnabled(true)
                                        showLastfmLoginDialog = false
                                    }
                                    .onFailure { e ->
                                        lastfmError = FriendlyError.message(context, e)
                                    }
                            } catch (e: Exception) {
                                lastfmError = FriendlyError.message(context, e)
                            } finally {
                                lastfmLoading = false
                            }
                        }
                    },
                    enabled = !lastfmLoading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                ) {
                    Text(stringResource(if (lastfmLoading) R.string.signing_in else R.string.sign_in))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLastfmLoginDialog = false }, enabled = !lastfmLoading) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showHiddenCategories) {
        AlertDialog(
            onDismissRequest = { showHiddenCategories = false },
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
                TextButton(onClick = { showHiddenCategories = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.quality_efficient
        AudioQuality.MEDIUM -> R.string.quality_high
        AudioQuality.HIGH -> R.string.quality_lossless
        AudioQuality.LOSSLESS -> R.string.quality_hi_res
    },
)

@Composable
private fun AudioQuality.localizedDetail(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.quality_efficient_detail
        AudioQuality.MEDIUM -> R.string.quality_high_detail
        AudioQuality.HIGH -> R.string.quality_lossless_detail
        AudioQuality.LOSSLESS -> R.string.quality_hi_res_detail
    },
)

@Composable
private fun DownloadQuality.localizedLabel(): String = stringResource(
    when (this) {
        DownloadQuality.STANDARD -> R.string.quality_efficient
        DownloadQuality.HIGH -> R.string.quality_high
        DownloadQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)

@Composable
private fun AppFont.localizedLabel(): String = stringResource(
    when (this) {
        AppFont.DEFAULT -> R.string.font_default
        AppFont.LEGACY -> R.string.font_legacy
    },
)

/** The typeface each [AppFont] sets, named under its label. */
@Composable
private fun AppFont.description(): String = stringResource(
    when (this) {
        AppFont.DEFAULT -> R.string.font_default_subtitle
        AppFont.LEGACY -> R.string.font_legacy_subtitle
    },
)

@Composable
private fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

/**
 * Hands the device's own effect panel this app's audio session.
 *
 * Shared with [EqualizerScreen], which offers the same escape hatch a second
 * time next to the equaliser it might be an alternative to.
 */
internal fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
    }
}

/** Above this, the cache limit slider's subtitle warns rather than reassures. */
private const val CACHE_WARNING_MB = 2048

/**
 * Where the cache limit slider's "Unlimited" stop sits: one 512MB step past
 * [AppSettings.MAX_CACHE_LIMIT_BYTES], so the fixed sizes keep their positions.
 */
private const val CACHE_UNLIMITED_STOP_MB = 10 * 1024 + 512

/** "512 MB", "2 GB", "2.5 GB" — whichever reads more naturally at that size. */
private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}

/** Who you're signed in as, straight from YouTube Music's account menu. */
@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onClick: (() -> Unit)? = null,
    /** Sits inside a [SettingsGroup] that already supplies the card, so draws none of its own. */
    grouped: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (grouped) {
                    Modifier
                } else {
                    Modifier
                        .padding(horizontal = GROUP_INSET)
                        .clip(GroupShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .then(
                when {
                    signedIn && onClick != null -> Modifier.clickable(onClick = onClick)
                    !signedIn -> Modifier.clickable(onClick = onSignIn)
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.name
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        if (signedIn) R.string.youtube_music_account else R.string.tap_to_sign_in_google,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

/**
 * A setting the bit-perfect output bypasses, faded and closed to touch while
 * that output is ready. With the mode on but its DAC unplugged the setting
 * works, and looks it.
 *
 * Only the touches that would change something are taken, so the page still
 * scrolls when a swipe starts on one of these rows: the press and the
 * release (no ripple, no click, no switch flip) and a sideways drag (no
 * slider). A vertical drag is left alone, and the list's scrolling still
 * starts from a press that was taken, so it scrolls as from any other row.
 */
@Composable
private fun BypassedByBitPerfect(bypassed: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (bypassed) 0.38f else 1f, label = "bypassed")
    // A Column, not a Box: a row can be several stacked pieces (a title row
    // and its control), and a Box would draw them over one another.
    Column(
        Modifier
            .alpha(alpha)
            .then(
                if (bypassed) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                            var sideways: Boolean? = null
                            var travel = Offset.Zero
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    when {
                                        change.changedToUpIgnoreConsumed() -> change.consume()
                                        change.positionChanged() -> {
                                            travel += change.positionChange()
                                            if (sideways == null && travel.getDistance() > viewConfiguration.touchSlop / 2) {
                                                sideways = abs(travel.x) > abs(travel.y)
                                            }
                                            if (sideways == true) change.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

@Composable
private fun BitPerfectMode.localizedLabel(): String = stringResource(
    when (this) {
        BitPerfectMode.OFF -> R.string.off
        BitPerfectMode.USB_ANDROID -> R.string.bit_perfect_mode_usb_android
        BitPerfectMode.DAP_EXCLUSIVE -> R.string.bit_perfect_mode_dap_exclusive
        BitPerfectMode.USB_DIRECT -> R.string.bit_perfect_mode_usb_direct
    },
)

/** Whether this phone can use [this] at all, independent of what is plugged in. */
private val BitPerfectMode.isSupported: Boolean
    get() = when (this) {
        BitPerfectMode.OFF -> true
        BitPerfectMode.USB_ANDROID -> Build.VERSION.SDK_INT >= 34
        BitPerfectMode.DAP_EXCLUSIVE -> Build.VERSION.SDK_INT >= 27
        BitPerfectMode.USB_DIRECT -> true
    }

@Composable
private fun BitPerfectMode.description(): String = stringResource(
    when {
        this == BitPerfectMode.USB_ANDROID && !isSupported -> R.string.bit_perfect_requires_android_14
        this == BitPerfectMode.DAP_EXCLUSIVE && !isSupported -> R.string.bit_perfect_requires_android_8_1
        this == BitPerfectMode.OFF -> R.string.bit_perfect_mode_off_subtitle
        this == BitPerfectMode.USB_ANDROID -> R.string.bit_perfect_mode_usb_android_subtitle
        this == BitPerfectMode.USB_DIRECT -> R.string.bit_perfect_mode_usb_direct_subtitle
        else -> R.string.bit_perfect_mode_dap_exclusive_subtitle
    },
)

/**
 * What the exclusive output is doing now, rather than what was asked of it:
 * a mode can be on while the playing track still goes through the mixer.
 */
@Composable
private fun bitPerfectSubtitle(mode: BitPerfectMode, status: BitPerfectOutput.Status): String = when {
    mode == BitPerfectMode.OFF || !mode.isSupported -> mode.description()
    status.active && status.dacLimited -> stringResource(
        R.string.bit_perfect_status_dac_limited,
        status.deviceName.orEmpty(),
        "%.1f".format(status.sampleRateHz / 1000.0),
        status.outputBits,
    )
    status.active && !status.exact -> stringResource(
        R.string.bit_perfect_status_inexact,
        status.deviceName.orEmpty(),
    )
    status.active -> stringResource(
        R.string.bit_perfect_status_active,
        status.deviceName.orEmpty(),
        "%.1f".format(status.sampleRateHz / 1000.0),
        status.outputBits,
    )
    status.fallbackReason != null -> stringResource(R.string.bit_perfect_status_unavailable, status.fallbackReason)
    else -> stringResource(R.string.bit_perfect_status_waiting)
}

// ---- Building blocks --------------------------------------------------------

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

/** Where a row's text starts — dividers are inset to match, as on iOS. */
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

/**
 * What is typed into the field under the Settings title, and whether anything
 * on the screen has answered to it.
 *
 * A blank query matches everything, so the screen with the field untouched is
 * the screen as it was before there was a field. Rows ask [matches] as they
 * are composed; the empty state at the foot of the list reads [anyMatch] after
 * every group has had its turn, which is why this is made fresh on each pass
 * rather than remembered.
 */
private class SettingsSearch(
    query: String,
    /** Drawing one category's page rather than the main one: its top bar names it. */
    val inPage: Boolean = false,
) {
    // Trimmed, because a trailing space is a typo rather than a search for
    // something ending in one — and on a phone keyboard it is one keystroke
    // away from every word typed.
    private val needle = query.trim()

    var anyMatch = false
        private set

    fun matches(vararg keywords: String?): Boolean {
        val hit = needle.isEmpty() ||
            keywords.any { it?.contains(needle, ignoreCase = true) == true }
        if (hit) anyMatch = true
        return hit
    }
}

/** Collects the rows of one group that survived the search. */
private class SettingsGroupScope(
    private val search: SettingsSearch,
    private val header: String?,
) {
    class Entry(val divided: Boolean, val content: @Composable () -> Unit)

    val entries = mutableListOf<Entry>()

    /**
     * One searchable setting. [keywords] is everything somebody might type
     * looking for it: its title at least, plus whatever its subtitle or the
     * controls under it say that the title doesn't — "Spotify", say, for the
     * canvas link that lives under Animated cover art.
     *
     * [divided] is false for the rows drawn as part of the row above them
     * rather than as settings in their own right, so they keep hugging their
     * parent when both survive the filter.
     */
    fun row(
        vararg keywords: String?,
        divided: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        // The header counts for every row beneath it: searching "playback"
        // should turn up the playback group entire, not nothing at all.
        if (search.matches(header, *keywords)) entries += Entry(divided, content)
    }
}

/**
 * A [SettingsGroup] whose rows are filtered by the search field, and which
 * takes itself off the screen when none of them are left. Dividers fall
 * between the rows that survived rather than around the gaps left by the ones
 * that didn't, so a filtered card is indistinguishable from a hand-written one.
 *
 * Inline so [content] is not a restartable lambda of its own. It only collects
 * rows into [SettingsGroupScope.entries], and drawing them happens here: as its
 * own recompose scope, a setting it reads — Automix hiding the crossfade
 * slider, say — re-ran it alone, filling a list nothing redrew, and the row
 * only came or went whenever something else recomposed the screen.
 */
@Composable
private inline fun SearchableSettingsGroup(
    search: SettingsSearch,
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope(search, header)
    scope.content()
    if (scope.entries.isEmpty()) return
    // A category's page is titled by its large heading; the header over its
    // first card would only say the same word again. The card takes the
    // header's place, straight under the heading, as on Account's page.
    val inPageHeader = header != null && search.inPage
    SettingsGroup(
        header = header.takeUnless { inPageHeader },
        footer = footer,
        topSpacing = if (inPageHeader) 0.dp else topSpacing,
    ) {
        scope.entries.forEachIndexed { index, entry ->
            when {
                index > 0 && entry.divided -> RowDivider()
                // An undivided row is drawn tight to the top because something
                // it belongs to is usually above it. Filtered down to itself it
                // has nothing to sit under, so it is given that room back.
                index == 0 && !entry.divided -> Spacer(Modifier.height(10.dp))
            }
            entry.content()
        }
    }
}

/**
 * The large heading a Settings page leads with, under the bar's own small one —
 * the main page's "Settings", and every page opened from it.
 */
@Composable
internal fun SettingsPageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
    )
}

/**
 * One inset card of rows, with an uppercase header above and an optional
 * plain-language [footer] below. Rows are separated by [RowDivider].
 */
@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    /** Room above the card, where there is no [header] to provide it. */
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The one switch palette every settings toggle uses.
 *
 * Material's off track is the card's own container colour, so a row's switch
 * all but disappears against it. The outline grey keeps the track visible at
 * rest, the way iOS's off state does, while the checked colours stay the
 * app's accent.
 */
@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = IosSwitchGreen,
    checkedBorderColor = IosSwitchGreen,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = IosSwitchGrey,
    uncheckedBorderColor = IosSwitchGrey,
    uncheckedThumbColor = Color.White,
)

/** iOS's switch palette: green when on, graphite when off, white knob in both. */
private val IosSwitchGreen = Color(0xFF34C759)
private val IosSwitchGrey = Color(0xFF39393D)

/**
 * A row's explanation, a clear step below its title: four-fifths of the title
 * size with a tight leading, so one-line subtitles stay compact.
 */
@Composable
private fun settingsSubtitleStyle() = MaterialTheme.typography.bodyLarge.copy(
    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 0.96f,
)

/**
 * The standard row: glyph, title, optional subtitle, and on the right either
 * [trailing] (a switch, say) or the current [value] followed by a chevron.
 *
 * [iconPainter] is for the handful of rows whose glyph is a drawable rather
 * than a Material icon — the Dolby double-D, which is a mark and not something
 * to approximate with the nearest speaker outline. Exactly one of it and [icon]
 * is expected; the painter wins where both are given.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = AppSpacing.ListRowMinHeight)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every row starts its text at the same x, whether or not it has a
        // glyph: a row without one keeps an empty slot the size of the icon
        // instead of pulling its title left.
        Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
            if (iconPainter != null) {
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(ICON_SIZE),
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 */
@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The empty slot a full row's icon would occupy, so a nested toggle's
        // label lines up with the text above it.
        Spacer(Modifier.width(ICON_SIZE + ICON_GAP))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** Marks the connection whose ceiling is actually in force right now. */
@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        // A badge is one short word; squeezed by a long trailing value it must
        // not break into two stacked lines (사용 / 중).
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        SfIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

/** A continuous setting: label and current value on one line, track beneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            // Bare track: the step ticks and the end-stop dot are noise when the
            // value is already spelled out on the line above.
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

/** Sign out: centered, accent-coloured, no glyph — the shape of a real one. */
@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Sliding pill selector, for the handful of settings with two or three states —
 * and the Search tab's source switcher. See [PillSegmentedControl].
 */
@Composable
internal fun SettingsSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PillSegmentedControl(options, selectedIndex, onSelect, modifier, enabled)
}

// ---- Pages ------------------------------------------------------------------

/**
 * The groups of Settings that are pages of their own, in the order the main
 * page lists them. Account, Listen Together and Audio quality aren't here:
 * they stay on the main page, where they always were.
 */
enum class SettingsCategory(@param:StringRes val title: Int) {
    DOWNLOADS(R.string.downloads),
    PLAYBACK(R.string.playback),
    APPEARANCE(R.string.appearance),
    PERFORMANCE(R.string.performance),
    LOCAL_MUSIC(R.string.local_music),
    STORAGE(R.string.storage),
    YOUR_DATA(R.string.your_data),
    MISCELLANEOUS(R.string.miscellaneous),
    LANGUAGE(R.string.language),
    ADVANCED(R.string.advanced_options),
    ;

    val icon: ImageVector
        get() = when (this) {
            DOWNLOADS -> SfIcons.Download
            PLAYBACK -> SfIcons.PlayCircle
            APPEARANCE -> SfIcons.Appearance
            PERFORMANCE -> SfIcons.Performance
            LOCAL_MUSIC -> SfIcons.LibraryMusic
            STORAGE -> SfIcons.Storage
            YOUR_DATA -> SfIcons.YourData
            MISCELLANEOUS -> SfIcons.Miscellaneous
            LANGUAGE -> SfIcons.Language
            ADVANCED -> SfIcons.Advanced
        }
}

/** The settings whose options are a page of their own — see [SettingsChoicePage]. */
enum class SettingsChoiceKind(@param:StringRes val title: Int) {
    AUDIO_QUALITY_WIFI(R.string.on_wifi),
    AUDIO_QUALITY_CELLULAR(R.string.on_mobile_data),
    DOWNLOAD_QUALITY(R.string.download_quality),
    AUTOMIX_PERFORMANCE(R.string.automix_performance),
    BIT_PERFECT(R.string.bit_perfect_output),
    FONT(R.string.font),
}

/** The main page's list of categories: a glyph and a name each, and a chevron into it. */
@Composable
private fun SettingsCategoryList(onOpen: (SettingsCategory) -> Unit) {
    SettingsGroup {
        SettingsCategory.entries.forEachIndexed { index, category ->
            if (index > 0) RowDivider()
            SettingsRow(
                icon = category.icon,
                title = stringResource(category.title),
                onClick = { onOpen(category) },
            )
        }
    }
}

/**
 * One of [SettingsChoiceKind]'s settings as a page: its options in a card,
 * the one in force ticked, and what the choice is about in the footer. A pick
 * is saved at once and [onChosen] then leaves the page, as the drawer this
 * replaces closed on one.
 */
@Composable
fun SettingsChoicePage(
    choice: SettingsChoiceKind,
    onChosen: () -> Unit,
    /** Asks before a bit-perfect mode is turned on — see [SettingsScreen]. */
    onConfirmBitPerfect: (BitPerfectMode) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(choice.title))
        when (choice) {
            SettingsChoiceKind.AUDIO_QUALITY_WIFI, SettingsChoiceKind.AUDIO_QUALITY_CELLULAR -> {
                val wifi = choice == SettingsChoiceKind.AUDIO_QUALITY_WIFI
                val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
                val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
                ChoiceGroup(
                    // Best first — the option most people want shouldn't be last.
                    options = AudioQuality.entries.reversed(),
                    selected = if (wifi) wifiQuality else cellularQuality,
                    label = { it.localizedLabel() },
                    detail = { stringResource(R.string.quality_hourly, it.detail, it.hourly) },
                    footer = stringResource(
                        R.string.audio_quality_connection,
                        stringResource(if (wifi) R.string.wifi else R.string.mobile_data).lowercase(Locale.getDefault()),
                    ),
                ) { quality ->
                    // Writes the one ceiling being edited and nothing else —
                    // which sources a rung consults is read per stream off the
                    // connection in force; see [AudioQuality.permits].
                    if (wifi) AppSettings.setAudioQualityWifi(quality) else AppSettings.setAudioQualityCellular(quality)
                    onChosen()
                }
            }

            // Priced per track rather than per hour, unlike the streaming
            // ceilings: a stream is paid for again on every replay, a download
            // once. Same list, different question.
            SettingsChoiceKind.DOWNLOAD_QUALITY -> {
                val selected by AppSettings.downloadQuality.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = DownloadQuality.entries.reversed(),
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { stringResource(R.string.quality_per_track, it.detail, it.perTrack) },
                    footer = stringResource(R.string.download_quality_dialog_subtitle),
                ) { quality ->
                    AppSettings.setDownloadQuality(quality)
                    onChosen()
                }
            }

            SettingsChoiceKind.AUTOMIX_PERFORMANCE -> {
                val selected by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = AutomixPerformanceMode.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = {
                        stringResource(
                            when (it) {
                                AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                                AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                                AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                            },
                        )
                    },
                    footer = stringResource(R.string.automix_performance_warning),
                ) { mode ->
                    AppSettings.setAutomixPerformanceMode(mode)
                    onChosen()
                }
            }

            SettingsChoiceKind.BIT_PERFECT -> {
                val selected by AppSettings.bitPerfectMode.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = BitPerfectMode.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { it.description() },
                    footer = stringResource(R.string.bit_perfect_sheet_warning),
                    enabled = { it.isSupported },
                ) { mode ->
                    onChosen()
                    // Leaving the mode needs no warning; entering it hands
                    // volume to the DAC, at full scale.
                    if (mode == BitPerfectMode.OFF) AppSettings.setBitPerfectMode(mode) else onConfirmBitPerfect(mode)
                }
            }

            SettingsChoiceKind.FONT -> {
                val selected by AppSettings.appFont.collectAsStateWithLifecycle()
                ChoiceGroup(
                    options = AppFont.entries,
                    selected = selected,
                    label = { it.localizedLabel() },
                    detail = { it.description() },
                    footer = stringResource(R.string.font_footer),
                ) { font ->
                    AppSettings.setAppFont(font)
                    onChosen()
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * A card of options, one of them ticked: the body of every choice page. A
 * label and a dimmer line under it per option; [enabled] greys out the ones
 * this device can't take.
 */
@Composable
internal fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    footer: String?,
    detail: (@Composable (T) -> String)? = null,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Every page it is the body of leads with its large heading — see
    // [SettingsPageTitle], whose own bottom padding is all the room it needs.
    SettingsGroup(footer = footer, topSpacing = 0.dp) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val available = enabled(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = available) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(option)
                    }
                    .alpha(if (available) 1f else 0.45f)
                    .heightIn(min = 52.dp)
                    .padding(horizontal = ROW_INSET, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    detail?.let {
                        Text(
                            text = it(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (option == selected) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        SfIcons.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
