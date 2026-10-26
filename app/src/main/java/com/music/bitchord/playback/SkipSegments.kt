package com.music.bitchord.playback

import android.content.Context
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * One SponsorBlock segment that can be jumped over — the talking at the start
 * of a cover video, the channel plug at the end.
 *
 * Only segments YouTube users submitted with `actionType: "skip"` get this far:
 * a "mute" segment is meant to be played quietly and a "poi" is a highlight,
 * and jumping either of them would leave playback somewhere the submitter
 * never asked for.
 */
data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val uuid: String,
) {
    /** What [PlaybackService] uses to remember it has already been skipped. */
    val id: String get() = uuid.ifBlank { "$category:$startMs:$endMs" }

    val lengthMs: Long get() = endMs - startMs
}

/** What should happen to the playhead at one position. */
internal sealed interface SkipAction {
    data object None : SkipAction

    /** Jump to the end of [segment]. */
    data class Skip(val segment: SkipSegment) : SkipAction

    /**
     * The listener seeked into [segment] themselves a moment ago, so leave it
     * alone rather than pulling them back out of the part they asked for.
     */
    data class Leave(val segment: SkipSegment) : SkipAction
}

internal val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

internal fun isYouTubeVideoId(videoId: String): Boolean = YOUTUBE_VIDEO_ID.matches(videoId)

internal const val MUSIC_OFF_TOPIC = "music_offtopic"

/** The categories the secondary switch adds to the one this feature is for. */
internal val EXTRA_SKIP_CATEGORIES = listOf("sponsor", "selfpromo", "intro", "outro")

/**
 * Everything asked for in the one lookup, regardless of the current switches.
 * Requesting all of them together means turning the secondary switch on in the
 * middle of a track takes effect without another round trip.
 */
internal val REQUESTED_SKIP_CATEGORIES = listOf(MUSIC_OFF_TOPIC) + EXTRA_SKIP_CATEGORIES

internal fun skipCategories(includeExtras: Boolean): Set<String> =
    if (includeExtras) REQUESTED_SKIP_CATEGORIES.toSet() else setOf(MUSIC_OFF_TOPIC)

/** A seek into a segment this recent is treated as the listener's own choice. */
internal const val MANUAL_SEEK_GRACE_MS = 2_000L

/** No jump whose end is this close to the track's end is worth making. */
internal const val EDGE_OF_VIDEO_MS = 1_000L

/**
 * A segment covering more than this share of the video is never skipped: it is
 * almost certainly a mis-submission, and jumping it would delete most of the
 * song.
 */
internal const val MAX_SEGMENT_PERCENT = 80

internal fun isTooMuchOfTheVideo(segment: SkipSegment, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    if (segment.endMs >= durationMs - EDGE_OF_VIDEO_MS) return true
    return segment.lengthMs * 100L > durationMs * MAX_SEGMENT_PERCENT
}

/**
 * Which segment, if any, the playhead at [positionMs] should be moved out of.
 *
 * Pure so the guards can be tested directly: the service supplies the
 * categories the listener left switched on, the segments already handled this
 * playback, and the last seek the listener made by hand.
 */
internal fun decideSkip(
    segments: List<SkipSegment>,
    positionMs: Long,
    durationMs: Long,
    categories: Set<String>,
    skippedIds: Set<String>,
    lastManualSeekAtMs: Long,
    lastManualSeekPositionMs: Long,
    nowMs: Long,
): SkipAction {
    val containing = segments
        .asSequence()
        .filter { it.category in categories && it.id !in skippedIds }
        .filter { positionMs >= it.startMs && positionMs < it.endMs }
        .maxByOrNull { it.startMs }
        ?: return SkipAction.None
    if (isTooMuchOfTheVideo(containing, durationMs)) return SkipAction.None
    val seekedIntoIt = lastManualSeekAtMs > 0L &&
        nowMs - lastManualSeekAtMs <= MANUAL_SEEK_GRACE_MS &&
        lastManualSeekPositionMs >= containing.startMs &&
        lastManualSeekPositionMs < containing.endMs
    return if (seekedIntoIt) SkipAction.Leave(containing) else SkipAction.Skip(containing)
}

/**
 * The first four hex characters of sha256(videoId), which is how the hashed
 * endpoint is asked for a video without naming it — see
 * https://wiki.sponsor.ajay.app/index.php/API_Docs#GET_/api/skipSegments/:sha256HashPrefix
 */
internal fun videoIdHashPrefix(videoId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
    return buildString(4) {
        for (index in 0 until 2) {
            val value = digest[index].toInt() and 0xFF
            append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
    }
}

/** The plain `skipSegments?videoID=` answer: an array of segments. */
internal fun parseSkipSegments(body: String): List<SkipSegment> = runCatching {
    JSON.decodeFromString(SEGMENT_LIST, body).mapNotNull(RawSegment::toSegment)
}.getOrDefault(emptyList())

/** The hashed endpoint's answer: entries for several videos, one of them ours. */
internal fun parseHashedSkipSegments(body: String, videoId: String): List<SkipSegment> = runCatching {
    JSON.decodeFromString(VIDEO_LIST, body)
        .firstOrNull { it.videoId == videoId }
        ?.segments
        .orEmpty()
        .mapNotNull(RawSegment::toSegment)
}.getOrDefault(emptyList())

private fun RawSegment.toSegment(): SkipSegment? {
    if (actionType != "skip") return null
    if (segment.size < 2) return null
    val startMs = (segment[0] * 1000.0).roundToLong()
    val endMs = (segment[1] * 1000.0).roundToLong()
    if (startMs < 0L || endMs <= startMs) return null
    return SkipSegment(startMs, endMs, category, uuid)
}

@Serializable
private data class RawSegment(
    val segment: List<Double> = emptyList(),
    val category: String = "",
    val actionType: String = "skip",
    @SerialName("UUID") val uuid: String = "",
)

@Serializable
private data class RawVideoSegments(
    @SerialName("videoID") val videoId: String = "",
    val segments: List<RawSegment> = emptyList(),
)

private val JSON = Json { ignoreUnknownKeys = true }
private val SEGMENT_LIST = ListSerializer(RawSegment.serializer())
private val VIDEO_LIST = ListSerializer(RawVideoSegments.serializer())
private const val HEX = "0123456789abcdef"

/**
 * SponsorBlock lookups for the track now playing.
 *
 * The service asks once per track, off the main thread and with a three second
 * ceiling, and playback is never held up waiting for the answer: by the time
 * anything comes back the track is already running, and the skip ticker simply
 * starts seeing segments. Everything is answered from memory after the first
 * look at a video, and answers that had segments are kept on disk so a replay
 * the next day costs nothing.
 *
 * The hashed endpoint is used rather than `?videoID=`: it asks the server for
 * every submission under the same hash prefix, so it cannot see which video is
 * being played any more precisely than that.
 */
object SkipSegments {

    private const val TAG = "BitChordSponsorBlock"
    private const val BASE_URL = "https://sponsor.ajay.app/api/skipSegments"
    private const val FILE_NAME = "skip_segments.json"
    private const val MAX_VIDEOS = 500
    private const val KEEP_DAYS = 30L
    private const val REQUEST_TIMEOUT_SECONDS = 3L

    private lateinit var file: File

    private val memory = ConcurrentHashMap<String, List<SkipSegment>>()
    private val storedAt = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    @Volatile
    private var loaded = false

    /**
     * The app's one HTTP client, with a call timeout of its own. `newBuilder()`
     * keeps the shared connection pool and dispatcher, so the lookup rides the
     * app's proxy and connection configuration rather than standing up a second
     * stack; only the three second ceiling is new.
     */
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    private val ready: Boolean get() = this::file.isInitialized

    /**
     * The segments known for [videoId], asking SponsorBlock if this is the
     * first time this session. Never throws: a failed lookup is an empty list,
     * which the caller reads as "nothing to skip".
     */
    suspend fun segmentsFor(videoId: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (!ready || !isYouTubeVideoId(videoId)) return@withContext emptyList()
        memory[videoId]?.let { return@withContext it }
        ensureLoaded()
        memory[videoId]?.let { return@withContext it }
        val found = request(videoId) ?: return@withContext emptyList()
        memory[videoId] = found
        if (found.isNotEmpty()) {
            storedAt[videoId] = System.currentTimeMillis()
            save()
        }
        found
    }

    private fun request(videoId: String): List<SkipSegment>? {
        val categories = URLEncoder.encode(
            REQUESTED_SKIP_CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" },
            "UTF-8",
        )
        val request = Request.Builder()
            .url("$BASE_URL/${videoIdHashPrefix(videoId)}?categories=$categories")
            .header("User-Agent", "BitChord/${BuildConfig.VERSION_NAME}")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> emptyList()
                    !response.isSuccessful -> {
                        TrackLog.w(TAG, "lookup HTTP ${response.code}")
                        null
                    }
                    else -> parseHashedSkipSegments(response.body?.string().orEmpty(), videoId)
                }
            }
        }.onFailure {
            Log.d(TAG, "lookup failed for $videoId: ${it.message}")
            TrackLog.w(TAG, "lookup failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                if (file.exists()) JSON.decodeFromString(Stored.serializer(), file.readText()) else null
            }.onSuccess { stored ->
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_DAYS)
                stored?.videos
                    ?.filter { it.at >= cutoff }
                    ?.forEach { video ->
                        memory[video.videoId] = video.segments.map {
                            SkipSegment(it.startMs, it.endMs, it.category, it.uuid)
                        }
                        storedAt[video.videoId] = video.at
                    }
            }.onFailure {
                Log.w(TAG, "discarding unreadable SponsorBlock cache", it)
                runCatching { file.delete() }
            }
            loaded = true
        }
    }

    private fun save() {
        synchronized(lock) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_DAYS)
            val videos = storedAt.entries
                .filter { it.value >= cutoff }
                .sortedByDescending { it.value }
                .take(MAX_VIDEOS)
                .mapNotNull { (videoId, at) ->
                    val segments = memory[videoId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    StoredVideo(
                        videoId = videoId,
                        at = at,
                        segments = segments.map { StoredSegment(it.startMs, it.endMs, it.category, it.uuid) },
                    )
                }
            runCatching {
                file.writeText(JSON.encodeToString(Stored.serializer(), Stored(videos = videos)))
            }.onFailure { Log.w(TAG, "could not write SponsorBlock cache", it) }
        }
    }

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val videos: List<StoredVideo> = emptyList(),
    )

    @Serializable
    private data class StoredVideo(
        val videoId: String,
        val at: Long,
        val segments: List<StoredSegment> = emptyList(),
    )

    @Serializable
    private data class StoredSegment(
        val startMs: Long,
        val endMs: Long,
        val category: String,
        val uuid: String = "",
    )
}
