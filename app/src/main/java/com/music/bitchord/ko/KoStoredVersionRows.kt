package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.MvSoundRedownload
import com.music.bitchord.playback.AudioCache

/**
 * [KO] Review VER6: a saved song row that may be another version of its song
 * (see [KoStoredVersionFix]) is looked up like a video row when it plays or is
 * prepared as next — never by a scan or a timer — and re-pointed only when
 * [KoStoredVersionFix.judge] accepts the version it found.
 *
 * A downloaded file is never deleted here. An accepted correction marks it for
 * the existing MV-sound repair, which downloads the song first and removes the
 * old file only after that download succeeded
 * ([com.music.bitchord.ko.downloads.KoDownloadPipeline.afterRemember]); a
 * failed repair leaves the old file in place. Until then the song is streamed
 * and the file stays the offline copy.
 */
internal object KoStoredVersionRows {

    private const val TAG = "BitChord"
    private const val SUSPECTS_KEPT = 256

    /**
     * Rows [suspectReason] sent to the lookup this run, so an answer that
     * lands after the first sound ([lateAllowed]) is judged like one before it.
     */
    private val suspects = object : LinkedHashMap<String, String>(SUSPECTS_KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > SUSPECTS_KEPT
    }

    /** Why [song] is looked up as a possible other version, or null. Online only. */
    fun suspectReason(song: Song): String? =
        suspicionOf(song)?.also { why -> synchronized(suspects) { suspects[song.videoId] = why } }

    /**
     * Whether a late answer may still take [row] over: always for a row that
     * was never a stored-version suspect, otherwise only as [allows] says.
     */
    fun lateAllowed(context: Context, row: Song, late: Song): Boolean {
        val why = synchronized(suspects) { suspects[row.videoId] } ?: return true
        return allows(context, row, late, why)
    }

    private fun suspicionOf(song: Song): String? {
        if (AudioCache.isOffline()) return null
        val downloaded = Downloads.isDownloaded(song.videoId)
        // The listener's own device files are never second-guessed.
        if (song.localUri != null && !downloaded) return null
        if (downloaded) {
            // A file with no recorded provenance or upload type is already
            // looked up by the unknown-download verification
            // (SongVersionPolicy.verifiesUnknownDownload), which has its own rule.
            val source = Downloads.savedSoundSourceFor(song.videoId) ?: return null
            if (Downloads.savedIsVideoFor(song.videoId) == null) return null
            // A file that already holds another upload's sound (a finished
            // repair, a download that resolved its song) is the alias paths' to
            // play; it is not marked for a repair again.
            if (source != song.videoId) return null
        }
        val row = if (downloaded) {
            song.copy(
                isVideo = song.isVideo || Downloads.savedIsVideoFor(song.videoId) == true,
                typeUnknown = song.typeUnknown || Downloads.savedTypeUnknownFor(song.videoId),
                albumName = song.albumName?.takeIf { it.isNotBlank() }
                    ?: Downloads.savedMetadataFor(song.videoId)?.albumName,
            )
        } else {
            song
        }
        return KoStoredVersionFix.suspicion(row, downloaded)
    }

    /**
     * Whether [version] may replace the suspect [row], with the one
     * `version-fix:` line for the decision. A refused version is dropped for
     * this run so no other path (the long-file stream, the lyrics reference)
     * picks it up from the cache.
     */
    fun allows(context: Context, row: Song, version: Song, suspicion: String): Boolean {
        // A queue row can lose its length; the download record kept it.
        val judged = if (row.durationText.isNullOrBlank()) {
            row.copy(durationText = Downloads.savedMetadataFor(row.videoId)?.durationText)
        } else {
            row
        }
        val verdict = KoStoredVersionFix.judge(judged, version, suspicion)
        if (!verdict.accepted) {
            log(row.videoId, KoStoredVersionFix.keptLine(row.videoId, version.videoId, verdict))
            if (verdict.dropsVersion) YtMusicRepository.refuseSongVersion(row)
            return false
        }
        val download = if (Downloads.isDownloaded(row.videoId)) markForRepair(context, row, verdict.overhangMs) else "none"
        log(
            row.videoId,
            KoStoredVersionFix.fixLine(row.videoId, version.videoId, verdict.reason, download, verdict.evidence),
        )
        return true
    }

    /**
     * Whether a download record's saved song version [alias] may still play
     * for [row]. Offline the file is the only sound there is, so it stays.
     */
    fun aliasAllowed(row: Song, alias: Song, offline: Boolean): Boolean {
        if (offline) return true
        val refusal = KoStoredVersionFix.aliasRefusal(row, alias) ?: return true
        val verdict = KoStoredVersionFix.Verdict(false, refusal, "saved-alias download=kept")
        log(row.videoId, KoStoredVersionFix.keptLine(row.videoId, alias.videoId, verdict))
        return false
    }

    /**
     * Marks the file for the MV-sound repair, which downloads the song first
     * and deletes this file only after that succeeded. `pending` when the
     * repair will fetch it, `kept` when automatic repair is off and the file
     * waits for the listener's banner tap.
     */
    private fun markForRepair(context: Context, row: Song, overhangMs: Long): String {
        val playId = Downloads.songIdForFile(row.videoId)
        if (Downloads.mvSoundSuspectMs(playId) == null) {
            Downloads.markMvSoundSuspect(playId, overhangMs)
            MvSoundRedownload.scheduleIfEnabled(context)
        }
        return if (AppSettings.autoRedownloadMvSound.value) "pending" else "kept"
    }

    private fun log(id: String, line: String) {
        if (KoRepeatLog.shouldLog(id, line)) TrackLog.i(TAG, line, about = id)
    }
}
