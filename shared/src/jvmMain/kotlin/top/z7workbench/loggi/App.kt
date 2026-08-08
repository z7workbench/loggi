package top.z7workbench.loggi

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.z7workbench.loggi.engine.LogEncoding
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.settings.SearchLayout
import top.z7workbench.loggi.settings.TabPlacement
import top.z7workbench.loggi.theme.LocalLoggiColors
import top.z7workbench.loggi.theme.LoggiTheme
import top.z7workbench.loggi.ui.AboutDialog
import top.z7workbench.loggi.ui.ColorPickerDialog
import top.z7workbench.loggi.ui.CompactButton
import top.z7workbench.loggi.ui.CompactDropdownMenu
import top.z7workbench.loggi.ui.CompactMenuItem
import top.z7workbench.loggi.ui.GoToLineDialog
import top.z7workbench.loggi.ui.HighlightersWindow
import top.z7workbench.loggi.ui.LocalColorPickerHost
import top.z7workbench.loggi.ui.LogView
import top.z7workbench.loggi.ui.RenameDialog
import top.z7workbench.loggi.ui.SearchHistoryWindow
import top.z7workbench.loggi.ui.SearchPane
import top.z7workbench.loggi.ui.SettingsWindow
import top.z7workbench.loggi.ui.TabBar
import top.z7workbench.loggi.ui.rememberUiFontFamily
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.FileTab
import top.z7workbench.loggi.vm.FileViewModel
import top.z7workbench.loggi.vm.TabState

/**
 * Root composition of the main window: menu bar, tab bar, active tab content
 * (indexing / failed / ready), status bar, dialogs, detached search window.
 */
@Composable
fun FrameWindowScope.App(app: AppViewModel, onExit: () -> Unit) {
    val strings = remember(app.settings.locale) { app.strings }
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalColorPickerHost provides { req -> app.colorPicker = req },
    ) {
        LoggiTheme(app.settings.themeMode, rememberUiFontFamily(app.settings.uiFontFamily)) {
            AppMenuBar(app, onExit)
            // The top-level Surface sets `LocalContentColor` from the themed
            // background (via M3's contrast algorithm), so any `Text` deeper
            // in the tree that doesn't pass an explicit `color = …` inherits
            // the readable foreground instead of falling back to Color.Black
            // (which is what made the log view's main line text invisible in
            // dark mode).
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Toolbar(app)
                    Row(Modifier.weight(1f)) {
                        if (app.settings.tabPlacement == TabPlacement.VERTICAL) TabBar(app)
                        Column(Modifier.weight(1f)) {
                            if (app.settings.tabPlacement == TabPlacement.HORIZONTAL) TabBar(app)
                            Box(Modifier.weight(1f)) { ActiveTabContent(app) }
                        }
                    }
                    StatusBar(app)
                }
            }
            AppDialogs(app)
            DetachedSearchWindow(app)
            FollowTicker(app)
        }
    }
}

/** Compact one-click toolbar (M8.5): open, follow, go-to-line, wrap, layout, settings. */
@Composable
private fun Toolbar(app: AppViewModel) {
    val strings = LocalStrings.current
    val vm = app.activeVm
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactButton(strings.menuOpen, onClick = { chooseFiles().forEach(app::openFile) })
        CompactButton(
            strings.menuFollow,
            onClick = { vm?.let { it.follow = !it.follow } },
            modifier = Modifier.padding(start = 4.dp),
            enabled = vm != null,
            selected = vm?.follow == true,
        )
        CompactButton(
            strings.menuGoToLine,
            onClick = { app.goToLineFor = vm },
            modifier = Modifier.padding(start = 4.dp),
            enabled = vm != null,
        )
        CompactButton(
            strings.wrapLabel,
            onClick = { app.updateSettings { it.copy(wrapLines = !it.wrapLines) } },
            modifier = Modifier.padding(start = 4.dp),
            selected = app.settings.wrapLines,
        )
        Box(Modifier.padding(start = 4.dp)) {
            var layoutOpen by remember { mutableStateOf(false) }
            CompactButton(
                "${strings.layoutLabel}  ▾",
                onClick = { layoutOpen = true },
            )
            CompactDropdownMenu(expanded = layoutOpen, onDismissRequest = { layoutOpen = false }) {
                listOf(
                    SearchLayout.BOTTOM to strings.menuLayoutBottom,
                    SearchLayout.SIDE to strings.menuLayoutSide,
                    SearchLayout.DETACHED to strings.menuLayoutDetached,
                ).forEach { (layout, label) ->
                    CompactMenuItem(
                        text = (if (app.settings.searchLayout == layout) "✓ " else "　") + label,
                        onClick = {
                            layoutOpen = false
                            app.updateSettings { it.copy(searchLayout = layout) }
                        },
                    )
                }
            }
        }
        CompactButton(
            strings.unpinAllLabel,
            onClick = { vm?.clearPins() },
            modifier = Modifier.padding(start = 4.dp),
            enabled = (vm?.results?.pinCount ?: 0) > 0,
        )
        CompactButton(
            strings.menuSettings,
            onClick = { app.showSettings = true },
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    HorizontalDivider()
}

@Composable
private fun FrameWindowScope.AppMenuBar(app: AppViewModel, onExit: () -> Unit) {
    val strings = LocalStrings.current
    val vm = app.activeVm
    val layout = app.settings.searchLayout
    MenuBar {
        Menu(strings.menuFile, mnemonic = 'F') {
            Item(strings.menuOpen, onClick = { chooseFiles().forEach(app::openFile) })
            Item(
                strings.menuCloseTab,
                enabled = app.activeTab != null,
                onClick = { app.activeTab?.let(app::closeTab) },
            )
            Separator()
            Item(strings.menuSettings, onClick = { app.showSettings = true })
            Separator()
            Item(strings.menuExit, onClick = onExit)
        }
        Menu(strings.menuSearch, mnemonic = 'S') {
            Item(strings.menuGoToLine, enabled = vm != null, onClick = { app.goToLineFor = vm })
            CheckboxItem(
                strings.menuFollow,
                checked = vm?.follow == true,
                enabled = vm != null,
                onCheckedChange = { vm?.follow = it },
            )
            Separator()
            Item(strings.highlightersTitle, onClick = { app.showHighlighters = true })
            Item(strings.searchHistoryTitle, onClick = { app.showSearchHistory = true })
        }
        Menu(strings.menuView, mnemonic = 'V') {
            RadioButtonItem(
                strings.menuLayoutSide,
                selected = layout == SearchLayout.SIDE,
                onClick = { app.updateSettings { it.copy(searchLayout = SearchLayout.SIDE) } },
            )
            RadioButtonItem(
                strings.menuLayoutBottom,
                selected = layout == SearchLayout.BOTTOM,
                onClick = { app.updateSettings { it.copy(searchLayout = SearchLayout.BOTTOM) } },
            )
            RadioButtonItem(
                strings.menuLayoutDetached,
                selected = layout == SearchLayout.DETACHED,
                onClick = { app.updateSettings { it.copy(searchLayout = SearchLayout.DETACHED) } },
            )
        }
        Menu(strings.menuHelp, mnemonic = 'H') {
            Item(strings.menuAbout, onClick = { app.showAbout = true })
        }
    }
}

@Composable
private fun ActiveTabContent(app: AppViewModel) {
    val strings = LocalStrings.current
    val tab = app.activeTab
    when (val st = tab?.state) {
        null -> Centered {
            Text(strings.noFileOpen, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        is TabState.Indexing -> Centered {
            CircularProgressIndicator()
            Text(
                strings.openingFile(tab.fileName),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                strings.indexingProgress(st.done, st.total),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(onClick = { app.closeTab(tab) }, modifier = Modifier.padding(top = 12.dp)) {
                Text(strings.cancelButton)
            }
        }

        is TabState.Failed -> Centered {
            Text(strings.openFailedTitle, color = MaterialTheme.colorScheme.error)
            Text(
                st.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(onClick = { app.closeTab(tab) }, modifier = Modifier.padding(top = 12.dp)) {
                Text(strings.tabClose)
            }
        }

        is TabState.Ready -> MainContent(app, st.vm)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

/** Main view + search pane arranged per the layout setting. */
@Composable
private fun MainContent(app: AppViewModel, vm: FileViewModel) {
    when (app.settings.searchLayout) {
        SearchLayout.DETACHED -> LogView(vm, Modifier.fillMaxSize())
        SearchLayout.BOTTOM -> SplitColumn(
            fraction = app.settings.splitterBottomFraction,
            onFraction = { f -> app.updateSettings { it.copy(splitterBottomFraction = f) } },
            first = { LogView(vm, it) },
            second = { SearchPane(vm, it) },
        )

        SearchLayout.SIDE -> SplitRow(
            fraction = app.settings.splitterSideFraction,
            onFraction = { f -> app.updateSettings { it.copy(splitterSideFraction = f) } },
            first = { LogView(vm, it) },
            second = { SearchPane(vm, it) },
        )
    }
}

@Composable
private fun SplitColumn(
    fraction: Float,
    onFraction: (Float) -> Unit,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val colors = LocalLoggiColors.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        var current by remember { mutableStateOf(fraction) }
        LaunchedEffect(fraction) { current = fraction }
        val firstPx = (totalPx * current).coerceIn(totalPx * 0.1f, totalPx * 0.9f)
        Column(Modifier.fillMaxSize()) {
            first(Modifier.height(with(density) { firstPx.toDp() }).fillMaxWidth())
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(colors.splitter)
                    .pointerInput(totalPx) {
                        detectVerticalDragGestures { _, dy ->
                            current = ((current * totalPx + dy) / totalPx).coerceIn(0.15f, 0.85f)
                            onFraction(current)
                        }
                    },
            )
            second(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun SplitRow(
    fraction: Float,
    onFraction: (Float) -> Unit,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val colors = LocalLoggiColors.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        var current by remember { mutableStateOf(fraction) }
        LaunchedEffect(fraction) { current = fraction }
        val firstPx = (totalPx * current).coerceIn(totalPx * 0.15f, totalPx * 0.85f)
        Row(Modifier.fillMaxSize()) {
            first(Modifier.width(with(density) { firstPx.toDp() }).fillMaxSize())
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxSize()
                    .background(colors.splitter)
                    .pointerInput(totalPx) {
                        detectHorizontalDragGestures { _, dx ->
                            current = ((current * totalPx + dx) / totalPx).coerceIn(0.2f, 0.8f)
                            onFraction(current)
                        }
                    },
            )
            second(Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
private fun StatusBar(app: AppViewModel) {
    val strings = LocalStrings.current
    val vm = app.activeVm
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        val text = if (vm == null) {
            strings.appTitle
        } else {
            buildList {
                add(vm.engine.path)
                add(strings.statusLineCount(vm.info.lineCount))
                add(strings.statusSize(vm.info.sizeBytes))
                add(
                    when (vm.info.encoding) {
                        LogEncoding.UTF8 -> "UTF-8"
                        LogEncoding.UTF16LE -> "UTF-16LE"
                        LogEncoding.UTF16BE -> "UTF-16BE"
                        LogEncoding.UTF32LE -> "UTF-32LE"
                        LogEncoding.UTF32BE -> "UTF-32BE"
                        LogEncoding.OTHER -> vm.info.encodingName.ifBlank { "?" }
                    },
                )
                if (vm.searching || vm.matchesFound > 0) add(strings.matchesCount(vm.matchesFound))
                if (vm.follow) add(strings.followButton)
                vm.statusMessage?.let { add(it) }
            }.joinToString("    ")
        }
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppDialogs(app: AppViewModel) {
    if (app.showSettings) SettingsWindow(app) { app.showSettings = false }
    if (app.showHighlighters) HighlightersWindow(app) { app.showHighlighters = false }
    if (app.showSearchHistory) SearchHistoryWindow(app) { app.showSearchHistory = false }
    if (app.showAbout) AboutDialog { app.showAbout = false }
    app.goToLineFor?.let { vm -> GoToLineDialog(vm) { app.goToLineFor = null } }
    app.renameFor?.let { tab -> RenameDialog(tab) { app.renameFor = null } }
    app.colorPicker?.let { req -> ColorPickerDialog(req) { app.colorPicker = null } }
}

@Composable
private fun DetachedSearchWindow(app: AppViewModel) {
    if (app.settings.searchLayout != SearchLayout.DETACHED) return
    val vm = app.activeVm ?: return
    val strings = LocalStrings.current
    Window(
        onCloseRequest = { app.updateSettings { it.copy(searchLayout = SearchLayout.BOTTOM) } },
        title = "${strings.appTitle} — ${strings.searchButton}",
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SearchPane(vm, Modifier.fillMaxSize())
        }
    }
}

/** One-second ticker driving follow-mode refreshes of every open file. */
@Composable
private fun FollowTicker(app: AppViewModel) {
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            app.tabs.forEach { tab ->
                val vm = (tab.state as? TabState.Ready)?.vm ?: return@forEach
                if (vm.follow) app.scope.launch { vm.refreshTick() }
            }
        }
    }
}

/** Native AWT file picker (multi-select). */
private fun chooseFiles(): List<String> {
    val dialog = FileDialog(null as Frame?, "Open", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.map { it.absolutePath } ?: emptyList()
}
