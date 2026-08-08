package top.z7workbench.loggi.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.theme.LocalLoggiColors
import top.z7workbench.loggi.vm.FileViewModel
import top.z7workbench.loggi.vm.LinePos
import top.z7workbench.loggi.vm.LineSelection

/**
 * Search bar + streaming results pane (pins ∪ matches, ordered by line
 * number). Shared by all three layouts, including the detached window.
 */
@Composable
fun SearchPane(vm: FileViewModel, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val colors = LocalLoggiColors.current

    Column(modifier) {
        // ---- search bar (compact density, M8.5) ---------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = vm.searchPattern,
                onValueChange = { vm.searchPattern = it },
                placeholder = strings.searchPlaceholder,
                isError = vm.patternError,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.startSearch() }),
                modifier = Modifier.weight(1f),
            )
            var historyOpen by remember { mutableStateOf(false) }
            Box {
                CompactButton(
                    text = "▾",
                    onClick = { historyOpen = true },
                    modifier = Modifier.padding(start = 4.dp),
                    enabled = vm.app.searchHistory.isNotEmpty(),
                )
                DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                    vm.app.searchHistory.forEach { past ->
                        CompactMenuItem(
                            text = past,
                            onClick = {
                                historyOpen = false
                                vm.searchPattern = past
                                vm.startSearch()
                            },
                        )
                    }
                    HorizontalDivider()
                    CompactMenuItem(
                        text = strings.manageHistoryLabel,
                        onClick = {
                            historyOpen = false
                            vm.app.showSearchHistory = true
                        },
                    )
                }
            }
            CompactButton(
                text = strings.ignoreCaseLabel,
                onClick = { vm.ignoreCase = !vm.ignoreCase },
                modifier = Modifier.padding(start = 4.dp),
                selected = vm.ignoreCase,
            )
            CompactButton(
                text = strings.regexLabel,
                onClick = { vm.useRegex = !vm.useRegex },
                modifier = Modifier.padding(start = 4.dp),
                selected = vm.useRegex,
            )
            CompactButton(
                text = if (vm.searching) strings.stopButton else strings.searchButton,
                onClick = { if (vm.searching) vm.stopSearch() else vm.startSearch() },
                modifier = Modifier.padding(start = 4.dp),
                enabled = vm.searching || (vm.searchPattern.isNotBlank() && !vm.patternError),
            )
        }

        // ---- progress ------------------------------------------------------
        if (vm.searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    strings.searchProgress(vm.processedLines, vm.info.lineCount),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (vm.info.lineCount > 0) {
                        (vm.processedLines.toFloat() / vm.info.lineCount.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(
                strings.matchesCount(vm.matchesFound),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        // ---- results ---------------------------------------------------------
        val listState = rememberLazyListState()
        LaunchedEffect(listState, vm) {
            snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.firstOrNull()?.index ?: 0) to
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0)
            }.collect { (first, last) -> vm.onResultsVisible(first, last) }
        }
        val resultsVersion = vm.results.version // state read: recompose on streaming updates

        // Horizontal scroll: the widest line drives the content width, capped
        // below the Compose Constraints limit (same approach as the log view).
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val paneSettings = vm.app.settings
        val paneFontFamily = rememberLogFontFamily(paneSettings.fontFamily)
        val charWidthPx = remember(paneFontFamily, paneSettings.fontSizeSp, density) {
            val style = TextStyle(fontFamily = paneFontFamily, fontSize = paneSettings.fontSizeSp.sp)
            measurer.measure("0", style).size.width.toFloat().coerceAtLeast(1f)
        }
        val hScroll = rememberScrollState()
        val maxLineChars = vm.info.maxLineLen.coerceIn(0, 1_000_000L)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val contentWidth = with(density) {
                (maxLineChars * charWidthPx + 64.dp.toPx()).coerceAtMost(262_000f).toDp()
            }
            val listWidth = maxOf(maxWidth, contentWidth)
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                LazyColumn(state = listState, modifier = Modifier.width(listWidth).fillMaxHeight()) {
                    items(count = vm.results.size, key = { it }) { index ->
                        ResultRow(vm, index)
                    }
                }
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(hScroll),
            modifier = Modifier.fillMaxWidth().testTag("resultsHScroll"),
        )
    }
}

@Composable
private fun ResultRow(vm: FileViewModel, index: Int) {
    val colors = LocalLoggiColors.current
    val settings = vm.app.settings
    val line = remember(vm.results.version, index) {
        if (index < vm.results.size) vm.results.lineAt(index) else -1L
    }
    if (line < 0) return
    val pinned = remember(vm.results.version, line) { vm.results.isPinned(line) }
    val raw = remember(vm.chunks.version, line) { vm.lineText(line) }
    val fontFamily = rememberLogFontFamily(settings.fontFamily)

    // Right-click opens the same context menu as the main log view.
    var rowMenu by remember { mutableStateOf<Pair<Offset, Long>?>(null) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.jumpToLine(line) }
                .pointerInput(vm, line) {
                    while (true) {
                        val event = awaitPointerEventScope { awaitPointerEvent() }
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val linePos = LinePos(line, 0)
                            val sel = vm.selection
                            if (sel == null || line !in sel.lineRange()) {
                                vm.selection = LineSelection(linePos, linePos)
                            }
                            rowMenu = event.changes.first().position to line
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (pinned) "●" else "○",
                color = if (pinned) colors.pinIndicator else colors.gutterText,
                fontSize = 9.sp,
                modifier = Modifier
                    .width(16.dp)
                    .clickable { vm.setPinned(line, !pinned) },
            )
            Text(
                text = "${line + 1}:",
                fontFamily = fontFamily,
                fontSize = settings.fontSizeSp.sp,
                color = colors.gutterText,
                modifier = Modifier.padding(end = 8.dp),
                maxLines = 1,
                softWrap = false,
            )
            if (raw != null) {
                Text(
                    text = rememberAnnotatedLine(vm, raw),
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "…",
                    fontSize = settings.fontSizeSp.sp,
                    color = colors.gutterText,
                )
            }
        }
        rowMenu?.let { (pos, menuLine) ->
            LogLineContextMenu(
                vm = vm,
                position = pos,
                linePos = LinePos(menuLine, 0),
                onDismiss = { rowMenu = null },
            )
        }
    }
}
