package top.z7workbench.loggi.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sorted union of search matches and pinned lines for the results pane.
 *
 * Matches stream in from the engine in ascending batches (per stripe, stripes
 * may interleave), so inserts keep a sorted list with an append fast path.
 * Pins are user-driven and few, kept in a second sorted list. Display order is
 * the union of both (duplicates shown once), resolved by binary search at
 * access time — no merged copy is ever materialized.
 *
 * Mutations bump [version] so Compose recomposes.
 */
class ResultsModel {
    private val matches = ArrayList<Long>() // sorted ascending
    private val pins = ArrayList<Long>() // sorted ascending
    private var dupCount = 0 // |pins ∩ matches|, maintained incrementally

    var version by mutableStateOf(0L)
        private set

    val matchCount: Int get() = matches.size

    /** Pin count as snapshot state (toolbar/menu enabled states observe it). */
    var pinCount by mutableStateOf(0)
        private set

    /**
     * Size of the union (pins ∪ matches, duplicates once), held as snapshot
     * state. The results-pane `LazyColumn` re-runs its content lambda only
     * when snapshot state read inside it changes — as a plain getter this
     * left the list stale (M8.5 bug: results/pins never appeared).
     */
    var size by mutableStateOf(0)
        private set

    fun isPinned(line: Long): Boolean = pins.binarySearch(line) >= 0

    fun setPinned(line: Long, pinned: Boolean) {
        val i = pins.binarySearch(line)
        if (pinned && i < 0) {
            pins.add(-i - 1, line)
            if (matches.binarySearch(line) >= 0) dupCount++
            bump()
        } else if (!pinned && i >= 0) {
            pins.removeAt(i)
            if (matches.binarySearch(line) >= 0) dupCount--
            bump()
        }
    }

    fun togglePinned(line: Long) = setPinned(line, !isPinned(line))

    fun pinsSnapshot(): List<Long> = pins.toList()

    /** Up to [maxSamples] match lines, stride-sampled, for the overview strip. */
    fun sampleMatches(maxSamples: Int): LongArray {
        val n = matches.size
        if (n == 0) return LongArray(0)
        val step = maxOf(1, n / maxSamples)
        val out = LongArray((n + step - 1) / step)
        var i = 0
        var j = 0
        while (i < n) {
            out[j++] = matches[i]
            i += step
        }
        return out.copyOf(j)
    }

    fun restorePins(lines: Collection<Long>) {
        pins.clear()
        pins.addAll(lines.sorted().distinct())
        dupCount = pins.count { matches.binarySearch(it) >= 0 }
        bump()
    }

    fun clearMatches() {
        if (matches.isEmpty()) return
        matches.clear()
        dupCount = 0
        bump()
    }

    /** Drop all pins (toolbar "unpin all"); matches are untouched. */
    fun clearPins() {
        if (pins.isEmpty()) return
        pins.clear()
        dupCount = 0
        bump()
    }

    /** Merge an ascending batch of match lines (engine guarantee) into the set. */
    fun addMatchBatch(batch: LongArray, length: Int) {
        if (length <= 0) return
        // Fast path: batch starts past the current tail.
        if (matches.isEmpty() || batch[0] > matches.last()) {
            for (i in 0 until length) matches.add(batch[i])
        } else {
            val merged = ArrayList<Long>(matches.size + length)
            var i = 0
            var j = 0
            while (i < matches.size && j < length) {
                if (matches[i] <= batch[j]) merged.add(matches[i++]) else merged.add(batch[j++])
            }
            while (i < matches.size) merged.add(matches[i++])
            while (j < length) merged.add(batch[j++])
            matches.clear()
            matches.addAll(merged)
        }
        // New duplicates: pins that appear among the freshly added values.
        val lo = batch[0]
        val hi = batch[length - 1]
        var p = insertionPoint(pins, lo)
        while (p < pins.size && pins[p] <= hi) {
            if (binarySearch(batch, length, pins[p]) >= 0) dupCount++
            p++
        }
        bump()
    }

    /** Line number at union index [index] in `[0, size)`. O(log V × pins). */
    fun lineAt(index: Int): Long {
        var lo = 0L
        var hi = maxOf(matches.lastOrNull() ?: -1L, pins.lastOrNull() ?: -1L) + 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (countLE(mid) > index) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Number of distinct union values ≤ [v]. */
    private fun countLE(v: Long): Int {
        val m = upperBound(matches, v)
        val p = upperBound(pins, v)
        var d = 0
        for (j in 0 until p) {
            if (matches.binarySearch(pins[j]) >= 0) d++
        }
        return m + p - d
    }

    private fun bump() {
        version++
        size = matches.size + pins.size - dupCount
        pinCount = pins.size
    }

    private fun <T : Comparable<T>> List<T>.binarySearch(v: T): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = get(mid).compareTo(v)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
        }
        return -(lo + 1)
    }

    /** First index whose value > [v] (count of values ≤ v); hi is exclusive. */
    private fun upperBound(list: List<Long>, v: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid] <= v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun insertionPoint(list: List<Long>, v: Long): Int {
        val i = list.binarySearch(v)
        return if (i >= 0) i else -i - 1
    }

    private fun binarySearch(arr: LongArray, length: Int, v: Long): Int {
        var lo = 0
        var hi = length - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < v) lo = mid + 1 else if (arr[mid] > v) hi = mid - 1 else return mid
        }
        return -(lo + 1)
    }
}
