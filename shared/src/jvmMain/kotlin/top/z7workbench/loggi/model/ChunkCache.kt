package top.z7workbench.loggi.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.z7workbench.loggi.engine.EngineFile

/** Lines are loaded in chunks of this many lines. */
const val CHUNK_LINES: Long = 512

/**
 * Decoded-line cache for one open file: LRU over [CHUNK_LINES]-line chunks,
 * loaded on demand via [EngineFile.readLines] (always off the UI thread).
 * Only chunk metadata lives here long-term; every cache has a hard cap.
 *
 * [version] bumps whenever a chunk lands so Compose placeholders recompose.
 */
class ChunkCache(
    private val engine: EngineFile,
    private val maxChunks: Int = 96,
) {
    private val chunks = object : LinkedHashMap<Long, LineChunk>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LineChunk>?): Boolean =
            size > maxChunks
    }

    var version by mutableStateOf(0L)
        private set

    class LineChunk(val startLine: Long, val lines: List<String>)

    /** Chunk index containing [line]. */
    private fun chunkIndex(line: Long): Long = line / CHUNK_LINES

    /** True when the chunk containing [line] is loaded (line may still be absent if it exceeds the byte budget). */
    fun hasChunk(line: Long): Boolean = synchronized(chunks) { chunks.containsKey(chunkIndex(line)) }

    /** Cached line, or null when its chunk is not loaded yet. */
    fun line(line: Long): String? {
        val chunk = synchronized(chunks) { chunks[chunkIndex(line)] } ?: return null
        val k = (line - chunk.startLine).toInt()
        return chunk.lines.getOrNull(k)
    }

    /** Ensure chunks covering `[firstLine, lastLine]` are loaded. Blocking-free for caller? No — call off the UI thread. */
    fun ensure(firstLine: Long, lastLine: Long) {
        val lineCount = engine.info.lineCount
        if (lineCount <= 0) return
        val first = firstLine.coerceAtLeast(0)
        val last = lastLine.coerceAtMost(lineCount - 1)
        var changed = false
        var ci = chunkIndex(first)
        while (ci <= chunkIndex(last)) {
            // Read-touch present chunks so visible ones are not LRU-evicted
            // by results-pane churn (M8.5 white-screen fix).
            val missing = synchronized(chunks) { chunks[ci] == null }
            if (missing) {
                val start = ci * CHUNK_LINES
                val raw = runCatching { engine.readLines(start, CHUNK_LINES.toInt()) }.getOrNull()
                val lines = ArrayList<String>(raw?.lineCount ?: 0)
                if (raw != null) {
                    var line = raw.startLine
                    while (line < raw.endLine) {
                        lines.add(engine.decodeLine(raw, line))
                        line++
                    }
                }
                synchronized(chunks) { chunks[ci] = LineChunk(start, lines) }
                changed = true
            }
            ci++
        }
        if (changed) version++
    }

    /** Drop every cached chunk (file changed underneath us). */
    fun clear() {
        synchronized(chunks) { chunks.clear() }
        version++
    }

    /** Load a single line (used by clipboard copy); never leaves the UI with a stall on cache hits. */
    fun loadLine(line: Long): String? {
        if (line(line) == null) ensure(line, line)
        return line(line)
    }
}
