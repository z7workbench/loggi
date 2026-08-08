package top.z7workbench.loggi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.settings.TabPlacement
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.FileTab

/**
 * File tab bar: horizontal (top) or vertical (left) placement, close actions,
 * middle-click close, drag reorder, right-click context menu. A "+" new-tab
 * button is pinned at the strip's end (browser-style; replaces the toolbar's
 * Open button) and stays visible even with zero tabs.
 */
@Composable
fun TabBar(app: AppViewModel, onNewTab: () -> Unit, modifier: Modifier = Modifier) {
    when (app.settings.tabPlacement) {
        TabPlacement.HORIZONTAL -> Row(
            modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                app.tabs.forEachIndexed { index, tab ->
                    TabChip(app, tab, index, vertical = false)
                }
            }
            NewTabButton(vertical = false, onClick = onNewTab)
        }

        TabPlacement.VERTICAL -> Column(
            modifier.fillMaxHeight().width(180.dp),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                app.tabs.forEachIndexed { index, tab ->
                    TabChip(app, tab, index, vertical = true)
                }
            }
            NewTabButton(vertical = true, onClick = onNewTab)
        }
    }
}

/** "+" new-tab button pinned at the end of the tab strip (opens the file picker). */
@Composable
private fun NewTabButton(vertical: Boolean, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        Modifier
            .then(if (vertical) Modifier.fillMaxWidth().height(26.dp) else Modifier.size(26.dp))
            .clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = strings.menuOpen }
            .background(if (hovered) scheme.surfaceVariant else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", fontSize = 16.sp, color = scheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun TabChip(app: AppViewModel, tab: FileTab, index: Int, vertical: Boolean) {
    val strings = LocalStrings.current
    val selected = app.activeTab == tab
    val chipSizes = remember { mutableStateMapOf<FileTab, Int>() }
    var contextMenuAt by remember { mutableStateOf<Offset?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Box {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            modifier = Modifier
                .then(if (vertical) Modifier.fillMaxWidth() else Modifier.widthIn(min = 90.dp, max = 220.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .onSizeChanged { chipSizes[tab] = if (vertical) it.height else it.width }
                .graphicsLayer {
                    if (dragging) {
                        if (vertical) translationY = dragOffset else translationX = dragOffset
                    }
                }
                .pointerInput(tab) {
                    // Left click activates, middle click closes, right click opens the menu.
                    // Presses consumed by a descendant (the "×" close button) are skipped,
                    // otherwise clicking "×" would re-activate the tab that was just
                    // removed and the main view would keep showing it (ghost tab).
                    while (true) {
                        val event = awaitPointerEventScope { awaitPointerEvent() }
                        if (event.type != PointerEventType.Press) continue
                        if (event.changes.first().isConsumed) continue
                        val buttons = event.buttons
                        when {
                            buttons.isTertiaryPressed -> app.closeTab(tab)
                            buttons.isSecondaryPressed -> contextMenuAt = event.changes.first().position
                            buttons.isPrimaryPressed -> app.activeTab = tab
                        }
                    }
                }
                .then(
                    Modifier.pointerInput(tab, vertical) {
                        detectDragReorder(
                            vertical = vertical,
                            onStart = { dragging = true; dragOffset = 0f },
                            onEnd = { dragging = false },
                            onDelta = { delta ->
                                dragOffset += delta
                                val i = app.tabs.indexOf(tab)
                                val size = (chipSizes[tab] ?: 120).toFloat()
                                when {
                                    dragOffset > size / 2 && i < app.tabs.lastIndex -> {
                                        app.moveTab(i, i + 1)
                                        dragOffset -= size
                                    }
                                    dragOffset < -size / 2 && i > 0 -> {
                                        app.moveTab(i, i - 1)
                                        dragOffset += size
                                    }
                                }
                            },
                        )
                    },
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = tab.title,
                    fontSize = 12.sp,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "  ×",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .pointerInput(tab) {
                            // Close on primary press; consume so the parent chip's
                            // press handler doesn't re-activate the removed tab.
                            while (true) {
                                val event = awaitPointerEventScope { awaitPointerEvent() }
                                if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    app.closeTab(tab)
                                }
                            }
                        }
                        .padding(horizontal = 4.dp),
                )
            }
        }

        val menuAt = contextMenuAt
        if (menuAt != null) {
            // Zero-size anchor box at the click point: DropdownMenu's DpOffset
            // is mis-scaled on HiDPI displays.
            Box(
                Modifier
                    .padding(start = with(density) { menuAt.x.toDp() }, top = with(density) { menuAt.y.toDp() })
                    .size(0.dp),
            ) {
                CompactDropdownMenu(
                    expanded = true,
                    onDismissRequest = { contextMenuAt = null },
                ) {
                    CompactMenuItem(text = strings.tabClose, onClick = {
                        contextMenuAt = null
                        app.closeTab(tab)
                    })
                    CompactMenuItem(
                        text = strings.tabCloseOthers,
                        enabled = app.tabs.size > 1,
                        onClick = {
                            contextMenuAt = null
                            app.closeOthers(tab)
                        },
                    )
                    CompactMenuItem(
                        text = strings.tabCloseLeft,
                        enabled = index > 0,
                        onClick = {
                            contextMenuAt = null
                            app.closeLeft(tab)
                        },
                    )
                    CompactMenuItem(
                        text = strings.tabCloseRight,
                        enabled = index < app.tabs.lastIndex,
                        onClick = {
                            contextMenuAt = null
                            app.closeRight(tab)
                        },
                    )
                    CompactMenuItem(
                        text = strings.tabCloseAll,
                        enabled = app.tabs.isNotEmpty(),
                        onClick = {
                            contextMenuAt = null
                            app.closeAll()
                        },
                    )
                    HorizontalDivider()
                    CompactMenuItem(text = strings.tabRename, onClick = {
                        contextMenuAt = null
                        app.renameFor = tab
                    })
                    CompactMenuItem(text = strings.tabCopyName, onClick = {
                        contextMenuAt = null
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(tab.fileName), null)
                        }
                    })
                    CompactMenuItem(text = strings.tabCopyPath, onClick = {
                        contextMenuAt = null
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(tab.path), null)
                        }
                    })
                    CompactMenuItem(text = strings.tabOpenFolder, onClick = {
                        contextMenuAt = null
                        runCatching {
                            val parent = File(tab.path).absoluteFile.parentFile
                            if (parent != null && Desktop.isDesktopSupported()) Desktop.getDesktop().open(parent)
                        }
                    })
                }
            }
        }
    }
}

/** Axis-aware drag detector for tab reordering. */
private suspend fun PointerInputScope.detectDragReorder(
    vertical: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDelta: (Float) -> Unit,
) {
    detectDragGestures(
        onDragStart = { onStart() },
        onDragEnd = { onEnd() },
        onDragCancel = { onEnd() },
    ) { change, dragAmount ->
        change.consume()
        onDelta(if (vertical) dragAmount.y else dragAmount.x)
    }
}
