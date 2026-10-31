package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlin.math.abs
import java.util.Locale

/**
 * Builds the station that plays on after a one-off song.
 *
 * Shared: Android and the desktop assemble the same station from the same
 * watch queue, so the de-duplication and the per-artist caps are one
 * implementation rather than two that drift.
 *
 * Two problems this exists to solve:
 *
 *  - YouTube's watch queues routinely carry the same recording twice — the
 *    official audio and the music video are separate videoIds with near
 *    identical titles — so de-duping on id alone lets a track play, then play
 *    again as its video. Songs are matched on a normalised title plus lead
 *    artist instead.
 *
 *  - A radio mix leans hard on the seed's own artist. Capping how many tracks
 *    any one artist contributes keeps a station from turning into a single
 *    artist's discography.
 */
object QueueBuilder {

    /** No more than this many tracks by one artist in a single batch. */
    const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    const val SEED_ARTIST_LIMIT = 4

    /** Fewer videos than this in a mix and a video station plays on with its songs too. */
    const val MIN_VIDEO_STATION = 3

    /**
     * The subset of [candidates] worth appending after [existing]: nothing
     * already queued, nothing repeated, at most [limit] tracks.
     *
     * [seedArtist] is the artist the station was started for. Left out, the
     * last entry of [existing] stands in, which is only right when the list
     * *is* the station build. AutoPlay passes the queue plus the session's own
     * history, so that last entry is usually the autoplay track that played
     * just before the seed — and the seed's own artist then got the ordinary
     * two-track cap instead of the four a station is seeded on.
     */
    fun extend(existing: List<Song>, candidates: List<Song>, limit: Int, preferVideos: Boolean = false): List<Song> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = (seedArtist?.takeIf { it.isNotBlank() } ?: existing.lastOrNull()?.artist)
            ?.let(::artistSet)
            .orEmpty()
        val out = mutableListOf<Song>()

        // A mix pairs all but every track with its own music-video upload —
        // the same recording under a different id, titled far enough apart
        // ("Dildaara (Stand By Me)" against "Lyrical Video: Dildara Song")
        // that no title match will catch it. The catalogue cut is the one a
        // music app wants; videos only stand in when there is nothing else.
        // [preferVideos] turns that round for a station playing on from a music
        // video: its videos, where the mix has enough of them to be one.
        val songsOnly = candidates.filterNot { it.isVideo }
        val videosOnly = candidates.filter { it.isVideo }
        val ordered = when {
            preferVideos && videosOnly.size >= MIN_VIDEO_STATION -> videosOnly
            preferVideos -> candidates
            else -> songsOnly.ifEmpty { candidates }
        }

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (taken.any { isSameRecording(it, candidate) || isVersionOf(it, candidate) }) continue

            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }

            taken += candidate
            out += candidate
        }
        return out
    }

    /**
     * Whether two entries are the same recording. Ids differ between the audio
     * and video cuts of a track, which is exactly the case id equality misses.
     */
    fun isSameRecording(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        val title = normalisedTitle(a.title)
        if (title.isEmpty() || title != normalisedTitle(b.title)) return false
        // One shared name is enough. The two cuts of a track get billed in
        // whatever order the upload used — "Pritam, Arijit Singh & Shilpa Rao"
        // against "Shilpa Rao, Arijit Singh, & Pritam" is one song, twice.
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        if (left.isEmpty() || right.isEmpty() || left.any { it in right }) return true
        // The same act billed in two scripts: YouTube credits a Korean-region
        // upload to "에이제이알" and the catalogue track to "AJR", so no name
        // is shared even though it is one song. Taken as the same recording
        // only when the scripts really do differ and the lengths, where both
        // are known, agree — a same-titled song by someone else is neither.
        if (!creditedInDifferentScripts(a.artist, b.artist)) return false
        val lengthA = a.durationMillis()
        val lengthB = b.durationMillis()
        return lengthA <= 0L || lengthB <= 0L || abs(lengthA - lengthB) <= SAME_LENGTH_SLACK_MS
    }

    /**
     * Whether [b] is another take of [a]'s song by the same act — a live
     * recording, an acoustic or remastered cut, a remix. Different recordings,
     * so [isSameRecording] keeps them apart; but a station that follows
     * "Helena" with "Helena (Live for BBC Radio 1)" is playing the same song
     * twice, so the station leaves them out.
     */
    fun isVersionOf(a: Song, b: Song): Boolean {
        val base = baseTitle(a.title)
        if (base.isEmpty() || base != baseTitle(b.title)) return false
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        return left.isNotEmpty() && right.isNotEmpty() && left.any { it in right }
    }

    /**
     * The title with every bracketed note and any " - …" tail taken off.
     *
     * Public so the taste pool can name a candidate the same way the queue
     * does, and two takes of one song compare equal there too.
     */
    fun baseTitle(raw: String): String =
        normalisedTitle(raw.replace(BRACKETED_NOTE, " ").substringBefore(" - "))

    private val BRACKETED_NOTE = Regex("""\([^)]*\)|\[[^]]*]""")

    /** Lengths this close are one recording's audio and video cuts. */
    private const val SAME_LENGTH_SLACK_MS = 6_000L

    /**
     * One credit in Latin letters only and the other with none at all —
     * Hangul, kana or Han instead.
     */
    private fun creditedInDifferentScripts(a: String, b: String): Boolean {
        val aLatin = hasLatin(a)
        val bLatin = hasLatin(b)
        val aOther = hasNonLatinLetters(a)
        val bOther = hasNonLatinLetters(b)
        return (aLatin && !aOther && bOther && !bLatin) || (bLatin && !bOther && aOther && !aLatin)
    }

    private fun hasLatin(text: String): Boolean = text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
    }

    private fun hasNonLatinLetters(text: String): Boolean = text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
    }

    /**
     * `Kesariya (From "Brahmastra") | Official Video` and `Kesariya` are one
     * recording as far as a queue is concerned. Remix and cover markers are
     * deliberately left in — those really are different tracks.
     */
    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /** The cast behind a credit, split out so billing order stops mattering. */
    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .flatMap { part ->
            // "My Chemical Romance(마이 케미컬 로맨스)" answers to either half as
            // well as to the whole, so it meets the plain "My Chemical Romance".
            val whole = part.clean()
            val aliases = (listOf(part.replace(ALIAS, " ")) + ALIAS.findAll(part).map { it.value })
                .map { it.clean() }
                .filter { it.length >= 2 && it != whole }
            listOf(whole) + aliases
        }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun String.clean(): String = replace(PUNCTUATION, " ").replace(SPACES, " ").trim()

    private val ALIAS = Regex("""\([^)]*\)""")

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
