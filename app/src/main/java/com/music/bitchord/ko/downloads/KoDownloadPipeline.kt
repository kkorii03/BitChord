package com.music.bitchord.ko.downloads

import android.content.Context
import androidx.core.net.toUri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.DownloadedMvPolicy
import com.music.bitchord.data.net.HqAutoDownloadPolicy
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.DownloadedRecordingPolicy
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.DownloadHttpException
import com.music.bitchord.download.Downloader
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.ManifestFile
import com.music.bitchord.download.OfflineDash
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.download.downloadBadge
import com.music.bitchord.ko.KoThinRendition
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.flow.asSharedFlow

/** The fork's download pipeline pieces that are not tied to the upstream flow. */
internal object KoDownloadPipeline {

    private const val TAG = "BitChord"

    /** The pass has parked this host until the time it named. */
    class AutoDownloadDeferred(val waitMs: Long, message: String = "server busy") : Exception(message)

    /** What an automatic download's exception means to the high-quality pass. */
    fun automaticFailure(error: Exception): DownloadFailure {
        val deferred = error as? AutoDownloadDeferred
        val http = error as? DownloadHttpException
        val code = http?.code ?: HqAutoDownloadPolicy.statusCode(error.message)
        return DownloadFailure(
            transient = deferred != null ||
                HqAutoDownloadPolicy.isTransientFailure(code, error.message),
            retryAfterHeader = http?.retryAfter,
            retryAfterMs = deferred?.waitMs,
        )
    }

    /** The 429/503 status behind this failure, when it is the server asking for time. */
    fun rateLimitCode(error: Throwable): Int? {
        val code = (error as? DownloadHttpException)?.code
            ?: HqAutoDownloadPolicy.statusCode(error.message)
        return code?.takeIf(HqRetryPolicy::isRateLimited)
    }

    /**
     * The route for the exact pair a sound comparison accepted.
     *
     * Deliberately not the ordinary source search: that can land on a
     * different row under the same title. The verdict names one source and one
     * track id, and the replacement has to be that stream or nothing. A source
     * that now answers with a lower rung than was proved is refused too, so a
     * failed catalogue cannot quietly downgrade the file it was meant to
     * upgrade.
     */
    suspend fun routeForVerified(
        context: Context,
        track: Song,
        quality: DownloadQuality,
        hq: HqDownloadSource,
    ): Downloads.Route {
        val stream = SourceResolver.forDownloadVerified(
            sourceConfigId = hq.sourceConfigId,
            trackId = hq.trackId,
            request = SourceResolver.requestForDownload(quality),
        ) ?: error("검증된 고음질 음원을 받을 수 없습니다")
        val served = HqQuality.tierOf(stream.format)
        if (served.rank > hq.tier.rank) {
            error("검증된 고음질 음원이 더 낮은 등급으로 응답했습니다 (${served.label})")
        }
        val storable = DownloadStore.storable(stream.format.codec)
            ?: error("이 기기에 저장할 수 없는 형식입니다 (${stream.format.codec})")
        val probe = Downloader.probe(stream.url, stream.headers, stream.format.codec)
        // [KO] violin: a "320 kbps" row that serves a 96 kbps file is not the upgrade it was stored as.
        val bytes = (probe as? Downloader.Probe.File)?.totalBytes ?: 0L
        if (KoThinRendition.thin(stream.format, stream.durationSec ?: TrackMatcher.secondsOf(track.durationText), bytes)) {
            error("검증된 고음질 음원의 파일이 표시된 음질보다 작습니다 ($bytes B)")
        }
        return routeFromSource(context, track, stream, storable, probe, recordingVerified = true)
            ?: error("고음질 음원을 내보낼 수 없습니다")
    }

    /**
     * The shape a source URL really is, from the probe when one answered and
     * from the URL otherwise. A source may label an extensionless HLS/DASH
     * stream as FLAC; only the bytes (or the catalogued extension) say it is an
     * index rather than audio.
     */
    class ManifestShape(val packaged: Boolean, val dash: Boolean)

    fun manifestShape(url: String, probe: Downloader.Probe?): ManifestShape = when (probe) {
        // The bytes decide. A server can serve an ordinary file from a `.mpd`
        // or `.m3u8` URL, and as a file it must stay one.
        is Downloader.Probe.Manifest -> ManifestShape(packaged = true, dash = probe.dash)
        is Downloader.Probe.File -> ManifestShape(packaged = false, dash = false)
        null -> {
            val dash = OfflineDash.handles(url)
            val hls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            ManifestShape(packaged = hls || dash, dash = dash)
        }
    }

    /**
     * Turn an open source stream into a file route, or null when it cannot be
     * filed where this download is going (an exported manifest, for instance).
     */
    fun routeFromSource(
        context: Context,
        track: Song,
        stream: SourceStream,
        storable: DownloadStore.Storable,
        probe: Downloader.Probe? = null,
        recordingVerified: Boolean = false,
    ): Downloads.Route? {
        // A probed file is named for what its bytes are, not what the source
        // called it: an AAC/MP4 served as FLAC must not be saved as `.flac`
        // and later trusted for its sound alone. A manifest has no single
        // container yet, so only [Downloader.Probe.File] is checked.
        if (probe is Downloader.Probe.File &&
            !DownloadedRecordingPolicy.containerMatches(storable.extension, probe.container.extension)
        ) {
            Log.d(
                TAG,
                "${stream.format.summary}: bytes are .${probe.container.extension}, " +
                    "not .${storable.extension}; passing",
            )
            return null
        }
        // A manifest is an index, not audio. Whichever kind it is, fetching
        // it as a file writes the index into something named `.flac` —
        // which is exactly what a `.mpd` did until [OfflineDash] existed:
        // a 2.7 KB download that reported success and could never play.
        // The probe reads the bytes, so an extensionless manifest is caught
        // too; the URL is the fallback for a caller that did not probe.
        val shape = manifestShape(stream.url, probe)
        val dash = shape.dash
        val packaged = shape.packaged
        // A manifest's segments are joined back into one ordinary file —
        // see [ManifestFile] — exported or not, so a lossless download is
        // a `.flac` wherever it lands. Exports used to decline manifests
        // instead, which meant YouTube: Tidal serves every lossless tier
        // as a manifest, so an exporting user heard FLAC and saved 131kbps
        // AAC. Only a codec the assembler can't file falls through to the
        // package (in-app) or YouTube (export) below.
        if (packaged && storable.extension in ManifestFile.EXTENSIONS) {
            return Downloads.Route(
                extension = storable.extension,
                mimeType = storable.mimeType,
                describe = "${stream.format.summary} (assembled from ${if (dash) "DASH" else "HLS"})",
                host = HqHostTracker.hostOf(stream.url),
                downloadFormat = stream.format.downloadBadge(),
                tier = HqQuality.tierOf(stream.format),
                recordingVerified = recordingVerified,
                sourceTitle = stream.trackTitle,
                sourceArtist = stream.trackArtist,
                write = { sink, onProgress ->
                    ManifestFile.write(
                        context.cacheDir, stream.url, stream.headers, dash,
                        storable.extension, sink, onProgress,
                    )
                },
            )
        }
        if (packaged && AppSettings.exportDownloads.value) {
            Log.d(TAG, "can't export a .${storable.extension} manifest; taking YouTube for ${track.videoId}")
            return null
        }
        // dupdl: the player filling this very file now hands its copy over instead of a second GET.
        // F2: asked once — by the automatic pass before it takes the host slot, else by the write.
        val playersFill = com.music.bitchord.ko.KoPlayersFill.Asked<AudioCache.CompleteCopy> {
            if (packaged) null else cachedSourceCopy(track.videoId, stream, storable, probe)
                ?: AudioCache.playersFillOf(track.videoId, stream.url, storable.extension)
        }
        return Downloads.Route(
            // DASH is saved *as* HLS — see [OfflineDash] for why — so both
            // kinds land as the same package and are named for what was
            // written rather than for what was fetched.
            extension = if (packaged) "m3u8" else storable.extension,
            mimeType = if (packaged) "application/vnd.apple.mpegurl" else storable.mimeType,
            describe = stream.format.summary,
            host = HqHostTracker.hostOf(stream.url),
            downloadFormat = stream.format.downloadBadge(),
            tier = HqQuality.tierOf(stream.format),
            recordingVerified = recordingVerified,
            sourceTitle = stream.trackTitle,
            sourceArtist = stream.trackArtist,
            taggable = true,
            offlineHls = Downloads.Manifest(stream.url, stream.headers, dash = dash).takeIf { packaged },
            write = { sink, onProgress ->
                val fill = playersFill.get()
                if (fill != null) {
                    AudioCache.copyOut(fill, sink, onProgress)
                } else {
                    Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
                }
            },
            beforeHostSlot = { playersFill.get() },
        )
    }

    /**
     * [KO] w1120-battery: the song cache's whole copy of this very source file
     * (an upgrade's `#hifi` fill, say), or null to fetch; see [com.music.bitchord.ko.KoSourceCopy].
     * Only for a probed file whose server stated its length, from a source that allows downloads.
     */
    internal suspend fun cachedSourceCopy(
        videoId: String,
        stream: SourceStream,
        storable: DownloadStore.Storable,
        probe: Downloader.Probe?,
    ): AudioCache.CompleteCopy? {
        val file = probe as? Downloader.Probe.File ?: return null
        val configId = stream.sourceConfigId ?: return null
        if (com.music.bitchord.data.sources.SourceRegistry.config(configId)?.allowDownloads == false) return null
        val copy = AudioCache.sourceCopyOf(videoId, configId, stream.trackId, stream.format.codec, file.totalBytes)
            ?: return null
        if (!DownloadedRecordingPolicy.containerMatches(storable.extension, copy.extension)) {
            Log.d(TAG, "download: the cached copy is .${copy.extension}, not .${storable.extension}; fetching ($videoId)")
            return null
        }
        com.music.bitchord.data.TrackLog.d(
            TAG,
            "download: copying the cached copy of the same source file (${copy.key}, ${copy.length} B); no second fetch",
            about = videoId,
        )
        return copy
    }
    data class PreviousRecordState(
        val uris: Set<String>,
        val suspects: List<String>,
        /** Per id: where the old file's bytes came from, read before the new record ([repairReplacesUpload]). */
        val origins: Map<String, FileOrigin> = emptyMap(),
        /** [KO] u1121 LIBRARY-3: per id, the old file's verified pair (source, track); null for YouTube's bytes. */
        val pairs: Map<String, Pair<String, String?>?> = emptyMap(),
        /** [KO] l1121 CLOCK-1: per id, the (offset, rate) the old file's record carries for that pair. */
        val clocks: Map<String, Pair<Long, Double>> = emptyMap(),
    )

    /**
     * [KO] u1121 LIBRARY-3: a saved file of [ids] now lives at [newUri]; [oldUris]
     * named the files it replaced. Collected by the playback service
     * ([com.music.bitchord.ko.koOnSavedFileReplaced]), which moves a player still
     * reading an old file onto the new one: the record, the song check and the
     * lyric clock already describe the new file.
     */
    data class FileReplaced(
        val ids: Set<String>,
        val oldUris: Set<String>,
        val newUri: String,
        val oldPairs: Map<String, Pair<String, String?>?>,
        /** [KO] l1121 CLOCK-1: the old records' own (offset, rate), by id; see [PreviousRecordState.clocks]. */
        val oldClocks: Map<String, Pair<Long, Double>> = emptyMap(),
    ) {
        /** The pair the old file of [id] was verified as; an alias id takes the one pair all ids shared. */
        fun oldPairOf(id: String): Pair<String, String?>? =
            if (id in oldPairs) oldPairs[id] else oldPairs.values.distinct().singleOrNull()

        /**
         * [KO] l1121 CLOCK-1: the (offset, rate) of the old file [id] was reading; null for
         * YouTube's bytes. The verdict store is keyed by the song id, so an alias (a music
         * video row on the song's file) finds nothing under its own id and started the move
         * as if the old file ran on YouTube's clock: off by the old file's whole offset.
         * Asks [verdict] under [id], then under the ids that owned the pair, then reads
         * their old records.
         */
        fun oldClockOf(id: String, verdict: (owner: String, pair: Pair<String, String?>) -> Pair<Long, Double>?): Pair<Long, Double>? {
            val pair = oldPairOf(id) ?: return null
            val owners = listOf(id) + oldPairs.filter { (owner, value) -> owner != id && value == pair }.keys
            return owners.firstNotNullOfOrNull { verdict(it, pair) } ?: owners.firstNotNullOfOrNull { oldClocks[it] }
        }
    }

    private val _replaced = kotlinx.coroutines.flow.MutableSharedFlow<FileReplaced>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val replaced: kotlinx.coroutines.flow.SharedFlow<FileReplaced> = _replaced.asSharedFlow()

    /**
     * Whether the playing item [playingId], reading [playingUri], is still on a file [event] replaced.
     * An .m4a record plays through its file path, not its content:// uri (PlayerConnection's
     * resolvePlaybackUri), so a file:// uri of a replaced id counts as well: when it is the new
     * file's own path, koUseVerifiedDownload finds the same uri and declines.
     */
    fun movesPlayer(event: FileReplaced, playingId: String?, playingUri: String?): Boolean =
        playingId != null && playingId in event.ids &&
            playingUri != null && playingUri != event.newUri &&
            (playingUri in event.oldUris || playingUri.startsWith("file://"))

    /** What the record of one id says about the upload its file was fetched from. */
    data class FileOrigin(
        /** The recorded provenance, null on a legacy record. */
        val soundSourceId: String?,
        /** Every id filed against the same file, this one included. */
        val fileIds: Set<String>,
        /** The song side of the file ([KoDownloadRecords.songIdForFile]). */
        val songIdOfFile: String,
        /**
         * RG5-3: [songIdOfFile] is itself recorded as the music video (the song
         * row had no length, so the video won the shortest-length pick). Its
         * own stream is then the MV sound, not the fix.
         */
        val songSideIsVideo: Boolean = false,
    )

    /** The old files this record named, and the length verdicts that pointed at them. */
    fun previousStateFor(ids: Set<String>, uri: android.net.Uri): PreviousRecordState {
        val saved = Downloads.savedValue()
        val metadata = Downloads.savedMetadataValue()
        val previousUris = ids.mapNotNull { id ->
            metadata[id]?.uri ?: saved[id]
        }.toSet()
        val previousSuspects = KoDownloadRecords.suspectsSnapshot().keys.filter { id ->
            val old = metadata[id]?.uri ?: saved[id]
            old != null && (old == uri.toString() || old in previousUris)
        }
        val origins = ids.mapNotNull { id -> KoDownloadRecords.fileOriginFor(id)?.let { id to it } }.toMap()
        val pairs = ids.mapNotNull { id ->
            metadata[id]?.let { meta -> id to meta.hqSourceConfigId?.takeIf { it.isNotBlank() }?.let { it to meta.hqTrackId } }
        }.toMap()
        val clocks = ids.mapNotNull { id ->
            metadata[id]?.let { meta -> com.music.bitchord.ko.KoRecordedClock.fileAlignment(null, meta)?.let { id to it } }
        }.toMap()
        return PreviousRecordState(previousUris, previousSuspects, origins, pairs, clocks)
    }

    /**
     * Review SW7-1: whether [existing] is the file one of [ids] is recorded
     * under, read the way [previousStateFor] reads it. Only then may a
     * replacement write back over the plain name; a same-named file recorded
     * under a different upload's id is that other song's file.
     */
    fun existingIsOwnFile(ids: Set<String>, existing: android.net.Uri?): Boolean {
        if (existing == null) return false
        val saved = Downloads.savedValue()
        val metadata = Downloads.savedMetadataValue()
        return recordsName(ids, existing.toString()) { id -> metadata[id]?.uri ?: saved[id] }
    }

    internal fun recordsName(ids: Set<String>, existing: String, recordedUri: (String) -> String?): Boolean =
        ids.any { id -> id.isNotBlank() && recordedUri(id) == existing }

    /**
     * Whether fetching [fetchedId] for [askedId] replaces the upload the old
     * file was made from — what an MV sound repair must do, and what a
     * finished download must prove before it clears the suspect.
     *
     * Compared with the file's own upload, not the asked id (review D5).
     * Recorded provenance names it. A legacy file filed under a music video
     * and its song carries none, and the length verdict that made it a suspect
     * says its bytes are the video's: a repair queued as the song id that
     * fetches that id's own stream is exactly the fix, yet "fetched == asked"
     * refused it forever. A file under one id only ([origin] null or no other
     * id) keeps the old rule: a different id is required.
     */
    fun repairReplacesUpload(askedId: String, fetchedId: String, origin: FileOrigin?): Boolean {
        if (askedId.isBlank() || fetchedId.isBlank()) return false
        val source = origin?.soundSourceId?.takeIf { it.isNotBlank() }
        if (source != null) return fetchedId != source
        if (DownloadedMvPolicy.repairFetchedSongVersion(askedId, fetchedId)) return true
        return origin != null &&
            askedId == origin.songIdOfFile &&
            !origin.songSideIsVideo &&
            origin.fileIds.any { it != askedId }
    }

    /**
     * Whether what was measured against the old file (AI line timing, heard
     * words, the automatic recheck sheet, the "already checked" mark, version
     * alignments) must be dropped for the new one.
     *
     * A new path proves new bytes. So does a deliberate replacement ([replacing]:
     * a verified hq pair or forceReplace) even when it was written back over the
     * same path — app-private and legacy downloads rename the staged part over
     * the old file, so the uri does not change. A plain adopt of the file that
     * is already there rewrote nothing and keeps its answers.
     */
    fun dropsOldTiming(
        previousUris: Set<String>,
        newUri: String,
        replacing: Boolean,
        suspects: List<String>,
    ): Boolean = previousUris.any { it != newUri } || replacing || suspects.isNotEmpty()

    /**
     * [videoId]'s file now holds other bytes (or is gone): version alignments
     * measured against the old recording must not be reused for it — see
     * [com.music.bitchord.playback.smart.VersionAudioAligner.forget].
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun forgetAlignment(videoId: String) {
        com.music.bitchord.playback.smart.VersionAudioAligner.forget(videoId)
    }

    /**
     * [KO] l1121 CLOCK-1: the other ids (a music video and its song) whose record named a
     * file that [ids] just replaced: those still on an old path, and, when a replacement
     * was written back over the same path, those already on it. A plain adopt of the file
     * already there rewrote nothing and touches none.
     */
    internal fun aliasesOfReplacedFile(
        saved: Map<String, String>,
        ids: Set<String>,
        previousUris: Set<String>,
        newUri: String,
        replacing: Boolean,
    ): Set<String> = saved
        .filter { (id, value) -> id !in ids && value in previousUris && (value != newUri || replacing) }
        .keys

    /**
     * [KO] l1121 CLOCK-1: [alias] shares the file the new record [file] describes. The
     * fields that describe the file follow it — its format and stored name, the hq pair
     * and alignment the lyric clock reads ([com.music.bitchord.playback.smart.LyricsTiming.playingClock],
     * [com.music.bitchord.ko.KoRecordedClock]), the measured tier and the sound-check
     * verdict. Kept, the alias played the new bytes on the old file's clock. The row's own
     * title, artist, type, album, artwork and dates stay.
     */
    internal fun aliasOfReplacedFile(
        alias: SavedSongMetadata,
        file: SavedSongMetadata?,
        uri: String,
        soundSourceVideoId: String,
    ): SavedSongMetadata {
        val moved = alias.copy(uri = uri, soundSourceVideoId = soundSourceVideoId)
        if (file == null || file.uri != uri) return moved
        return moved.copy(
            displayName = file.displayName,
            downloadFormat = file.downloadFormat,
            hqSourceConfigId = file.hqSourceConfigId,
            hqTrackId = file.hqTrackId,
            hqOffsetMs = file.hqOffsetMs,
            hqRate = file.hqRate,
            hqTier = file.hqTier,
            hqSourceTitle = file.hqSourceTitle,
            hqSourceArtist = file.hqSourceArtist,
            recordingVerified = file.recordingVerified,
            wrongRecording = file.wrongRecording,
        )
    }

    /**
     * The record has been written: refresh artwork rows, drop verdicts and
     * timing answers that belonged to the old file, repoint aliases and remove
     * the replaced file once nothing else names it.
     */
    suspend fun afterRemember(
        asked: Song,
        fetched: Song,
        uri: android.net.Uri,
        soundSourceVideoId: String,
        previous: PreviousRecordState,
        coverSaved: Boolean,
        finalName: String? = null,
        stagedName: String? = null,
        /** These bytes were written as a deliberate replacement; see [dropsOldTiming]. */
        replacing: Boolean = false,
    ) {
        val ids = setOf(asked.videoId, fetched.videoId)
        // The cover file has landed with the track. Tell any row already
        // showing these ids to read it again; its earlier ask may have seen a
        // miss, and a remembered miss is no longer timed out on its own.
        if (coverSaved) {
            com.music.bitchord.data.remote.DownloadedArtworkEvents.coverSaved(ids)
        }
        // A file whose recorded source is one of these ids is the recording
        // these rows asked for, so a length verdict from the old file no longer
        // applies. When the new file landed elsewhere, remove the old one once
        // nothing else names it — that is the "replace the old file" half of a
        // re-download.
        if (soundSourceVideoId in ids) {
            // Only a different upload proves the MV sound has been replaced.
            // A same-id re-fetch wrote the same bytes again; clearing here is
            // what made the warning disappear while the file stayed wrong.
            if (repairReplacesUpload(asked.videoId, fetched.videoId, previous.origins[asked.videoId])) {
                // Review VER6: the one line for a saved wrong version whose file was replaced.
                if (previous.suspects.isNotEmpty()) {
                    com.music.bitchord.data.TrackLog.i(
                        TAG,
                        com.music.bitchord.ko.KoStoredVersionFix.fixLine(asked.videoId, fetched.videoId, "mv", "replaced", "file=new"),
                        about = asked.videoId,
                    )
                }
                KoDownloadRecords.clearMvSoundSuspects(previous.suspects + ids)
            }
            val context = Downloads.applicationContext()
            val dropTiming = dropsOldTiming(previous.uris, uri.toString(), replacing, previous.suspects)
            if (context != null && dropTiming) {
                // A song's file was replaced. Any AI timing answer was measured
                // against the old bytes, so drop it (and the spent engine
                // record) and let the song check run against the new
                // recording. Video rows keep their own timing.
                // The version alignment measured against the old file goes too,
                // video rows included: their pair shares the replaced bytes.
                (setOf(asked.videoId, fetched.videoId) + previous.suspects).forEach(::forgetAlignment)
                buildSet {
                    if (!asked.isVideo) add(asked.videoId)
                    if (!fetched.isVideo) add(fetched.videoId)
                    addAll(previous.suspects)
                }.forEach { id ->
                    runCatching {
                        com.music.bitchord.playback.smart.LyricsTiming.resetSongCheck(context, id)
                    }
                }
            }
            // Other ids that named the old file are aliases of the same
            // download (a music video and its song). Point them at the new
            // file rather than dropping them, so a playlist that was filed
            // under the old id keeps its track.
            val saved = Downloads.savedValue()
            val staleIds = saved
                .filterValues { value -> value != uri.toString() && value in previous.uris }
                .keys.toList()
            // [KO] l1121 CLOCK-1: an alias already on this path when a replacement was written over it.
            val fileAliases = aliasesOfReplacedFile(saved, ids, previous.uris, uri.toString(), replacing)
            if (dropTiming) fileAliases.forEach(::forgetAlignment)
            if (fileAliases.isNotEmpty()) {
                Downloads.record(
                    saved = { it + staleIds.associateWith { uri.toString() } },
                    meta = { meta ->
                        val file = meta[fetched.videoId] ?: meta[asked.videoId]
                        meta.mapValues { (id, value) ->
                            if (id in fileAliases) {
                                aliasOfReplacedFile(value, file, uri.toString(), soundSourceVideoId)
                            } else {
                                value
                            }
                        }
                    },
                )
            }
            // [KO] u1121 LIBRARY-3: a player still reading the old file moves onto this one.
            if (previous.uris.any { it != uri.toString() }) {
                _replaced.tryEmit(FileReplaced(ids + staleIds, previous.uris, uri.toString(), previous.pairs, previous.clocks))
            }
            if (context != null) {
                previous.uris.forEach { old ->
                    if (old != uri.toString() && Downloads.savedValue().values.none { it == old }) {
                        runCatching { DownloadStore.delete(context, old.toUri()) }
                    }
                }
                // The old row is gone, so the media store should accept the
                // real name instead of appending "(1)". App-private/legacy
                // files already have it and return true without touching the
                // disk. Read the name back even when the update reports
                // success: the store appends "(1)" when another row still owns
                // the name and still counts that as success. Recording the
                // name it kept is what lets the next lookup find the committed
                // file instead of downloading it again.
                if (finalName != null) {
                    val renamed = DownloadStore.renamePublished(context, uri, finalName)
                    val recordedName = DownloadStore.reconcilePublishedName(
                        finalName = finalName,
                        stagedName = stagedName
                            ?: DownloadStore.stagingFileName(finalName, fetched.videoId),
                        renamed = renamed,
                    ) { DownloadStore.displayName(context, uri) }
                    Downloads.record(
                        saved = { it },
                        meta = { meta ->
                            meta.mapValues { (id, value) ->
                                if (id in ids || id in fileAliases) value.copy(displayName = recordedName) else value
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Resolve the catalogue release a download should fetch.
     *
     * A music video keeps the video's sound if downloaded as its own row. The
     * catalogue release is the recording the app plays, so a video row is
     * resolved first and the bytes, the name and the record all follow that
     * id. A row already known to have no version keeps its own, and a
     * catalogue row is never looked up at all. A download made before its
     * provenance or upload type was recorded is treated like a video
     * candidate.
     */
    suspend fun resolveTrack(song: Song, mvSoundRepair: Boolean): Song {
        val storedIsVideo = KoDownloadRecords.savedIsVideoFor(song.videoId)
        val storedTypeUnknown = KoDownloadRecords.savedTypeUnknownFor(song.videoId)
        val soundSourceId = KoDownloadRecords.savedSoundSourceFor(song.videoId)
        val downloaded = KoDownloadRecords.isDownloaded(song.videoId)
        val effectiveIsVideo: Boolean? = when {
            song.isVideo || storedIsVideo == true -> true
            storedIsVideo == null -> null
            else -> false
        }
        val unknownDownload = SongVersionPolicy.verifiesUnknownDownload(
            localFile = downloaded,
            downloaded = downloaded,
            provenanceKnown = soundSourceId != null,
            provenanceIsOwnId = soundSourceId == song.videoId,
            isVideo = effectiveIsVideo,
            typeUnknown = song.typeUnknown || storedTypeUnknown,
            offline = AudioCache.isOffline(),
        )
        val versionSong = if (unknownDownload) song.copy(typeUnknown = true) else song
        val track = if (SongVersionPolicy.downloadIsSongVersion(
                versionSong.isVideo,
                versionSong.typeUnknown,
                YtMusicRepository.isSongVersionMissing(versionSong.videoId),
            )
        ) {
            runCatching { YtMusicRepository.songVersion(versionSong) }.getOrNull()
                ?.takeIf { SongVersionPolicy.mayInstallSongVersion(versionSong) }
                ?: versionSong
        } else {
            versionSong
        }
        // A repair exists to fetch a different upload. Falling back to the same
        // id would save the same MV stream again and then clear the warning, so
        // fail instead and leave the suspect for the next attempt.
        if (mvSoundRepair &&
            !repairReplacesUpload(song.videoId, track.videoId, KoDownloadRecords.fileOriginFor(song.videoId))
        ) {
            error("MV sound repair found no different song version")
        }
        return track
    }

    /**
     * A repair that could not prove its replacement first must still not leave
     * a lower rendition in place of the file it is replacing.
     */
    fun requireMinimumTier(route: Downloads.Route, minimumTier: com.music.bitchord.data.sources.HqTier) {
        val served = route.tier
        if (served == null || served.rank > minimumTier.rank) {
            error(
                "고음질 복구 음원이 기존 파일보다 낮은 등급으로 응답했습니다 " +
                    "(${served?.label ?: "unknown"}, ${minimumTier.label})",
            )
        }
    }

    /**
     * Pick and reserve the destination name for a transfer.
     *
     * A different upload already owning the name keeps both files; a different
     * transfer currently writing it gets the id suffix. A deliberate
     * replacement never adopts the existing file.
     */
    fun reserveFileName(
        track: Song,
        extension: String,
        baseName: String,
        alreadyThere: android.net.Uri?,
        existingIsRecording: Boolean,
        replacing: Boolean,
        existingIsOwn: Boolean,
        stageReplacement: Boolean = false,
    ): String = synchronized(Downloads.lock) {
        var candidate = if (stageReplacement) {
            DownloadStore.stagingFileName(baseName, track.videoId)
        } else {
            DownloadStore.downloadFileName(
                song = track,
                extension = extension,
                nameTaken = alreadyThere != null,
                existingIsRecording = existingIsRecording,
                replacing = replacing,
                existingIsOwn = existingIsOwn,
            )
        }
        if (!KoDownloadQueue.reserveName(candidate)) {
            // A different transfer is already writing this name. Its final
            // file does not exist yet, so `existing` could not see it; the id
            // suffix keeps the two apart.
            candidate = DownloadStore.disambiguatedFileName(track, extension)
            KoDownloadQueue.reserveName(candidate)
        }
        if (!stageReplacement && alreadyThere != null && candidate == baseName && !replacing) {
            // Same name, but not a file this record knows to be the requested
            // recording. A video's sound sitting under the song's name is
            // exactly what used to be adopted here.
            Log.d(TAG, "$baseName exists but is not ${track.videoId}'s verified recording; downloading it")
        }
        if (!stageReplacement && alreadyThere != null && candidate != baseName) {
            // A different upload already owns this name. Keep both files;
            // overwriting would make the old record play these bytes under the
            // old song's name.
            Log.d(TAG, "$baseName belongs to another recording; saving as $candidate")
        }
        candidate
    }

    /**
     * Take the automatic pass's host slot, or throw [AutoDownloadDeferred] when
     * that host is still resting. The scheduler gets the remaining time, so
     * this is not a poll.
     */
    suspend fun beginAutomaticDownload(id: String, route: Downloads.Route?): String? {
        val host = route?.host ?: return null
        // A host the pass or playback has just been refused by is not asked
        // again until its own wait has passed.
        val rest = HqHostTracker.restRemainingMs(host)
        if (rest > 0L) throw AutoDownloadDeferred(rest)
        // F2: the wait for the player's own fill of this file asks nothing of the
        // host, so it runs before the slot and the candidate-host lock. Held under
        // them it kept the next track's read-ahead and sound checks off this host
        // until the player let go, near the song's end.
        route.beforeHostSlot?.invoke()
        // Playback's upgrade/read-ahead and its verification hold the same host
        // slots; an automatic file waits for them rather than opening a second
        // request to the same server.
        HqHostTracker.awaitIdle(host, exceptTrack = id)
        HqHostTracker.beginDownload(id, host)
        return host
    }

    suspend fun transferAutomatic(
        automatic: Boolean,
        host: String?,
        work: suspend () -> Boolean,
    ): Boolean = if (automatic && host != null) {
        com.music.bitchord.playback.smart.HqAudioVerify.withCandidateHost(host) { work() }
    } else {
        work()
    }

    /** One log line per failure: a rest or a 429 is news, not a stack. */
    fun logDownloadFailure(id: String, error: Exception) {
        val deferred = error as? AutoDownloadDeferred
        val rateLimit = rateLimitCode(error)
        when {
            deferred != null ->
                Log.w(TAG, "download deferred for $id: host resting ${deferred.waitMs}ms")
            rateLimit != null ->
                Log.w(TAG, "download rate limited for $id: HTTP $rateLimit")
            else -> Log.w(TAG, "download failed for $id: ${error.message}", error)
        }
    }

    /**
     * One HQ target at a time, and a repair's success or failure recorded.
     * Cheap for ordinary downloads — the id is not the pass's target.
     */
    fun settleDownload(
        context: Context,
        id: String,
        completed: Boolean,
        cancelled: Boolean,
        mvRepair: Boolean,
        failure: DownloadFailure?,
        host: String?,
    ) {
        if (host != null) HqHostTracker.endDownload(id, host)
        if (mvRepair && !cancelled) {
            if (completed) {
                com.music.bitchord.download.MvSoundRedownload.clearFailed(context, id)
            } else {
                com.music.bitchord.download.MvSoundRedownload.markFailed(context, id)
            }
        }
        com.music.bitchord.download.HqLibraryUpgrade.onDownloadSettled(context, id, completed, failure, cancelled)
    }

    /** A downloaded cover is still an upload even when its queue row lost the flag. */
    fun recordedIsVideo(song: Song): Boolean =
        song.isVideo || CoverTitles.lyricsQuery(song).isVideo

    /** One record row for an id filed against a newly written file. */
    fun metadataFor(
        song: Song,
        thumbnailUrl: String?,
        album: String?,
        uri: android.net.Uri,
        downloadFormat: String?,
        dateAddedSeconds: Long,
        soundSourceVideoId: String,
        hq: HqDownloadSource?,
        sourceTitle: String?,
        sourceArtist: String?,
        recordingVerified: Boolean,
    ): SavedSongMetadata = SavedSongMetadata(
        videoId = song.videoId,
        title = song.title,
        artist = song.artist,
        thumbnailUrl = thumbnailUrl,
        durationText = song.durationText,
        albumName = album,
        uri = uri.toString(),
        downloadFormat = downloadFormat,
        dateAddedSeconds = dateAddedSeconds,
        soundSourceVideoId = soundSourceVideoId,
        isVideo = recordedIsVideo(song),
        typeUnknown = song.typeUnknown,
        hqSourceConfigId = hq?.sourceConfigId,
        hqTrackId = hq?.trackId,
        hqOffsetMs = hq?.offsetMs,
        hqRate = hq?.rate,
        hqTier = hq?.tier?.name ?: KoFlacMeasure.measuredTierName(uri, downloadFormat), // [KO] HQ-DOWN
        hqSourceTitle = hq?.sourceTitle ?: sourceTitle,
        hqSourceArtist = hq?.sourceArtist ?: sourceArtist,
        recordingVerified = recordingVerified,
    )

}

/** Whether this record names a music-video row. */
internal fun SavedSongMetadata.wasVideoRow(): Boolean =
    isVideo == true || (soundSourceVideoId != null && soundSourceVideoId != videoId) ||
        // A cover saved before the flag was kept still reads as its upload.
        isCoverRecording()

/** Whether this record is a cover, live clip or other chosen performance. */
internal fun SavedSongMetadata.isCoverRecording(): Boolean =
    DownloadedMvPolicy.isCover(videoId, title, artist, isVideo)

/**
 * Keep the type already on the record when the incoming row is only the
 * unknown placeholder a repair uses to ask for the song version.
 */
internal fun SavedSongMetadata.preservingUploadTypeFrom(
    previous: SavedSongMetadata?,
): SavedSongMetadata {
    if (previous == null) return this
    return if (DownloadedMvPolicy.keepsStoredUploadType(
            storedIsVideo = previous.isVideo,
            storedTypeUnknown = previous.typeUnknown,
            incomingIsVideo = isVideo,
            incomingTypeUnknown = typeUnknown,
        )
    ) {
        copy(isVideo = previous.isVideo, typeUnknown = previous.typeUnknown)
    } else {
        this
    }
}
