package com.music.bitchord.data.stats

import com.music.bitchord.data.DebugLog as Debug
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ListeningSink
import com.music.bitchord.data.settings.PrivateListening
import com.music.bitchord.data.taste.TasteSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Turns the player's ticking into the numbers [ListeningStats] keeps.
 *
 * ## Why it samples rather than measures
 *
 * The tempting implementation reads the track's position when it ends and calls
 * that the listening. It is wrong in every direction that matters: a track
 * skipped at 0:20 reports twenty seconds it did play, a track seeked back over
 * reports less than it played, a track left paused for an hour reports the same
 * as one played through, and a track the process is killed under reports
 * nothing at all.
 *
 * So this is fed from the service's existing progress sampler, which only ticks
 * while audio is actually coming out, and each tick contributes the *wall-clock*
 * time since the last one. Seeking cannot inflate it because position is never
 * consulted. Pausing cannot inflate it because no tick happens. The gap either
 * side of a pause is capped at [MAX_STEP_MS], which is what stops an hour on the
 * lock screen from arriving as an hour of listening on the first tick after
 * resume.
 *
 * The cost is that the few seconds before the first tick of each track are not
 * counted. That is a systematic undercount of at most one sample interval per
 * track, and it is the right way to be wrong: every alternative overcounts, and
 * a Replay that flatters is worth less than one that is a little shy.
 *
 * ## Minutes and plays are different questions
 *
 * Minutes accumulate continuously. A *play* is counted once, when enough of the
 * track has gone by to call it listened to — the same half-or-four-minutes rule
 * the scrobbler uses, so the two never disagree about what a play is.
 */
object ListeningRecorder {

    private var currentId: String? = null
    private var lastSampleAt: Long = 0L
    private var playedThisTrack: Long = 0L
    private var playCounted = false
    private var completionCounted = false
    private var samplesSinceFlush = 0

    /**
     * The playthrough private mode interrupted.
     *
     * Set while private listening is on and cleared only when a different
     * track arrives. Without it, turning private mode off halfway through a
     * song would let the rest of that same playthrough count — a play made
     * partly in private would be written after the fact.
     */
    private var privatePlaythroughId: String? = null

    /**
     * The track whose start has already been announced to Recents.
     *
     * Kept across a pause or stop so resuming the same track does not announce
     * it again: the store emits at most twice per track, on the first played
     * step and when the play is counted. A different track clears it.
     */
    private var startReportedId: String? = null

    private fun recordingAllowed(): Boolean {
        // Cheap time comparison, not a timer: the first sample after a session
        // has run its six hours is what ends it. Every other sink then sees the
        // same switched-off flow PlaybackService already watches.
        AppSettings.checkPrivateListeningExpiry()
        return PrivateListening.allows(
            ListeningSink.LISTENING_STATS,
            AppSettings.privateListening.value,
        )
    }

    /**
     * One tick of the player's sampler.
     *
     * [durationMs] is the decoder's figure when it has one; the row's stated
     * runtime stands in until it does, and a track with neither simply has to
     * clear the thirty-second floor to count as a play.
     */
    @Synchronized
    fun onSample(song: Song, durationMs: Long) {
        if (!recordingAllowed()) {
            // Keep reminding ourselves which playthrough is private; a private
            // session that moves on to another track must suppress that one too.
            if (privatePlaythroughId != song.videoId) {
                Debug.i(TAG, "recents: not recorded, private listening")
            }
            privatePlaythroughId = song.videoId
            if (currentId != null) {
                // Anything already counted belongs to the part that was not
                // private. Flush it now, then stop the clock so the gap across
                // the switch is not credited to either side.
                currentId = null
                playedThisTrack = 0L
                playCounted = false
                completionCounted = false
                samplesSinceFlush = 0
                ListeningStats.flush(force = true)
            }
            return
        }
        if (privatePlaythroughId != null) {
            if (song.videoId == privatePlaythroughId) return
            privatePlaythroughId = null
        }
        val now = System.currentTimeMillis()
        if (song.videoId != currentId) {
            // A new track anchors the clock and contributes nothing yet — see
            // the class note on undercounting.
            if (startReportedId != song.videoId) startReportedId = null
            currentId = song.videoId
            lastSampleAt = now
            playedThisTrack = 0L
            playCounted = false
            completionCounted = false
            return
        }
        val step = (now - lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        lastSampleAt = now
        if (step <= 0L) return
        val playedBefore = playedThisTrack
        playedThisTrack += step

        val length = durationMs.takeIf { it > 0 } ?: song.durationMillis()
        val threshold = if (length > 0) {
            min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
        } else {
            PLAY_FLOOR_MS
        }
        val counts = !playCounted && playedThisTrack >= threshold
        if (counts) playCounted = true

        val record = enriched(song)
        if (announcesRecentsStart(playedBefore, startReportedId, song.videoId)) {
            startReportedId = song.videoId
            if (ListeningStats.isReady) {
                Debug.i(TAG, "recents: start ${record.title} (${record.videoId})")
                // Files this step and wakes the shelf now. The play count, when
                // it comes, is a separate event with no time of its own.
                ListeningStats.recordStarted(record, step)
                if (counts) ListeningStats.record(record, 0L, countsAsPlay = true)
            } else {
                Debug.i(TAG, "recents: not recorded, stats not ready")
            }
        } else {
            ListeningStats.record(record, step, counts)
        }

        // The recommendation store reads the same two events the listening
        // record does, at the moment they are already decided here: a play is
        // the same threshold, and a completion is four fifths of the track —
        // close enough to "to the end" that a listener who stops at the outro
        // is not filed as having walked away. Neither runs per sample: the
        // flags keep each event to once per playthrough.
        if (counts) {
            Debug.i(TAG, "recents: counted ${record.title}")
            TasteSignals.onPlayed(record)
        }
        if (!completionCounted && length > 0L && playedThisTrack >= length * 4 / 5) {
            completionCounted = true
            TasteSignals.onCompleted(record)
        }

        if (++samplesSinceFlush >= FLUSH_EVERY) {
            samplesSinceFlush = 0
            ListeningStats.flush()
        }
    }

    /**
     * Playback stopped, paused, or moved on.
     *
     * Forgetting the current track is what makes the *next* tick anchor rather
     * than contribute: without it, a player paused for an afternoon would hand
     * [MAX_STEP_MS] of listening to whatever was on screen when it resumed.
     */
    @Synchronized
    fun onStopped() {
        currentId = null
        playedThisTrack = 0L
        playCounted = false
        completionCounted = false
        samplesSinceFlush = 0
        // A pause or a stop is the one moment the tail must reach disk.
        ListeningStats.flush(force = true)
    }

    /**
     * The toggle turned private listening on.
     *
     * Closes the ordinary part of the current playthrough straight away rather
     * than waiting for the next sampler tick, and forgets the track so the next
     * tick anchors instead of crediting the gap across the switch.
     */
    @Synchronized
    fun onPrivateModeEnabled() {
        if (currentId == null) return
        currentId = null
        playedThisTrack = 0L
        playCounted = false
        completionCounted = false
        samplesSinceFlush = 0
        ListeningStats.flush(force = true)
    }

    /**
     * The queue ran out, which is the one way a track can reach its end
     * without the sampler having seen 80% of it — a song shorter than a couple
     * of sample intervals, or one whose last tick arrived early.
     *
     * Only closes out the track the sampler is still on; anything else ended
     * through a transition which has already been accounted for.
     */
    @Synchronized
    fun onEnded(song: Song) {
        if (!recordingAllowed()) return
        if (song.videoId == privatePlaythroughId) return
        if (song.videoId != currentId || completionCounted) return
        completionCounted = true
        TasteSignals.onCompleted(enriched(song))
    }

    // ── Filling in what the queue didn't carry ──────────────────────────────

    /**
     * What a lookup found out about a track, by video id.
     *
     * ## Why a lookup is needed at all
     *
     * Most tracks reach the player without an album. A row off the home feed,
     * a search hit, an AutoPlay suggestion — none of them state one, because
     * nothing on those surfaces draws one. That is invisible everywhere else in
     * the app and fatal here: an album chart counted off what the queue carries
     * is empty for almost everybody, and the artist rows have no page to open.
     *
     * The player already asks this same question, but only while its screen is
     * up (see MainActivity's `links`), so listening with the phone in a pocket —
     * which is most listening — would be exactly the listening that went
     * uncredited.
     *
     * One request per track, kept for the life of the process, and the answer is
     * cached a second time by the repository itself.
     */
    private val extras = ConcurrentHashMap<String, Song>()

    /** Ids already sent for, so a track on repeat is asked about once. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [song] with whatever the lookup has found so far.
     *
     * Returns the song unchanged while the request is in flight rather than
     * holding the sample back: a few seconds filed without an album is a few
     * seconds missing from the album chart, and blocking the sampler on a
     * network call would be a few seconds missing from everything.
     */
    private fun enriched(song: Song): Song {
        extras[song.videoId]?.let { extra ->
            return song.copy(
                artistId = song.artistId ?: extra.artistId,
                albumId = song.albumId ?: extra.albumId,
                albumName = song.albumName ?: extra.albumName,
            )
        }
        if (song.albumName != null && song.artistId != null) return song
        // Only tracks YouTube can answer for. A file on the device and a
        // source-module track both carry ids this endpoint has never heard of,
        // and asking would be a failed request per play, forever.
        if (song.localUri != null || song.videoId.length != YOUTUBE_ID_LENGTH) return song
        if (asked.add(song.videoId)) {
            scope.launch {
                YtMusicRepository.trackLinks(song.videoId).getOrNull()?.let {
                    extras[song.videoId] = it
                }
            }
        }
        return song
    }

    /**
     * The most a single tick may contribute.
     *
     * A shade over the sampler's interval, so an ordinary tick that arrived late
     * — a busy main thread, a device coming out of doze — is still counted in
     * full, while the unbounded gap across a pause is not.
     */
    private const val MAX_STEP_MS = 8_000L

    /** Under half a minute is not a listen, however it ended. */
    private const val PLAY_FLOOR_MS = 30_000L

    /** Past four minutes, half a track is more listening than anyone disputes. */
    private const val PLAY_CEILING_MS = 4 * 60 * 1000L

    /** Two minutes of listening between routine disk writes. */
    private const val FLUSH_EVERY = 24

    /** What a YouTube video id looks like, and nothing else this app plays does. */
    private const val YOUTUBE_ID_LENGTH = 11

    /** Diagnostic tag shared with the rest of the Recents trail. */
    private const val TAG = "BitChord"
}

/**
 * Whether this sample is the point at which a track's start is announced.
 *
 * [playedBeforeMs] is the track's total before this sample, so zero is the
 * first positive step. [announcedId] is the track whose start has already been
 * announced; a pause resets the total but must not announce the same track a
 * second time.
 *
 * Pure so the "at most twice per track" rule can be tested without a player.
 */
internal fun announcesRecentsStart(
    playedBeforeMs: Long,
    announcedId: String?,
    videoId: String,
): Boolean = playedBeforeMs == 0L && announcedId != videoId
