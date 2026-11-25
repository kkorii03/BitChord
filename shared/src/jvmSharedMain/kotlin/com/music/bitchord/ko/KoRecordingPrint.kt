package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.ChromaAlignment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * [KO] Whether two decodes are the same recording, not only the same song.
 *
 * The chroma match hears chords and the words check hears lyrics; a cover on
 * the same chords singing the same lyrics passes both (11-24 눈의 꽃 by a
 * violin, 11-25 another voice). This is the classic sign fingerprint: per
 * [FRAME_SECONDS] step, whether the energy step between neighbouring bands
 * (300-2000 Hz) rose or fell. A codec, a level or an equaliser hardly moves
 * those signs; another performance has its own.
 *
 * Measured 11-25 on store previews (two stores, AAC / MP3 against Opus 96k on
 * another grid and sample rate; share of bits that differ, best lag of each
 * five seconds): the same recording 0.08-0.13; remixes and other-language takes
 * built on the original stems 0.15-0.44; live takes 0.34-0.46; covers,
 * instrumentals and piano versions 0.42-0.47.
 */
object KoRecordingPrint {

    enum class Sound { SAME, DIFFERENT, UNCLEAR }

    private const val FRAME_SECONDS = 0.032
    private const val WINDOW = 2048
    private const val BANDS = 32 // signs per frame, from BANDS + 1 energies
    private const val LOW_HZ = 300.0
    private const val HIGH_HZ = 2000.0

    /** Frames between the two band profiles a sign compares. */
    private const val STEP = 2

    private const val SILENT_RMS = 0.002
    private const val SILENT_BIT = 1L shl BANDS
    private const val BITS = SILENT_BIT - 1

    /** Five seconds: long enough that a chance lag stays far above a true one. */
    private const val BLOCK = 156

    /** Fewer sounding blocks (30 s) than this say nothing. */
    private const val MIN_BLOCKS = 6

    private const val SAME_MEDIAN = 0.22

    /** One five seconds closer than this is the original's own sound (a remix, a long lead-in): never DIFFERENT. */
    private const val OTHER_BEST = 0.30
    private const val OTHER_QUARTER = 0.36
    private const val OTHER_MEDIAN = 0.40

    /** Collects the print of decoded, reduced samples as they arrive. */
    internal class Printer(private val rate: Double) {
        private val ring = FloatArray(WINDOW)
        private var taken = 0L
        private var frame = 0L
        private var nextEnd = WINDOW.toLong()
        private val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / WINDOW)).toFloat() }
        private val re = DoubleArray(WINDOW)
        private val im = DoubleArray(WINDOW)

        /** Band of each bin; -1 outside the range. */
        private val bandOf = IntArray(WINDOW / 2).also { bands ->
            bands.fill(-1)
            for (band in 0..BANDS) {
                val from = edge(band)
                val to = edge(band + 1)
                for (bin in bands.indices) {
                    val hz = bin * rate / WINDOW
                    if (hz >= from && hz < to) bands[bin] = band
                }
            }
        }
        private val steps = Array(STEP + 1) { DoubleArray(BANDS) }
        private var out = LongArray(4096)
        private var size = 0

        private fun edge(band: Int) = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(band / (BANDS + 1.0))

        fun push(sample: Float) {
            ring[(taken % WINDOW).toInt()] = sample
            taken++
            if (taken != nextEnd) return
            frame()
            // By time, not by a rounded hop: 44.1 and 48 kHz files reduce to different rates.
            nextEnd = WINDOW + (frame * FRAME_SECONDS * rate).roundToLong()
        }

        private fun frame() {
            var energy = 0.0
            val start = taken - WINDOW
            for (k in 0 until WINDOW) {
                val v = ring[((start + k) % WINDOW).toInt()]
                energy += v * v
                re[k] = (v * hann[k]).toDouble()
                im[k] = 0.0
            }
            ChromaAlignment.fft(re, im)
            val bands = DoubleArray(BANDS + 1)
            for (bin in bandOf.indices) {
                val band = bandOf[bin]
                if (band >= 0) bands[band] += re[bin] * re[bin] + im[bin] * im[bin]
            }
            val now = steps[(frame % (STEP + 1)).toInt()]
            for (band in 0 until BANDS) now[band] = bands[band] - bands[band + 1]
            if (frame >= STEP) {
                val before = steps[((frame - STEP) % (STEP + 1)).toInt()]
                var bits = if (sqrt(energy / WINDOW) < SILENT_RMS) SILENT_BIT else 0L
                for (band in 0 until BANDS) if (now[band] - before[band] > 0.0) bits = bits or (1L shl band)
                if (size == out.size) out = out.copyOf(size * 2)
                out[size++] = bits
            }
            frame++
        }

        fun print(): LongArray = out.copyOf(size)
    }

    /**
     * [reference] against [candidate]. UNCLEAR when either carries no print,
     * too little of it sounds, or the figures lie between a recording and
     * another performance (a remix, a remaster from other tapes).
     */
    fun compare(reference: LongArray?, candidate: LongArray?): Sound {
        val errors = errors(reference ?: return Sound.UNCLEAR, candidate ?: return Sound.UNCLEAR)
        if (errors.size < MIN_BLOCKS) return Sound.UNCLEAR
        return when {
            errors[errors.size / 2] <= SAME_MEDIAN -> Sound.SAME
            errors[0] >= OTHER_BEST && errors[errors.size / 4] >= OTHER_QUARTER &&
                errors[errors.size / 2] >= OTHER_MEDIAN -> Sound.DIFFERENT
            else -> Sound.UNCLEAR
        }
    }

    /** The log's figures: quarter and median share of differing bits. */
    fun figures(reference: LongArray?, candidate: LongArray?): String {
        val errors = errors(reference ?: return "none", candidate ?: return "none")
        if (errors.isEmpty()) return "none"
        fun percent(v: Double) = (v * 100).roundToInt()
        return "${percent(errors[errors.size / 4])}/${percent(errors[errors.size / 2])}%"
    }

    /**
     * Each sounding five seconds of [reference], at the place of [candidate]
     * it fits best, ascending. Searched block by block, so a lead-in, a cut
     * or a drifting clock costs the blocks it touches and nothing else.
     */
    private fun errors(reference: LongArray, candidate: LongArray): DoubleArray {
        if (candidate.size < BLOCK) return DoubleArray(0)
        val found = ArrayList<Double>()
        var at = 0
        while (at + BLOCK <= reference.size) {
            val silent = (at until at + BLOCK).count { reference[it] and SILENT_BIT != 0L }
            if (silent <= BLOCK * 3 / 10) {
                var best = Int.MAX_VALUE
                for (lag in 0..candidate.size - BLOCK) {
                    var wrong = 0
                    var k = 0
                    while (k < BLOCK && wrong < best) {
                        wrong += java.lang.Long.bitCount((reference[at + k] xor candidate[lag + k]) and BITS)
                        k++
                    }
                    if (wrong < best) best = wrong
                }
                found += best.toDouble() / (BLOCK * BANDS)
            }
            at += BLOCK
        }
        return found.toDoubleArray().also { it.sort() }
    }
}