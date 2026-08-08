package top.z7workbench.loggi.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import top.z7workbench.loggi.jni.LoggiBridge

/** Text encoding reported by the engine (codes match `fileInfo`). */
enum class LogEncoding {
    UTF8,
    UTF16LE,
    UTF16BE,
    UTF32LE,
    UTF32BE,
    OTHER,
    ;

    companion object {
        fun fromCode(code: Long): LogEncoding = entries.getOrElse(code.toInt()) { OTHER }
    }
}

data class FileInfoData(
    val sizeBytes: Long,
    val lineCount: Long,
    val maxLineLen: Long,
    val indexBytes: Long,
    val indexTimeMillis: Long,
    val encoding: LogEncoding,
    val encodingName: String,
)

/** Raw (undecoded) chunk as returned by `readLines`. */
class RawChunk(
    val startLine: Long,
    val endLine: Long,
    val bytes: ByteArray,
    /** Content-start offsets per line, plus the final content end (size = lineCount+1). */
    val offsets: IntArray,
) {
    val lineCount: Int get() = endLine.let { (it - startLine).toInt() }
}

data class SearchPoll(
    val matchesFound: Long,
    val processedLines: Long,
    val totalLines: Long,
    val done: Boolean,
    val cancelled: Boolean,
    val returned: Int,
)

class OpenCancelledException : IOException("indexing cancelled")

/**
 * One open file on the engine. All engine calls are blocking — invoke them
 * from `Dispatchers.Default`, never the UI thread. [close] cancels and joins
 * every in-flight search; after it returns the handle is gone.
 */
class EngineFile private constructor(
    val path: String,
    private val handle: Long,
    val info: FileInfoData,
) {
    /** Matches the engine `LazyReader` budget so one read always fits. */
    private val readBuf = ByteBuffer.allocateDirect(ENGINE_BUDGET_BYTES)
    private val readLock = Any()

    @Volatile
    private var closed = false

    val charset: Charset = when (info.encoding) {
        LogEncoding.UTF8 -> StandardCharsets.UTF_8
        LogEncoding.UTF16LE -> StandardCharsets.UTF_16LE
        LogEncoding.UTF16BE -> StandardCharsets.UTF_16BE
        // UTF-32 is decoded manually (no JDK charset).
        LogEncoding.UTF32LE, LogEncoding.UTF32BE -> StandardCharsets.UTF_8
        LogEncoding.OTHER -> info.encodingName
            .takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: StandardCharsets.UTF_8
    }

    /** Read lines `[start, start+count)`; may return fewer when the byte budget hits. */
    fun readLines(start: Long, count: Int): RawChunk = synchronized(readLock) {
        check(!closed) { "file closed" }
        val offsets = IntArray(count + 1)
        val r = LoggiBridge.readLines(handle, start, count.toLong(), readBuf, offsets)
        val endLine = r[0]
        val byteLen = r[2].toInt()
        val n = (endLine - start).coerceAtLeast(0).toInt()
        val bytes = ByteArray(byteLen)
        readBuf.clear()
        readBuf.get(bytes)
        RawChunk(start, endLine, bytes, offsets.copyOf(n + 1))
    }

    /** Decode the raw bytes of one line out of a [RawChunk] (CR stripped, no tab expansion). */
    fun decodeLine(chunk: RawChunk, line: Long): String {
        val k = (line - chunk.startLine).toInt()
        if (k < 0 || k >= chunk.lineCount) return ""
        val a = chunk.offsets[k]
        val b = chunk.offsets[k + 1].coerceAtLeast(a)
        if (b <= a) return ""
        var s = when (info.encoding) {
            LogEncoding.UTF32LE -> decodeUtf32(chunk.bytes, a, b, littleEndian = true)
            LogEncoding.UTF32BE -> decodeUtf32(chunk.bytes, a, b, littleEndian = false)
            else -> String(chunk.bytes, a, b - a, charset)
        }
        // Offsets are content starts, so a line slice runs to the next line's
        // start and carries its terminator; content never contains \n itself.
        if (s.endsWith('\n')) s = s.dropLast(1)
        if (s.endsWith('\r')) s = s.dropLast(1)
        return s
    }

    fun refresh(): Boolean = !closed && LoggiBridge.refresh(handle)

    /** Re-read file info after a successful [refresh] (size/line count may move). */
    fun currentInfo(): FileInfoData = synchronized(readLock) {
        check(!closed) { "file closed" }
        companionInfo(LoggiBridge.fileInfo(handle), handle)
    }

    fun searchStart(pattern: String, ignoreCase: Boolean, useRegex: Boolean): Long =
        LoggiBridge.searchStart(handle, pattern, ignoreCase, useRegex)

    fun searchPoll(searchId: Long, out: LongArray): SearchPoll {
        val m = LoggiBridge.searchPoll(handle, searchId, out)
        return SearchPoll(
            matchesFound = m[0],
            processedLines = m[1],
            totalLines = m[2],
            done = m[3] != 0L,
            cancelled = m[4] != 0L,
            returned = m[5].toInt(),
        )
    }

    /** Blocking join of the native search thread; call off the UI thread. */
    fun searchCancel(searchId: Long) = LoggiBridge.searchCancel(handle, searchId)

    /** UTF-8 byte offsets of [pattern] matches within one decoded line. */
    fun matchPositions(pattern: String, ignoreCase: Boolean, useRegex: Boolean, utf8Line: ByteArray): IntArray =
        if (closed) IntArray(0) else LoggiBridge.matchInLine(handle, pattern, ignoreCase, useRegex, utf8Line)

    fun close() = synchronized(readLock) {
        if (!closed) {
            closed = true
            LoggiBridge.closeFile(handle)
        }
    }

    companion object {
        /** The engine caps one read at 8 MiB (`LazyReader.budget`). */
        const val ENGINE_BUDGET_BYTES = 8 shl 20

        fun engineVersion(): String = runCatching { LoggiBridge.version() }.getOrDefault("unavailable")

        /**
         * Open + index a file, polling progress every 100 ms. Blocking; run on
         * `Dispatchers.Default`. Throws [OpenCancelledException] when [cancelled]
         * flips, [IOException] on indexing failure.
         */
        fun open(
            path: String,
            onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
            cancelled: () -> Boolean = { false },
        ): EngineFile {
            val id = LoggiBridge.openFile(path)
            try {
                while (true) {
                    if (cancelled()) throw OpenCancelledException()
                    val p = LoggiBridge.indexProgress(id)
                    onProgress(p[0], p[1])
                    if (p[2] != 0L) {
                        val err = LoggiBridge.indexError(id)
                        if (err.isNotEmpty()) throw IOException(err)
                        return EngineFile(
                            path = path,
                            handle = id,
                            info = companionInfo(LoggiBridge.fileInfo(id), id),
                        )
                    }
                    Thread.sleep(100)
                }
            } catch (t: Throwable) {
                runCatching { LoggiBridge.closeFile(id) }
                throw t
            }
        }

        private fun companionInfo(f: LongArray, id: Long): FileInfoData {
            val enc = LogEncoding.fromCode(f[5])
            return FileInfoData(
                sizeBytes = f[0],
                lineCount = f[1],
                maxLineLen = f[2],
                indexBytes = f[3],
                indexTimeMillis = f[4],
                encoding = enc,
                encodingName = if (enc == LogEncoding.OTHER) LoggiBridge.encodingName(id) else "",
            )
        }
    }
}

private fun decodeUtf32(bytes: ByteArray, offset: Int, end: Int, littleEndian: Boolean): String {
    val sb = StringBuilder((end - offset) / 4)
    var i = offset
    while (i + 4 <= end) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        val b2 = bytes[i + 2].toInt() and 0xFF
        val b3 = bytes[i + 3].toInt() and 0xFF
        val cp = if (littleEndian) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) {
            sb.appendCodePoint(cp)
        } else {
            sb.append('�')
        }
        i += 4
    }
    return sb.toString()
}
