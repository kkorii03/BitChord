package com.music.bitchord.data.sources

import com.music.bitchord.data.BoundedMemo
import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoTrackMatcher // [KO] track-matcher
import com.music.bitchord.ko.KoTitleRules // [KO] title-rules
import kotlin.math.abs
import java.text.Normalizer
import java.util.Locale

/**
 * Decides whether one catalogue's track is the same recording as another's,
 * and what to ask that catalogue for in the first place.
 *
 * Split out of [SourceResolver] because it is the only part of the source
 * layer that is a judgement rather than plumbing, and because both of its
 * failure modes are silent:
 *
 *  - **Too loose** and the wrong recording plays under the right title — a
 *    cover, a remix, an hour-long loop — with nothing on screen to say so.
 *  - **Too strict** and the module the user configured is quietly never used,
 *    which is what "Paniyon Sa (From "Satyamev Jayate")" by Atif Aslam did
 *    against a catalogue holding the same audio as "Paniyon Sa" by
 *    Atif Aslam, Tulsi Kumar.
 *
 * The way out of both is to be explicit about *what part of a title carries
 * identity*. Services disagree constantly about the packaging — the film a
 * song is from, the "Official Audio" tag, which of the credited singers make
 * it into the title — and agree about the recording underneath. So the title
 * is taken apart into three pieces:
 *
 *  - [TitleParts.words], the title proper. Must agree exactly.
 *  - [TitleParts.versions], the words that mean *a different take* — remix,
 *    live, acoustic. Must agree exactly, in both directions: "Song (Live)"
 *    is not "Song", and neither is the other way round.
 *  - [TitleParts.context], everything else thrown away with the brackets.
 *    Never a veto, only a tie-break, because one service writing the film
 *    name in the title is not a disagreement about the audio.
 *
 * Artist and duration are then checked against that: a shared credit is
 * required when both sides name one, and a runtime far from the one asked for
 * rules a candidate out however well its title reads.
 */
object TrackMatcher {

    private const val MEMO_ENTRIES = 256
    private val queryMemo = BoundedMemo<Pair<String, String>, List<String>>(MEMO_ENTRIES)

    /** The recording being looked for, as much of it as the queue knows. */
    data class Target(
        val title: String,
        val artist: String = "",
        /** Runtime in whole seconds; null when the queue row never carried one. */
        val durationSec: Int? = null,
        /** Release name, when the queue knows it. Used to separate catalogue collisions. */
        val album: String? = null,
        /** Explicit/clean edition, null when the originating catalogue did not say. */
        val isExplicit: Boolean? = null,
        /** Music-video timing includes visual intros/outros that catalogue audio omits. */
        val isVideo: Boolean = false,
        /** The artist's own channel, when the queue row knew it. */
        val artistId: String? = null,
        /** Every credited artist's channel the row stated, [artistId] included. */
        val artistIds: List<String> = emptyList(),
        /** The release's browse id, when the queue row knew it. */
        val albumId: String? = null,
        /** A soundtrack upload that printed no performer, so title/work is the only evidence. */
        val noPerformer: Boolean = false,
    )

    fun targetOf(song: Song) = Target(
        // A cover is matched as the upload it is — see CoverTitles.
        song.originalTitle ?: song.title,
        song.originalArtist ?: song.artist,
        secondsOf(song.durationText),
        song.albumName,
        song.isExplicit,
        song.isVideo,
        song.artistId,
        (song.artists.mapNotNull { it.browseId } + listOfNotNull(song.artistId))
            .filter { it.isNotBlank() }
            .distinct(),
        song.albumId,
    )

    // ── Asking ──────────────────────────────────────────────────────────────

    /**
     * What to put to a source's search box, best query first.
     *
     * The raw title is deliberately *not* one of them. A YouTube title carries
     * the packaging — `Paniyon Sa (From "Satyamev Jayate")` — and handing that
     * verbatim to a catalogue that lists the track as `Paniyon Sa` is asking it
     * to match on words it has never stored. Most search backends score that as
     * a poor hit or no hit at all, and the track was then written off as
     * missing before any of the matching below ever ran.
     *
     * Two queries, not one: the second drops the artist, for the catalogues
     * that credit a track to the composer or the film rather than the singer
     * and would otherwise score every result down. A kana title gets a third,
     * in the other script (below).
     */
    fun queries(target: Target): List<String> =
        queryMemo.getOrPut(target.title to target.artist) { queriesUncached(target) }

    private fun queriesUncached(target: Target): List<String> {
        val identity = audioSwitchIdentity(target.title, target.artist)
        if (!identity.hasSoundtrackTail) return plainQueries(target.title, target.artist)
        // The upload's own credit on a soundtrack fan upload is the channel
        // ("Joker OST"), not the performer. Asking for it spends the search on
        // a name no catalogue row carries.
        if (identity.artist.isNotBlank()) return plainQueries(identity.title, identity.artist)
        val work = identity.work
        // The channel is the uploader only when the row's credit actually
        // looks like one ("Joker OST", "Various Artists", a Topic channel).
        // The pipe/trailing-label shape alone does not prove that: a fan upload
        // can still carry the real performer in its credit, and dropping them
        // spends the search on a name no catalogue row carries.
        val uploader = isUploaderCredit(target.artist)
        if (work == null || !uploader) {
            return plainQueries(identity.title, if (uploader) "" else target.artist)
        }
        val plain = plainQueries(identity.title, "")
        val soundtrack = titleVariants(identity.title, "")
            .firstOrNull()
            ?.let { "$it $work soundtrack" }
            ?: return plain
        return (listOf(soundtrack) + plain).distinct()
    }

    private fun plainQueries(titleRaw: String, artistRaw: String): List<String> {
        val titles = titleVariants(titleRaw, artistRaw)
        val title = titles.firstOrNull().orEmpty()
        if (title.isBlank()) return emptyList()
        val artist = primaryArtist(artistRaw)
        val out = if (artist.isBlank()) mutableListOf(title) else mutableListOf("$title $artist", title)
        // The other script, asked once and without the artist: a pure-katakana
        // title ("コイコガレ") never meets its romaji-filed row ("Koi Kogare")
        // when only one script is asked for, and neither index answers the
        // other script of a transliterated pair ("コイコガレ - koikogare").
        // One extra query, not a second pair: every query is a search on every
        // source, and the download lookup budget is sized off the count.
        // Romaji is skipped when kana leaves non-Latin behind (kanji has no
        // reading) — a half-garbled query helps nobody.
        val latin = romajiOf(title).trim()
        val otherScript = latin.takeIf { it.isNotBlank() && it != title && it.all { c -> c in LATIN_QUERY_CHARS } }
            ?: scriptMates(titleRaw).firstOrNull { it.isNotBlank() && it != title }
        otherScript?.let { out += it }
        // A title that states its translation after a dash is filed under
        // either language. The first title above is the one the listing leads
        // with; this asks the other without spending a second artist pair.
        titles.drop(1).firstOrNull { it.isNotBlank() && it !in out }?.let { out += it }
        return out.distinct()
    }

    fun titleVariants(raw: String, artist: String = "") = KoTrackMatcher.titleVariants(raw, artist) // [KO] titleVariants
    fun titleCores(raw: String, artist: String = "") = KoTrackMatcher.titleCores(raw, artist) // [KO] titleCores
    fun hasLeadingTrackNumber(raw: String) = KoTrackMatcher.hasLeadingTrackNumber(raw) // [KO] hasLeadingTrackNumber
    /** The title with the packaging taken off, version markers kept. */
    internal fun searchableTitle(title: String, artist: String = ""): String =
        parseTitle(title, artist).let { (it.words + it.versions).joinToString(" ") }

    /** The first credited artist — who a catalogue is most likely to file the track under. */
    internal fun primaryArtist(artist: String): String = KoTrackMatcher.primaryArtist(artist) // [KO] primaryArtist

    internal fun artistVariants(artist: String) = KoTrackMatcher.artistVariants(artist) // [KO] artistVariants
    /** Whether both credits name at least one of the same artists. */
    fun sharesArtist(wanted: String, got: String): Boolean {
        val want = artistNames(wanted)
        val have = artistNames(got)
        return want.isNotEmpty() && have.isNotEmpty() &&
                want.any { w -> have.any { h -> sameArtist(w, h) } }
    }

    // ── Judging ─────────────────────────────────────────────────────────────

    /**
     * The best of [candidates] that is genuinely [target], or null if none is.
     *
     * Best, not first. A search for a track routinely answers with the single,
     * the album cut, a sped-up edit and a karaoke version, in whatever order
     * the backend felt like — and taking the first acceptable one means the
     * ranking of somebody else's search engine decides which copy plays.
     * Scoring them all and taking the top lets the runtime and the fuller
     * artist credit break that tie instead.
     */
    fun best(candidates: List<Song>, target: Target): Song? =
        ranked(candidates, target).firstOrNull()

    fun bestSameArtist(candidates: List<Song>, target: Target) = KoTrackMatcher.bestSameArtist(candidates, target) // [KO] bestSameArtist
    internal fun namesTheSameArtist(wanted: String, got: String) = KoTrackMatcher.namesTheSameArtist(wanted, got) // [KO] namesTheSameArtist
    internal fun sharesArtistChannel(target: Target, candidate: Song) = KoTrackMatcher.sharesArtistChannel(target, candidate) // [KO] sharesArtistChannel
    internal fun sharesRelease(target: Target, candidate: Song) = KoTrackMatcher.sharesRelease(target, candidate) // [KO] sharesRelease
    internal fun videoRuntimeAllows(target: Target, candidate: Song) = KoTrackMatcher.videoRuntimeAllows(target, candidate) // [KO] videoRuntimeAllows
    private fun sameArtistIdentity(target: Target, candidate: Song) = KoTrackMatcher.sameArtistIdentity(target, candidate) // [KO] sameArtistIdentity
    internal fun isCoverOf(candidate: Song, target: Target) = KoTrackMatcher.isCoverOf(candidate, target) // [KO] isCoverOf
    fun sameRecording(candidate: Song, target: Target) = KoTrackMatcher.sameRecording(candidate, target) // [KO] sameRecording
    fun isOriginalRecording(
        candidate: Song,
        target: Target,
        maxDriftSec: Int = SUBSTITUTE_DRIFT_SEC,
        /**
         * Accept a row whose artist is written in a different script, as long
         * as title, take and runtime agree — see [isArtistUnverifiedOriginal].
         * The sound comparison still has to accept it; this only lets the
         * caller *find* it and offer it to that comparison.
         */
        allowArtistUnverified: Boolean = false,
    ) = KoTrackMatcher.isOriginalRecording(candidate, target, maxDriftSec, allowArtistUnverified) // [KO] isOriginalRecording
    fun isArtistUnverifiedOriginal(
        candidate: Song,
        target: Target,
        maxDriftSec: Int = ARTIST_UNVERIFIED_DRIFT_SEC,
    ) = KoTrackMatcher.isArtistUnverifiedOriginal(candidate, target, maxDriftSec) // [KO] isArtistUnverifiedOriginal
    fun artistScriptsDiffer(wanted: String, got: String) = KoTrackMatcher.artistScriptsDiffer(wanted, got) // [KO] artistScriptsDiffer
    fun rejectionReason(
        candidate: Song,
        target: Target,
        strictOriginal: Boolean = false,
        requireSharedArtist: Boolean = false,
        strictLength: Boolean = false,
        /**
         * The same runtime window the caller's real filter used. It is a
         * parameter because the upgrade path is stricter than a substitute
         * search, and a row rejected at three seconds must be reported as
         * `duration` rather than as a match against a wider default.
         */
        driftSec: Int = SUBSTITUTE_DRIFT_SEC,
        /**
         * Report a cross-script artist as `artist-unverified` rather than
         * `artist`. The upgrade path sets this so a localised catalogue row is
         * still offered to the sound comparison instead of being dropped at
         * the credit check.
         */
        allowArtistUnverified: Boolean = false,
        /**
         * The runtime window for the cross-script grade. Separate from
         * [driftSec] so the localised row is judged at [ARTIST_UNVERIFIED_DRIFT_SEC]
         * while a normally credited row keeps the caller's stricter window.
         */
        artistUnverifiedDriftSec: Int =
            maxOf(driftSec, ARTIST_UNVERIFIED_DRIFT_SEC),
    ) = KoTrackMatcher.rejectionReason(candidate, target, strictOriginal, requireSharedArtist, strictLength, driftSec, allowArtistUnverified, artistUnverifiedDriftSec) // [KO] rejectionReason
    fun hasLatinLetters(text: String) = KoTrackMatcher.hasLatinLetters(text) // [KO] hasLatinLetters
    fun hasOtherScript(text: String) = KoTrackMatcher.hasOtherScript(text) // [KO] hasOtherScript
    fun isCoverArrangement(candidate: Song, seed: Song? = null) = KoTrackMatcher.isCoverArrangement(candidate, seed) // [KO] isCoverArrangement
    fun isKaraokeOrTributeArrangement(candidate: Song, seed: Song? = null) = KoTrackMatcher.isKaraokeOrTributeArrangement(candidate, seed) // [KO] isKaraokeOrTributeArrangement
    fun bestForVideoSwitch(candidates: List<Song>, target: Target) = KoTrackMatcher.bestForVideoSwitch(candidates, target) // [KO] bestForVideoSwitch
    fun songVersionRejectReason(candidate: Song, target: Target) = KoTrackMatcher.songVersionRejectReason(candidate, target) // [KO] songVersionRejectReason
    /**
     * The catalogue counterpart for a music-video upload selected explicitly
     * by the listener.
     *
     * A video and the song it promotes can legitimately differ by well over a
     * minute: the video includes an intro, an outro and sometimes dialogue.
     * The ordinary source matcher must reject that gap because it is choosing
     * a stream without anyone watching it. The player audio switch is a
     * different contract: it asks YouTube Music's *Songs* shelf for the
     * official release of a known video. Here an exact recording title,
     * identical version markers and the same artist are enough to establish
     * that relationship; a video being longer only ranks equivalent releases
     * and never vetoes one. A song much longer than the video is still a
     * different edit and is refused as before.
     *
     * The same artist must be established by a name that matches exactly or
     * by the same artist channel / release id. A merely non-comparable credit
     * ("f(x)" has no words to compare) is not "same artist": that was how
     * Justin Bieber's "Sorry" could have become f(x)'s song of the same name.
     *
     * This remains deliberately stricter than a search engine's first row.
     * A cover, remix or karaoke version still cannot cross the switch just
     * because it happens to share a title.
     */
    fun bestOfficialAudioForVideo(candidates: List<Song>, target: Target): Song? {
        val wanted = parseTitle(target.title, target.artist)
        if (wanted.core.isEmpty()) return null
        return candidates
            .filterNot { isCoverOf(it, target) }
            .mapNotNull { candidate ->
                val got = parseTitle(candidate.title, candidate.artist)
                if (!coresEqual(wanted.cores(), got.cores()) || wanted.versions != got.versions) {
                    return@mapNotNull null
                }
                // A vocal song must not be switched to the instrumental or
                // karaoke row of an "… (Instrumental Edition)" album.
                if ((albumVersionMarkers(candidate.albumName) intersect NOVOCAL_TAKES)
                        .any { it !in wanted.versions }
                ) {
                    return@mapNotNull null
                }
                val credited = artistScore(target.artist, candidate.artist)
                val artist = when {
                    // A soundtrack upload that named no performer has no credit
                    // to compare; its cleaned title/work is the whole target.
                    target.noPerformer -> credited ?: 0
                    namesTheSameArtist(target.artist, candidate.artist) -> credited ?: ARTIST_SHARED
                    sameArtistIdentity(target, candidate) -> ARTIST_SHARED
                    else -> return@mapNotNull null
                }
                if (!videoRuntimeAllows(target, candidate)) return@mapNotNull null
                val duration = target.durationSec?.let { expected ->
                    secondsOf(candidate.durationText)?.let { actual -> -abs(expected - actual) }
                        ?: -120
                } ?: 0
                candidate to (artist * 1_000 + duration)
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /**
     * Every candidate that really is [target], most confident first.
     *
     * The whole list rather than the winner, because identity is not the only
     * question worth asking of it: two catalogues can both genuinely hold a
     * recording and offer it at different qualities, and that choice belongs
     * to [SourceResolver], which knows what was asked for. Confidence orders
     * what it is given; it does not get to spend it.
     */
    fun ranked(candidates: List<Song>, target: Target): List<Song> =
        candidates
            .mapNotNull { candidate -> score(candidate, target)?.let { candidate to it } }
            // A runtime-identical cover is only a last-resort explanation for
            // catalogues crediting the same master differently. If this search
            // also returned anything carrying the requested artist, there is
            // no reason to keep the different-artist rows in contention. Apart
            // from preventing covers winning ties, this avoids opening their
            // stream URLs while a correct lossy result waits to be returned.
            .let { scored ->
                val credited = scored.filter { (candidate, _) ->
                    artistScore(target.artist, candidate.artist) != null
                }
                credited.ifEmpty { scored }
            }
            .sortedByDescending { it.second }
            .map { it.first }

    /**
     * How confident this is the same recording, or null when it is not one.
     *
     * Null is the common answer and the safe one: it costs a source its turn,
     * and the next source — ultimately YouTube, which by definition has the
     * track — still plays what the user asked for.
     */
    fun score(candidate: Song, target: Target): Int? {
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return null
        // Same word, different script: katakana vs romaji is bridged by
        // Hepburn transliteration; kanji has no algorithmic reading and stays
        // exact-only. Past that, a guarded containment absorbs minor
        // punctuation and symbol differences. A bracketed translation is the
        // same title in another script: the bracket word lives in
        // [TitleParts.context], so a core appearing verbatim as the other
        // side's context is accepted — but only across scripts (CJK-only vs
        // Latin), so same-script packaging never rescues a different title.
        val isTitleMatch = coresMatch(wanted.cores(), got.cores(), containment = true) ||
            isTranslationMatch(wanted, got) // [KO] title-rules
        if (!isTitleMatch) return null
        // Direction matters both ways round: asking for the album cut must not
        // land on the live take, and asking for the live take must not land on
        // the album cut. Markers living on the release rather than the row come
        // in two kinds. A wanted take found on the album completes the row
        // (Tidal's "KALYANI" + "KALYANI (Remix)"), but only ever in that
        // direction. The no-vocal takes are bidirectional instead: a row from
        // an "INSTRUMENTAL EDITION" album *is* the instrumental even when its
        // title says nothing, so it must neither stand in for the vocal nor be
        // refused its own instrumental request. Anything else on an album
        // ("Party Mix 2024", "Deluxe Version") is compilation naming and is
        // rightly ignored in both directions.
        // Only markers the request asked for are taken from the album: the
        // rest of an album name ("Song (Remixes)", "MTV Unplugged") describes
        // the release, and adding it would refuse the right row.
        val albumMarks = albumVersionMarkers(candidate.albumName)
        val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
            (albumMarks intersect wanted.versions)
        if (wanted.versions != effectiveVersions) return null

        val creditedArtist = artistScore(target.artist, candidate.artist)
            ?: ARTIST_SHARED.takeIf {
                namesTheSameArtist(target.artist, candidate.artist) ||
                    sameArtistIdentity(target, candidate)
            }
        val duration = durationScore(
            target.durationSec,
            secondsOf(candidate.durationText),
            // A music video may carry a long visual intro or outro. That drift
            // is credible only when the catalogue row names the same artist;
            // duration must never make an unrelated artist into the song.
            // YouTube does not reliably label official music videos as video
            // rows (square release artwork is common), so the flag cannot be
            // the only way through the wider window. Exact title/version plus
            // a shared artist is strong enough; a different artist still gets
            // the ordinary 30-second ceiling below.
            allowVideoDrift = creditedArtist != null,
        ) ?: return null
        val artist = creditedArtist
        // The credits don't merely differ in spelling, they name different
        // people — and sometimes that is because they are describing the
        // same recording from different ends of it. Film catalogues are
        // full of this: YouTube Music files "Jhak Maar Ke" under Pritam,
        // who *wrote* it, while every store files it under Neeraj
        // Shridhar, who *sang* it. Neither is wrong and nothing in either
        // credit hints at the other, so a matcher that insists on an
        // overlap refuses the correct track every time.
        //
        // What breaks the tie is length. Two recordings that share an
        // exact title and agree on their runtime to the second are the
        // same master; a cover, a remix or a re-recording essentially
        // never lands there — of the four candidates for that track, the
        // remix ran 241s and the acoustic cover 66s against the 233s being
        // played. So an exact runtime is allowed to stand in for a shared
        // credit, and *only* an exact one: with no runtime on either side
        // there is nothing corroborating anything, and the strict refusal
        // stands. The match still scores below a genuine credit match, so
        // it never wins where a properly-credited copy exists.
        // A music video's runtime includes visuals and therefore cannot
        // vouch for a catalogue row credited to completely different
        // people. This exact exception is what admitted the 3:30 Lovely
        // track for Yo Yo Honey Singh's 3:31 "Brown Rang" video.
            ?: CREDITS_DISAGREE.takeIf {
                !target.isVideo && withinSeconds(candidate, target, CREDIT_OVERRIDE_SEC) &&
                    KoTitleRules.creditsOverrideAllowed(target.artist, candidate.artist) // [KO] artist-script override
            }
            ?: return null
        val explicit = explicitScore(target.isExplicit, candidate.isExplicit) ?: return null
        return BASE + artist + duration + albumScore(target.album, candidate.albumName) + explicit +
                contextScore(wanted, got)
    }

    /**
     * Whether otherwise valid rows describe more than one release while the
     * requested track gives us no release with which to choose between them.
     *
     * JioSaavn has catalogue collisions where title and artist are identical
     * but the audio is not. Picking the runtime-nearest row is unsafe there:
     * a different recording can be only a second nearer than the wanted one.
     * The caller uses this as a conservative source miss and leaves the track
     * on YouTube instead of guessing.
     */
    fun hasConflictingAlbums(candidates: List<Song>, target: Target): Boolean {
        if (!target.album.isNullOrBlank()) return false
        val comparable = if (target.durationSec != null) {
            candidates.filter { withinSeconds(it, target, DURATION_LIMIT_SEC) }
        } else {
            candidates
        }
        // No catalogue audio is within the normal song window. This is the
        // shape of an unlabelled music video with a long visual intro/outro;
        // release disagreement cannot be resolved from that video runtime and
        // must not veto the search engine's top same-artist result.
        if (target.durationSec != null && comparable.isEmpty()) return false
        return comparable.mapNotNull { albumKey(it.albumName) }.distinct().size > 1
    }

    /**
     * Resolves a JioSaavn release collision only when one candidate is plainly
     * more specifically credited than every other close-duration candidate.
     *
     * A film's original release often names the complete vocal ensemble while
     * compilation rows reduce it to the lead singer or add composer/lyricist
     * credits. That is useful evidence, but not enough to guess on a tie: a
     * unique, fuller credit may win; otherwise callers must keep the fallback.
     */
    fun uniquelyMostCreditedCloseMatch(candidates: List<Song>, target: Target): Song? {
        val close = candidates.filter { withinSeconds(it, target, DURATION_LIMIT_SEC) }
        if (close.size < 2) return null
        val ranked = close.map { it to artistNames(it.artist).size }
        val topCredits = ranked.maxOfOrNull { it.second } ?: return null
        // A single name cannot establish that a row is the canonical recording
        // rather than a compilation's abbreviated credit.
        if (topCredits < 2) return null
        val winners = ranked.filter { it.second == topCredits }.map { it.first }
        return winners.singleOrNull()
    }

    fun sameTitle(a: Song, b: Song) = KoTrackMatcher.sameTitle(a, b) // [KO] sameTitle
    /**
     * Whether [candidate] states a runtime, and one within [seconds] of
     * [target]'s.
     *
     * Both halves are requirements. A candidate that doesn't say how long it
     * is fails this — for the callers that ask, an unstated runtime is not a
     * near miss, it is a candidate that cannot be checked, and the whole
     * reason to ask is that the check is the last thing standing between a
     * listener and the wrong recording.
     */
    fun withinSeconds(candidate: Song, target: Target, seconds: Int): Boolean {
        val wanted = target.durationSec ?: return false
        val got = secondsOf(candidate.durationText) ?: return false
        return abs(wanted - got) <= seconds
    }

    /**
     * Kept for the callers that only want a yes or no — the YouTube seed
     * lookup behind AutoPlay, and the tests.
     */
    fun matches(candidate: Song, title: String, artist: String, durationSec: Int? = null): Boolean =
        score(candidate, Target(title, artist, durationSec)) != null

    // ── Title ───────────────────────────────────────────────────────────────

    /**
     * A soundtrack upload's title and credit, with the packaging that names
     * the work taken off. [work] is the film/game/show the tail named, used
     * only when the upload has no performer to search for.
     */
    internal data class AudioSwitchIdentity(
        val title: String,
        val artist: String,
        val work: String?,
        val hasSoundtrackTail: Boolean,
        val tailRemoved: Boolean,
        /** The label was attached with a dash/pipe, not a bare word on the title. */
        val labelPackaged: Boolean,
    )

    internal fun audioSwitchIdentity(rawTitle: String, rawArtist: String) = KoTrackMatcher.audioSwitchIdentity(rawTitle, rawArtist) // [KO] audioSwitchIdentity
    internal fun soundtrackIdentity(rawTitle: String, rawArtist: String) = KoTrackMatcher.soundtrackIdentity(rawTitle, rawArtist) // [KO] soundtrackIdentity
    internal fun audioSwitchTargetOf(song: Song) = KoTrackMatcher.audioSwitchTargetOf(song) // [KO] audioSwitchTargetOf
    internal fun audioSwitchQueries(song: Song) = KoTrackMatcher.audioSwitchQueries(song) // [KO] audioSwitchQueries
    private fun isUploaderCredit(artist: String) = KoTrackMatcher.isUploaderCredit(artist) // [KO] isUploaderCredit
    internal fun isSoundtrackCredit(credit: String) = KoTrackMatcher.isSoundtrackCredit(credit) // [KO] isSoundtrackCredit
    /**
     * A title split into the part that is the recording's identity and the
     * parts that are the listing's.
     */
    internal data class TitleParts(
        /** The title proper, lowercased, one entry per word. */
        val words: List<String>,
        /** [words] with everything but letters and digits removed — what identity is compared on. */
        val core: String,
        /** Markers that mean a different take of the same song: `remix`, `live`, `acoustic`. */
        val versions: Set<String>,
        /** Words dropped with the packaging. A hint for scoring, never a veto. */
        val context: Set<String>,
        /**
         * Other spellings the same listing states outright, one word list each:
         * the English half of "여수밤바다 - Yeosu Night Sea", the Hangul half
         * of the reverse. Catalogues file the song under either, so identity
         * has to accept both without guessing at a translation.
         */
        val altWords: List<List<String>> = emptyList(),
        /** [altWords] reduced the same way as [core]. */
        val altCores: List<String> = emptyList(),
    ) {
        /** Every core this title is filed under, the primary one first. */
        fun cores(): List<String> = (listOf(core) + altCores).filter { it.isNotBlank() }.distinct()
    }

    internal fun parseTitle(raw: String, artist: String = "") = KoTrackMatcher.parseTitle(raw, artist) // [KO] parseTitle
    private fun coresMatch(wanted: List<String>, got: List<String>, containment: Boolean) = KoTrackMatcher.coresMatch(wanted, got, containment) // [KO] coresMatch
    private fun coresEqual(wanted: List<String>, got: List<String>) = KoTrackMatcher.coresEqual(wanted, got) // [KO] coresEqual
    /**
     * NFKC + lowercase: full-width alphanumerics fold to ASCII (`Ｄａｉ` to
     * `dai`, `０-９` to `0-9`, ideographic spaces to spaces) and CJK brackets
     * fold to ASCII parens so the bracket pass handles `「title」` like
     * `(title)`. CJK letters themselves survive — stripping them is what kept
     * every non-Latin catalogue out of matching entirely.
     */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(CJK_OPEN, "(")
            .replace(CJK_CLOSE, ")")

    /**
     * Latin that actually files the track. A head holding nothing but a take
     * marker ("絆ノ奇跡 -instrumental-") carries no filing — without this the
     * marker alone would keep the unmatchable script as the identity.
     */

    /**
     * Whether a bracketed aside is the same title in another script rather
     * than packaging around it.
     *
     * True when one core is CJK-only, the other is Latin, and the other's
     * aside is that core in full (spaced CJK joins back to it). Same-script
     * asides never qualify.
     */
    internal fun isTranslationMatch(wanted: TitleParts, got: TitleParts): Boolean {
        val wantedCJK = containsCJKChar(wanted.core) && !hasFilingLatin(wanted.core)
        val gotCJK = containsCJKChar(got.core) && !hasFilingLatin(got.core)
        val wantedLatin = hasFilingLatin(wanted.core)
        val gotLatin = hasFilingLatin(got.core)
        // Checking both asides covers both orders: Latin in the CJK row's
        // brackets, and CJK in the Latin row's brackets. The aside must be
        // the whole title — one word of "(Racing Into The Night)" is not the
        // song "Night".
        if ((wantedCJK && gotLatin) || (gotCJK && wantedLatin)) {
            if (wanted.context.joinToString("") == got.core) return true
            if (got.context.joinToString("") == wanted.core) return true
        }
        return false
    }

    private fun containsCJKChar(text: String): Boolean = text.any {
        Character.UnicodeBlock.of(it) in CJK_BLOCKS
    }

    private val CJK_BLOCKS = setOf(
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
    )

    internal fun hasFilingLatin(text: String): Boolean =
        text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
            .any {
                LATIN.containsMatchIn(it) && it !in VERSION_WORDS && it !in NOISE_WORDS &&
                    it !in TRAILING_NOISE && it !in JOINING_WORDS
            }

    /**
     * Both spellings when a dash joins the same word in two scripts
     * ("コイコガレ - koikogare", either order). Empty unless the two sides
     * transliterate to each other, so a film packaging ("Paniyon Sa -
     * Satyamev Jayate") never qualifies.
     */
    internal fun scriptMates(raw: String): List<String> {
        val text = normalize(raw)
        val dash = DASH.find(text) ?: return emptyList()
        val head = searchableTitle(text.substring(0, dash.range.first))
        val tail = searchableTitle(text.substring(dash.range.last + 1))
        if (head.isBlank() || tail.isBlank() || head == tail) return emptyList()
        return if (romajiOf(head) == tail || romajiOf(tail) == head) listOf(head, tail)
        else emptyList()
    }

    /**
     * Hepburn transliteration for kana; anything else (kanji, Latin, digits)
     * passes through untouched. Bridges コイコガレ/koikogare and
     * アブナイキオク/abunaikioku — but deliberately not kanji (no algorithmic
     * reading) nor English loans (ドア reads "doa", not "door").
     */
    fun romajiOf(text: String): String {
        val out = StringBuilder()
        var i = 0
        var geminate = false
        var lastVowel: Char? = null
        fun emit(roma: String) {
            var r = roma
            if (geminate) {
                geminate = false
                r = when {
                    r.startsWith("ch") -> "t$r"
                    r.startsWith("sh") -> "s$r"
                    r.startsWith("ts") -> "t$r"
                    else -> "${r[0]}$r"
                }
            }
            out.append(r)
            lastVowel = r.lastOrNull { it in "aeiou" }
        }
        while (i < text.length) {
            var c = text[i]
            if (c in 'ァ'..'ヶ') c = (c.code - 0x60).toChar()
            when (c) {
                'っ' -> { geminate = true; i++ }
                'ー' -> { lastVowel?.let(out::append); i++ }
                'ん' -> {
                    val peek = text.getOrNull(i + 1)?.let { nc ->
                        val fc = if (nc in 'ァ'..'ヶ') (nc.code - 0x60).toChar() else nc
                        KANA_ROMAJI[fc]?.firstOrNull()
                    }
                    // No "n'" before a vowel: catalogue romaji drops the
                    // apostrophe ("Renai" for レンアイ) and identity strips
                    // punctuation anyway, so writing it only ever misses.
                    emit(if (peek == 'b' || peek == 'm' || peek == 'p') "m" else "n")
                    i++
                }
                else -> {
                    val base = KANA_ROMAJI[c]
                    if (base == null) {
                        out.append(c); lastVowel = null; geminate = false; i++
                    } else {
                        val n1 = text.getOrNull(i + 1)?.let { nc ->
                            if (nc in 'ァ'..'ヶ') (nc.code - 0x60).toChar() else nc
                        }
                        val small = n1?.let { SMALL_Y[it] ?: SMALL_V[it] }
                        if (small != null) {
                            val cons = CONS_OVERRIDES.entries
                                .firstOrNull { base.startsWith(it.key) }?.value
                                ?: base.dropLast(1)
                            // sh/ch/j already carry the glide: しゃ is "sha",
                            // ジャ is "ja", not "shya"/"jya".
                            val glide = if (cons == "sh" || cons == "ch" || cons == "j") small.removePrefix("y") else small
                            emit(cons + glide)
                            i += 2
                        } else {
                            emit(base); i++
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    // ── Artist ──────────────────────────────────────────────────────────────

    /**
     * Points for the credit agreeing, or null when it disagrees.
     *
     * The disagreement that matters is a cover: same title, different singer.
     * The agreement that has to survive is a *partial* credit, because which
     * of a duet's singers reaches the title is a formatting choice — YouTube's
     * "Atif Aslam" and a module's "Atif Aslam, Tulsi Kumar" are one recording
     * with two spellings of its credit, and refusing that pairing is what kept
     * the module out of the way of the very tracks it held.
     *
     * A side with no credit at all scores zero rather than failing: there is
     * nothing to disagree with, and the title has already had to match exactly.
     */
    internal fun artistScore(wanted: String, got: String): Int? { // [KO] track-matcher
        val want = artistNames(wanted)
        val have = artistNames(got)
        if (want.isEmpty() || have.isEmpty()) return 0
        val shared = sharesArtist(wanted, got)
        if (!shared) return null
        return if (want == have) ARTIST_EXACT else ARTIST_SHARED
    }

    /**
     * The credited artists, each as its own list of words.
     *
     * Words rather than one run-together string, so that containment is
     * checked on whole names: "Queen" is inside "Queensrÿche" as text and is
     * not one of its artists, while "Atif Aslam" is genuinely one of
     * "Atif Aslam, Tulsi Kumar". Single letters go — an initialled
     * "A. R. Rahman" and a plain "AR Rahman" are the same person.
     */
    internal fun artistNames(value: String): Set<List<String>> = KoTrackMatcher.artistNames(value) // [KO] artistNames

    private fun sameArtist(a: List<String>, b: List<String>) = runOf(a, b) || runOf(b, a)

    /** Whether [outer] contains [inner] as a run of whole words. */
    private fun runOf(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        return (0..outer.size - inner.size).any { at ->
            outer.subList(at, at + inner.size) == inner
        }
    }

    // ── Duration ────────────────────────────────────────────────────────────

    /**
     * Points for the runtimes agreeing, or null when they are too far apart to
     * be the same recording.
     *
     * The strongest signal available, and the one that catches what titles
     * cannot: the ten-minute loop, the album-side upload, the snippet. Only
     * consulted when both sides state a runtime — most module rows do, and a
     * queue row usually does.
     */
    private fun durationScore(wanted: Int?, got: Int?, allowVideoDrift: Boolean = false): Int? {
        if (wanted == null || got == null) return 0
        val drift = abs(wanted - got)
        return when {
            drift > DURATION_LIMIT_SEC && allowVideoDrift && drift <= VIDEO_DURATION_LIMIT_SEC -> 0
            drift > DURATION_LIMIT_SEC -> null
            drift <= DURATION_TIGHT_SEC -> DURATION_TIGHT
            else -> DURATION_LOOSE
        }
    }

    // ── Album ───────────────────────────────────────────────────

    /**
     * Exact release agreement is deliberately stronger than the difference
     * between a tight and a loose runtime. That lets the known album beat a
     * different release whose duration happens to be one second closer.
     * A disagreement is not a veto: compilations and reissues can contain the
     * same master, and title/artist/runtime still have to do their usual work.
     */
    private fun albumScore(wanted: String?, got: String?): Int {
        val want = albumKey(wanted) ?: return 0
        val have = albumKey(got) ?: return 0
        return if (want == have) ALBUM_EXACT else 0
    }

    /**
     * Clean and uncensored editions can be otherwise metadata-identical. When
     * both catalogues state the flag they must agree; an unknown source is not
     * rejected because it has made no contradictory claim.
     */
    private fun explicitScore(wanted: Boolean?, got: Boolean?): Int? = when {
        wanted == null || got == null -> 0
        wanted != got -> null
        else -> EXPLICIT_EXACT
    }

    /**
     * Take markers carried by the release rather than the row ("KALYANI
     * (Remix)" as the album of a plain-titled "KALYANI"). Read with the same
     * parser, so the same version vocabulary applies on both sides.
     */
    internal fun albumVersionMarkers(album: String?): Set<String> =
        if (album.isNullOrBlank()) emptySet() else parseTitle(album).versions

    /** Punctuation, spacing and a trailing edition label are catalogue formatting, not release identity. */
    private fun albumKey(value: String?): String? {
        var text = normalize(value.orEmpty()).trim()
        if (text.isEmpty()) return null
        repeat(BRACKET_PASSES) { text = BRACKETED.replace(text, " ") }
        val words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in ALBUM_NOISE_WORDS }
        return words.joinToString("").takeIf { it.isNotEmpty() }
    }

    /** "3:45" or "1:02:03" as whole seconds; null for anything else. */
    fun secondsOf(text: String?): Int? {
        val parts = text?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        if (numbers.last() > 59) return null
        if (numbers.size == 3 && numbers[1] > 59) return null
        var total = 0L
        for (part in numbers) {
            total = total * 60 + part
            if (total > Int.MAX_VALUE) return null
        }
        return total.toInt().takeIf { it > 0 }
    }

    // ── Context ─────────────────────────────────────────────────────────────

    /** A nudge when both listings mention the same film or album in their asides. */
    private fun contextScore(wanted: TitleParts, got: TitleParts): Int =
        if (wanted.context.any { it in got.context }) CONTEXT_SHARED else 0

    // ── Weights ─────────────────────────────────────────────────────────────

    /** Everything that reaches scoring has already matched on title and version. */
    private const val BASE = 100
    private const val ARTIST_EXACT = 25
    private const val ARTIST_SHARED = 10

    /**
     * Carried by a match the runtime vouched for rather than the credit. A
     * penalty, not a pass: any candidate whose credit genuinely agrees beats
     * it by at least 25, so this only ever decides what plays when nothing
     * properly credited exists.
     */
    private const val CREDITS_DISAGREE = -30

    /**
     * How exactly two runtimes must agree before that is allowed to stand in
     * for a shared credit. To the second, near enough — this is the only
     * evidence there is in that case, so it has to be the strong kind.
     */
    private const val CREDIT_OVERRIDE_SEC = 2
    private const val DURATION_TIGHT = 40
    private const val DURATION_LOOSE = 15
    private const val ALBUM_EXACT = 35
    private const val EXPLICIT_EXACT = 20
    private const val CONTEXT_SHARED = 20

    /** Within this many seconds is the same master, allowing for trimmed silence. */
    private const val DURATION_TIGHT_SEC = 3

    /**
     * Past this, two tracks sharing a title are not sharing a recording.
     * Wide enough for a fade or an intro a service trims differently, narrow
     * enough to rule out an extended cut or a full-album upload.
     */
    const val DURATION_LIMIT_SEC = 30

    /**
     * How far a substitute source's runtime may sit from the requested
     * recording's before it is not that recording. Tighter than
     * [DURATION_LIMIT_SEC] on purpose: this is the gate for replacing what the
     * listener asked for, not for accepting a search result.
     */
    const val SUBSTITUTE_DRIFT_SEC = 5

    /**
     * How close an artist-unverified row's runtime must be. The credit is the
     * one identity signal missing, so the remaining signals have to be
     * unusually strong; three seconds is the same tight window the normal
     * matcher treats as one master.
     */
    const val ARTIST_UNVERIFIED_DRIFT_SEC = 3

    /**
     * Whether two runtimes differ by more than [DURATION_LIMIT_SEC], meaning
     * they cannot be the same recording. Used to detect when a cached or
     * live stream is a different edit from the requested catalogue track.
     */
    fun isSevereMismatch(expectedSec: Int?, actualSec: Int?): Boolean {
        if (expectedSec == null || actualSec == null) return false
        return abs(actualSec - expectedSec) > DURATION_LIMIT_SEC
    }

    /**
     * Under this, a file is a preview clip rather than a song.
     *
     * The catalogue services hand out thirty-second excerpts of the real
     * recording, and an addon that answers with one is not lying about its
     * container or its bitrate — only about being the track. Measured (phone
     * 11-05): NEON (rlYOsUfpi3M) played 30s of `audio/mpeg` 128kbps against a
     * three-minute row, from a copy an earlier play had cached, and the lyrics
     * sheet then refused to sync against it (`refused-match match=47%`).
     */
    const val PREVIEW_SEC = 60

    /**
     * Whether a stream that runs [actualSec] may play as the recording
     * [expectedSec] long.
     *
     * Three ways to be the wrong file, in order of certainty:
     *
     *  - **A preview.** Anything under [PREVIEW_SEC] against a row that is
     *    longer than that is an excerpt, whatever else it claims.
     *  - **Half the row.** A stream that would run out before the row is half
     *    over is not a shorter edit of it; it is a different recording or a
     *    truncated one.
     *  - [isSevereMismatch]. The long-standing window, which still catches
     *    the moderate differences the two rules above are too blunt for.
     *
     * Unknown on either side passes. An unmeasured length is not evidence — a
     * container that states none is common — and refusing on it would block
     * good tracks, which is worse than the wrong file this exists to stop.
     */
    fun isWrongLength(expectedSec: Int?, actualSec: Int?): Boolean {
        if (expectedSec == null || actualSec == null || actualSec <= 0) return false
        // A row that is itself a clip has no longer recording to be short of.
        if (expectedSec < PREVIEW_SEC) return isSevereMismatch(expectedSec, actualSec)
        if (actualSec < PREVIEW_SEC) return true
        if (actualSec * 2 < expectedSec) return true
        return isSevereMismatch(expectedSec, actualSec)
    }

    /** Visual intros/outros can make the video substantially longer than its audio master. */
    const val VIDEO_DURATION_LIMIT_SEC = 90 // [KO] track-matcher

    internal const val BRACKET_PASSES = 3 // [KO] track-matcher
    internal const val DASH_PASSES = 3 // [KO] track-matcher

    internal val BRACKETED = Regex("""[(\[]([^()\[\]]*)[)\]]""") // [KO] track-matcher
    internal val DASH = Regex("""\s+[-–—|]+\s+""") // [KO] track-matcher
    internal val FEATURING = Regex("""\b(feat|ft|featuring|with)\b.*""") // [KO] track-matcher
    /** `~` and the wave dash `〜` join words in Japanese titles ("真夜中のドア〜stay"). */
    internal val WORD_SPLIT = Regex("""[\s.·~〜]+""") // [KO] track-matcher
    /**
     * What survives into identity: any language's letters and digits.
     * ASCII-only (`[^a-z0-9]`) is what emptied every CJK/Devanagari title to
     * `""` — and an empty core matches nothing and asks for nothing.
     */
    internal val NON_ALNUM = Regex("""[^\p{L}\p{N}]""") // [KO] track-matcher
    internal val LATIN = Regex("[a-z0-9]") // [KO] track-matcher
    internal const val LATIN_QUERY_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789 " // [KO] track-matcher
    private val KANA_ROMAJI: Map<Char, String> = run {
        fun row(consonant: String, kana: String, vowels: String = "aiueo"): Map<Char, String> =
            kana.toList().zip(vowels.toList()).associate { (k, v) -> k to "$consonant$v" }
        buildMap {
            putAll(row("", "あいうえお"))
            putAll(row("k", "かきくけこ")); putAll(row("g", "がぎぐげご"))
            putAll(row("s", "さしすせそ")); put('し', "shi")
            putAll(row("z", "ざじずぜぞ")); put('じ', "ji")
            putAll(row("t", "たちつてと")); put('ち', "chi"); put('つ', "tsu")
            putAll(row("d", "だぢづでど")); put('ぢ', "ji"); put('づ', "zu")
            putAll(row("n", "なにぬねの"))
            putAll(row("h", "はひふへほ")); put('ふ', "fu")
            putAll(row("b", "ばびぶべぼ")); putAll(row("p", "ぱぴぷぺぽ"))
            putAll(row("m", "まみむめも"))
            put('や', "ya"); put('ゆ', "yu"); put('よ', "yo")
            putAll(row("r", "らりるれろ"))
            put('わ', "wa"); put('を', "wo"); put('ん', "n")
            put('ゔ', "vu")
            put('ぁ', "a"); put('ぃ', "i"); put('ぅ', "u"); put('ぇ', "e"); put('ぉ', "o")
            put('ゃ', "ya"); put('ゅ', "yu"); put('ょ', "yo"); put('ゎ', "wa")
            put('ゕ', "ka"); put('ゖ', "ke")
        }
    }
    private val SMALL_Y = mapOf('ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo")
    private val SMALL_V = mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o")
    private val CONS_OVERRIDES = mapOf(
        "shi" to "sh", "chi" to "ch", "tsu" to "ts", "fu" to "f", "ji" to "j", "zu" to "z",
    )
    internal val CJK_OPEN = Regex("[「『【〈《〔［｛]") // [KO] track-matcher
    internal val CJK_CLOSE = Regex("[」』】〉》〕］｝]") // [KO] track-matcher
    // Not the katakana middle dot `・`: it sits *inside* a transliterated
    // name (ジョン・レノン), and splitting there makes two Johns one artist.
    internal val ARTIST_SEPARATORS = // [KO] track-matcher
        Regex("""\s*(?:[,&/;·|、，×]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|및)\s*""")

    /**
     * What makes a listing a different recording rather than a different
     * listing of the same one. A title carrying one of these on one side only
     * is refused outright.
     */
    internal val VERSION_WORDS = setOf( // [KO] track-matcher
        "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
        "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
        // A stem is not the song. "Vocals Only", "Acapella", "Backing Track"
        // and friends carry the right title and the right artist and are not
        // remotely the recording anybody asked for.
        "vocals", "vocal", "acapella", "acappella", "cappella", "backing", "stems", "stem",
        "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
        "version", "mix", "dub", "vip", "session", "sessions",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
        "part", "pt", "chapter",
        // Non-Latin spellings of the same takes. Without these a `カバー` or
        // `カラオケ` row carries no version marker and scores as the original.
        "カバー", "カラオケ", "リミックス", "ライブ", "アコースティック",
        "インスト", "バージョン", "歌ってみた", "踊ってみた", "弾いてみた",
        "翻唱", "现场", "混音",
        "커버", "라이브", "리믹스",
    )

    /**
     * The [VERSION_WORDS] that are a take even printed bare at the end of a
     * title ("Kizuna No Kiseki Instrumental"). The rest are real title words
     * often enough ("Let Me Live", "Take Cover", "The Final Chapter") that
     * reading them as a take would let the live or cover recording stand in
     * for the original.
     */
    internal val TRAILING_TAKES = setOf( // [KO] track-matcher
        "instrumental", "karaoke", "acapella", "acappella", "cappella", "remix", "rmx", "nightcore",
    )

    /**
     * Takes whose absence from the audio is the point: a row filed under an
     * album declaring one of these *is* that take even when its own title
     * says nothing ("Am I Dreaming" on "... (METROVERSE INSTRUMENTAL
     * EDITION)"). Applied from the album in both directions, unlike the
     * rescue above — which is what stops the 256s instrumental twin from
     * standing in for the 256s vocal no duration check can separate.
     */
    internal val NOVOCAL_TAKES = setOf( // [KO] track-matcher
        "instrumental", "karaoke", "acapella", "acappella", "cappella", "backing", "stems", "stem",
    )

    /**
     * Asides that read like a version and describe the ordinary release. The
     * exception list to [VERSION_WORDS] — without it, "Song (Album Version)"
     * and "Song" would be two different recordings.
     */
    internal val NEUTRAL_SEGMENTS = setOf( // [KO] track-matcher
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack",
    )


    /** Packaging words, worth nothing as a tie-break because everything has them. */
    internal val NOISE_WORDS = setOf( // [KO] track-matcher
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
        "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
        "featuring", "with", "new", "latest", "free", "download", "remaster",
        "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
        "album", "single", "hd", "hq", "4k", "mp3",
    )

    internal val ALBUM_NOISE_WORDS = setOf( // [KO] track-matcher
        "album", "deluxe", "edition", "expanded", "remaster", "remastered",
        "version", "explicit", "clean", "bonus", "anniversary",
    )

    /** Trailing labels an upload hangs on a title with no brackets to hold them. */
    internal val TRAILING_NOISE = setOf( // [KO] track-matcher
        "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
        "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack",
        // `Youngblood M/V` and `Youngblood MV` print the tag without brackets.
        // Normalisation drops the slash, so both arrive here as `mv`.
        "mv",
    )

    /** Dropped from the core so that "Jack and Jill" and "Jack & Jill" are one title. */
    internal val JOINING_WORDS = setOf("and") // [KO] track-matcher
}
