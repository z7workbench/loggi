package top.z7workbench.loggi.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.z7workbench.loggi.engine.EngineFile
import top.z7workbench.loggi.engine.FileInfoData
import top.z7workbench.loggi.model.ChunkCache
import top.z7workbench.loggi.model.LineSpan
import top.z7workbench.loggi.model.ResultsModel
import top.z7workbench.loggi.model.byteSpansToCharSpans
import top.z7workbench.loggi.model.expandTabsRemap
import top.z7workbench.loggi.settings.HighlighterRule
import top.z7workbench.loggi.settings.TabSession

/** A caret position in (line, display-column) space. */
data class LinePos(val line: Long, val col: Int) : Comparable<LinePos> {
    override fun compareTo(other: LinePos): Int =
        compareValuesBy(this, other, LinePos::line, LinePos::col)
}

/** Drag selection in the log view; [anchor] stays, [focus] moves. */
data class LineSelection(val anchor: LinePos, val focus: LinePos) {
    val start: LinePos get() = minOf(anchor, focus)
    val end: LinePos get() = maxOf(anchor, focus)
    fun isEmpty(): Boolean = anchor == focus
    fun lineRange(): LongRange = start.line..end.line
}

/** Per-char highlight spans already mapped into display coordinates. */
data class ColoredSpan(val span: LineSpan, val color: Color)

/**
 * View model of one open file tab: chunk cache, search lifecycle, pins,
 * selection/clipboard, follow. Blocking engine work runs on the app scope
 * (`Dispatchers.Default`); the UI thread only touches Compose state.
 */
class FileViewModel(
    val engine: EngineFile,
    val app: AppViewModel,
    session: TabSession?,
) {
    private val scope = app.scope

    var info: FileInfoData by mutableStateOf(engine.info)
        private set

    val chunks = ChunkCache(engine)
    val results = ResultsModel()

    // ---- search state -----------------------------------------------------
    var searchPattern by mutableStateOf(session?.searchPattern ?: "")
    var ignoreCase by mutableStateOf(session?.ignoreCase ?: false)
    var useRegex by mutableStateOf(session?.regex ?: false)
    var searching by mutableStateOf(false)
        private set
    var matchesFound by mutableStateOf(0L)
        private set
    var processedLines by mutableStateOf(0L)
        private set

    /** Pattern-as-regex is invalid (UI-side pre-validation). */
    val patternError: Boolean
        get() = useRegex && searchPattern.isNotEmpty() && runCatching { Regex(searchPattern) }.isFailure

    // ---- view state -------------------------------------------------------
    var follow by mutableStateOf(session?.follow ?: false)
    var selection by mutableStateOf<LineSelection?>(null)

    /** "Current" line marker (jump target / last click), tinted in the log view. */
    var currentLine by mutableStateOf<Long?>(null)
    var statusMessage by mutableStateOf<String?>(null)

    /** First visible line; captured for session restore (plain var on purpose). */
    @Volatile
    var topLine: Long = session?.topLine ?: 0

    /** Requests the log view to scroll to a line. */
    val jumpRequests = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /** Bumped when a follow refresh changed the file; the view scrolls to the end. */
    var followTick by mutableStateOf(0L)
        private set

    private var searchJob: Job? = null

    @Volatile
    private var cancelRequested = false

    init {
        session?.pins?.let(results::restorePins)
    }

    // ---- chunk loading ------------------------------------------------------

    /** Prefetch chunks for the visible range (log view or results pane). */
    fun onVisibleRange(first: Long, last: Long) {
        topLine = first
        scope.launch(Dispatchers.Default) {
            chunks.ensure(first - CHUNK_PREFETCH_MARGIN, last + CHUNK_PREFETCH_MARGIN)
        }
    }

    /** Prefetch chunks for visible result indices (per-line, never whole spans). */
    fun onResultsVisible(firstIdx: Int, lastIdx: Int) {
        val size = results.size
        val last = minOf(lastIdx, firstIdx + 200, size - 1)
        if (last < firstIdx) return
        scope.launch(Dispatchers.Default) {
            for (i in firstIdx..last) {
                if (i >= results.size) break
                val line = results.lineAt(i)
                chunks.ensure(line, line)
            }
        }
    }

    /** Decoded line text, or null while the chunk is loading. */
    fun lineText(line: Long): String? = chunks.line(line)

    /** Display text of one line (tabs expanded per settings). */
    fun displayText(line: Long): String? {
        val raw = chunks.line(line) ?: return null
        return expandTabsRemap(raw, effectiveTabStop(), emptyList()).first
    }

    private fun effectiveTabStop(): Int = with(app.settings) { if (expandTabs) tabStop else 0 }

    // ---- search lifecycle ---------------------------------------------------

    fun startSearch() {
        val pattern = searchPattern
        if (pattern.isBlank() || patternError) return
        app.pushSearchHistory(pattern)
        val previous = searchJob
        searchJob = scope.launch {
            // Stop whatever ran before, then start fresh.
            cancelRequested = true
            previous?.join()
            cancelRequested = false
            results.clearMatches()
            matchesFound = 0
            processedLines = 0
            searching = true
            val sid = try {
                engine.searchStart(pattern, ignoreCase, useRegex)
            } catch (t: Throwable) {
                searching = false
                statusMessage = t.message
                return@launch
            }
            val buf = LongArray(SEARCH_DRAIN_CAPACITY)
            var done = false
            while (!done) {
                if (cancelRequested) {
                    engine.searchCancel(sid) // joins the native search thread
                    break
                }
                val poll = try {
                    engine.searchPoll(sid, buf)
                } catch (t: Throwable) {
                    statusMessage = t.message
                    break
                }
                if (poll.returned > 0) results.addMatchBatch(buf, poll.returned)
                matchesFound = poll.matchesFound
                processedLines = poll.processedLines
                done = poll.done
                if (!done) delay(100)
            }
            searching = false
        }
    }

    fun stopSearch() {
        cancelRequested = true
    }

    // ---- refresh / follow -----------------------------------------------------

    /** Poll the engine for file changes; invoked by the app ticker (off-UI). */
    fun refreshTick() {
        val changed = runCatching { engine.refresh() }.getOrDefault(false)
        if (!changed) return
        info = engine.currentInfo()
        chunks.clear()
        if (searchPattern.isNotBlank() && !patternError) startSearch()
        if (follow) followTick++
    }

    // ---- pins ------------------------------------------------------------------

    fun togglePinsSelected() {
        val sel = selection ?: return
        val first = sel.start.line
        val last = minOf(sel.end.line, first + MAX_PIN_RANGE)
        val target = !results.isPinned(first)
        for (line in first..last) results.setPinned(line, target)
        app.requestSave()
    }

    fun setPinned(line: Long, pinned: Boolean) {
        results.setPinned(line, pinned)
        app.requestSave()
    }

    /** Drop every pin of this file (toolbar "unpin all"). */
    fun clearPins() {
        results.clearPins()
        app.requestSave()
    }

    // ---- selection / clipboard ---------------------------------------------------

    fun jumpToLine(line: Long) {
        if (info.lineCount <= 0) return
        val target = line.coerceIn(0, info.lineCount - 1)
        selection = LineSelection(LinePos(target, 0), LinePos(target, 0))
        currentLine = target
        jumpRequests.tryEmit(target)
    }

    /** Plain left-click in the log view: drop any selection, mark the clicked line. */
    fun onLineClick(pos: LinePos) {
        selection = null
        currentLine = pos.line
    }

    /** Selected text in display coordinates; null when empty / multi-line for highlight use. */
    fun selectedText(): String? {
        val sel = selection ?: return null
        if (sel.isEmpty()) return null
        if (sel.start.line != sel.end.line) return null
        val text = displayText(sel.start.line) ?: return null
        val a = sel.start.col.coerceIn(0, text.length)
        val b = sel.end.col.coerceIn(0, text.length)
        return if (a < b) text.substring(a, b) else null
    }

    /** Copy the exact selection (may span lines) to the system clipboard. */
    suspend fun copySelection() = copyToClipboard(linesOfSelection(wholeLines = false))

    /** Copy whole lines covered by the selection (or the current line). */
    suspend fun copySelectionLines() = copyToClipboard(linesOfSelection(wholeLines = true))

    /** Copy a `name.log:123`-style reference of the given line. */
    fun copyReference(linePos: LinePos) {
        val name = engine.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { engine.path }
        copyToClipboard("$name:${linePos.line + 1}")
    }

    private suspend fun linesOfSelection(wholeLines: Boolean): String? = withContext(Dispatchers.Default) {
        val sel = selection ?: return@withContext null
        val firstLine = sel.start.line
        val lastLine = sel.end.line
        val sb = StringBuilder()
        var truncated = false
        var line = firstLine
        while (line <= lastLine) {
            val raw = chunks.loadLine(line) ?: ""
            val display = expandTabsRemap(raw, effectiveTabStop(), emptyList()).first
            val piece = when {
                wholeLines || sel.isEmpty() -> display
                line == firstLine && line == lastLine ->
                    display.substring(sel.start.col.coerceIn(0, display.length), sel.end.col.coerceIn(0, display.length))
                line == firstLine -> display.substring(sel.start.col.coerceIn(0, display.length))
                line == lastLine -> display.substring(0, sel.end.col.coerceIn(0, display.length))
                else -> display
            }
            if (line != firstLine) sb.append('\n')
            if (sb.length + piece.length > COPY_CHAR_CAP) {
                truncated = true
                break
            }
            sb.append(piece)
            line++
        }
        if (truncated) statusMessage = app.strings.copyTruncatedWarning
        sb.toString()
    }

    private fun copyToClipboard(text: String?) {
        if (text == null) return
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /**
     * Add a highlighter from the context menu: with a single-line text
     * selection the selected text becomes a substring highlighter; otherwise
     * the right-clicked line's full text becomes a whole-line highlighter.
     */
    fun addHighlighterAt(linePos: LinePos, colorArgb: Long) {
        scope.launch(Dispatchers.Default) {
            val selected = selectedText()
            val pattern: String
            val wholeLine: Boolean
            if (selected != null) {
                pattern = selected
                wholeLine = false
            } else {
                pattern = chunks.loadLine(linePos.line)?.takeIf { it.isNotEmpty() } ?: return@launch
                wholeLine = true
            }
            app.addHighlighter(
                HighlighterRule(pattern = pattern, colorArgb = colorArgb, ignoreCase = false, regex = false, wholeLine = wholeLine),
            )
        }
    }

    // ---- highlighting -------------------------------------------------------------

    /**
     * Highlight spans (display coordinates) for one visible line: user
     * highlighters in order (later wins), search matches on top. Cheap enough
     * for the visible set (~50 lines); matching runs in the engine.
     */
    fun computeLineSpans(text: String): List<ColoredSpan> {
        val rules = app.settings.highlighters
        val search = searchPattern
        if (rules.isEmpty() && search.isBlank()) return emptyList()
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val out = ArrayList<ColoredSpan>()
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue
            val flat = runCatching {
                engine.matchPositions(rule.pattern, rule.ignoreCase, rule.regex, utf8)
            }.getOrNull() ?: continue
            if (rule.wholeLine) {
                if (flat.isNotEmpty()) out.add(ColoredSpan(LineSpan(0, text.length), Color(rule.colorArgb)))
            } else {
                byteSpansToCharSpans(text, flat).forEach { out.add(ColoredSpan(it, Color(rule.colorArgb))) }
            }
        }
        if (search.isNotBlank() && !patternError) {
            val flat = runCatching {
                engine.matchPositions(search, ignoreCase, useRegex, utf8)
            }.getOrNull()
            if (flat != null) {
                if (app.settings.searchMatchWholeLine && flat.isNotEmpty()) {
                    out.add(ColoredSpan(LineSpan(0, text.length), Color(app.settings.searchMatchColorArgb)))
                } else {
                    byteSpansToCharSpans(text, flat)
                        .forEach { out.add(ColoredSpan(it, Color(app.settings.searchMatchColorArgb))) }
                }
            }
        }
        return out
    }

    // ---- lifecycle -----------------------------------------------------------------

    /** Cancel + join searches and release the engine handle. Call off the UI thread. */
    fun close() {
        cancelRequested = true
        searchJob?.let { job ->
            kotlinx.coroutines.runBlocking { job.join() }
        }
        engine.close()
    }

    companion object {
        private const val CHUNK_PREFETCH_MARGIN = 2 * 512L
        private const val SEARCH_DRAIN_CAPACITY = 8192
        private const val COPY_CHAR_CAP = 20_000_000
        private const val MAX_PIN_RANGE = 10_000L
    }
}
