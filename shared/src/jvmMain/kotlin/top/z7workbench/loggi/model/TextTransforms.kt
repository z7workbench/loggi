package top.z7workbench.loggi.model

/**
 * Pure display-text helpers shared by the log view and the results pane:
 * tab expansion with index mapping and UTF-8 byte → UTF-16 char index mapping
 * (engine match positions are UTF-8 byte offsets into the decoded line).
 */

/** Char-index span within a (display) line. */
data class LineSpan(val start: Int, val end: Int)

/**
 * Expand tabs to spaces at [tabStop] columns and remap [spans] (raw char
 * indices) into display char indices. Returns the raw line untouched when
 * expansion is disabled or the line contains no tabs.
 */
fun expandTabsRemap(
    raw: String,
    tabStop: Int,
    spans: List<LineSpan>,
): Pair<String, List<LineSpan>> {
    if (tabStop <= 0 || raw.indexOf('\t') < 0) return raw to spans
    val map = IntArray(raw.length + 1)
    val out = StringBuilder(raw.length + 16)
    var col = 0
    raw.forEachIndexed { i, c ->
        map[i] = out.length
        if (c == '\t') {
            val pad = tabStop - col % tabStop
            repeat(pad) { out.append(' ') }
            col += pad
        } else {
            out.append(c)
            col++
        }
    }
    map[raw.length] = out.length
    return out.toString() to spans.map { LineSpan(map[it.start.coerceIn(0, raw.length)], map[it.end.coerceIn(0, raw.length)]) }
}

/**
 * Map a display (tab-expanded) range back to raw char indices — the inverse
 * of [expandTabsRemap]'s span mapping, for line-anchored selection
 * highlights. A boundary that falls inside a tab's expansion snaps to the
 * tab itself (the whole tab is then included).
 */
fun displayRangeToRaw(raw: String, tabStop: Int, displayStart: Int, displayEnd: Int): LineSpan {
    if (tabStop <= 0 || raw.indexOf('\t') < 0) {
        return LineSpan(displayStart.coerceIn(0, raw.length), displayEnd.coerceIn(0, raw.length))
    }
    val map = IntArray(raw.length + 1)
    var col = 0
    raw.forEachIndexed { i, c ->
        map[i] = col
        col += if (c == '\t') tabStop - col % tabStop else 1
    }
    map[raw.length] = col
    // Raw index of the char whose expansion covers display position `dp`
    // (floor: the largest raw index with map[i] <= dp).
    fun rawOf(dp: Int): Int {
        val target = dp.coerceIn(0, col)
        var lo = 0
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (map[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo
    }
    return LineSpan(rawOf(displayStart), rawOf(displayEnd))
}

/**
 * Convert UTF-8 byte offsets (as returned by the engine matcher for the
 * UTF-8 encoding of [text]) to UTF-16 char indices of [text].
 */
fun byteSpansToCharSpans(text: String, flatByteOffsets: IntArray): List<LineSpan> {
    if (flatByteOffsets.isEmpty()) return emptyList()
    // Fast path: ASCII → byte index == char index.
    var ascii = true
    for (c in text) {
        if (c.code > 0x7F) {
            ascii = false
            break
        }
    }
    val out = ArrayList<LineSpan>(flatByteOffsets.size / 2)
    if (ascii) {
        var i = 0
        while (i + 1 < flatByteOffsets.size) {
            out.add(LineSpan(flatByteOffsets[i].coerceIn(0, text.length), flatByteOffsets[i + 1].coerceIn(0, text.length)))
            i += 2
        }
        return out
    }
    // char index → cumulative UTF-8 byte offset
    val charToByte = IntArray(text.length + 1)
    var bytePos = 0
    text.forEachIndexed { i, c ->
        charToByte[i] = bytePos
        bytePos += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            // A surrogate pair encodes one 4-byte code point: 2 bytes per char
            // here keeps byte offsets strictly increasing per char index.
            c.isHighSurrogate() || c.isLowSurrogate() -> 2
            else -> 3
        }
    }
    charToByte[text.length] = bytePos
    fun byteToChar(b: Int): Int {
        var lo = 0
        var hi = text.length
        val target = b.coerceIn(0, bytePos)
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (charToByte[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo
    }
    var i = 0
    while (i + 1 < flatByteOffsets.size) {
        out.add(LineSpan(byteToChar(flatByteOffsets[i]), byteToChar(flatByteOffsets[i + 1])))
        i += 2
    }
    return out
}
