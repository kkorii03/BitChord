package dev.dunkblock.core

import kotlin.math.floor
import kotlin.math.sqrt

/** Distribution summary. All values share the unit of the input (the caller states the unit). */
data class Summary(
    val count: Int,
    val mean: Double,
    val median: Double,
    val p95: Double,
    val p99: Double,
    val min: Double,
    val max: Double,
    val stdDev: Double,
) {
    fun fmt(unit: String = "", digits: Int = 3): String =
        if (count == 0) "n=0" else
            "n=$count mean=${f(mean, digits)}$unit median=${f(median, digits)}$unit " +
                "p95=${f(p95, digits)}$unit p99=${f(p99, digits)}$unit max=${f(max, digits)}$unit"

    companion object {
        val EMPTY = Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }
}

internal fun f(v: Double, digits: Int = 3): String =
    if (v.isNaN()) "NaN" else String.format(java.util.Locale.ROOT, "%.${digits}f", v)

object Stats {
    /** Linear-interpolated percentile of an ascending-sorted array. [p] in 0..100. */
    fun percentileSorted(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = (p / 100.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    fun summarize(values: DoubleArray): Summary {
        if (values.isEmpty()) return Summary.EMPTY
        val s = values.copyOf().also { it.sort() }
        var sum = 0.0
        for (v in s) sum += v
        val mean = sum / s.size
        var sq = 0.0
        for (v in s) sq += (v - mean) * (v - mean)
        val sd = if (s.size > 1) sqrt(sq / (s.size - 1)) else 0.0
        return Summary(
            s.size, mean, percentileSorted(s, 50.0), percentileSorted(s, 95.0),
            percentileSorted(s, 99.0), s[0], s[s.size - 1], sd,
        )
    }

    fun summarizeInts(values: IntArray, from: Int = 0, to: Int = values.size, scale: Double = 1.0): Summary {
        val n = (to - from).coerceAtLeast(0)
        val d = DoubleArray(n) { values[from + it] * scale }
        return summarize(d)
    }

    /** Successive differences (b[i+1]-b[i]) of a monotonic-ish series, converted by [scale]. */
    fun deltas(values: LongArray, n: Int, scale: Double): DoubleArray {
        if (n < 2) return DoubleArray(0)
        return DoubleArray(n - 1) { (values[it + 1] - values[it]) * scale }
    }
}
