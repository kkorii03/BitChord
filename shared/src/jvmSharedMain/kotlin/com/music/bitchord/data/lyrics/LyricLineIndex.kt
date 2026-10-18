package com.music.bitchord.data.lyrics

/**
 * A position index over a lyric list: which line is current, and which lines
 * are still being sung, without scanning the whole list on every frame.
 *
 * Lines are stamped in time order, so the current line is a binary search.
 * The unfinished vocals behind it (overlaps, answering vocals) are found by
 * walking backwards only as far as the last line whose end could still reach
 * the playhead, which is a prefix maximum worked out once here.
 *
 * Not thread-safe: one instance per composition (or per caller), because the
 * active-row result is reused between calls whose answer has not changed. That
 * reuse is the point: the lyric clock ticks every frame, and a fresh list per
 * tick was garbage for a picture that had not moved.
 */
class LyricLineIndex(private val lines: List<LyricLine>) {
    private val times = LongArray(lines.size) { lines[it].timeMs }
    /** The furthest any line up to and including [i] can still be sung. */
    private val reach = LongArray(lines.size)
    private val ordered: Boolean

    private var scratch = IntArray(0)
    private var published: List<Int> = emptyList()
    private var publishedSize = 0

    init {
        var sorted = true
        var furthest = Long.MIN_VALUE
        for (i in lines.indices) {
            if (i > 0 && times[i] < times[i - 1]) sorted = false
            val end = maxOf(times[i], lines[i].endMs)
            if (end > furthest) furthest = end
            reach[i] = furthest
        }
        ordered = sorted
    }

    /**
     * The last line whose stamp has passed, or -1 before the first one. The
     * same answer `indexOfLast { it.timeMs <= positionMs }` gives, in
     * O(log n) on a list that is in time order.
     */
    fun lineAt(positionMs: Long): Int {
        if (!ordered) {
            for (i in times.indices.reversed()) {
                if (times[i] <= positionMs) return i
            }
            return -1
        }
        var low = 0
        var high = times.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (times[mid] <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * Every row still unfinished at [positionMs], in list order: the current
     * line and any earlier vocal whose stated end reaches past the playhead.
     * Same answer as `activeLyricRows`, without the per-frame full scan.
     */
    fun activeRows(positionMs: Long): List<Int> {
        val latest = lineAt(positionMs)
        if (latest < 0) return emptyList()
        if (!ordered) return linearRows(latest, positionMs)
        if (scratch.size <= latest) scratch = IntArray(latest + 1)
        var count = 0
        var i = latest
        while (i >= 0) {
            // Everything at or before this row ends before the playhead, so
            // nothing further back can be active either.
            if (i != latest && reach[i] <= positionMs) break
            if (i == latest || rowActive(lines[i], positionMs)) scratch[count++] = i
            i--
        }
        // Scratch is newest first; published is in list order.
        var same = count == publishedSize
        if (same) {
            for (k in 0 until count) {
                if (published[count - 1 - k] != scratch[k]) {
                    same = false
                    break
                }
            }
        }
        if (same) return published
        val rows = ArrayList<Int>(count)
        for (k in count - 1 downTo 0) rows.add(scratch[k])
        published = rows
        publishedSize = count
        return rows
    }

    /** The old whole-list answer, kept for a list that is not in time order. */
    private fun linearRows(latest: Int, positionMs: Long): List<Int> {
        val rows = ArrayList<Int>(2)
        for (index in 0..latest) {
            if (index == latest || rowActive(lines[index], positionMs)) rows.add(index)
        }
        return rows
    }

    private fun rowActive(line: LyricLine, positionMs: Long): Boolean {
        if (line.isGap) return false
        val knownEnd = line.hasKnownEnd || line.background?.hasKnownEnd == true
        return knownEnd && line.timeMs <= positionMs && positionMs < line.endMs
    }
}
