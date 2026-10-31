package com.music.bitchord.ko

import com.music.bitchord.data.sources.TrackMatcher

/**
 * Korean-fork matcher declarations that lived inside upstream TrackMatcher
 * before the v1.9 merge and were lost with that file's conflict hunks.
 *
 * The call sites stay in TrackMatcher; the declarations live here so the next
 * upstream merge has nothing of ours to overwrite.
 */

/** "(From \"Joker\")", the other way a soundtrack upload names its work. */
internal val FROM_BRACKET = Regex("""\(\s*from\s+([^)]*)\)""", RegexOption.IGNORE_CASE)

/** The last pipe-separated segment, where a fan upload puts "Joker OST". */
internal val PIPE_TAIL = Regex("""\s*\|\s*([^|]+)\s*$""")

internal val SOUNDTRACK_WORD = Regex(
    """(?<![a-z])ost(?![a-z])|\bsoundtracks?\b""",
    RegexOption.IGNORE_CASE,
)

/** " - Original Soundtrack", " - OST" as a label, without eating a take marker after it. */
internal val TRAILING_SOUNDTRACK = Regex(
    """\s+[-–—]\s+(?:(?:original\s+(?:motion\s+picture\s+)?)?soundtracks?|ost)\b""",
    RegexOption.IGNORE_CASE,
)

/** A bare "OST"/"Soundtrack" label hung on the end without a dash. */
internal val TRAILING_BARE_OST = Regex(
    """\s+(?:(?:original\s+(?:motion\s+picture\s+)?)?soundtracks?|ost)\s*$""",
    RegexOption.IGNORE_CASE,
)

internal val SOUNDTRACK_WORK_TAIL = Regex(
    """(?:\s*[-–—|]\s*)?(?:(?<!\p{L})(?:original\s+(?:motion\s+picture\s+)?)?soundtracks?|(?<!\p{L})ost)\s*$""",
    RegexOption.IGNORE_CASE,
)

/**
 * A listing's track number in front of the name: "3.", "03 ", "3) ",
 * "Track 3 - ". A bare number followed by a word ("7 Rings") is a title
 * and is left alone; so is "3 Nights". The leading-zero form is accepted
 * because a zero-padded number is never how a title starts.
 */
internal val LEADING_TRACK_NUMBER = Regex(
    """^\s*(?:track\s+\d{1,3}\s*[-–—.:)]\s*|\d{1,3}\s*[.)\]\-–—]\s*(?=\D|$)|0\d{1,2}\s+)\s*""",
    RegexOption.IGNORE_CASE,
)

/** A trailing "(stage name)" that can be split off an artist credit. */
internal val TRAILING_ALIAS = Regex("""^(.*?)[(\[（【]([^)\]）】]*)[)\]）】]\s*$""")

/** Packaging words that follow a soundtrack label without naming a take. */
internal val NEUTRAL_SUFFIX_WORDS = setOf("version", "edition")

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
internal fun koArtistVariants(artist: String): List<String> {
    val out = LinkedHashSet<String>()
    splitArtistTopLevel(TrackMatcher.normalize(artist), ARTIST_SEPARATORS).forEach { part ->
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

private fun splitArtistTopLevel(text: String, separators: Regex): List<String> {
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

private val ARTIST_SEPARATORS =
    Regex("""\s*(?:[,&/;·|、，×]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|및)\s*""")

private val TOPIC_CHANNEL_SUFFIX = Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE)

private val VEVO_CHANNEL_SUFFIX = Regex("""(?i)(?<=[a-z0-9])\s*vevo$""")
