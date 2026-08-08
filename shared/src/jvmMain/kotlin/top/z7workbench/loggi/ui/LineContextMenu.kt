@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package top.z7workbench.loggi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.vm.ColorPickerRequest
import top.z7workbench.loggi.vm.FileViewModel
import top.z7workbench.loggi.vm.LinePos

/**
 * Shared right-click context menu for one log line (main view + results
 * pane): copy selection / copy lines / copy file:line reference, highlight
 * (the submenu opens on hover, no click needed), pin/unpin.
 *
 * [position] is the click point in pixels, relative to the layout this
 * composable is declared in. Positioning uses a zero-size anchor box moved
 * with [Modifier.offset] instead of DropdownMenu's `DpOffset`: the DpOffset
 * is applied unscaled on HiDPI displays, which landed the menu away from the
 * cursor (covered by `LogViewUiTest.contextMenuOpensAtCursor` at 2x).
 */
@Composable
fun LogLineContextMenu(
    vm: FileViewModel,
    position: Offset,
    linePos: LinePos,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var highlightSubMenu by remember { mutableStateOf(false) }

    val sel = vm.selection
    val hasSelection = sel != null && !sel.isEmpty()
    val pinned = vm.results.isPinned(linePos.line)

    fun close() {
        highlightSubMenu = false
        onDismiss()
    }

    // Zero-size anchor placed at the click point via padding: DropdownMenu's
    // DpOffset is applied unscaled on HiDPI displays, and Modifier.offset's
    // placement shift is not honored by the popup's anchor bounds either.
    Box(
        Modifier
            .padding(start = with(density) { position.x.toDp() }, top = with(density) { position.y.toDp() })
            .size(0.dp),
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { close() },
            offset = DpOffset.Zero,
        ) {
            CompactMenuItem(
                text = strings.ctxCopy,
                enabled = hasSelection,
                onClick = {
                    close()
                    scope.launch { vm.copySelection() }
                },
                modifier = Modifier.onPointerEvent(PointerEventType.Enter) { highlightSubMenu = false },
            )
            CompactMenuItem(
                text = strings.ctxCopyLines,
                onClick = {
                    close()
                    scope.launch { vm.copySelectionLines() }
                },
                modifier = Modifier.onPointerEvent(PointerEventType.Enter) { highlightSubMenu = false },
            )
            CompactMenuItem(
                text = strings.ctxCopyReference,
                onClick = {
                    close()
                    vm.copyReference(linePos)
                },
                modifier = Modifier.onPointerEvent(PointerEventType.Enter) { highlightSubMenu = false },
            )
            HorizontalDivider()
            Box {
                CompactMenuItem(
                    text = "${strings.ctxHighlight}  ▸",
                    onClick = { highlightSubMenu = true },
                    modifier = Modifier.onPointerEvent(PointerEventType.Enter) { highlightSubMenu = true },
                )
                // Submenu anchored to the item's right edge.
                Box(Modifier.align(Alignment.CenterEnd)) {
                    DropdownMenu(
                        expanded = highlightSubMenu,
                        onDismissRequest = { highlightSubMenu = false },
                        offset = DpOffset.Zero,
                    ) {
                        vm.app.settings.highlightPresets.forEach { argb ->
                            CompactMenuCustom(
                                onClick = {
                                    close()
                                    vm.addHighlighterAt(linePos, argb)
                                },
                                modifier = Modifier.testTag("hlPreset-$argb"),
                            ) {
                                Box(Modifier.size(14.dp).background(Color(argb), RoundedCornerShape(3.dp)))
                            }
                        }
                        CompactMenuItem(
                            text = strings.ctxCustomColor,
                            onClick = {
                                close()
                                vm.app.colorPicker = ColorPickerRequest(0x66FFEB3B) { argb ->
                                    vm.addHighlighterAt(linePos, argb)
                                }
                            },
                        )
                    }
                }
            }
            CompactMenuItem(
                text = if (pinned) strings.ctxUnpin else strings.ctxPin,
                onClick = {
                    close()
                    vm.togglePinsSelected()
                },
                modifier = Modifier.onPointerEvent(PointerEventType.Enter) { highlightSubMenu = false },
            )
        }
    }
}
