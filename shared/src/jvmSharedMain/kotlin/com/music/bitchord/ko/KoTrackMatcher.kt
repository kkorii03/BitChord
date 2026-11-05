package com.music.bitchord.ko

import com.music.bitchord.data.BoundedMemo
import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.TrackMatcher.Target
import com.music.bitchord.data.sources.TrackMatcher.TitleParts
import com.music.bitchord.data.sources.TrackMatcher.AudioSwitchIdentity
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Korean-fork title, soundtrack and recording-matching rules kept out of
 * the upstream TrackMatcher file.
 *
 * TrackMatcher keeps the upstream functions and delegates to this object
 * through the lines marked `// [KO]`.
 */
internal object KoTrackMatcher {

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

    /** The first credited artist — who a catalogue is most likely to file the track under. */
    internal fun primaryArtist(artist: String): String =
        artistVariants(artist).firstOrNull().orEmpty()

    private val artistNamesMemo = BoundedMemo<String, Set<List<String>>>(MEMO_ENTRIES)

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
        val want = artistNames(wanted)
        val have = artistNames(got)
        if (want.any { it in have }) return true
        // Two printed names that YouTube has linked to one channel are the
        // same act: a solo member and their group, or an act's former name.
        // Only a channel both names have actually been seen under counts.
        val wantedIds = artistVariants(wanted).mapNotNull { ArtistNameIndex.lookup(it) }.toSet()
        val gotIds = artistVariants(got).mapNotNull { ArtistNameIndex.lookup(it) }.toSet()
        if (wantedIds.isNotEmpty() && gotIds.isNotEmpty()) {
            return wantedIds.any { it in gotIds }
        }
        // A leading article is how listings print the same act ("The 1975" and
        // "1975" are one band); it is not part of the name for identity. The
        // rest of the name still has to match whole, so a tribute band that
        // merely starts with it stays a different artist. When both names have
        // known but different channels, the ids above have already ruled the
        // article match out.
        if (want.any { withoutArticle(it) in have } ||
            have.any { withoutArticle(it) in want }
        ) {
            return true
        }
        return false
    }

    /** The name without a leading "the", when there is a name left. */
    private fun withoutArticle(name: List<String>): List<String> =
        if (name.size > 1 && name.first() == "the") name.drop(1) else name

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
    internal fun sameArtistIdentity(target: Target, candidate: Song): Boolean =
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
        /**
         * Accept a row whose artist is written in a different script, as long
         * as title, take and runtime agree — see [isArtistUnverifiedOriginal].
         * The sound comparison still has to accept it; this only lets the
         * caller *find* it and offer it to that comparison.
         */
        allowArtistUnverified: Boolean = false,
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
            if (!sameArtist &&
                !(allowArtistUnverified && isArtistUnverifiedOriginal(candidate, target, maxDriftSec))
            ) {
                return false
            }
        }
        if (target.durationSec != null) {
            val gotSec = secondsOf(candidate.durationText) ?: return false
            if (abs(target.durationSec - gotSec) > maxDriftSec) return false
        }
        return true
    }

    /**
     * Whether [candidate] is the requested recording apart from an artist
     * credit that cannot be compared because it is written in another script.
     *
     * A catalogue localises a Western act's name (Qobuz returns
     * "Flowers/マイリー・サイラス" for Miley Cyrus). The name then shares no
     * letters with the queue row, so an ordinary credit check can only ever
     * say "different" — and every such localised result was thrown away before
     * the audio comparison could rescue it. This keeps the row only when every
     * signal that *is* comparable agrees: the exact normalised title (featuring
     * credits and other brackets are already excluded by [parseTitle]), the
     * same take markers, a runtime within [ARTIST_UNVERIFIED_DRIFT_SEC], and no
     * cover/karaoke/tribute arrangement. A same-script but different artist
     * still fails [artistScriptsDiffer], so this never becomes a general
     * artist bypass.
     *
     * This is a search grade, not a replacement verdict: callers must still
     * have the sound comparison accept the row before it may play.
     */
    fun isArtistUnverifiedOriginal(
        candidate: Song,
        target: Target,
        maxDriftSec: Int = ARTIST_UNVERIFIED_DRIFT_SEC,
    ): Boolean {
        if (target.artist.isBlank() || candidate.artist.isBlank()) return false
        if (!artistScriptsDiffer(target.artist, candidate.artist)) return false
        // A channel or release identity that proves the same act is a normal
        // match, not an unverified one.
        if (namesTheSameArtist(target.artist, candidate.artist) ||
            sharesArtistChannel(target, candidate) ||
            sharesRelease(target, candidate)
        ) {
            return false
        }
        if (isCoverOf(candidate, target)) return false
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return false
        if (!coresEqual(wanted.cores(), got.cores())) return false
        val albumMarks = albumVersionMarkers(candidate.albumName)
        val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
            (albumMarks intersect wanted.versions)
        if (wanted.versions != effectiveVersions) return false
        val wantedSec = target.durationSec ?: return false
        val gotSec = secondsOf(candidate.durationText) ?: return false
        return abs(wantedSec - gotSec) <= maxDriftSec
    }

    /**
     * Whether the two credits are written in scripts that share no letters at
     * all (Latin vs kana/Hangul/Han/Cyrillic, ...). When they do share a script
     * a different name is a different artist and must stay a rejection.
     */
    fun artistScriptsDiffer(wanted: String, got: String): Boolean =
        scriptsDiffer(scriptsOf(wanted), scriptsOf(got))

    /**
     * Whether [candidate] is the requested recording apart from a title filed
     * under another script's name.
     *
     * A catalogue names a release in its own language: Deezer lists ILLIT's
     * "체포하겠어" as "You're Under Arrest". The title then shares no letters
     * with the queue row, so the exact-title gate could only ever answer
     * "different" — the FLAC was dropped before the sound comparison was asked
     * about it, and the listener was told there was no better copy while one
     * sat in the search results. This keeps the row only when every signal that
     * *is* comparable agrees: the same act by name, channel or release, a title
     * in the other writing system ([latinAgainstOtherScript]), the same take
     * markers, a runtime within [ARTIST_UNVERIFIED_DRIFT_SEC], and no
     * cover/karaoke/tribute arrangement. The verified credit is what keeps this
     * from becoming a general title bypass — a same-act row filed in Latin
     * while the request is not, at the same length, is the only thing it
     * admits.
     *
     * This is a search grade, not a replacement verdict: the sound comparison
     * still has to accept the row before it may play.
     */
    fun isTitleUnverifiedOriginal(
        candidate: Song,
        target: Target,
        maxDriftSec: Int = ARTIST_UNVERIFIED_DRIFT_SEC,
    ): Boolean {
        if (target.title.isBlank() || candidate.title.isBlank()) return false
        if (target.artist.isBlank() || candidate.artist.isBlank()) return false
        // The act has to be the one asked for, by a signal that survives a
        // display-name difference. Without this the other-script title would
        // admit any row of the right length — the Takedown row a search for
        // '체포하겠어' also returned.
        val sameAct = namesTheSameArtist(target.artist, candidate.artist) ||
            sharesArtistChannel(target, candidate) ||
            sharesRelease(target, candidate)
        if (!sameAct) return false
        if (!latinAgainstOtherScript(target.title, candidate.title)) return false
        if (isCoverOf(candidate, target)) return false
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isBlank() || got.core.isBlank()) return false
        val albumMarks = albumVersionMarkers(candidate.albumName)
        val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
            (albumMarks intersect wanted.versions)
        if (wanted.versions != effectiveVersions) return false
        val wantedSec = target.durationSec ?: return false
        val gotSec = secondsOf(candidate.durationText) ?: return false
        return abs(wantedSec - gotSec) <= maxDriftSec
    }

    /**
     * Whether one title is written in Latin and the other in a script that
     * carries none — the shape a catalogue's own-language filing takes
     * ("You're Under Arrest" for ILLIT's "체포하겠어").
     *
     * Deliberately not "any two scripts": Hangul against kana is a release's
     * Japanese version far more often than a localised title, and a chroma
     * comparison cannot tell two vocal languages of one melody apart — the
     * version would then be swapped in under the Korean title.
     */
    internal fun latinAgainstOtherScript(wanted: String, got: String): Boolean =
        hasFilingLatin(wanted) != hasFilingLatin(got)

    /**
     * Whether [candidate] may be adopted as the audio of music video [target]
     * on the other-script-title grade alone — the same grade the quality
     * upgrade uses, see [isTitleUnverifiedOriginal].
     *
     * Its three-second window is the one thing that does not carry over: a
     * video's runtime is visual timing, so the 3:12 upload is the 3:05 album
     * cut. The window is the video switch's own ([videoRuntimeAllows] with
     * [VIDEO_DURATION_LIMIT_SEC]) and nothing wider. The same act, the same
     * take markers and "not a cover" still come from that grade, and a cover,
     * karaoke or remix row is refused there.
     */
    internal fun isAudioSwitchTitleUnverified(candidate: Song, target: Target): Boolean =
        videoRuntimeAllows(target, candidate) &&
            isTitleUnverifiedOriginal(candidate, target, VIDEO_DURATION_LIMIT_SEC) // [KO] title-unverified

    private fun scriptsDiffer(
        want: Set<Character.UnicodeScript>,
        have: Set<Character.UnicodeScript>,
    ): Boolean = want.isNotEmpty() && have.isNotEmpty() && want.intersect(have).isEmpty()

    private fun scriptsOf(text: String): Set<Character.UnicodeScript> {
        val out = mutableSetOf<Character.UnicodeScript>()
        for (ch in normalize(text)) {
            if (!ch.isLetter()) continue
            when (val script = Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> Unit
                else -> out += script
            }
        }
        return out
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
     * `match`, `title`, `title-unverified`, `version`, `artist`,
     * `artist-unverified`, `duration`.
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
    ): String {
        if (strictOriginal) {
            if (isCoverOf(candidate, target)) return "version"
            val wanted = parseTitle(target.title, target.artist)
            val got = parseTitle(candidate.title, candidate.artist)
            if (wanted.core.isBlank() || got.core.isBlank()) return "title"
            if (!coresEqual(wanted.cores(), got.cores())) {
                // A title filed under another script's name is offered to the
                // sound comparison rather than reported as a plain title miss
                // — see [isTitleUnverifiedOriginal].
                if (allowArtistUnverified &&
                    isTitleUnverifiedOriginal(candidate, target, artistUnverifiedDriftSec)
                ) {
                    return "title-unverified"
                }
                return "title"
            }
            val albumMarks = albumVersionMarkers(candidate.albumName)
            val effectiveVersions = got.versions + (albumMarks intersect NOVOCAL_TAKES) +
                (albumMarks intersect wanted.versions)
            if (wanted.versions != effectiveVersions) return "version"
            if (target.artist.isNotBlank()) {
                val sameArtist = (candidate.artist.isNotBlank() &&
                    namesTheSameArtist(target.artist, candidate.artist)) ||
                    sharesArtistChannel(target, candidate) ||
                    sharesRelease(target, candidate)
                if (!sameArtist) {
                    if (allowArtistUnverified) {
                        if (isArtistUnverifiedOriginal(candidate, target, artistUnverifiedDriftSec)) {
                            return "artist-unverified"
                        }
                        // Title and take may agree while only the runtime is
                        // out. Report the term that actually rejected the row
                        // instead of calling a localised credit an artist miss.
                        if (artistScriptsDiffer(target.artist, candidate.artist) &&
                            strictLength && target.durationSec != null
                        ) {
                            val gotSec = secondsOf(candidate.durationText) ?: return "duration"
                            if (abs(target.durationSec - gotSec) > artistUnverifiedDriftSec &&
                                isArtistUnverifiedOriginal(candidate, target, Int.MAX_VALUE)
                            ) {
                                return "duration"
                            }
                        }
                    }
                    return "artist"
                }
            }
            if (strictLength && target.durationSec != null) {
                val gotSec = secondsOf(candidate.durationText) ?: return "duration"
                if (abs(target.durationSec - gotSec) > driftSec) return "duration"
            }
            return "match"
        }
        if (requireSharedArtist && !sharesArtist(target.artist, candidate.artist)) {
            if (allowArtistUnverified) {
                if (isArtistUnverifiedOriginal(candidate, target, artistUnverifiedDriftSec)) {
                    return "artist-unverified"
                }
                if (strictLength && target.durationSec != null &&
                    artistScriptsDiffer(target.artist, candidate.artist)
                ) {
                    val gotSec = secondsOf(candidate.durationText) ?: return "duration"
                    if (abs(target.durationSec - gotSec) > artistUnverifiedDriftSec &&
                        isArtistUnverifiedOriginal(candidate, target, Int.MAX_VALUE)
                    ) {
                        return "duration"
                    }
                }
            }
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
     * audio-switch log reads: `title`, `artist`, `duration Δ초`, `type(video)`
     * or `cover`. Null when either [bestSameArtist] or
     * [bestOfficialAudioForVideo] would take it.
     *
     * Only a diagnostic: the decision itself stays in those two functions, so
     * this cannot quietly widen what plays. It exists because the log used to
     * say only "no official song match", which left a rejected official row
     * and a genuinely absent one indistinguishable on a device.
     */
    fun songVersionRejectReason(candidate: Song, target: Target): String? {
        // resolveAudio only offers non-video rows to the two matchers. A
        // youtube.com fallback answers every row with a 16:9 still, so this is
        // the line that says whether the page had an audio row at all.
        if (candidate.isVideo) return "type(video)"
        // Ask the real gates first, so a candidate they accept can never be
        // logged as rejected because this diagnostic forgot one of their
        // terms. Only the failing check below is reproduced.
        if (bestSameArtist(listOf(candidate), target) != null) return null
        if (bestOfficialAudioForVideo(listOf(candidate), target) != null) return null
        // The other-script grade the audio switch adopts (see
        // [isAudioSwitchTitleUnverified]) is a real gate too, so a row it takes
        // is never logged as a title miss.
        if (isAudioSwitchTitleUnverified(candidate, target)) return null
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
     * The row among [candidates] that is [target]'s song under another name,
     * proven by a row on the same [page] that prints both names.
     *
     * A Korean release files its track as "체포하겠어" while the music video is
     * titled "You're Under Arrest", and those two strings share nothing any
     * matcher can compare: the official audio row is refused as a title miss
     * and the video keeps playing. The two names do meet somewhere — a fanchant
     * or stage upload is titled "체포하겠어 (You're Under Arrest)" — so the page
     * itself is the evidence and nothing is guessed; see [bracketReadings] for
     * how that aside is read as a second name.
     *
     * Only [candidates] may be accepted: an audio row, never a video. The
     * bridging row may be anything, including a video, because it is evidence
     * rather than something that will play.
     *
     * Three checks keep the wider title from playing a wrong song. The take
     * markers must agree, as they must for the ordinary video switch. The
     * accepted row must be the same act as the row that printed both names (by
     * name, channel or release). And its runtime must be within
     * [videoRuntimeAllows] of the target's. Nothing after the swap re-measures
     * the recording, so these are all that stand between a wider title and a
     * wrong track.
     *
     * Returns the accepted row and the bridge reading that matched the target,
     * or null when no row on the page states both names.
     */
    fun bestTitleBridged(
        page: List<Song>,
        candidates: List<Song>,
        target: Target,
    ): Pair<Song, String>? {
        val wanted = parseTitle(target.title, target.artist)
        if (wanted.core.isBlank()) return null
        val wantedCores = wanted.cores()
        for (bridge in page) {
            val readings = readingCores(bridge)
            // One reading is just a title; a bridge states the song twice.
            if (readings.size < 2) continue
            val onTarget = readings.firstOrNull {
                coresMatch(wantedCores, listOf(it), containment = true)
            } ?: continue
            val bridgeTarget = targetOf(bridge)
            for (candidate in candidates) {
                if (candidate.videoId == bridge.videoId || candidate.isVideo) continue
                if (isCoverOf(candidate, target)) continue
                // The take markers are identity, exactly as they are for the
                // ordinary video switch: a live or remix row is a different
                // recording, and an "… (Instrumental Edition)" album row is
                // that take even when its title says nothing.
                val got = parseTitle(candidate.title, candidate.artist)
                if (wanted.versions != got.versions) continue
                if ((albumVersionMarkers(candidate.albumName) intersect NOVOCAL_TAKES)
                        .any { it !in wanted.versions }
                ) {
                    continue
                }
                if (!videoRuntimeAllows(target, candidate)) continue
                val sameAct = (candidate.artist.isNotBlank() &&
                    namesTheSameArtist(bridge.artist, candidate.artist)) ||
                    sharesArtistChannel(bridgeTarget, candidate) ||
                    sharesRelease(bridgeTarget, candidate)
                if (!sameAct) continue
                val gotCores = readingCores(candidate)
                val onCandidate = readings.any {
                    it != onTarget && coresMatch(listOf(it), gotCores, containment = true)
                }
                if (!onCandidate) continue
                return candidate to onTarget
            }
        }
        return null
    }

    /**
     * Every reading a row files its title under: the one it displays, and for a
     * row whose display title was replaced by the catalogue release's, the
     * upload's own title too (see `Song.uploadTitle`).
     */
    private fun readingCores(song: Song): List<String> {
        val upload = song.uploadTitle?.takeIf { it.isNotBlank() }
        return (readingsOf(song.title, song.artist) +
            upload?.let { readingsOf(it, song.artist) }.orEmpty())
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** A title's own readings plus the names its brackets state — see [bracketReadings]. */
    private fun readingsOf(title: String, artist: String): List<String> =
        parseTitle(title, artist).cores() + bracketReadings(title, artist)

    /**
     * The bracketed spellings of [raw] that are the title itself in the other
     * script, folded the way [TitleParts.cores] folds a title.
     *
     * [parseTitle] reads a bracketed aside as packaging, which is what it is on
     * most uploads ("(Official Video)", "(Live at Wembley)"). A fanchant or
     * stage upload is the exception: it prints both names of the song
     * ("체포하겠어 (You're Under Arrest)"), and that is the only place a Korean
     * release title and its English music-video title meet. This stays out of
     * [parseTitle] on purpose — the search queries and every source and
     * replacement gate read its readings, and none of them may start comparing
     * an aside. Only [bestTitleBridged] asks for these.
     */
    internal fun bracketReadings(raw: String, artist: String = ""): List<String> {
        val text = normalize(stripSoundtrackTail(raw).text)
        if (!BRACKETED.containsMatchIn(text)) return emptyList()
        return BRACKETED.findAll(text)
            .mapNotNull { match ->
                val aside = match.groupValues[1]
                val translated = isTranslationPair(
                    text.substring(0, match.range.first),
                    aside,
                    artist,
                )
                if (!translated || !isNamedAside(aside)) return@mapNotNull null
                // A bracket holding the act's own name ("ILLIT (아일릿)") is a
                // credit, not a second name for the song.
                if (isArtistName(aside, artist)) return@mapNotNull null
                foldAside(aside)?.second
            }
            .distinct()
            .toList()
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
        // A featured artist is a credited performer too. YouTube Music files
        // "Get Lucky" under Daft Punk while a search can return the release
        // credited to Pharrell Williams alone, so both credits have to count.
        val withFeatured = base.copy(artist = withFeaturingArtists(base.artist, base.title))
        if (!identity.hasSoundtrackTail) return withFeatured
        // A pipe/trailing OST label is the fan-upload shape, but a real
        // performer in the row's credit is still the performer. Blank only
        // when there is no credit or the credit is itself a channel.
        val artist = when {
            identity.artist.isNotBlank() -> identity.artist
            isUploaderCredit(base.artist) -> ""
            else -> base.artist
        }
        return withFeatured.copy(
            title = identity.title,
            artist = withFeaturingArtists(artist, identity.title),
            noPerformer = artist.isBlank(),
        )
    }

    /**
     * [artist] plus the performers a title credits after "feat."/"ft."/
     * "featuring". A trailing dash label or bracket packaging is not a name.
     */
    private fun withFeaturingArtists(artist: String, title: String): String {
        val featured = FEATURING_CREDITS.findAll(normalize(title))
            .flatMap { match ->
                val credit = DASH.find(match.groupValues[1])
                    ?.let { match.groupValues[1].substring(0, it.range.first) }
                    ?: match.groupValues[1]
                splitTopLevel(credit, ARTIST_SEPARATORS)
            }
            .map { it.trim() }
            .filter { it.isNotBlank() && !isPackagingOrTake(it) }
            .distinct()
            .toList()
        if (featured.isEmpty()) return artist
        return (listOf(artist).filter { it.isNotBlank() } + featured).joinToString(", ")
    }

    /** The performers after a featuring credit, up to a bracket or dash label. */
    private val FEATURING_CREDITS = Regex(
        """(?i)\b(?:featuring|feat|ft)\.?\s+([^()\[\]{}（）【】]+)""",
    )

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
                // The label is packaging, but a take after it ("Joker OST
                // Korean Version", "Joker OST (Live)") still names the
                // recording. Dropping the whole pipe tail let the original
                // stand in for that take.
                val kept = keptSoundtrackTakeWords(tail)
                val before = text.substring(0, match.range.first)
                text = if (kept.isEmpty()) {
                    before
                } else {
                    before + " (" + kept.joinToString(" ") + ")"
                }
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

    internal fun isUploaderCredit(artist: String): Boolean {
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
        // A named label ("Joker OST Korean Version") puts the take after the
        // soundtrack word, so the language check has to read that suffix.
        // Without it only the packaging "version" survived and the original
        // recording could stand in for the language take.
        val label = SOUNDTRACK_WORD.findAll(text).lastOrNull()
        val takeText = label?.let { text.substring(it.range.last + 1) } ?: text
        // Neutral packaging after the label describes the ordinary release
        // ("Radio Edit", "Original Mix"), so it is not a take. The dash path
        // already applies this gate; the pipe path needs it too or splitting
        // the tail turns an "edit"/"mix" word into a version and the plain
        // catalogue row stops matching.
        val takeWords = wordsOf(takeText)
        if (takeWords.joinToString("") in NEUTRAL_SEGMENTS) return emptyList()
        val languageTake = isLanguageVersion(takeText)
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
        val containsCJK = text.any {
            Character.UnicodeBlock.of(it) in setOf(
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.HANGUL_SYLLABLES
            )
        }

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
            val (altWordList, altCore) = foldAside(alt) ?: return@forEach
            if (altCore != coreString) {
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
     * One alternate spelling folded into the shape [TitleParts.core] uses: its
     * words, and the letters-and-digits core they join to. Null when nothing
     * but punctuation is left.
     */
    private fun foldAside(raw: String): Pair<List<String>, String>? {
        val clean = raw.replace(FEATURING, " ").trim()
        if (clean.isBlank()) return null
        val cjk = clean.any {
            Character.UnicodeBlock.of(it) in setOf(
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.HANGUL_SYLLABLES,
            )
        }
        val words = clean.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in JOINING_WORDS }
        if (words.isEmpty()) return null
        val core = if (cjk) {
            clean.replace(Regex("""[\s\p{Punct}・～〜ー-]+"""), "")
        } else {
            words.joinToString("")
        }
        return if (core.isBlank()) null else words to core
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
     * Whether a bracketed aside reads as a name for the song rather than as a
     * take or as packaging.
     *
     * The other half of the bracket translation pair: a take marker
     * ("(Live)", "(Instrumental)") is a different recording and must never
     * become a second reading of the title, and an aside made only of
     * packaging words ("(Feat. X)", "(Official Video)") names nothing.
     */
    private fun isNamedAside(aside: String): Boolean {
        val words = normalize(aside).split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty() || words.any { it in VERSION_WORDS }) return false
        return words.any { it.length > 1 && it !in NOISE_WORDS && it !in TRAILING_NOISE }
    }

    /**
     * Whether any identity of [wanted] meets any identity of [got]. The lists
     * carry a title and its stated translation, so an English catalogue row
     * still matches the Korean listing that named both.
     */
    internal fun coresMatch(wanted: List<String>, got: List<String>, containment: Boolean): Boolean {
        for (w in wanted) {
            for (g in got) {
                if (w == g || romajiOf(w) == romajiOf(g)) return true
                if (containment && w.length > 3 && g.length > 3 &&
                    (w.contains(g) || g.contains(w))
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Exact identity (or the same title in the other script), across every reading. */
    internal fun coresEqual(wanted: List<String>, got: List<String>): Boolean =
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
        takeMarkers: Boolean = true,
    ) {
        val words = segment.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return
        // Text before a named soundtrack label is the work name, not the
        // song's take: "Korean OST Drama" names a Korean-drama soundtrack,
        // while the language of the take can only follow the label
        // ("Joker OST Korean Version"). Reading the prefix as a take made
        // the plain catalogue row look like a different recording.
        if (!takeMarkers) {
            context += words.filter { it.length > 2 && it !in NOISE_WORDS }
            return
        }
        // A named soundtrack label inside a dropped tail names the release,
        // not the take: "Joker OST Korean" keeps its language marker while
        // "Joker" and "OST" stay context. Only segments with a take after the
        // label are split; a bare/terminal label keeps the old reading.
        val label = SOUNDTRACK_WORD.findAll(segment).lastOrNull()
        if (label != null) {
            val before = segment.substring(0, label.range.first)
            val after = segment.substring(label.range.last + 1)
            if (after.isNotBlank()) {
                if (before.isNotBlank()) classify(before, versions, context, takeMarkers = false)
                classify(after, versions, context)
                return
            }
        }
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
        context += words.filter { it.length > 2 && it !in NOISE_WORDS }
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

    /** The last word of a text, for stripping trailing edition packaging. */
    private val TRAILING_WORD = Regex("""\s+(\S+)\s*$""")

    /** The last word after any [WORD_SPLIT] boundary, for stripping a CJK core's packaging. */
    private val TRAILING_SPLIT_TOKEN = Regex("""[.\s·~〜]+[^.\s·~〜]+\s*$""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * YouTube's auto-generated artist channel suffix: "Artist - Topic". It
     * names the uploader, not another performer, so it is dropped from the
     * credit before identity is compared.
     */
    private val TOPIC_CHANNEL_SUFFIX = Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE)

    /** A VEVO channel is the artist's own channel, not a second name ("ArtistVEVO"). */
    private val VEVO_CHANNEL_SUFFIX = Regex("""(?i)(?<=[a-z0-9])\s*vevo$""")

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

    /** Packaging words that follow a soundtrack label without naming a take. */
    private val NEUTRAL_SUFFIX_WORDS = setOf("version", "edition")

    // Forwarders to upstream members that remain in TrackMatcher.
    internal fun normalize(text: String): String = TrackMatcher.normalize(text)
    internal fun hasFilingLatin(text: String): Boolean = TrackMatcher.hasFilingLatin(text)
    fun romajiOf(text: String): String = TrackMatcher.romajiOf(text)
    internal fun albumVersionMarkers(album: String?): Set<String> = TrackMatcher.albumVersionMarkers(album)
    fun secondsOf(text: String?): Int? = TrackMatcher.secondsOf(text)
    fun withinSeconds(candidate: Song, target: Target, seconds: Int): Boolean = TrackMatcher.withinSeconds(candidate, target, seconds)
    fun sharesArtist(wanted: String, got: String): Boolean = TrackMatcher.sharesArtist(wanted, got)
    fun queries(target: Target): List<String> = TrackMatcher.queries(target)
    private fun artistScore(wanted: String, got: String): Int? = TrackMatcher.artistScore(wanted, got)
    fun score(candidate: Song, target: Target): Int? = TrackMatcher.score(candidate, target)
    fun ranked(candidates: List<Song>, target: Target): List<Song> = TrackMatcher.ranked(candidates, target)
    fun bestOfficialAudioForVideo(candidates: List<Song>, target: Target): Song? = TrackMatcher.bestOfficialAudioForVideo(candidates, target)
    fun targetOf(song: Song): Target = TrackMatcher.targetOf(song)
    private val BRACKETED: Regex get() = TrackMatcher.BRACKETED
    private val DASH: Regex get() = TrackMatcher.DASH
    private val FEATURING: Regex get() = TrackMatcher.FEATURING
    private val WORD_SPLIT: Regex get() = TrackMatcher.WORD_SPLIT
    private val NON_ALNUM: Regex get() = TrackMatcher.NON_ALNUM
    private val LATIN: Regex get() = TrackMatcher.LATIN
    private val CJK_OPEN: Regex get() = TrackMatcher.CJK_OPEN
    private val CJK_CLOSE: Regex get() = TrackMatcher.CJK_CLOSE
    private val ARTIST_SEPARATORS: Regex get() = TrackMatcher.ARTIST_SEPARATORS
    private val VERSION_WORDS: Set<String> get() = TrackMatcher.VERSION_WORDS
    private val TRAILING_TAKES: Set<String> get() = TrackMatcher.TRAILING_TAKES
    private val NOVOCAL_TAKES: Set<String> get() = TrackMatcher.NOVOCAL_TAKES
    private val NEUTRAL_SEGMENTS: Set<String> get() = TrackMatcher.NEUTRAL_SEGMENTS
    private val NOISE_WORDS: Set<String> get() = TrackMatcher.NOISE_WORDS
    private val TRAILING_NOISE: Set<String> get() = TrackMatcher.TRAILING_NOISE
    private val JOINING_WORDS: Set<String> get() = TrackMatcher.JOINING_WORDS
    private val DURATION_LIMIT_SEC: Int get() = TrackMatcher.DURATION_LIMIT_SEC
    private val SUBSTITUTE_DRIFT_SEC: Int get() = TrackMatcher.SUBSTITUTE_DRIFT_SEC
    private val ARTIST_UNVERIFIED_DRIFT_SEC: Int get() = TrackMatcher.ARTIST_UNVERIFIED_DRIFT_SEC
    private val VIDEO_DURATION_LIMIT_SEC: Int get() = TrackMatcher.VIDEO_DURATION_LIMIT_SEC
    private val ALBUM_NOISE_WORDS: Set<String> get() = TrackMatcher.ALBUM_NOISE_WORDS
    private val LATIN_QUERY_CHARS: String get() = TrackMatcher.LATIN_QUERY_CHARS
    private val BRACKET_PASSES: Int get() = TrackMatcher.BRACKET_PASSES
    private val DASH_PASSES: Int get() = TrackMatcher.DASH_PASSES
}
