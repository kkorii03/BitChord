package com.music.bitchord.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
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
/**
 * Whether a finished verdict may be recorded as "checked" for the file.
 *
 * A verdict that could not hear the file is not a completed check. Neither is
 * an upgrade the Wi-Fi-only rule refused: the file must stay unchecked so the
 * next allowed playback asks again instead of swallowing the verdict.
 */
internal fun downloadHealthRecordsCheck(
    heard: Boolean,
    needsUpgrade: Boolean,
    upgradeAccepted: Boolean,
): Boolean = heard && (!needsUpgrade || upgradeAccepted)

/**
 * Whether an UPGRADE verdict must stay pending instead of being recorded as
 * checked.
 *
 * A request that was only searched is not a queued download yet: the search
 * can still end on a connection the Wi-Fi-only rule refuses. Keeping the
 * marker lets the next allowed playback retry the request without decoding
 * the file again.
 */
internal fun downloadHealthKeepsPendingUpgrade(
    needsUpgrade: Boolean,
    upgradeQueued: Boolean,
): Boolean = needsUpgrade && !upgradeQueued

/**
 * How many times a pending upgrade marker may ask for a search before it is
 * retired.
 *
 * The repair cooldown already limits a retry to once a day. This cap stops a
 * track that no source can improve from starting one search per day forever:
 * after it, the verdict is recorded as done and the ordinary high-quality pass
 * remains responsible for finding a source if one appears later.
 */
internal const val PENDING_UPGRADE_MAX_ATTEMPTS = 3

/**
 * Whether a pending upgrade marker may ask the resolver for a better source at
 * [nowMs]. A marker with no recorded request is due once; after that the same
 * 24-hour repair cooldown as a condemned file applies.
 */
internal fun downloadHealthMayRetryPending(
    lastRequestMs: Long?,
    attempts: Int,
    nowMs: Long,
    maxAttempts: Int = PENDING_UPGRADE_MAX_ATTEMPTS,
): Boolean =
    attempts < maxAttempts &&
        DownloadHealthPolicy.mayRequestRepair(lastRequestMs, nowMs)

/**
 * Whether a request outcome may start the repair cooldown.
 *
 * A queued download and a launched search both spent the request; only a
 * refusal before any work started must leave the cooldown untouched so the
 * next allowed playback asks again.
 */
internal fun downloadHealthCountsAsRequested(
    request: HqLibraryUpgrade.UpgradeRequest,
): Boolean = request != HqLibraryUpgrade.UpgradeRequest.REFUSED

object DownloadHealth {

    private const val TAG = "BitChord"
    private const val PREFS = "download_health"
    private const val KEY_CHECKED = "checked"
    private const val KEY_VERSION = "version"
    private const val KEY_REPAIR_REQUESTED = "repair_requested"
    private const val KEY_PENDING_UPGRADE = "pending_upgrade"
    private const val KEY_UPGRADE_ATTEMPTS = "upgrade_attempts"

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
                    // Only start the cooldown when a repair was actually
                    // accepted. On mobile data the Wi-Fi-only rule refuses it;
                    // recording that as requested would park the repair for a
                    // day even though nothing was queued. A launched search is
                    // accepted too, even though the download it may find can
                    // still be refused later.
                    val request = HqLibraryUpgrade.requestUpgrade(
                        context,
                        song,
                        allowEqual = true,
                        automatic = true,
                    )
                    if (downloadHealthCountsAsRequested(request)) {
                        markRepairRequested(context, id, now)
                    }
                } else {
                    TrackLog.i(TAG, "download-check: repair retry cooling down", id)
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
            // A previous play already decided this file needs a better copy but
            // the queue could not take it (a search was still running, or the
            // Wi-Fi-only rule refused it). Asking again is cheap; decoding the
            // same file on every metered playback is not. A matching marker
            // skips the decode while the request is still pending.
            if (pendingUpgradeMatches(context, id, signature)) {
                if (!AppSettings.downloadsAllowedNow) {
                    TrackLog.i(
                        TAG,
                        "download-check: waiting for an unmetered connection",
                        id,
                    )
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                val attempts = upgradeAttempts(context, id)
                if (!downloadHealthMayRetryPending(lastRepairRequest(context, id), attempts, now)) {
                    if (attempts >= PENDING_UPGRADE_MAX_ATTEMPTS) {
                        // No source proved better in the allowed tries. Record
                        // the verdict as done so playback stops decoding the
                        // same file; the ordinary HQ pass can still find a
                        // source later if one appears.
                        markChecked(context, id, signature)
                        clearPendingUpgrade(context, id)
                        clearUpgradeAttempts(context, id)
                        TrackLog.i(
                            TAG,
                            "download-check: upgrade search retired attempts=$attempts",
                            id,
                        )
                    } else {
                        TrackLog.i(TAG, "download-check: upgrade retry cooling down", id)
                    }
                    return@withContext null
                }
                when (HqLibraryUpgrade.requestUpgrade(context, song, automatic = true)) {
                    HqLibraryUpgrade.UpgradeRequest.QUEUED -> {
                        markRepairRequested(context, id, now)
                        markChecked(context, id, signature)
                        clearPendingUpgrade(context, id)
                        clearUpgradeAttempts(context, id)
                    }
                    HqLibraryUpgrade.UpgradeRequest.SEARCHING -> {
                        markRepairRequested(context, id, now)
                        markUpgradeAttempt(context, id, attempts + 1)
                        TrackLog.i(TAG, "download-check: upgrade search started", id)
                    }
                    HqLibraryUpgrade.UpgradeRequest.REFUSED -> Unit
                }
                return@withContext null
            }

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
            val heard = sound !is HqAudioVerify.DownloadedSound.Unavailable ||
                decision.verdict != DownloadHealthPolicy.Verdict.OK
            val outcome = Outcome(decision.verdict, decision.action, currentTier.name)
            log(id, outcome)
            var upgradeQueued = true
            when (decision.action) {
                DownloadHealthPolicy.Action.STREAM,
                DownloadHealthPolicy.Action.REDOWNLOAD,
                -> {
                    if (!Downloads.markWrongRecording(id, meta.uri, version)) {
                        return@withContext null
                    }
                    // The file is condemned either way, so the branch above
                    // retries later even if this request was refused for now.
                    // A launched search counts as an accepted attempt and
                    // starts the cooldown; otherwise every playback would run
                    // it again with no pause.
                    val request = HqLibraryUpgrade.requestUpgrade(
                        context,
                        song,
                        allowEqual = true,
                        automatic = true,
                    )
                    if (downloadHealthCountsAsRequested(request)) {
                        markRepairRequested(context, id, System.currentTimeMillis())
                    }
                }
                DownloadHealthPolicy.Action.UPGRADE -> {
                    when (val request = HqLibraryUpgrade.requestUpgrade(
                        context,
                        song,
                        automatic = true,
                    )) {
                        HqLibraryUpgrade.UpgradeRequest.QUEUED -> {
                            upgradeQueued = true
                        }
                        HqLibraryUpgrade.UpgradeRequest.SEARCHING -> {
                            upgradeQueued = false
                        }
                        HqLibraryUpgrade.UpgradeRequest.REFUSED -> {
                            upgradeQueued = false
                        }
                    }
                }
                DownloadHealthPolicy.Action.NONE -> Unit
            }
            // A queued pair is a completed verdict; a request that is only
            // searching, or that the connection rule refused, stays pending so
            // the next allowed play retries it without another decode.
            if (downloadHealthKeepsPendingUpgrade(
                    needsUpgrade = decision.action == DownloadHealthPolicy.Action.UPGRADE,
                    upgradeQueued = upgradeQueued,
                )
            ) {
                markPendingUpgrade(context, id, signature)
            } else if (decision.action != DownloadHealthPolicy.Action.UPGRADE) {
                clearPendingUpgrade(context, id)
            }
            // An upgrade the Wi-Fi-only rule refused is not a completed check:
            // leaving the file unchecked is what makes the next allowed
            // playback ask again instead of swallowing the verdict. The check
            // stays event-driven (one per playback), not a timer.
            if (downloadHealthRecordsCheck(
                    heard = heard,
                    needsUpgrade = decision.action == DownloadHealthPolicy.Action.UPGRADE,
                    upgradeAccepted = upgradeQueued,
                )
            ) {
                markChecked(context, id, signature)
                if (decision.action == DownloadHealthPolicy.Action.UPGRADE) {
                    clearPendingUpgrade(context, id)
                }
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
            .remove(KEY_PENDING_UPGRADE)
            .remove(KEY_UPGRADE_ATTEMPTS)
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

    private fun pendingUpgradeMatches(context: Context, id: String, signature: String): Boolean =
        readPendingUpgrades(prefs(context))[id] == signature

    private fun markPendingUpgrade(context: Context, id: String, signature: String) {
        val prefs = prefs(context)
        val next = readPendingUpgrades(prefs) + (id to signature)
        prefs.edit()
            .putString(KEY_PENDING_UPGRADE, encodePairs(next))
            .apply()
    }

    private fun clearPendingUpgrade(context: Context, id: String) {
        val prefs = prefs(context)
        val pending = readPendingUpgrades(prefs)
        if (id !in pending) return
        prefs.edit()
            .putString(KEY_PENDING_UPGRADE, encodePairs(pending - id))
            .apply()
    }

    private fun readPendingUpgrades(prefs: android.content.SharedPreferences): Map<String, String> =
        prefs.getString(KEY_PENDING_UPGRADE, null).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            .toMap()

    private fun upgradeAttempts(context: Context, id: String): Int =
        readUpgradeAttempts(prefs(context))[id] ?: 0

    private fun markUpgradeAttempt(context: Context, id: String, attempts: Int) {
        val prefs = prefs(context)
        val next = readUpgradeAttempts(prefs) + (id to attempts)
        prefs.edit()
            .putString(KEY_UPGRADE_ATTEMPTS, encodePairs(next.mapValues { it.value.toString() }))
            .apply()
    }

    private fun clearUpgradeAttempts(context: Context, id: String) {
        val prefs = prefs(context)
        val attempts = readUpgradeAttempts(prefs)
        if (id !in attempts) return
        prefs.edit()
            .putString(KEY_UPGRADE_ATTEMPTS, encodePairs((attempts - id).mapValues { it.value.toString() }))
            .apply()
    }

    private fun readUpgradeAttempts(
        prefs: android.content.SharedPreferences,
    ): Map<String, Int> =
        prefs.getString(KEY_UPGRADE_ATTEMPTS, null).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val attempts = line.substring(tab + 1).toIntOrNull() ?: return@mapNotNull null
                line.substring(0, tab) to attempts
            }
            .toMap()

    private fun encodePairs(pairs: Map<String, String>): String =
        pairs.entries.joinToString("\n") { (key, value) -> "$key\t$value" }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun online(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
