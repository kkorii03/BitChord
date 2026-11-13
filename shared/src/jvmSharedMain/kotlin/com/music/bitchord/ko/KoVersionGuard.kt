package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Another recording over the same backing track: a remix that hands the first
 * verse to a different singer, filed under the original's title and credit.
 *
 * The sound comparison cannot see it. Chroma follows the chords and the
 * melody's pitch classes, and a remix built on the original's instrumental
 * shares both: 11-13 Despacito, a Telegram FLAC tagged 'Despacito' by
 * 'Luis Fonsi' scored match=96% against the YouTube original and was swapped
 * in, and the listener heard Justin Bieber's English verse under the Spanish
 * lyric sheet. The same search had answered rows credited to
 * 'Luis Fonsi, Daddy Yankee, Justin Bieber': the catalogue held a second
 * version of this song, so the one labelled as the original could be it.
 *
 * Two parts, both pure:
 *  - [namesOtherVersion] / [noteSearch] / [risky]: a search that also offered
 *    this song by its own artist with an extra credited singer or a version
 *    marker ("remix", "live" …) the song does not have marks every row of
 *    that search as needing the words check. Nothing else pays for it.
 *  - [compareWords]: what the YouTube copy was heard to sing (kept by the
 *    song check) against what the candidate is heard to sing over the same
 *    stretch, carried onto the YouTube clock through the measured offset.
 *    The same recording heard twice shares most of its words; a different
 *    singer's verse shares almost none.
 */
object KoVersionGuard {

    /** What the words of the two copies say. */
    enum class Words { SAME, DIFFERENT, UNDECIDED }

    /** The comparison, with counts only: the log line must never carry lyric text. */
    data class WordsCheck(
        val verdict: Words,
        val referenceWords: Int,
        val candidateWords: Int,
        val shared: Int,
        val windowMs: Long,
    )

    /** What the verification does with a chroma verdict and, when one was needed, a words check. */
    enum class Decision { ACCEPT, REJECT, UNMEASURED }

    /**
     * The final answer for one measured pair.
     *
     * A chroma refusal stands as before. A pair that needed the words check
     * and could not get one (no reply from the engine) is unmeasured rather
     * than accepted: the YouTube copy keeps playing, nothing is stored, and
     * the next event asks again. Only words that clearly disagree reject;
     * too few words on either side (an instrumental opening) leave the chroma
     * verdict standing.
     */
    fun decide(chromaAccepted: Boolean, needsWords: Boolean, words: WordsCheck?): Decision = when {
        !chromaAccepted -> Decision.REJECT
        !needsWords -> Decision.ACCEPT
        words == null -> Decision.UNMEASURED
        words.verdict == Words.DIFFERENT -> Decision.REJECT
        else -> Decision.ACCEPT
    }

    // ── Which candidates need the words check ───────────────────────────────

    /**
     * Whether [rowTitle]/[rowArtist] is this song, by its own artist, in a
     * version the song itself does not name: another credited singer, or a
     * take marker (remix, live, acoustic …) the song's title does not carry.
     *
     * A row by somebody else entirely is a cover and is the chroma's and the
     * arrangement check's business; this is only about the artist's own
     * second version, which is the one a channel mislabels as the original.
     */
    fun namesOtherVersion(
        targetTitle: String,
        targetArtist: String,
        rowTitle: String,
        rowArtist: String,
    ): Boolean {
        if (targetTitle.isBlank() || rowTitle.isBlank()) return false
        val wanted = TrackMatcher.parseTitle(targetTitle, targetArtist)
        val got = TrackMatcher.parseTitle(rowTitle, rowArtist)
        val wantedCores = wanted.cores()
        if (wantedCores.isEmpty() || got.cores().none { it in wantedCores }) return false
        if (!TrackMatcher.sharesArtist(targetArtist, rowArtist)) return false
        if ((got.versions - wanted.versions).isNotEmpty()) return true
        val want = TrackMatcher.artistNames(targetArtist) + featuredIn(targetTitle)
        if (want.isEmpty()) return false
        val credited = TrackMatcher.artistNames(rowArtist) + featuredIn(rowTitle)
        return credited.any { name -> want.none { sameName(it, name) } }
    }

    /** The names a title credits after `feat.`/`ft.`/`featuring`, as [TrackMatcher.artistNames] words. */
    private fun featuredIn(title: String): Set<List<String>> {
        val tail = TrackMatcher.FEATURING.find(TrackMatcher.normalize(title))?.value ?: return emptySet()
        val names = tail.replaceFirst(FEAT_WORD, " ").replace(BRACKETS, " ")
        return TrackMatcher.artistNames(names)
    }

    /** One credit is the other as a run of whole words ("Daddy Yankee" in "Daddy Yankee Remix"). */
    private fun sameName(a: List<String>, b: List<String>): Boolean = runOf(a, b) || runOf(b, a)

    private fun runOf(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        return (0..outer.size - inner.size).any { at -> outer.subList(at, at + inner.size) == inner }
    }

    private val FEAT_WORD = Regex("""^\s*(feat|ft|featuring)\.?""")
    private val BRACKETS = Regex("""[()\[\]{}]""")

    /** Source rows whose search also offered another version of their song, by `source\u0001track`. */
    private val riskyRows = ConcurrentHashMap<String, Boolean>()

    /** One evening's searches; an old mark is worth no more than a fresh search. */
    private const val MAX_RISKY_ROWS = 1024

    /**
     * Marks every row [rows] of one search for the words check when any of
     * them is another version of the song asked for. Called once per search
     * answer, by the search itself; nothing here runs on its own.
     */
    fun noteSearch(
        sourceId: String,
        targetTitle: String,
        targetArtist: String,
        rows: List<Song>,
        trackIdOf: (Song) -> String,
    ) {
        if (rows.isEmpty()) return
        if (rows.none { namesOtherVersion(targetTitle, targetArtist, it.title, it.artist) }) return
        if (riskyRows.size + rows.size > MAX_RISKY_ROWS) riskyRows.clear()
        rows.forEach { row ->
            val track = trackIdOf(row).takeIf { it.isNotBlank() } ?: return@forEach
            riskyRows[key(sourceId, track)] = true
        }
    }

    /** Whether this source row came from a search that also held another version of its song. */
    fun risky(sourceId: String?, trackId: String?): Boolean {
        val source = sourceId?.takeIf { it.isNotBlank() } ?: return false
        val track = trackId?.takeIf { it.isNotBlank() } ?: return false
        return riskyRows[key(source, track)] == true
    }

    internal fun clearForTest() = riskyRows.clear()

    private fun key(sourceId: String, trackId: String) = sourceId + '\u0001' + trackId

    // ── The words check ─────────────────────────────────────────────────────

    /**
     * Fewer distinct words than this on either side says nothing: an
     * instrumental opening, or a hum the engine heard as two words.
     */
    const val MIN_WORDS = 6

    /**
     * The share of the smaller side's distinct words the other side must also
     * hold. The same recording heard twice by the same engine over the same
     * stretch agrees on most of its words — the edges are trimmed so a word
     * cut in half by one window and not the other does not count — so even a
     * rough transcript of a remaster sits far above this. Another singer's
     * verse shares only the odd common word ("yeah"), and one in another
     * language none at all. Only clearly below this rejects.
     */
    const val MIN_SHARED_SHARE = 0.25

    /** Words this close to either end of the shared stretch are not compared. */
    const val EDGE_MS = 2_000L

    /** A shared stretch shorter than this is too little to call either way. */
    const val MIN_WINDOW_MS = 10_000L

    /**
     * The YouTube copy's words ([reference], heard up to [referenceUntilMs])
     * against the candidate's ([candidate], heard up to [candidateUntilMs] on
     * its own clock), over the stretch both cover. [offsetMs] and [rate] are
     * the chroma alignment's: `candidate = offset + rate × youtube`.
     */
    fun compareWords(
        reference: List<LyricsWordAlign.Heard>,
        referenceUntilMs: Long,
        candidate: List<LyricsWordAlign.Heard>,
        candidateUntilMs: Long,
        offsetMs: Long,
        rate: Double,
    ): WordsCheck {
        val pace = if (rate.isFinite() && rate > 0.0) rate else 1.0
        fun onYouTube(candidateMs: Long): Double = (candidateMs - offsetMs) / pace
        val from = maxOf(0.0, onYouTube(0L)) + EDGE_MS
        val until = minOf(referenceUntilMs.toDouble(), onYouTube(candidateUntilMs)) - EDGE_MS
        val windowMs = (until - from).toLong().coerceAtLeast(0L)
        val heardHere = reference
            .filter { it.startMs >= from && it.startMs <= until }
            .flatMap { tokens(it.text) }
            .toSet()
        val heardThere = candidate
            .filter { val at = onYouTube(it.startMs); at >= from && at <= until }
            .flatMap { tokens(it.text) }
            .toSet()
        val shared = heardHere.count { it in heardThere }
        val verdict = when {
            windowMs < MIN_WINDOW_MS -> Words.UNDECIDED
            heardHere.size < MIN_WORDS || heardThere.size < MIN_WORDS -> Words.UNDECIDED
            shared < MIN_SHARED_SHARE * minOf(heardHere.size, heardThere.size) -> Words.DIFFERENT
            else -> Words.SAME
        }
        return WordsCheck(verdict, heardHere.size, heardThere.size, shared, windowMs)
    }

    /**
     * The words worth comparing in one heard item: letters and digits only,
     * lowercased. Latin and other spaced scripts keep words of three letters
     * or more, which drops the particles every verse shares ("de", "la",
     * "oh", "my"); a CJK word keeps two characters or more.
     */
    internal fun tokens(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .split(SPACES)
            .map { it.replace(NOT_A_LETTER, "") }
            .filter { word ->
                word.length >= 3 || (word.length >= 2 && TrackMatcher.containsCJKChar(word))
            }

    private val SPACES = Regex("""\s+""")
    private val NOT_A_LETTER = Regex("""[^\p{L}\p{N}]""")
}

/**
 * The opening of a candidate, kept as 16 kHz mono 16-bit PCM while the chroma
 * decode reads it, so the words check needs no second decode.
 *
 * Only made for a candidate that needs the words check. Each block of input
 * samples is averaged into one output sample — a box filter, enough for a
 * speech engine that resamples to 16 kHz itself — and anything past
 * [seconds] is ignored.
 */
class KoOpeningClip(private val seconds: Double) {

    private var inputRate = 0
    private var outputRate = 0
    private var step = 1.0
    private var buffer: ShortArray? = null
    private var written = 0
    private var phase = 0.0
    private var sum = 0.0
    private var count = 0

    /** Mono samples in −1..1 at [rate]; a chunk at another rate than the first is ignored. */
    fun push(samples: FloatArray, rate: Int) {
        if (rate <= 0) return
        if (inputRate == 0) {
            inputRate = rate
            outputRate = minOf(rate, TARGET_RATE)
            step = rate.toDouble() / outputRate
            buffer = ShortArray((seconds * outputRate).toInt().coerceAtLeast(1))
        }
        if (rate != inputRate) return
        val out = buffer ?: return
        for (sample in samples) {
            if (written >= out.size) return
            sum += sample
            count++
            phase += 1.0
            if (phase >= step) {
                phase -= step
                val mean = (sum / count).coerceIn(-1.0, 1.0)
                out[written++] = (mean * Short.MAX_VALUE).toInt().toShort()
                sum = 0.0
                count = 0
            }
        }
    }

    /** How much opening is held, in milliseconds. */
    val durationMs: Long
        get() = if (outputRate <= 0) 0L else written * 1000L / outputRate

    /** The held opening as a WAV file, or null when nothing was pushed. */
    fun wav(): ByteArray? {
        val out = buffer ?: return null
        if (written <= 0) return null
        val dataBytes = written * 2
        val wav = ByteArray(44 + dataBytes)
        fun putInt(at: Int, value: Int) {
            wav[at] = value.toByte()
            wav[at + 1] = (value shr 8).toByte()
            wav[at + 2] = (value shr 16).toByte()
            wav[at + 3] = (value shr 24).toByte()
        }
        fun putShort(at: Int, value: Int) {
            wav[at] = value.toByte()
            wav[at + 1] = (value shr 8).toByte()
        }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wav, 0)
        putInt(4, 36 + dataBytes)
        "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(wav, 8)
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, outputRate)
        putInt(28, outputRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(wav, 36)
        putInt(40, dataBytes)
        for (i in 0 until written) putShort(44 + i * 2, out[i].toInt())
        return wav
    }

    private companion object {
        const val TARGET_RATE = 16_000
    }
}
