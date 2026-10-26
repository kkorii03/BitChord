package com.music.bitchord.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.DownloadHealthPolicy
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.playback.smart.HqAudioVerify
import com.music.bitchord.playback.smart.HqVerificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The one health check a downloaded file gets when it is played.
 *
 * A file saved months ago can be the wrong recording — a piano cover a source
 * returned under the right title — or a rung below what a source has since
 * proved. Nothing in the old record can tell, so this asks once per file
 * version: the file's length, its sound against the YouTube song version, and
 * the source row's own names. The rules are in [DownloadHealthPolicy]; this
 * object supplies the measurements and carries out the verdict.
 *
 * A wrong file is never rewritten in place. It is marked, the player is handed
 * the song-version stream (see `PlaybackService`), and the ordinary download
 * queue fetches the correct recording under its own pending destination. The
 * old bytes stay until the new file is committed.
 *
 * Event-driven only: called from a track transition, once for the file in
 * hand. There is no timer and no background sweep.
 */
object DownloadHealth {

    private const val TAG = "BitChord"
    private const val PREFS = "download_health"
    private const val KEY_CHECKED = "checked"
    private const val KEY_VERSION = "version"
    private const val KEY_REPAIR_REQUESTED = "repair_requested"

    /**
     * Bump when the rules change enough that every file should be looked at
     * again. The version is part of each file's signature, so an old entry
     * simply stops matching — no migration is needed.
     */
    const val CHECK_VERSION = 2

    data class Outcome(
        val verdict: DownloadHealthPolicy.Verdict,
        val action: DownloadHealthPolicy.Action,
        val tier: String,
    )

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Inspects [song]'s downloaded file, if it has one, and acts on the answer.
     *
     * Null when there is nothing to do: not a download, already checked for
     * this file version, or another check of the same id is running.
     *
     * The caller decides whether a wrong verdict needs a stream handoff — it
     * knows whether the item being played is the file itself. The repair is
     * queued either way.
     */
    suspend fun onPlayback(context: Context, song: Song): Outcome? = withContext(Dispatchers.IO) {
        val id = song.videoId
        if (id.isBlank() || SourceTrackKeys.parse(id) != null) return@withContext null
        // A downloaded video is deliberately the video's own cut; comparing it
        // with the song version would condemn the right file.
        if (song.isVideo) return@withContext null
        val meta = Downloads.savedMetadataFor(id) ?: return@withContext null
        if (!Downloads.isDownloaded(id)) return@withContext null
        if (!inFlight.add(id)) return@withContext null
        try {
            val currentTier = DownloadedQuality.tierOf(meta.downloadFormat, meta.hqTier)
            val bestTier = HqVerificationStore.bestTier(context, id)
            val online = online(context)

            // Already condemned: keep it out of the player and let the repair
            // run again if the last attempt did not land. No second decode.
            if (Downloads.isWrongRecording(id)) {
                val now = System.currentTimeMillis()
                if (DownloadHealthPolicy.mayRequestRepair(lastRepairRequest(context, id), now)) {
                    HqLibraryUpgrade.requestUpgrade(context, song, allowEqual = true, automatic = true)
                    markRepairRequested(context, id, now)
                }
                val action = if (online) {
                    DownloadHealthPolicy.Action.STREAM
                } else {
                    DownloadHealthPolicy.Action.REDOWNLOAD
                }
                val outcome = Outcome(
                    DownloadHealthPolicy.Verdict.WRONG_RECORDING,
                    action,
                    currentTier.name,
                )
                log(id, outcome)
                return@withContext outcome
            }

            val version = Downloads.fileVersion(context, meta.uri)
            val signature = signature(meta, version)
            if (isChecked(context, id, signature)) return@withContext null

            val sound = HqAudioVerify.verifyDownloaded(
                context = context,
                youtubeId = id,
                uri = meta.uri,
                sourceConfigId = meta.hqSourceConfigId,
                trackId = meta.hqTrackId,
                tier = currentTier,
            )
            val soundAccepted = when (sound) {
                is HqAudioVerify.DownloadedSound.Accepted -> true
                HqAudioVerify.DownloadedSound.Rejected -> false
                HqAudioVerify.DownloadedSound.Unavailable -> null
            }
            val soundCached = sound is HqAudioVerify.DownloadedSound.Accepted && sound.cached
            // A repair can commit while the decode is running. The verdict is
            // about the file that was opened, so a different file now in the
            // record must not inherit it — not the wrong flag, not the stream
            // handoff, not another repair.
            val stillCurrent = Downloads.savedMetadataFor(id)?.uri == meta.uri &&
                Downloads.fileVersion(context, meta.uri) == version
            if (!stillCurrent) return@withContext null
            val decision = DownloadHealthPolicy.judge(
                DownloadHealthPolicy.Input(
                    songTitle = meta.title,
                    songArtist = meta.artist,
                    catalogDurationMs = meta.durationText?.durationMillis()
                        ?: song.durationMillis(),
                    fileDurationMs = Downloads.localDurationMs(context, meta.uri),
                    sourceTitle = meta.hqSourceTitle,
                    sourceArtist = meta.hqSourceArtist,
                    soundAccepted = soundAccepted,
                    soundCached = soundCached,
                    currentTier = currentTier,
                    verifiedTier = bestTier,
                    online = online,
                ),
            )
            // A verdict that could not hear the file is not a completed check:
            // leave it unchecked so a later play with the screen on can still
            // answer. A decision made from length or names is complete.
            if (sound !is HqAudioVerify.DownloadedSound.Unavailable ||
                decision.verdict != DownloadHealthPolicy.Verdict.OK
            ) {
                markChecked(context, id, signature)
            }
            val outcome = Outcome(decision.verdict, decision.action, currentTier.name)
            log(id, outcome)
            when (decision.action) {
                DownloadHealthPolicy.Action.STREAM,
                DownloadHealthPolicy.Action.REDOWNLOAD,
                -> {
                    if (!Downloads.markWrongRecording(id, meta.uri, version)) {
                        return@withContext null
                    }
                    HqLibraryUpgrade.requestUpgrade(context, song, allowEqual = true, automatic = true)
                }
                DownloadHealthPolicy.Action.UPGRADE -> {
                    HqLibraryUpgrade.requestUpgrade(context, song, automatic = true)
                }
                DownloadHealthPolicy.Action.NONE -> Unit
            }
            outcome
        } finally {
            inFlight.remove(id)
        }
    }

    /**
     * The settings button: forget every file's checked version, so each is
     * looked at once more the next time it plays. Nothing runs now, and the
     * "wrong recording" marks are deliberately kept — clearing them would put
     * a condemned file back in the player before a repair has landed.
     */
    fun resetChecks(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CHECKED)
            .remove(KEY_REPAIR_REQUESTED)
            .putInt(KEY_VERSION, CHECK_VERSION)
            .apply()
        TrackLog.i(TAG, "download-check: versions cleared")
    }

    private fun log(id: String, outcome: Outcome) {
        TrackLog.i(
            TAG,
            "download-check: $id result=${outcome.verdict.wire} " +
                "action=${outcome.action.wire} tier=${outcome.tier}",
            id,
        )
    }

    private fun signature(meta: SavedSongMetadata, version: String): String =
        "$CHECK_VERSION|${meta.uri}|$version|" +
            "${meta.hqSourceConfigId.orEmpty()}|${meta.hqTrackId.orEmpty()}|${meta.hqTier.orEmpty()}"

    private fun isChecked(context: Context, id: String, signature: String): Boolean {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_VERSION, CHECK_VERSION) != CHECK_VERSION) {
            prefs.edit().remove(KEY_CHECKED).putInt(KEY_VERSION, CHECK_VERSION).apply()
            return false
        }
        return readChecked(prefs)[id] == signature
    }

    private fun markChecked(context: Context, id: String, signature: String) {
        val prefs = prefs(context)
        val next = readChecked(prefs) + (id to signature)
        prefs.edit()
            .putString(
                KEY_CHECKED,
                next.entries.joinToString("\n") { (key, value) -> "$key\t$value" },
            )
            .putInt(KEY_VERSION, CHECK_VERSION)
            .apply()
    }

    private fun lastRepairRequest(context: Context, id: String): Long? =
        readRepairRequests(prefs(context))[id]

    private fun markRepairRequested(context: Context, id: String, nowMs: Long) {
        val prefs = prefs(context)
        val next = readRepairRequests(prefs) + (id to nowMs)
        prefs.edit()
            .putString(
                KEY_REPAIR_REQUESTED,
                next.entries.joinToString("\n") { (key, value) -> "$key\t$value" },
            )
            .apply()
    }

    private fun readRepairRequests(prefs: android.content.SharedPreferences): Map<String, Long> =
        prefs.getString(KEY_REPAIR_REQUESTED, null).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val requested = line.substring(tab + 1).toLongOrNull() ?: return@mapNotNull null
                line.substring(0, tab) to requested
            }
            .toMap()

    private fun readChecked(prefs: android.content.SharedPreferences): Map<String, String> =
        prefs.getString(KEY_CHECKED, null).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            .toMap()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun online(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
