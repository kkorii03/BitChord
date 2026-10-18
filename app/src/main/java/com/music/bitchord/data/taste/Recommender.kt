package com.music.bitchord.data.taste

import java.util.Locale
import java.util.Random
import kotlin.math.ln

/**
 * Where a candidate came from, which says how much it is trusted before
 * anything is known about the listener.
 */
enum class CandidateSource {
    /** The track the station was seeded on — the strongest statement of intent. */
    RADIO_SEED,

    /** Another track from the seed's own radio batch. */
    RADIO_SESSION,

    /** Something the listener saved: a library row is an explicit preference. */
    FAVOURITE,

    /** A track the listener has not heard in a long time, offered again. */
    REDISCOVER,
}

/** A track worth offering, with the facts the ranking needs. */
data class Candidate(
    val videoId: String,
    val title: String,
    val artistNames: List<String>,
    /**
     * The song's name with version notes and video dressing removed, so covers
     * and alternate cuts of one song compare equal — see
     * [com.music.bitchord.playback.QueueBuilder] for the same idea.
     */
    val baseTitle: String,
    val source: CandidateSource = CandidateSource.RADIO_SESSION,
    /** Position in the source's own ordering; earlier is better. */
    val sourceRank: Int = 0,
    /** Who sings a cover, when [com.music.bitchord.data.cover.CoverTitles] names one. */
    val coverSinger: String? = null,
    val artistIds: List<String> = emptyList(),
)

/** The listener's current rating of a track, as last stated explicitly. */
enum class TasteRating { NONE, LIKE, DISLIKE }

/** One track's taste counters, already decayed to the moment they are read. */
data class TrackTaste(
    val plays: Double = 0.0,
    val completes: Double = 0.0,
    val skips: Double = 0.0,
    val likes: Double = 0.0,
    val dislikes: Double = 0.0,
    val lastPlayedAt: Long = 0L,
    /** Skip timestamps, newest last, kept only so the recent-skip rule can be applied. */
    val recentSkips: List<Long> = emptyList(),
    val rating: TasteRating = TasteRating.NONE,
)

/** One artist's or cover singer's counters, already decayed. */
data class ArtistTaste(
    val completes: Double = 0.0,
    val likes: Double = 0.0,
    val skips: Double = 0.0,
    val dislikes: Double = 0.0,
)

/**
 * Everything [Recommender] is allowed to know, built by [TasteSignals].
 *
 * Keys are already normalised by [key]; building this snapshot is where the
 * decay happened, so ranking itself is a pure function of values and cannot
 * drift with the clock.
 */
class TasteView(
    val tracks: Map<String, TrackTaste> = emptyMap(),
    val artists: Map<String, ArtistTaste> = emptyMap(),
    val coverSingers: Map<String, ArtistTaste> = emptyMap(),
    /** Artists with a disliked track, so a whole artist can be dropped. */
    val dislikedArtists: Set<String> = emptySet(),
) {
    companion object {
        /** The one spelling a name is looked up by: trimmed and lower-cased. */
        fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}

/**
 * What happened in the current listening session.
 *
 * The session is memory-only: it is what makes the next few tracks respond to
 * the last few, and it deliberately does not survive the process. A song
 * finished an hour ago before the app was killed has already been absorbed into
 * the long-term counters; carrying the moment across a restart would make the
 * opening of every session look like a continuation of the last one.
 */
data class SessionContext(
    /** Artists of the last five completed tracks, most recent last. */
    val completedArtists: List<String> = emptyList(),
    /** Cover singers of those same tracks. */
    val completedCoverSingers: List<String> = emptyList(),
    /** Artists of the last three tracks the listener skipped. */
    val skippedArtists: List<String> = emptyList(),
)

/**
 * Ranks candidate tracks for a personalised station.
 *
 * Pure Kotlin and no Android: the inputs are a [TasteView] snapshot, the
 * session's last few tracks, and an injected [Random], so every decision is a
 * function of its arguments and the tests pin the outcome.
 *
 * ## The score, and why each term is there
 *
 * - **Prior by source.** A favourite and the seed of a station are stronger
 *   signals than a track that merely came back from the radio. Ranking inside a
 *   source falls away with its position.
 * - **Affinity** is `log1p(completes + 2 * likes) - 0.5 * log1p(skips)`. Likes
 *   count double because a like is a deliberate statement where a completion may
 *   only be a listener who did not reach for the phone. The logarithm keeps one
 *   much-loved artist from drowning out everything else; skips subtract at half
 *   weight, so a listener who skips an artist now and then is not punished like
 *   one who always does.
 * - **Recency.** A track played within three hours is almost certainly still
 *   fresh in the listener's mind; within a day it is at least familiar.
 * - **Novelty** gives an unknown artist a small boost, so a station can find new
 *   music rather than only reshuffling what is known.
 * - **Session momentum** rewards following the last few completions and avoids
 *   the artists skipped moments ago.
 *
 * Hard rules sit outside the score: a disliked track, a track skipped twice in
 * the last month, and every track by a disliked artist are never returned.
 *
 * ## Exploration and diversity
 *
 * Roughly a quarter of the output is reserved for artists the taste store has
 * never seen, picked by score among them. That is epsilon-greedy: the injected
 * [Random] decides each exploration slot, and a fixed seed makes the result
 * reproducible. The final order is a greedy diversity pass: no third track in a
 * row by one artist or cover singer, and the same base title at least five
 * positions apart. When no candidate fits, the lowest-scored one that conflicts
 * is dropped — a station that cannot keep its spacing loses the weaker row
 * rather than the rule.
 */
object Recommender {

    /** How much of the output is offered to artists the taste store does not know. */
    const val EXPLORATION_EPSILON = 0.25

    /** Two plays of one base title must be this far apart in the output. */
    const val BASE_TITLE_SPACING = 5

    /** A track skipped this often inside [RECENT_SKIP_WINDOW_MS] is dropped. */
    const val RECENT_SKIP_LIMIT = 2

    private const val RECENT_SKIP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val THREE_HOURS_MS = 3L * 60 * 60 * 1000
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

    private const val ARTIST_WEIGHT = 1.2
    private const val COVER_SINGER_WEIGHT = 1.0
    private const val TRACK_WEIGHT = 0.6
    private const val TRACK_SKIP_WEIGHT = 2.5
    private const val ARTIST_SKIP_WEIGHT = 1.0
    private const val NOVELTY_BONUS = 0.3
    private const val SESSION_MOMENTUM_BONUS = 0.5
    private const val SESSION_SKIP_PENALTY = 1.0
    private const val SOURCE_RANK_STEP = 0.05

    fun rank(
        candidates: List<Candidate>,
        taste: TasteView,
        session: SessionContext,
        nowMs: Long,
        random: Random,
    ): List<Candidate> {
        val scored = candidates
            .asSequence()
            .filter { !isDropped(it, taste, nowMs) }
            .map { Scored(it, score(it, taste, session, nowMs)) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.candidate.videoId })
            // One row per track, whatever the source repeated.
            .distinctBy { it.candidate.videoId }
            .toMutableList()

        val out = ArrayList<Candidate>(scored.size)
        while (scored.isNotEmpty()) {
            val fillable = scored.filter { fits(it.candidate, out) }
            if (fillable.isEmpty()) {
                // Nothing can be placed without breaking a spacing rule. The
                // lower-scored of the conflicting rows is the one to lose.
                val victim = (scored.filterNot { fits(it.candidate, out) }.ifEmpty { scored })
                    .minByOrNull { it.score }
                    ?: break
                scored.remove(victim)
                continue
            }
            val exploration = fillable.filter { leadArtists(it.candidate).none { name -> name in taste.artists } }
            val pick = if (exploration.isNotEmpty() && random.nextDouble() < EXPLORATION_EPSILON) {
                exploration.first()
            } else {
                fillable.first()
            }
            out += pick.candidate
            scored.remove(pick)
        }
        return out
    }

    private class Scored(val candidate: Candidate, val score: Double)

    private fun score(
        candidate: Candidate,
        taste: TasteView,
        session: SessionContext,
        nowMs: Long,
    ): Double {
        val artists = leadArtists(candidate)
        val artistEntries = artists.mapNotNull(taste.artists::get)
        val artistAffinity = artistEntries.maxOfOrNull { affinity(it) } ?: 0.0
        val artistSkip = artistEntries.maxOfOrNull { skipPenalty(it) } ?: 0.0

        val singer = candidate.coverSinger?.let { taste.coverSingers[TasteView.key(it)] }
        val singerAffinity = singer?.let { affinity(it) } ?: 0.0
        val track = taste.tracks[candidate.videoId]
        val trackAffinity = track?.let { affinity(it) } ?: 0.0

        var score = prior(candidate.source, candidate.sourceRank)
        score += ARTIST_WEIGHT * artistAffinity
        score += COVER_SINGER_WEIGHT * singerAffinity
        score += TRACK_WEIGHT * trackAffinity
        score -= TRACK_SKIP_WEIGHT * (track?.let { skipPenalty(it) } ?: 0.0)
        score -= ARTIST_SKIP_WEIGHT * artistSkip
        score -= recencyPenalty(track, nowMs)

        if (artists.isEmpty() || artists.none { name -> name in taste.artists }) score += NOVELTY_BONUS

        val completed = session.completedArtists.mapTo(HashSet()) { TasteView.key(it) }
        val completedSingers = session.completedCoverSingers.mapTo(HashSet()) { TasteView.key(it) }
        val skipped = session.skippedArtists.mapTo(HashSet()) { TasteView.key(it) }
        val sharesCompleted = artists.any { it in completed } ||
            candidate.coverSinger?.let { TasteView.key(it) in completedSingers } == true
        if (sharesCompleted) score += SESSION_MOMENTUM_BONUS
        if (artists.any { it in skipped }) score -= SESSION_SKIP_PENALTY
        return score
    }

    private fun prior(source: CandidateSource, sourceRank: Int): Double {
        val base = when (source) {
            CandidateSource.RADIO_SEED -> 1.0
            CandidateSource.RADIO_SESSION -> 0.8
            CandidateSource.FAVOURITE -> 1.2
            CandidateSource.REDISCOVER -> 0.5
        }
        return base - SOURCE_RANK_STEP * sourceRank.coerceAtLeast(0)
    }

    private fun affinity(taste: ArtistTaste): Double =
        ln(1.0 + taste.completes + 2.0 * taste.likes) - 0.5 * ln(1.0 + taste.skips)

    private fun affinity(taste: TrackTaste): Double =
        ln(1.0 + taste.completes + 2.0 * taste.likes) - 0.5 * ln(1.0 + taste.skips)

    private fun skipPenalty(taste: ArtistTaste): Double = ln(1.0 + taste.skips)

    private fun skipPenalty(taste: TrackTaste): Double = ln(1.0 + taste.skips)

    private fun recencyPenalty(track: TrackTaste?, nowMs: Long): Double {
        val playedAt = track?.lastPlayedAt ?: return 0.0
        if (playedAt <= 0L || playedAt > nowMs) return 0.0
        val elapsed = nowMs - playedAt
        return when {
            elapsed <= THREE_HOURS_MS -> 3.0
            elapsed <= ONE_DAY_MS -> 1.0
            else -> 0.0
        }
    }

    private fun isDropped(candidate: Candidate, taste: TasteView, nowMs: Long): Boolean {
        val track = taste.tracks[candidate.videoId]
        if (track?.rating == TasteRating.DISLIKE) return true
        if (track != null) {
            val windowStart = nowMs - RECENT_SKIP_WINDOW_MS
            val recent = track.recentSkips.count { it >= windowStart && it <= nowMs }
            if (recent >= RECENT_SKIP_LIMIT) return true
        }
        return leadArtists(candidate).any(taste.dislikedArtists::contains)
    }

    /** Every name on the credit, normalised. */
    private fun leadArtists(candidate: Candidate): List<String> =
        candidate.artistNames.map { TasteView.key(it) }.filter { it.isNotEmpty() }

    /**
     * Whether [candidate] may follow [out] without breaking a diversity rule.
     *
     * Only the last two rows are consulted for the artist rule, because "no more
     * than two in a row" is exactly what placing a third would break. The base
     * title window is the four positions before the candidate's own, which is
     * the same statement as "at least five positions apart".
     */
    private fun fits(candidate: Candidate, out: List<Candidate>): Boolean {
        if (out.isEmpty()) return true
        val artist = leadArtists(candidate).firstOrNull()
        if (artist != null && out.size >= 2) {
            val last = leadArtists(out.last()).firstOrNull()
            val previous = leadArtists(out[out.size - 2]).firstOrNull()
            if (artist == last && artist == previous) return false
        }
        val singer = candidate.coverSinger?.let { TasteView.key(it) }
        if (singer != null && out.size >= 2) {
            val last = out.last().coverSinger?.let { TasteView.key(it) }
            val previous = out[out.size - 2].coverSinger?.let { TasteView.key(it) }
            if (singer == last && singer == previous) return false
        }
        val base = baseKey(candidate.baseTitle)
        if (base.isNotEmpty()) {
            val window = out.takeLast(BASE_TITLE_SPACING - 1)
            if (window.any { baseKey(it.baseTitle) == base }) return false
        }
        return true
    }

    private fun baseKey(title: String): String = title.trim().lowercase(Locale.ROOT)
}



