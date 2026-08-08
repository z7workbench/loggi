package top.z7workbench.loggi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.model.LineSpan
import top.z7workbench.loggi.theme.LocalLoggiColors
import top.z7workbench.loggi.vm.FileViewModel
import top.z7workbench.loggi.vm.LinePos
import top.z7workbench.loggi.vm.LineSelection

/** Font-driven geometry shared by every line of the log view. */
private class LogMetrics(
    val fontFamily: FontFamily,
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val charWidthPx: Float,
    val gutterWidthPx: Float,
    val gutterWidthDp: Dp,
    val lineHeightDp: Dp,
    val wrap: Boolean,
)

@Composable
private fun rememberLogMetrics(vm: FileViewModel, lineCount: Long): LogMetrics {
    val settings = vm.app.settings
    val fontFamily = rememberLogFontFamily(settings.fontFamily)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontSize = settings.fontSizeSp
    val lineHeight = fontSize * settings.lineHeightFactor
    val charWidth = remember(fontFamily, fontSize, density) {
        val style = TextStyle(fontFamily = fontFamily, fontSize = fontSize.sp)
        measurer.measure("0", style).size.width.toFloat().coerceAtLeast(1f)
    }
    val gutterDigits = maxOf(4, lineCount.toString().length)
    return remember(fontFamily, fontSize, lineHeight, charWidth, gutterDigits, settings.wrapLines, density) {
        val gutterPx = with(density) { 12.dp.toPx() } + charWidth * gutterDigits
        LogMetrics(
            fontFamily = fontFamily,
            fontSizeSp = fontSize,
            lineHeightSp = lineHeight,
            charWidthPx = charWidth,
            gutterWidthPx = gutterPx,
            gutterWidthDp = with(density) { gutterPx.toDp() },
            lineHeightDp = with(density) { lineHeight.sp.toDp() },
            wrap = settings.wrapLines,
        )
    }
}

/**
 * The virtualized main log view: LazyColumn over engine chunks with a line
 * number gutter, drag selection, right-click context menu (copy / highlight /
 * pin), scrollbar and the match-overview strip.
 */
@Composable
fun LogView(vm: FileViewModel, modifier: Modifier = Modifier) {
    val lineCount = vm.info.lineCount
    val metrics = rememberLogMetrics(vm, lineCount)
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    val countInt = lineCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    // Restore the session top line once.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(vm, countInt) {
        if (!restored && countInt > 0) {
            restored = true
            listState.scrollToItem(vm.topLine.coerceIn(0, countInt - 1L).toInt())
        }
    }

    // Prefetch chunks for the visible range. Keyed on chunks.version too, so
    // a visible chunk evicted by results-pane churn reloads immediately
    // (M8.5 white-screen fix).
    LaunchedEffect(listState, vm) {
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                info.visibleItemsInfo.firstOrNull()?.index ?: 0,
                info.visibleItemsInfo.lastOrNull()?.index ?: 0,
                vm.chunks.version,
            )
        }.collect { (first, last, _) -> vm.onVisibleRange(first.toLong(), last.toLong()) }
    }

    // Jump requests (search result click, go-to-line).
    LaunchedEffect(vm, countInt) {
        vm.jumpRequests.collect { line ->
            if (countInt > 0) {
                val half = listState.layoutInfo.viewportSize.height / 2
                listState.scrollToItem(line.coerceIn(0, countInt - 1L).toInt(), scrollOffset = -half)
            }
        }
    }

    // Follow: scroll to the end when new data arrived.
    LaunchedEffect(vm.followTick, countInt) {
        if (vm.follow && countInt > 0) listState.scrollToItem(countInt - 1)
    }

    fun posFromOffset(offset: Offset): LinePos {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size }
            ?: info.visibleItemsInfo.lastOrNull()
        val line = (item?.index ?: 0).toLong()
        val col = if (metrics.wrap) {
            // Whole-line semantics while wrapping (column math is ambiguous).
            if (offset.x - metrics.gutterWidthPx > metrics.charWidthPx) Int.MAX_VALUE else 0
        } else {
            ((offset.x - metrics.gutterWidthPx) / metrics.charWidthPx).toInt().coerceAtLeast(0)
        }
        return LinePos(line, col)
    }

    var contextMenu by remember { mutableStateOf<Pair<Offset, LinePos>?>(null) }

    // Horizontal scroll range when wrap is off: the widest line drives the
    // content width. Compose `Constraints` caps every measured dimension at
    // 262_142 px, so the width must stay below that — longer lines ellipsize
    // at the cap (the per-line display budget remains the hard limit).
    val hScroll = rememberScrollState()
    val maxLineChars = vm.info.maxLineLen.coerceIn(0, 1_000_000L)
    Row(modifier) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val contentWidth = with(density) {
                    (metrics.gutterWidthPx + maxLineChars * metrics.charWidthPx + 16.dp.toPx())
                        .coerceAtMost(262_000f)
                        .toDp()
                }
                val listWidth = maxOf(maxWidth, contentWidth)
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (metrics.wrap) Modifier else Modifier.horizontalScroll(hScroll)),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .then(
                                if (metrics.wrap) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.width(listWidth).fillMaxHeight()
                                },
                            )
                            .pointerInput(vm, metrics) {
                                // Plain LEFT click: clear the selection, mark the current
                                // line. Hand-rolled because detectTapGestures has no button
                                // filter — a right-click release would also fire onTap and
                                // wipe the selection before the menu action reads it.
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val down = awaitPointerEventScope { awaitPointerEvent() }
                                    if (down.type != PointerEventType.Press || !down.buttons.isPrimaryPressed) continue
                                    val start = down.changes.first().position
                                    var isTap = true
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type == PointerEventType.Move &&
                                                (event.changes.first().position - start).getDistance() > slop
                                            ) {
                                                isTap = false
                                            }
                                            if (event.type == PointerEventType.Release) break
                                        }
                                    }
                                    if (isTap) vm.onLineClick(posFromOffset(start))
                                }
                            }
                            .pointerInput(vm, metrics) {
                                // Drag selection (primary button).
                                detectDragGestures(
                                    onDragStart = { start ->
                                        val pos = posFromOffset(start)
                                        vm.selection = LineSelection(pos, pos)
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {},
                                ) { change, _ ->
                                    val pos = change.position
                                    // Auto-scroll while dragging beyond the viewport.
                                    val viewportH = listState.layoutInfo.viewportSize.height.toFloat()
                                    val excess = when {
                                        pos.y < 0f -> pos.y
                                        pos.y > viewportH -> pos.y - viewportH
                                        else -> 0f
                                    }
                                    if (excess != 0f) listState.dispatchRawDelta(excess)
                                    val sel = vm.selection ?: return@detectDragGestures
                                    vm.selection = LineSelection(sel.anchor, posFromOffset(pos))
                                }
                            }
                            .pointerInput(vm, metrics) {
                                // Right click: position the selection, open the context menu.
                                while (true) {
                                    val event = awaitPointerEventScope { awaitPointerEvent() }
                                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                        val pos = event.changes.first().position
                                        val linePos = posFromOffset(pos)
                                        val sel = vm.selection
                                        if (sel == null || linePos.line !in sel.lineRange()) {
                                            vm.selection = LineSelection(linePos, linePos)
                                        }
                                        contextMenu = pos to linePos
                                    }
                                }
                            },
                    ) {
                        items(count = countInt, key = { it }) { index ->
                            LogLineItem(vm, index.toLong(), metrics)
                        }
                    }
                }

                // Context menu, anchored to the fixed viewport at the click
                // point (position converted from scroll-content coordinates).
                contextMenu?.let { (pos, linePos) ->
                    LogLineContextMenu(
                        vm = vm,
                        position = Offset(pos.x - hScroll.value, pos.y),
                        linePos = linePos,
                        onDismiss = { contextMenu = null },
                    )
                }
            }
            if (!metrics.wrap) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(hScroll),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.fillMaxHeight(),
        )
        OverviewStrip(vm, listState, Modifier.fillMaxHeight().width(14.dp))
    }
}

@Composable
private fun LogLineItem(vm: FileViewModel, line: Long, metrics: LogMetrics) {
    val colors = LocalLoggiColors.current
    val strings = LocalStrings.current
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    val raw = remember(vm.chunks.version, line) { vm.lineText(line) }
    val chunkLoaded = remember(vm.chunks.version, line) { vm.chunks.hasChunk(line) }
    val pinned = remember(vm.results.version, line) { vm.results.isPinned(line) }
    val isCurrent = vm.currentLine == line
    val annotated: AnnotatedString? = raw?.let { rememberAnnotatedLine(vm, it) }
    val displayLen = annotated?.length ?: 0

    // Selection column range for this line.
    val sel = vm.selection
    val selRange: LineSpan? = if (sel != null && line >= sel.start.line && line <= sel.end.line) {
        val startCol = if (line == sel.start.line) sel.start.col else 0
        val endCol = if (line == sel.end.line) sel.end.col else Int.MAX_VALUE
        LineSpan(startCol.coerceIn(0, displayLen), endCol.coerceIn(0, displayLen))
    } else {
        null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (isCurrent) Modifier.background(colors.currentLineBackground) else Modifier),
    ) {
        // Gutter: pin indicator + line number.
        Box(
            Modifier
                .width(metrics.gutterWidthDp)
                .height(metrics.lineHeightDp)
                .background(colors.gutterBackground)
                .padding(end = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (pinned) {
                Canvas(Modifier.size(6.dp).align(Alignment.CenterStart).offset(x = 2.dp)) {
                    drawCircle(colors.pinIndicator)
                }
            }
            Text(
                text = (line + 1).toString(),
                fontFamily = metrics.fontFamily,
                fontSize = metrics.fontSizeSp.sp,
                lineHeight = metrics.lineHeightSp.sp,
                color = colors.gutterText,
                maxLines = 1,
                softWrap = false,
            )
        }
        // Content.
        Box(
            Modifier
                .weight(1f)
                .drawSelection(selRange, metrics.charWidthPx, selectionColor)
                .then(if (metrics.wrap) Modifier else Modifier.height(metrics.lineHeightDp)),
        ) {
            when {
                annotated != null -> Text(
                    text = annotated,
                    fontFamily = metrics.fontFamily,
                    fontSize = metrics.fontSizeSp.sp,
                    lineHeight = metrics.lineHeightSp.sp,
                    softWrap = metrics.wrap,
                    maxLines = if (metrics.wrap) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                raw == null && !chunkLoaded -> Text(
                    text = "",
                    fontSize = metrics.fontSizeSp.sp,
                    lineHeight = metrics.lineHeightSp.sp,
                )
                else -> Text(
                    text = strings.lineTooLongPlaceholder,
                    fontFamily = metrics.fontFamily,
                    fontSize = metrics.fontSizeSp.sp,
                    lineHeight = metrics.lineHeightSp.sp,
                    color = colors.gutterText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Draw the selection rectangle behind the text. */
private fun Modifier.drawSelection(range: LineSpan?, charWidthPx: Float, color: Color): Modifier =
    if (range == null || range.end <= range.start) {
        this
    } else {
        drawBehind {
            drawRect(
                color = color,
                topLeft = Offset(range.start * charWidthPx, 0f),
                size = Size((range.end - range.start) * charWidthPx, size.height),
            )
        }
    }

/** Overview / minimap strip: match ticks, pins, viewport indicator, click-to-jump. */
@Composable
private fun OverviewStrip(vm: FileViewModel, listState: LazyListState, modifier: Modifier = Modifier) {
    val colors = LocalLoggiColors.current
    val lineCount = vm.info.lineCount
    val resultsVersion = vm.results.version
    val ticks = remember(resultsVersion) { vm.results.sampleMatches(3000) }
    val pins = remember(resultsVersion) { vm.results.pinsSnapshot() }

    fun jumpTo(y: Float, heightPx: Int) {
        if (lineCount <= 0 || heightPx <= 0) return
        val line = (y / heightPx * lineCount).toLong()
        vm.jumpToLine(line)
    }

    Canvas(
        modifier
            .pointerInput(vm, lineCount) {
                detectTapGestures { pos -> jumpTo(pos.y, size.height) }
            }
            .pointerInput(vm, lineCount) {
                detectDragGestures { change, _ -> jumpTo(change.position.y, size.height) }
            },
    ) {
        if (lineCount <= 0) return@Canvas
        val h = size.height
        val w = size.width
        for (t in ticks) {
            val y = (t.toDouble() / lineCount.toDouble() * h).toFloat()
            drawLine(colors.overviewTick, Offset(0f, y), Offset(w, y), strokeWidth = 2f)
        }
        for (p in pins) {
            val y = (p.toDouble() / lineCount.toDouble() * h).toFloat()
            drawLine(colors.pinIndicator, Offset(0f, y), Offset(w, y), strokeWidth = 3f)
        }
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()?.index ?: return@Canvas
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: first
        val top = first.toFloat() / lineCount.toFloat() * h
        val bottom = (last + 1).toFloat() / lineCount.toFloat() * h
        drawRect(colors.overviewViewport, topLeft = Offset(0f, top), size = Size(w, (bottom - top).coerceAtLeast(4f)))
    }
}
