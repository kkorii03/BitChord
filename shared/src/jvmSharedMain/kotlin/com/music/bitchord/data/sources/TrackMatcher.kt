package com.music.bitchord.data.sources

import com.music.bitchord.data.BoundedMemo
import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.Song
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

    /**
     * Title/credit normalisation is pure but not free: a version search asks
     * the same question of every source and every answer, and the lyrics,
     * cover and canvas paths ask it again for the same row. These small LRUs
     * make that one calculation per distinct input rather than one per caller.
     * Only pure results are kept; anything that reads [ArtistNameIndex] stays
     * out of the caches.
     */
    private const val MEMO_ENTRIES = 256
    private val titleMemo = BoundedMemo<Pair<String, String>, TitleParts>(MEMO_ENTRIES)
    private val artistMemo = BoundedMemo<String, List<String>>(MEMO_ENTRIES)
    private val artistNamesMemo = BoundedMemo<String, Set<List<String>>>(MEMO_ENTRIES)
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

    /**
     * The titles [raw] can be searched under, best first: the title proper,
     * then the other language of a `한글 - English` (or reverse) pair.
     *
     * A leading track number and a trailing translation are packaging, not
     * identity — see [parseTitle] — so "3.여수밤바다 - Yeosu Night Sea" asks
     * for "여수 밤바다" and "yeosu night sea", never for "3".
     */
    fun titleVariants(raw: String, artist: String = ""): List<String> {
        val parts = parseTitle(raw, artist)
        val main = (parts.words + parts.versions).joinToString(" ").trim()
        val out = LinkedHashSet<String>()
        if (main.isNotBlank()) out += main
        parts.altWords.forEach { words ->
            val title = (words + parts.versions).joinToString(" ").trim()
            if (title.isNotBlank()) out += title
        }
        return out.toList()
    }

    /** The core identities [raw] can be filed under, primary first. */
    fun titleCores(raw: String, artist: String = ""): List<String> =
        parseTitle(raw, artist).cores()

    /** Whether [raw] opens with a listing's track number rather than a title. */
    fun hasLeadingTrackNumber(raw: String): Boolean =
        LEADING_TRACK_NUMBER.containsMatchIn(normalize(raw))

    /** The title with the packaging taken off, version markers kept. */
    internal fun searchableTitle(title: String, artist: String = ""): String =
        parseTitle(title, artist).let { (it.words + it.versions).joinToString(" ") }

    /** The first credited artist — who a catalogue is most likely to file the track under. */
    internal fun primaryArtist(artist: String): String = KoTitleRules.primaryArtist(artist) // [KO] title-rules

    /**
     * Every name a credit line carries, in the order it reads.
     *
     * A byline is not one string: it joins collaborators with commas, `&`,
     * `및`, `x`, `feat.` and the rest, and a Korean act often writes its stage
     * name in brackets after the legal one — "강현준(Lil tachi)" answers to
     * either. Both halves are returned, base first, because a catalogue may
     * file the track under either and searching only the printed whole finds
     * neither. A bracket that is not a name ("f(x)", whose base is one letter)
     * is left alone rather than cut into nonsense.
     */
    internal fun artistVariants(artist: String): List<String> =
        artistMemo.getOrPut(artist) { artistVariantsUncached(artist) }

    private fun artistVariantsUncached(artist: String): List<String> {
        val out = LinkedHashSet<String>()
        splitTopLevel(normalize(artist), ARTIST_SEPARATORS).forEach { part ->
            // youtube.com's fallback search credits album audio to the
            // auto-generated channel ("5 Seconds of Summer - Topic"), not to a
            // different act. The suffix is the channel's, not the artist's, so
            // leaving it on made the exact-name check refuse the official song.
            // A VEVO channel is the same plumbing ("ArtistVEVO" is "Artist").
            expandArtistName(
                part.replace(TOPIC_CHANNEL_SUFFIX, "").replace(VEVO_CHANNEL_SUFFIX, ""),
            ).forEach { name ->
                out += name
            }
        }
        return out.filter { it.isNotBlank() }
    }

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

    /**
     * [best], but only a row that credits one of the target's artists.
     *
     * For swapping a playing upload to its catalogue song the recording must
     * be the same one, and [score] deliberately lets a different credit
     * through on title and length alone (film catalogues). That let a
     * title-only query turn Justin Bieber's "Sorry" into f(x)'s
     * "Sorry (Dear. Daddy)" mid-song.
     */
    fun bestSameArtist(candidates: List<Song>, target: Target): Song? =
        // A positive score only: artistScore answers 0 when either credit has
        // no comparable words ("f(x)" is all single letters), and "cannot tell"
        // must not count as the same artist here. A soundtrack target
        // explicitly flagged as having no performer is different: a fan upload
        // may name only its channel, and then there is no performer to require
        // — the cleaned title/work is all the evidence there is. A merely blank
        // credit on any other target is not that case.
        ranked(candidates, target).firstOrNull { candidate ->
            val uncredited = target.noPerformer
            val sameName = (artistScore(target.artist, candidate.artist) ?: 0) > 0 &&
                // The same person, not a name that merely contains them: "The
                // Justin Bieber Cover Band" shares every word of "Justin
                // Bieber" and is a piano cover of "Peaches", not the song.
                namesTheSameArtist(target.artist, candidate.artist)
            // A different printed name is still the same act when the row's
            // own channel or release says so (a solo member and their group).
            (uncredited || sameName || sameArtistIdentity(target, candidate)) &&
                !isCoverOf(candidate, target)
        }

    /** True when one credited artist of [wanted] is exactly one credited artist of [got]. */
    internal fun namesTheSameArtist(wanted: String, got: String): Boolean {
        val have = artistNames(got)
        if (artistNames(wanted).any { it in have }) return true
        // Two printed names that YouTube has linked to one channel are the
        // same act: a solo member and their group, or an act's former name.
        // Only a channel both names have actually been seen under counts.
        val wantedIds = artistVariants(wanted).mapNotNull { ArtistNameIndex.lookup(it) }.toSet()
        if (wantedIds.isEmpty()) return false
        return artistVariants(got).any { name ->
            val id = ArtistNameIndex.lookup(name)
            id != null && id in wantedIds
        }
    }

    /**
     * Whether the two rows point at the same artist even though the printed
     * names differ.
     *
     * YouTube sometimes credits a music video to the band's earlier name
     * ("Kara's Flowers" for Maroon 5's "She Will Be Loved") while the
     * catalogue song is filed under the current one. The channel ids are the
     * fact behind the display strings, so equal ids are proof that both rows
     * are the same act — something a title-only match can never establish, and
     * the reason this is not used to bypass the name check for different acts.
     */
    internal fun sharesArtistChannel(target: Target, candidate: Song): Boolean {
        val wanted = (target.artistIds + listOfNotNull(target.artistId))
            .filter { it.isNotBlank() }
            .toSet()
        val have = (candidate.artists.mapNotNull { it.browseId } + listOfNotNull(candidate.artistId))
            .filter { it.isNotBlank() }
            .toSet()
        return wanted.isNotEmpty() && have.isNotEmpty() && wanted.any { it in have }
    }

    /**
     * Whether both rows name the same release. A video upload and its
     * catalogue song can be credited to different artist spellings while
     * still coming off the same album, which is a safe signal that they are
     * the same recording — unlike a shared album *name*, an id is exact.
     */
    internal fun sharesRelease(target: Target, candidate: Song): Boolean {
        val wanted = target.albumId?.takeIf { it.isNotBlank() } ?: return false
        val have = candidate.albumId?.takeIf { it.isNotBlank() } ?: return false
        return wanted == have
    }

    /**
     * Whether a music video of [target] may be matched to [candidate] despite
     * their runtimes.
     *
     * A video can carry an intro, a skit or an outro the audio master does
     * not, so the song being shorter than the video — by any amount — is not
     * a disagreement about the recording. The reverse is: a catalogue track
     * well longer than the video is a different cut (an extended mix, a
     * full-album upload), so the existing [VIDEO_DURATION_LIMIT_SEC] window
     * still governs that direction.
     */
    internal fun videoRuntimeAllows(target: Target, candidate: Song): Boolean {
        val wanted = target.durationSec ?: return true
        val got = secondsOf(candidate.durationText) ?: return true
        if (got <= wanted) return true
        return got - wanted <= VIDEO_DURATION_LIMIT_SEC
    }

    /**
     * Whether a catalogue row belongs to the same act as [target] by a
     * signal that survives a display-name difference: the same artist channel
     * or the same release.
     */
    private fun sameArtistIdentity(target: Target, candidate: Song): Boolean =
        sharesArtistChannel(target, candidate) || sharesRelease(target, candidate)

    /**
     * A cover, karaoke or tribute row the target does not itself describe.
     * Catalogues file them under the original title ("Peaches (Originally
     * performed by Justin Bieber)"), so the title alone cannot rule them out.
     */
    internal fun isCoverOf(candidate: Song, target: Target): Boolean =
        isCoverArrangement(candidate, seedOf(target))

    /**
     * Whether [candidate] and [target] name the same recording: the title
     * identity agrees, the take markers agree, the credit names one of the
     * same artists, and the row is not a cover/karaoke/tribute arrangement.
     *
     * The rule behind it is the one the catalogue matcher already uses. It is
     * exposed for callers that compare a recording against a video without
     * going through [score] (lyrics alignment picks a reference recording),
     * so both places refuse the same rows.
     */
    fun sameRecording(candidate: Song, target: Target): Boolean {
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return false
        if (wanted.versions != got.versions) return false
        if (!coresMatch(wanted.cores(), got.cores(), containment = true)) return false
        if (isCoverArrangement(candidate, seedOf(target))) return false
        if (target.artist.isNotBlank() && candidate.artist.isNotBlank() &&
            !sharesArtist(target.artist, candidate.artist)
        ) {
            return false
        }
        return true
    }

    /** The target as a row, for comparisons that take a seed rather than a title. */
    private fun seedOf(target: Target) = Song(
        videoId = "",
        title = target.title,
        artist = target.artist,
        thumbnailUrl = null,
        albumName = target.album,
    )

    /**
     * Whether [candidate] is the *original recording* of [target], strictly
     * enough to stand in for it on a substitute source.
     *
     * This is the gate every replacement stream has to clear before it may be
     * played instead of YouTube's own copy. It is deliberately stricter than
     * [score]: a cover catalogue files its arrangement under the original
     * title, with the original artist in the credit and a runtime rounded to
     * the same second, so every signal [score] uses can agree while the audio
     * is a lullaby, a karaoke backing track or an 8-bit emulation. The markers
     * in [COVER_MARKERS], the exact normalised title, the take markers (from
     * the row *and* its album) and the artist's own identity are what separate
     * those rows from the record.
     *
     * A runtime outside [maxDriftSec] is refused outright. Title and artist
     * agreeing across two different edits of a song is ordinary; a runtime
     * that agrees this closely is one recording.
     */
    fun isOriginalRecording(
        candidate: Song,
        target: Target,
        maxDriftSec: Int = SUBSTITUTE_DRIFT_SEC,
    ): Boolean {
        if (isCoverOf(candidate, target)) return false
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return false
        // Exact normalised title, or the same title in the other script. No
        // containment here: a title that merely contains another is how a
        // different song with a shared word gets through. A cover's bracket
        // ("(16-Bit Aespa Emulation)") is dropped by the title parser, which is
        // why the marker check above has to run first.
        val sameTitle = coresEqual(wanted.cores(), got.cores())
        if (!sameTitle) return false
        // The take has to be the one asked for, in both directions. Album
        // markers count the same way [score] counts them: a no-vocal edition is
        // a different recording even when the row's title says nothing.
        val albumMarks = albumVersionMarkers(candidate.albumName)
        val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
            (albumMarks intersect wanted.versions)
        if (wanted.versions != effectiveVersions) return false
        // The same artist, established by a name that matches exactly or by the
        // channel/release identity behind it. A merely non-comparable credit is
        // not enough here: this is a replacement, not a search result.
        if (target.artist.isNotBlank()) {
            val sameArtist = (candidate.artist.isNotBlank() &&
                namesTheSameArtist(target.artist, candidate.artist)) ||
                sharesArtistChannel(target, candidate) ||
                sharesRelease(target, candidate)
            if (!sameArtist) return false
        }
        if (target.durationSec != null) {
            val gotSec = secondsOf(candidate.durationText) ?: return false
            if (abs(target.durationSec - gotSec) > maxDriftSec) return false
        }
        return true
    }

    /**
     * Why [candidate] was or was not accepted for [target], in one word.
     *
     * The same checks [isOriginalRecording], [sharesArtist] and
     * [withinSeconds] apply, in the order [SourceResolver] applies them, so
     * the `hq-search` log line can name the term that rejected a row instead
     * of leaving "results=12, no match" as the only evidence. It judges one
     * candidate; a caller with several passes the best-ranked one.
     *
     * The vocabulary is deliberately small and stable:
     * `match`, `title`, `version`, `artist`, `duration`.
     */
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
    ): String {
        if (strictOriginal) {
            if (isCoverOf(candidate, target)) return "version"
            val wanted = parseTitle(target.title, target.artist)
            val got = parseTitle(candidate.title, candidate.artist)
            if (wanted.core.isBlank() || got.core.isBlank()) return "title"
            if (!coresEqual(wanted.cores(), got.cores())) return "title"
            val albumMarks = albumVersionMarkers(candidate.albumName)
            val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
                (albumMarks intersect wanted.versions)
            if (wanted.versions != effectiveVersions) return "version"
            if (target.artist.isNotBlank()) {
                val sameArtist = (candidate.artist.isNotBlank() &&
                    namesTheSameArtist(target.artist, candidate.artist)) ||
                    sharesArtistChannel(target, candidate) ||
                    sharesRelease(target, candidate)
                if (!sameArtist) return "artist"
            }
            if (strictLength && target.durationSec != null) {
                val gotSec = secondsOf(candidate.durationText) ?: return "duration"
                if (abs(target.durationSec - gotSec) > driftSec) return "duration"
            }
            return "match"
        }
        if (requireSharedArtist && !sharesArtist(target.artist, candidate.artist)) {
            return "artist"
        }
        if (strictLength && !withinSeconds(candidate, target, driftSec)) {
            return "duration"
        }
        return "match"
    }

    /** Whether [text] carries Latin letters, the script catalogues file in. */
    fun hasLatinLetters(text: String): Boolean =
        text.any { it in 'a'..'z' || it in 'A'..'Z' }

    /** Whether [text] carries a non-Latin script (CJK, Hangul, kana, ...). */
    fun hasOtherScript(text: String): Boolean =
        text.any { it.code > 0x2E80 }

    /**
     * Whether [candidate] is a cover, karaoke, tribute or instrumental upload
     * rather than the recording the listener would expect.
     *
     * AutoPlay receives rows from a radio station, not a search for one known
     * song, so there is no [Target] to compare a marker against. [seed] stands
     * in for it when the caller has one: a seed that is itself called "Cover
     * Me" keeps that word out of the filter, exactly as [isCoverOf] does.
     *
     * The rule is the same marker list that already keeps covers out of source
     * matching, so one vocabulary decides both. It cannot recognise a cover
     * upload that names neither the original nor the arrangement (a children's
     * cover group titled plainly), and deliberately does not try to infer one
     * from an unfamiliar artist: a radio station is expected to move to other
     * artists, and guessing would discard legitimate suggestions.
     */
    fun isCoverArrangement(candidate: Song, seed: Song? = null): Boolean {
        val got = arrangementText(candidate)
        val wanted = seed?.let { arrangementText(it) }.orEmpty()
        return COVER_MARKERS.any { it.containsMatchIn(got) && !it.containsMatchIn(wanted) }
    }

    /**
     * Whether [candidate] is a karaoke, tribute, instrumental or other
     * non-performance arrangement rather than an ordinary cover.
     *
     * AutoPlay's cover-seed fallback is the caller: a seed that is itself a
     * cover gets a station of ordinary covers, and those are kept by any
     * artist, while the karaoke/tribute/instrumental uploads stay out. The
     * plain "cover"/"커버" markers are deliberately not part of this list —
     * an ordinary cover is exactly what the fallback is meant to rescue.
     */
    fun isKaraokeOrTributeArrangement(candidate: Song, seed: Song? = null): Boolean {
        val got = arrangementText(candidate)
        val wanted = seed?.let { arrangementText(it) }.orEmpty()
        return KARAOKE_TRIBUTE_MARKERS.any { it.containsMatchIn(got) && !it.containsMatchIn(wanted) }
    }

    private fun arrangementText(song: Song): String =
        (song.title + " " + song.artist + " " + song.albumName.orEmpty())
            .lowercase(Locale.ROOT)

    private val KARAOKE_TRIBUTE_MARKERS = listOf(
        Regex("""originally performed"""),
        Regex("""in the style of"""),
        Regex("""made famous"""),
        // Karaoke catalogue naming: "The Hills (Made Popular By The Weeknd)
        // [Vocal Version]" by Party Tyme Karaoke is the arrangement, not the
        // record, however exactly its title reads.
        Regex("""made popular by"""),
        Regex("""\bvocal version\b"""),
        Regex("""\bparty tyme\b"""),
        Regex("""\bkaraoke\b"""),
        Regex("""\btribute\b"""),
        Regex("""\binstrumental\b"""),
        Regex("""\bbacking (track|band|vocals?)\b"""),
        Regex("""\b(piano|guitar)\s+(version|cover|instrumental|arrangement)\b"""),
        Regex("""\b(kpop|idol)\s+guitar\b"""),
        Regex("""\blullaby\b"""),
        Regex("""\b8[- ]?bit\b"""),
        Regex("""\b16[- ]?bit\b"""),
        Regex("""\b(chiptune|chip tune)\b"""),
        Regex("""\bemulat(ion|ed|or)\b"""),
        Regex("""\b(video )?game (version|music|cover)\b"""),
        Regex("""\barcade player\b"""),
        Regex("""반주"""),
        Regex("""노래방"""),
    )

    /** The ordinary-cover markers, kept apart so an arrangement can be excluded. */
    private val COVER_MARKERS = KARAOKE_TRIBUTE_MARKERS + listOf(
        Regex("""\bcover\b"""),
        Regex("""\bcovers\b"""),
        Regex("""커버"""),
    )

    /**
     * The best video to play when the listener asks to switch an audio track
     * to its video version.
     *
     * A search for "After LIKE" happily returns a Music Bank fancam before the
     * official upload, and a fancam is the wrong recording to put under the
     * song: different camera, crowd noise, an intro from the host. A row whose
     * title carries a concert/stage/cover marker the original title does not
     * is skipped outright; the official channel and an "Official"/"MV" title
     * win among the survivors.
     */
    fun bestForVideoSwitch(candidates: List<Song>, target: Target): Song? {
        val wanted = normalize(target.title)
        val allowed = candidates.filter { candidate ->
            val got = normalize(candidate.title)
            VIDEO_SWITCH_MARKERS.none { marker -> marker in got && marker !in wanted }
        }
        val matches = ranked(allowed, target)
        if (matches.isEmpty()) return null
        return matches.maxWithOrNull(
            compareBy<Song> { officialRank(it, target) }
                .thenBy { score(it, target) ?: Int.MIN_VALUE },
        )
    }

    /**
     * How much a candidate reads as the artist's own video rather than an
     * upload of it. Higher wins; the score inside each tier still decides ties.
     */
    private fun officialRank(candidate: Song, target: Target): Int {
        if (target.artistId != null && candidate.artistId == target.artistId) return 4
        val channel = normalize(candidate.artist).trim()
        val artist = normalize(primaryArtist(target.artist)).trim()
        if (artist.isNotEmpty() &&
            (channel == artist || channel == "$artist official" || channel == "${artist}vevo")
        ) {
            return 3
        }
        if (VIDEO_TITLE_MARKER.containsMatchIn(normalize(candidate.title))) return 2
        return 0
    }

    /** `Official`, `MV`, `M/V` and `Music Video` in a video-switch candidate. */
    private val VIDEO_TITLE_MARKER = Regex(
        """(?<![a-z])(?:official|m\s*/\s*v|mv|music\s+video)(?![a-z])""",
    )

    /**
     * Markers that make a search hit a different video: a fancam, a stage, a
     * cover, a reaction, a karaoke/lyric upload, a loop. Allowed only when the
     * title being switched already says the same word.
     */
    private val VIDEO_SWITCH_MARKERS = listOf(
        "fancam", "\uC9C1\uCEA0", "live", "\uB77C\uC774\uBE0C", "stage", "\uBB34\uB300",
        "musicbank", "\uC778\uAE30\uAC00\uC694", "\uC5E0\uCE74\uC6B4\uD2B8\uB2E4\uC6B4",
        "m countdown", "mcountdown", "reaction", "cover", "dance practice", "lyrics",
        "karaoke", "1 hour", "\uAD50\uCC28\uD3B8\uC9D1",
    )

    /**
     * Why [candidate] is not the catalogue song for [target], in the shape the
     * audio-switch log reads: `title`, `artist`, `duration Δ초`, `type` or
     * `cover`. Null when either [bestSameArtist] or
     * [bestOfficialAudioForVideo] would take it.
     *
     * Only a diagnostic: the decision itself stays in those two functions, so
     * this cannot quietly widen what plays. It exists because the log used to
     * say only "no official song match", which left a rejected official row
     * and a genuinely absent one indistinguishable on a device.
     */
    fun songVersionRejectReason(candidate: Song, target: Target): String? {
        // resolveAudio only offers non-video rows to the two matchers.
        if (candidate.isVideo) return "type"
        // Ask the real gates first, so a candidate they accept can never be
        // logged as rejected because this diagnostic forgot one of their
        // terms. Only the failing check below is reproduced.
        if (bestSameArtist(listOf(candidate), target) != null) return null
        if (bestOfficialAudioForVideo(listOf(candidate), target) != null) return null
        if (isCoverOf(candidate, target)) return "cover"
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return "title"
        if (!coresMatch(wanted.cores(), got.cores(), containment = true)) return "title"
        if (wanted.versions != got.versions) return "title"
        // A row filed under an instrumental/karaoke album is that take even
        // when its title says nothing.
        if ((albumVersionMarkers(candidate.albumName) intersect NOVOCAL_TAKES)
                .any { it !in wanted.versions }
        ) {
            return "cover"
        }
        val sameArtist = (candidate.artist.isNotBlank() &&
            namesTheSameArtist(target.artist, candidate.artist)) ||
            sameArtistIdentity(target, candidate)
        if (!sameArtist) return "artist"
        val wantedSec = target.durationSec
        val gotSec = secondsOf(candidate.durationText)
        if (wantedSec != null && gotSec != null) {
            // A video may run longer than its song by any amount (intro/outro)
            // but not shorter by more than the ordinary window. A non-video
            // target keeps the ordinary window in both directions.
            val tooLong = gotSec - wantedSec
            if (target.isVideo && tooLong > 0) {
                if (tooLong > VIDEO_DURATION_LIMIT_SEC) return "duration ${tooLong}s"
            } else if (abs(wantedSec - gotSec) > DURATION_LIMIT_SEC) {
                return "duration ${abs(wantedSec - gotSec)}s"
            }
        }
        return null
    }

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
                !target.isVideo && withinSeconds(candidate, target, CREDIT_OVERRIDE_SEC)
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

    /**
     * Whether both rows carry the same recording title, with packaging and
     * artist credits set aside.
     *
     * Used by the alternate-version rescue: a different upload may be the same
     * song only if its title is genuinely the same one, not a lyric video, a
     * remix or a differently named stage performance that happens to share a
     * word.
     */
    fun sameTitle(a: Song, b: Song): Boolean {
        val wanted = parseTitle(a.title, a.artist)
        val got = parseTitle(b.title, b.artist)
        return wanted.core.isNotEmpty() &&
            coresEqual(wanted.cores(), got.cores()) &&
            wanted.versions == got.versions
    }

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

    /**
     * Reads "Artist - Title | Work OST", "Title (From "Work")" and their
     * variants into the title, the credit and the work.
     *
     * A soundtrack fan upload is titled for a browsing list, not for a
     * catalogue search: the work and its "OST"/"Original Soundtrack" label
     * follow the song, and the performer may be printed before the title.
     * The audio switch asks this for its query, so the title it searches is
     * the song's own and the credit is the performer rather than the channel.
     */
    internal fun audioSwitchIdentity(rawTitle: String, rawArtist: String): AudioSwitchIdentity {
        val identity = soundtrackIdentity(rawTitle, rawArtist)
        // A bare "Soundtrack"/"OST" word at the end may be part of the song's
        // own name ("Our Soundtrack", "My OST"). Only a named work or a
        // dash/pipe-attached release label is packaging; anything else keeps
        // the title exactly as uploaded. Same rule as the display path.
        if (!identity.hasSoundtrackTail ||
            (identity.work == null && !identity.labelPackaged)
        ) {
            return AudioSwitchIdentity(rawTitle, rawArtist, null, false, false, false)
        }
        // The audio switch searches a catalogue, so its query is folded to the
        // matching form; the display path keeps the upload's own casing.
        return identity.copy(
            title = normalize(identity.title).trim(),
            artist = normalize(identity.artist).trim(),
            work = identity.work?.let { cleanSoundtrackWork(it) },
        )
    }

    /**
     * The same soundtrack split as [audioSwitchIdentity], with the title,
     * artist and work left in their upload casing. Display cleanup uses this
     * so a fan upload reads the same in a playlist row as the version lookup
     * asks the catalogue, without lowercasing what the listener sees.
     */
    internal fun soundtrackIdentity(rawTitle: String, rawArtist: String): AudioSwitchIdentity {
        val soundtrack = stripSoundtrackTail(rawTitle)
        if (!soundtrack.present) {
            return AudioSwitchIdentity(rawTitle, rawArtist, null, false, false, false)
        }
        DASH.find(soundtrack.text)?.let { dash ->
            val head = soundtrack.text.substring(0, dash.range.first).trim()
            val tail = soundtrack.text.substring(dash.range.last + 1).trim()
            if (head.isNotBlank() && tail.isNotBlank()) {
                // A channel/OST label before the song ("Joker OST - Following
                // Sophie") is not a performer. Drop it and let the work query
                // ask for "<title> <work> soundtrack".
                if (isUploaderCredit(head)) {
                    return AudioSwitchIdentity(
                        tail,
                        "",
                        soundtrack.work,
                        hasSoundtrackTail = true,
                        tailRemoved = soundtrack.tailRemoved,
                        labelPackaged = soundtrack.labelPackaged,
                    )
                }
                // A take/packaging label after the dash ("Song - Live",
                // "Song | Official Video") belongs to the title; inverting it
                // would search for "live"/"official" instead of the song. The
                // marker may sit inside a longer tail ("Live at Wembley"), so
                // any take word rejects the inversion, not only a tail that is
                // entirely packaging.
                if (!isPackagingOrTake(tail) && !startsWithTakeMarker(tail) &&
                    !isArtistName(tail, rawArtist)
                ) {
                    return AudioSwitchIdentity(
                        tail,
                        head,
                        soundtrack.work,
                        hasSoundtrackTail = true,
                        tailRemoved = soundtrack.tailRemoved,
                        labelPackaged = soundtrack.labelPackaged,
                    )
                }
            }
        }
        return AudioSwitchIdentity(
            soundtrack.text,
            "",
            soundtrack.work,
            hasSoundtrackTail = true,
            tailRemoved = soundtrack.tailRemoved,
            labelPackaged = soundtrack.labelPackaged,
        )
    }

    /** The target an audio switch searches for, with soundtrack packaging off. */
    internal fun audioSwitchTargetOf(song: Song): Target {
        val base = targetOf(song)
        val identity = audioSwitchIdentity(base.title, base.artist)
        if (!identity.hasSoundtrackTail) return base
        // A pipe/trailing OST label is the fan-upload shape, but a real
        // performer in the row's credit is still the performer. Blank only
        // when there is no credit or the credit is itself a channel.
        val artist = when {
            identity.artist.isNotBlank() -> identity.artist
            isUploaderCredit(base.artist) -> ""
            else -> base.artist
        }
        return base.copy(
            title = identity.title,
            artist = artist,
            noPerformer = artist.isBlank(),
        )
    }

    /** The queries an audio switch runs, including its soundtrack work. */
    internal fun audioSwitchQueries(song: Song): List<String> = queries(targetOf(song))

    private data class SoundtrackTail(
        val text: String,
        val work: String?,
        val present: Boolean,
        val tailRemoved: Boolean,
        val labelPackaged: Boolean,
    )

    /**
     * Takes a listing's soundtrack packaging off [raw] without touching the
     * title proper. Bracketed "(From ...)" asides are left for the ordinary
     * bracket pass, which keeps nested take markers; they only mark [present]
     * and supply [work] here.
     */
    private fun stripSoundtrackTail(raw: String): SoundtrackTail {
        // Case-preserving: the display path shows these strings, while every
        // matcher below folds them itself. Only the CJK bracket aliases are
        // normalised so the same asides are found in either width.
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            .replace(CJK_OPEN, "(")
            .replace(CJK_CLOSE, ")")
        var work: String? = null
        var present = false
        var tailRemoved = false
        var labelPackaged = false

        FROM_BRACKET.find(text)?.let { match ->
            present = true
            work = work ?: cleanSoundtrackWorkPreserving(match.groupValues[1])
        }

        PIPE_TAIL.find(text)?.let { match ->
            val tail = match.groupValues[1].trim()
            if (SOUNDTRACK_WORD.containsMatchIn(tail)) {
                present = true
                tailRemoved = true
                labelPackaged = true
                work = work ?: cleanSoundtrackWorkPreserving(tail)
                text = text.substring(0, match.range.first)
            }
        }

        TRAILING_SOUNDTRACK.find(text)?.let { match ->
            val before = text.substring(0, match.range.first)
            val after = text.substring(match.range.last + 1)
            val suffix = after.trim()
            val suffixWords = wordsOf(suffix)
            when {
                suffix.isEmpty() -> {
                    present = true
                    tailRemoved = true
                    labelPackaged = true
                    text = before
                }
                // A bracketed take after the label ("Song - Original
                // Soundtrack (Live)") still reaches the ordinary bracket pass.
                // Packaging that follows the bracket ("... (Live) Version")
                // belongs to the label, not the song, so it goes too; otherwise
                // it survives the bracket pass as part of the core and the
                // exact-core gates refuse the row.
                suffix.startsWith("(") || suffix.startsWith("[") -> {
                    present = true
                    tailRemoved = true
                    labelPackaged = true
                    text = before + " " + stripTrailingNeutralSuffix(after)
                }
                isNeutralSoundtrackSuffix(suffixWords) -> {
                    present = true
                    tailRemoved = true
                    labelPackaged = true
                    text = before
                }
                isPackagingOrTake(suffix) -> {
                    present = true
                    tailRemoved = true
                    labelPackaged = true
                    // Keep a recognized take as a bracketed marker, dropping
                    // neutral packaging words ("Live Version" -> "(Live)").
                    val kept = keptSoundtrackTakeWords(suffix)
                    text = if (kept.isEmpty()) before else before + " (" + kept.joinToString(" ") + ")"
                }
                // The label sits in the middle of the title. What follows it is
                // the work's packaging, not the song's name, so the label and
                // its tail are removed rather than left to become the core
                // ("Song - OST Something" is still "song"). A take inside that
                // tail ("Live at Wembley", "Remix by X") is still the take and
                // must survive, or the studio recording stands in for it.
                else -> {
                    present = true
                    tailRemoved = true
                    val kept = keptSoundtrackTakeWords(suffix)
                    text = if (kept.isEmpty()) before else before + " (" + kept.joinToString(" ") + ")"
                }
            }
        }

        TRAILING_BARE_OST.find(text)?.let { match ->
            // A bare "Soundtrack"/"OST" tail may be the song's own name ("Our
            // Soundtrack", "My OST"). Only strip it when a work was already
            // named elsewhere; otherwise leave it for the ordinary title pass.
            if (match.range.first > 0 && work != null) {
                present = true
                tailRemoved = true
                text = text.substring(0, match.range.first)
            }
        }

        text = text.replace(WHITESPACE, " ")
            .trim()
            .trim('-', '–', '—', '|', ' ')
            .trim()
        return SoundtrackTail(
            text,
            work?.takeIf { it.isNotBlank() },
            present,
            tailRemoved,
            labelPackaged,
        )
    }

    private fun isUploaderCredit(artist: String): Boolean {
        val text = normalize(artist).trim()
        return text.isBlank() || isSoundtrackCredit(text) ||
            text.endsWith("topic") || text.contains("various artists")
    }

    /**
     * Whether a credit is only a soundtrack label — "Joker OST",
     * "Original Soundtrack", a bare "Soundtrack". Such a string names the
     * release, never the performer, so display cleanup refuses it as an artist.
     */
    internal fun isSoundtrackCredit(credit: String): Boolean {
        val text = normalize(credit).trim()
        return text.isNotEmpty() && SOUNDTRACK_WORD.containsMatchIn(text)
    }

    private fun wordsOf(text: String): List<String> =
        normalize(text).split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }

    /** Packaging that follows a soundtrack label without naming a take. */
    private fun isNeutralSoundtrackSuffix(words: List<String>): Boolean =
        words.isNotEmpty() && (
            words.joinToString("") in NEUTRAL_SEGMENTS ||
                words.all {
                    it in NEUTRAL_SUFFIX_WORDS || it in TRAILING_NOISE || it in NOISE_WORDS
                }
            )

    /**
     * Drops trailing version/edition packaging after a bracketed take, so
     * "Song - Original Soundtrack (Live) Version" keeps the live marker but
     * not the label's "version" word in the title proper.
     */
    private fun stripTrailingNeutralSuffix(text: String): String {
        var out = text.trimEnd()
        while (true) {
            val match = TRAILING_WORD.find(out) ?: return out
            val word = normalize(match.groupValues[1]).replace(NON_ALNUM, "")
            if (word !in NEUTRAL_SUFFIX_WORDS) return out
            out = out.substring(0, match.range.first).trimEnd()
        }
    }

    /**
     * The take markers to keep when a soundtrack label's tail is removed.
     * A language take ("Korean Version", "Japanese Ver.") must keep its
     * language word: dropping it would leave only the packaging "version" and
     * let the original recording stand in for the language version.
     * A language word that is merely work context ("Korean Drama") is not a
     * take and stays out.
     */
    private fun keptSoundtrackTakeWords(text: String): List<String> {
        val words = wordsOf(text)
        val languageTake = isLanguageVersion(text)
        return words.filter {
            (it in VERSION_WORDS && it !in NEUTRAL_SUFFIX_WORDS) ||
                (languageTake && it in VERSION_LANGUAGES)
        }
    }

    /**
     * Whether a dash/pipe tail names a take or upload packaging rather than
     * the song ("Live", "Remix", "Official Video"). Such a tail belongs to the
     * title; inverting it would make the song "live" or "official". A tail
     * that is a title *plus* a take marker ("White Room (Live)") is not a
     * label and still hands over from the performer.
     */
    private fun isPackagingOrTake(text: String): Boolean {
        val words = normalize(text).split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        return words.all {
            it in VERSION_WORDS || it in TRAILING_NOISE || it in NOISE_WORDS ||
                it in PACKAGING_CONNECTORS || it.all(Char::isDigit)
        }
    }

    /**
     * Whether [text] opens with a recognized take/version word, even when the
     * tail as a whole is not packaging ("Live at Wembley", "Remix by X"). A
     * tail whose take marker only follows a real title ("White Room (Live)")
     * still hands over from the performer as before.
     */
    private fun startsWithTakeMarker(text: String): Boolean {
        val first = normalize(text).split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .firstOrNull { it.isNotEmpty() } ?: return false
        return first in VERSION_WORDS || first in TRAILING_TAKES
    }

    private val PACKAGING_CONNECTORS = setOf(
        "at", "in", "from", "on", "the", "a", "an", "for", "of", "and", "with", "to",
    )

    private fun cleanSoundtrackWork(raw: String): String? =
        stripSoundtrackWorkLabel(normalize(raw))
            ?.replace(NON_ALNUM, " ")
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** [cleanSoundtrackWork] without folding case, for the strings shown to a listener. */
    private fun cleanSoundtrackWorkPreserving(raw: String): String? =
        stripSoundtrackWorkLabel(Normalizer.normalize(raw, Normalizer.Form.NFKC))
            ?.replace(NON_ALNUM, " ")
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * Removes only the trailing "OST"/"Original Soundtrack" release label.
     * The words inside a work name ("Original Sin", "The Motion Picture") are
     * the work, not noise, so they stay.
     */
    private fun stripSoundtrackWorkLabel(raw: String): String? {
        var text = raw
        while (true) {
            val next = text.replace(SOUNDTRACK_WORK_TAIL, "").trim()
            if (next == text) break
            text = next
        }
        return text.takeIf { it.isNotBlank() }
    }

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

    internal fun parseTitle(raw: String, artist: String = ""): TitleParts =
        titleMemo.getOrPut(raw to artist) { parseTitleUncached(raw, artist) }

    private fun parseTitleUncached(raw: String, artist: String = ""): TitleParts {
        val versions = sortedSetOf<String>()
        val context = mutableSetOf<String>()
        val soundtrack = stripSoundtrackTail(raw)
        // A playlist row prints its position in front of the name ("3.여수밤바다",
        // "03 Ditto", "Track 3 - Song"). It is the listing's, not the song's,
        // and every catalogue search for it misses.
        var text = LEADING_TRACK_NUMBER.replace(normalize(soundtrack.text), "")
        val altTexts = mutableListOf<String>()

        // A guest credit printed in Portuguese — YouTube Music Brazil writes
        // "O Mundo É Mal (part. Clara Lima)" — is a feat. credit, and `part`
        // alone is a [VERSION_WORDS] marker: left to the bracket pass, the
        // aside comes back out as the query "… part", a word no catalogue
        // files a track under. The dot is what tells it apart from an English
        // title ("Part of Me"), and a numeral after it is still the title
        // ("Part. 2").
        text = text.replace(PT_BR_FEATURING, " ")

        // Bracketed asides, innermost first: "(From "Satyamev Jayate")",
        // "[Official Audio]", "(Live at Wembley)".
        repeat(BRACKET_PASSES) {
            if (!BRACKETED.containsMatchIn(text)) return@repeat
            text = BRACKETED.replace(text) { match ->
                classify(match.groupValues[1], versions, context)
                " "
            }
        }
        // An unbalanced bracket — a title truncated mid-aside — takes the rest
        // of the line with it rather than leaving half an aside in the core.
        text.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { open ->
            classify(text.substring(open), versions, context)
            text = text.substring(0, open)
        }

        // A soundtrack upload writes the performer first: "Cream - White
        // Room | Joker OST". Once the OST tail is gone the remaining dash is
        // the credit, not the title, so the tail becomes the identity. A tail
        // that is itself the credit keeps the ordinary title-first reading.
        // A channel/OST label before the song is dropped, and a take or
        // packaging tail ("Live", "Official Video") is left for the ordinary
        // dash pass so it stays in versions instead of becoming the title.
        if (soundtrack.tailRemoved) {
            DASH.find(text)?.let { dash ->
                val head = text.substring(0, dash.range.first)
                val tail = text.substring(dash.range.last + 1)
                if (head.isNotBlank() && tail.isNotBlank()) {
                    when {
                        isUploaderCredit(head) -> {
                            classify(head, versions, context)
                            text = tail
                        }
                        isPackagingOrTake(tail) -> Unit
                        startsWithTakeMarker(tail) -> Unit
                        !isArtistName(head, artist) && !isArtistName(tail, artist) -> {
                            classify(head, versions, context)
                            text = tail
                        }
                    }
                }
            }
        }

        // Dash- and pipe-separated tails: "Paniyon Sa - Satyamev Jayate",
        // "Song | Official Video". The head is normally the title, but the
        // "Artist - Title" upload convention inverts that, so a head that is
        // just the artist's name hands over to the tail instead of eating it.
        // A mixed-script head with no Latin in it ("第ゼロ感 - Dai Zero Kan")
        // is the transliteration shape: catalogues file the track under the
        // Latin tail, so the tail becomes the identity and the head is kept
        // as context rather than the other way round. A Hangul/Latin pair is
        // the translation shape instead: both halves are the same song and
        // both are kept as identities, the Hangul one first.
        repeat(DASH_PASSES) {
            val dash = DASH.find(text) ?: return@repeat
            val head = text.substring(0, dash.range.first)
            val tail = text.substring(dash.range.last + 1)
            text = when {
                isArtistName(head, artist) -> {
                    classify(head, versions, context)
                    tail
                }
                isLanguageVersion(tail) -> {
                    // "Song - Korean Version" is a language take of "Song",
                    // not a song called "Korean Version". Tested before the
                    // translation pair so a Hangul title with a Latin language
                    // tail ("여수밤바다 - Japanese Version") stays one take of
                    // the Hangul title instead of gaining a phantom alt core.
                    classify(tail, versions, context)
                    head
                }
                isTranslationPair(head, tail, artist) -> {
                    val hangulHead = hasHangul(head)
                    val keep = if (hangulHead) head else tail
                    val other = if (hangulHead) tail else head
                    altTexts += other
                    keep
                }
                !hasFilingLatin(head) && hasFilingLatin(tail) && !isArtistName(tail, artist) -> {
                    // "紅蓮華 - LiSA" is the title and its artist, not a
                    // transliteration: the Latin tail only takes over when it
                    // isn't just the credit.
                    classify(head, versions, context)
                    tail
                }
                else -> {
                    classify(tail, versions, context)
                    head
                }
            }
        }

        // A feat. credit belongs to the artist field wherever a catalogue
        // chooses to print it.
        text = text.replace(FEATURING, " ")

        // CJK languages do not use spaces between
        // words. Standard word splitting and non-alphanumeric filtering fragment
        // these titles into empty or broken strings, so CJK titles keep their
        // raw character sequence intact (stripping only punctuation and whitespace).
        val containsCJK = text.any { Character.UnicodeBlock.of(it) in CJK_BLOCKS }

        var words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in JOINING_WORDS }
        var droppedTail = 0
        // "Paniyon Sa Full Song", "Tum Hi Ho Audio" — an upload's trailing
        // label, printed without brackets to hang it on. The unambiguous take
        // markers get the same treatment ("Kizuna No Kiseki Instrumental"):
        // they belong in versions, so the bracketed and unbracketed spellings
        // of one take meet instead of missing each other. Only those —
        // "Let Me Live" and "Take Cover" end in a version word that is the
        // title, and moving it would make the live take the studio one.
        // Never stripped down to nothing: a track really called "Song" keeps
        // its name.
        while (words.size > 1) {
            val last = words.last()
            // "Version"/"Edition" after a bracketed take is packaging, not a
            // take: "Song (Live) Version" is the live recording of "Song", and
            // leaving the word in the core made the exact-title gates refuse it.
            if (last in TRAILING_NOISE || last in NEUTRAL_SUFFIX_WORDS) {
                words = words.dropLast(1)
                droppedTail++
            } else if (last in TRAILING_TAKES) {
                versions += last
                words = words.dropLast(1)
                droppedTail++
            } else break
        }

        // CJK cores are built from the raw text rather than [words], so the
        // same trailing packaging has to come off that text too. Otherwise
        // "紅蓮華 (Live) Version" keeps `version` in its core and the
        // exact-title gates refuse the live take and the plain row alike.
        var coreText = text
        repeat(droppedTail) {
            val match = TRAILING_SPLIT_TOKEN.find(coreText) ?: return@repeat
            coreText = coreText.substring(0, match.range.first)
        }

        val coreString = if (containsCJK) {
            coreText.replace(Regex("""[\s\p{Punct}・～〜ー-]+"""), "")
        } else {
            words.joinToString("")
        }

        val altWords = mutableListOf<List<String>>()
        val altCores = mutableListOf<String>()
        altTexts.forEach { alt ->
            val clean = alt.replace(FEATURING, " ").trim()
            if (clean.isBlank()) return@forEach
            val altCjk = clean.any {
                Character.UnicodeBlock.of(it) in setOf(
                    Character.UnicodeBlock.HIRAGANA,
                    Character.UnicodeBlock.KATAKANA,
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                    Character.UnicodeBlock.HANGUL_SYLLABLES
                )
            }
            val altWordList = clean.split(WORD_SPLIT)
                .map { it.replace(NON_ALNUM, "") }
                .filter { it.isNotEmpty() && it !in JOINING_WORDS }
            if (altWordList.isEmpty()) return@forEach
            val altCore = if (altCjk) {
                clean.replace(Regex("""[\s\p{Punct}・～〜ー-]+"""), "")
            } else {
                altWordList.joinToString("")
            }
            if (altCore.isNotBlank() && altCore != coreString) {
                altWords += altWordList
                altCores += altCore
            }
        }

        return TitleParts(
            words = words,
            core = coreString,
            versions = versions,
            context = context,
            altWords = altWords,
            altCores = altCores,
        )
    }

    /**
     * Whether [head] and [tail] are the same song in Hangul and Latin script.
     * Only a Hangul side qualifies: kana has no algorithmic reading, and a
     * Japanese title with a romaji tail is handled as the filing shape above.
     */
    private fun isTranslationPair(head: String, tail: String, artist: String): Boolean {
        val headHangul = hasHangul(head)
        val tailHangul = hasHangul(tail)
        if (headHangul == tailHangul) return false
        return when {
            headHangul && !tailHangul && hasFilingLatin(tail) && !isArtistName(tail, artist) -> true
            tailHangul && !headHangul && hasFilingLatin(head) && !isArtistName(head, artist) -> true
            else -> false
        }
    }

    /** Whether [text] carries Hangul. */
    internal fun hasHangul(text: String): Boolean = text.any {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL
    }

    /**
     * Whether any identity of [wanted] meets any identity of [got]. The lists
     * carry a title and its stated translation, so an English catalogue row
     * still matches the Korean listing that named both.
     */
    private fun coresMatch(wanted: List<String>, got: List<String>, containment: Boolean): Boolean =
        KoTitleRules.coresMatch(wanted, got, containment) // [KO] title-rules

    /** Exact identity (or the same title in the other script), across every reading. */
    private fun coresEqual(wanted: List<String>, got: List<String>): Boolean =
        wanted.any { w -> got.any { g -> w == g || romajiOf(w) == romajiOf(g) } }

    /**
     * Files one dropped segment under [versions] or [context].
     *
     * A segment naming a take — `Remix`, `Live at Wembley`, `Slowed + Reverb` —
     * is identity and is kept. Everything else is packaging: the film, the
     * label, `Official Video`, the remaster note. The phrases in
     * [NEUTRAL_SEGMENTS] are the exceptions that read like takes and aren't:
     * `Album Version` and `Radio Edit` describe the ordinary release, and
     * treating them as versions would stop a module ever matching the plain
     * listing of the same track.
     */
    private fun classify(
        segment: String,
        versions: MutableSet<String>,
        context: MutableSet<String>,
    ) {
        val words = segment.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return
        val languages = words.mapNotNull { VERSION_LANGUAGES[it] }.distinct()
        if (languages.isNotEmpty() && isLanguageVersion(segment)) {
            // A language version is a different recording. "Version"/"ver" is
            // its packaging, so the language is the marker that has to agree;
            // any other take word ("Korean Remix") still counts. A language
            // word that is merely context ("Korean Drama OST", "English
            // Translation") names the work or the listing, not a take, so it
            // stays out of versions and cannot reject the plain catalogue row.
            versions += languages
            versions += words.filter { it in VERSION_WORDS && it !in LANGUAGE_PACKAGING }
            return
        }
        val marks = words.filter { it in VERSION_WORDS }
        if (marks.isNotEmpty()) {
            versions += marks
            return
        }
        // A single CJK syllable is a whole title: the length gate exists
        // for Latin glue words and must not drop it.
        context += words.filter { (it.length > 2 || containsCJKChar(it)) && it !in NOISE_WORDS }
    }

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

    /**
     * Whether [text] is only a language version of the title ("Korean",
     * "Korean Version", "Japanese Ver."), which belongs to the head rather
     * than taking over as the song's name.
     */
    private fun isLanguageVersion(text: String): Boolean {
        val words = normalize(text).split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.any { it in VERSION_LANGUAGES } &&
            words.all {
                it in VERSION_LANGUAGES || it in VERSION_WORDS || it in LANGUAGE_PACKAGING ||
                    it in PACKAGING_CONNECTORS || it in NOISE_WORDS || it.all(Char::isDigit)
            }
    }

    /** Whether [text] is nothing but (part of) [artist] — the "Artist - Title" upload shape. */
    private fun isArtistName(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        val words =
            text.split(WORD_SPLIT).map { it.replace(NON_ALNUM, "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // Use the same credit expansion as matching: it drops the Topic/VEVO
        // channel plumbing and splits aliases, so "Artist - Song" is inverted
        // when the row's credit is the artist's channel ("ArtistVEVO") just as
        // it is for a plain "Artist" credit. The raw words stay in the set so
        // every spelling the old check accepted still matches.
        val credited = (
            normalize(artist).split(WORD_SPLIT) +
                artistVariants(artist).flatMap { it.split(WORD_SPLIT) }
            )
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
            .toSet()
        return words.all { it in credited }
    }

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
    private fun artistScore(wanted: String, got: String): Int? {
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
    internal fun artistNames(value: String): Set<List<String>> =
        artistNamesMemo.getOrPut(value) { artistNamesUncached(value) }

    private fun artistNamesUncached(value: String): Set<List<String>> = artistVariants(value)
        .map { name ->
            name.split(WORD_SPLIT)
                .map { it.replace(NON_ALNUM, "") }
                .filter { it.length > 1 }
        }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * Splits [text] on [separators] that sit outside brackets, so a stage name
     * inside "(…)" stays with the name it belongs to.
     */
    private fun splitTopLevel(text: String, separators: Regex): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var at = 0
        while (at < text.length) {
            when (text[at]) {
                '(', '[', '（', '【' -> depth++
                ')', ']', '）', '】' -> if (depth > 0) depth--
            }
            if (depth == 0) {
                val match = separators.matchAt(text, at)
                if (match != null && match.value.isNotEmpty()) {
                    val part = text.substring(start, at).trim()
                    if (part.isNotEmpty()) out += part
                    at = match.range.last + 1
                    start = at
                    continue
                }
            }
            at++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /**
     * "강현준(lil tachi)" as its two names. The bracket has to look like a name
     * (two characters or more on both sides) so that "f(x)" is not read as an
     * artist called "x".
     */
    private fun expandArtistName(part: String): List<String> {
        val name = part.trim().trim(',', ';', '·', '|')
        if (name.isBlank()) return emptyList()
        val trailing = TRAILING_ALIAS.find(name) ?: return listOf(name)
        val base = trailing.groupValues[1].trim()
        val alias = trailing.groupValues[2].trim()
        return if (base.length >= 2 && alias.length >= 2 && base.any { it.isLetter() }) {
            listOf(base, alias)
        } else {
            listOf(name)
        }
    }

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
     * Whether two runtimes differ by more than [DURATION_LIMIT_SEC], meaning
     * they cannot be the same recording. Used to detect when a cached or
     * live stream is a different edit from the requested catalogue track.
     */
    fun isSevereMismatch(expectedSec: Int?, actualSec: Int?): Boolean {
        if (expectedSec == null || actualSec == null) return false
        return abs(actualSec - expectedSec) > DURATION_LIMIT_SEC
    }

    /** Visual intros/outros can make the video substantially longer than its audio master. */
    const val VIDEO_DURATION_LIMIT_SEC = 90

    private const val BRACKET_PASSES = 3
    private const val DASH_PASSES = 3

    private val CJK_BLOCKS = setOf(
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
    )

    private val BRACKETED = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val DASH = Regex("""\s+[-–—|]+\s+""")
    private val WHITESPACE = Regex("""\s+""")
    private val FEATURING = Regex("""\b(feat|ft|featuring|with)\b.*""")
    /** "(From \"Joker\")", the other way a soundtrack upload names its work. */
    private val FROM_BRACKET = Regex("""\(\s*from\s+([^)]*)\)""", RegexOption.IGNORE_CASE)
    /** The last pipe-separated segment, where a fan upload puts "Joker OST". */
    private val PIPE_TAIL = Regex("""\s*\|\s*([^|]+)\s*$""")
    private val SOUNDTRACK_WORD = Regex(
        """(?<![a-z])ost(?![a-z])|\bsoundtracks?\b""",
        RegexOption.IGNORE_CASE,
    )
    /** " - Original Soundtrack", " - OST" as a label, without eating a take marker after it. */
    private val TRAILING_SOUNDTRACK = Regex(
        """\s+[-–—]\s+(?:(?:original\s+(?:motion\s+picture\s+)?)?soundtracks?|ost)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** A bare "OST"/"Soundtrack" label hung on the end without a dash. */
    private val TRAILING_BARE_OST = Regex(
        """\s+(?:(?:original\s+(?:motion\s+picture\s+)?)?soundtracks?|ost)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val SOUNDTRACK_WORK_TAIL = Regex(
        """(?:\s*[-–—|]\s*)?(?:(?<!\p{L})(?:original\s+(?:motion\s+picture\s+)?)?soundtracks?|(?<!\p{L})ost)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * A listing's track number in front of the name: "3.", "03 ", "3) ",
     * "Track 3 - ". A bare number followed by a word ("7 Rings") is a title
     * and is left alone; so is "3 Nights". The leading-zero form is accepted
     * because a zero-padded number is never how a title starts.
     */
    private val LEADING_TRACK_NUMBER = Regex(
        """^\s*(?:track\s+\d{1,3}\s*[-–—.:)]\s*|\d{1,3}\s*[.)\]\-–—]\s*(?=\D|$)|0\d{1,2}\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )
    /** A trailing "(stage name)" that can be split off an artist credit. */
    private val TRAILING_ALIAS = Regex("""^(.*?)[(\[（【]([^)\]）】]*)[)\]）】]\s*$""")
    /**
     * The Portuguese spelling of the same credit, see [parseTitle]. Only the
     * dotted form and an explicit `part esp.` count: an undotted `part` is
     * English often enough ("Part of Me") to empty a real title. A numeral or
     * roman numeral after the marker is still the title ("Part. 2", "Part II"),
     * and `parte` is a different word.
     */
    private val PT_BR_FEATURING = Regex(
        """\bpart\.\s+(?!(?:\d|[ivx]+\b))[^)\]]*[)\]]?|\bpart\s+(?:esp\.?|especial)\b[^)\]]*[)\]]?""",
        RegexOption.IGNORE_CASE,
    )
    /** `~` and the wave dash `〜` join words in Japanese titles ("真夜中のドア〜stay"). */
    private val WORD_SPLIT = Regex("""[\s.·~〜]+""")
    /** The last word of a text, for stripping trailing edition packaging. */
    private val TRAILING_WORD = Regex("""\s+(\S+)\s*$""")
    /** The last word after any [WORD_SPLIT] boundary, for stripping a CJK core's packaging. */
    private val TRAILING_SPLIT_TOKEN = Regex("""[.\s·~〜]+[^.\s·~〜]+\s*$""")
    /**
     * What survives into identity: any language's letters and digits.
     * ASCII-only (`[^a-z0-9]`) is what emptied every CJK/Devanagari title to
     * `""` — and an empty core matches nothing and asks for nothing.
     */
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]""")
    private val LATIN = Regex("[a-z0-9]")
    private const val LATIN_QUERY_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789 "
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
    private val CJK_OPEN = Regex("[「『【〈《〔［｛]")
    private val CJK_CLOSE = Regex("[」』】〉》〕］｝]")
    // Not the katakana middle dot `・`: it sits *inside* a transliterated
    // name (ジョン・レノン), and splitting there makes two Johns one artist.
    private val ARTIST_SEPARATORS =
        Regex("""\s*(?:[,&/;·|、，×]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|및)\s*""")

    /**
     * YouTube's auto-generated artist channel suffix: "Artist - Topic". It
     * names the uploader, not another performer, so it is dropped from the
     * credit before identity is compared.
     */
    private val TOPIC_CHANNEL_SUFFIX = Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE)

    /** A VEVO channel is the artist's own channel, not a second name ("ArtistVEVO"). */
    private val VEVO_CHANNEL_SUFFIX = Regex("""(?i)(?<=[a-z0-9])\s*vevo$""")

    /**
     * What makes a listing a different recording rather than a different
     * listing of the same one. A title carrying one of these on one side only
     * is refused outright.
     */
    private val VERSION_WORDS = setOf(
        "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
        "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
        // A stem is not the song. "Vocals Only", "Acapella", "Backing Track"
        // and friends carry the right title and the right artist and are not
        // remotely the recording anybody asked for.
        "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
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
     * Language words that make a row a version of the song rather than the
     * song itself. Kept apart from [VERSION_WORDS] because the language is the
     * part that must agree: "Korean Ver." and "Korean Version" are the same
     * take, and both differ from the original. Every spelling folds to one
     * canonical key so a Japanese listing and an English catalogue row agree.
     */
    private val VERSION_LANGUAGES = mapOf(
        "korean" to "korean", "한국어" to "korean", "韓国語" to "korean", "韓語" to "korean",
        "english" to "english", "영어" to "english", "英語" to "english",
        "japanese" to "japanese", "일본어" to "japanese", "日本語" to "japanese",
        "chinese" to "chinese", "중국어" to "chinese", "中国語" to "chinese",
    )

    /** Packaging around a language marker: "Version", "Ver.". */
    private val LANGUAGE_PACKAGING = setOf("version", "ver")

    /**
     * The [VERSION_WORDS] that are a take even printed bare at the end of a
     * title ("Kizuna No Kiseki Instrumental"). The rest are real title words
     * often enough ("Let Me Live", "Take Cover", "The Final Chapter") that
     * reading them as a take would let the live or cover recording stand in
     * for the original.
     */
    private val TRAILING_TAKES = setOf(
        "instrumental", "karaoke", "acapella", "acappella", "remix", "rmx", "nightcore",
    )

    /**
     * Takes whose absence from the audio is the point: a row filed under an
     * album declaring one of these *is* that take even when its own title
     * says nothing ("Am I Dreaming" on "... (METROVERSE INSTRUMENTAL
     * EDITION)"). Applied from the album in both directions, unlike the
     * rescue above — which is what stops the 256s instrumental twin from
     * standing in for the 256s vocal no duration check can separate.
     */
    private val NOVOCAL_TAKES = setOf(
        "instrumental", "karaoke", "acapella", "acappella", "backing", "stems", "stem",
    )

    /**
     * Asides that read like a version and describe the ordinary release. The
     * exception list to [VERSION_WORDS] — without it, "Song (Album Version)"
     * and "Song" would be two different recordings.
     */
    private val NEUTRAL_SEGMENTS = setOf(
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack",
    )

    /** Packaging words that follow a soundtrack label without naming a take. */
    private val NEUTRAL_SUFFIX_WORDS = setOf("version", "edition")

    /** Where YouTube Music prints a type instead of an artist: "… — Música". */
    private val YOUTUBE_TYPE_LABELS = setOf(
        "música", "musica", "vídeo", "video", "song", "album", "álbum", "artista", "artist",
    )

    /** Packaging words, worth nothing as a tie-break because everything has them. */
    private val NOISE_WORDS = setOf(
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
        "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
        "featuring", "with", "new", "latest", "free", "download", "remaster",
        "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
        "album", "single", "hd", "hq", "4k", "mp3",
    )

    private val ALBUM_NOISE_WORDS = setOf(
        "album", "deluxe", "edition", "expanded", "remaster", "remastered",
        "version", "explicit", "clean", "bonus", "anniversary",
    )

    /** Trailing labels an upload hangs on a title with no brackets to hold them. */
    private val TRAILING_NOISE = setOf(
        "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
        "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack",
        // `Youngblood M/V` and `Youngblood MV` print the tag without brackets.
        // Normalisation drops the slash, so both arrive here as `mv`.
        "mv",
    )

    /** Dropped from the core so that "Jack and Jill" and "Jack & Jill" are one title. */
    private val JOINING_WORDS = setOf("and")
}
