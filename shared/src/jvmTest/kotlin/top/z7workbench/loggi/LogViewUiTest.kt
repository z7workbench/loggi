package top.z7workbench.loggi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.z7workbench.loggi.engine.EngineFile
import top.z7workbench.loggi.model.LineSpan
import top.z7workbench.loggi.settings.HighlighterRule
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.ui.LogView
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.ColoredSpan
import top.z7workbench.loggi.vm.FileViewModel

/**
 * UI tests for log-view interactions (M8.5): context-menu highlight, click-to-clear,
 * and the M9 selection-geometry fixes (grid matches rendered glyphs; multi-line
 * selections highlight every covered line).
 */
@OptIn(ExperimentalTestApi::class)
class LogViewUiTest {
    private val dir = Files.createTempDirectory("loggi-logview-")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun newVm(name: String): Pair<AppViewModel, FileViewModel> {
        val log = dir.resolve(name)
        Files.writeString(log, (0 until 100).joinToString("\n") { "row $it ERROR" } + "\n")
        val app = AppViewModel(SettingsStore(dir.resolve("$name.conf")))
        return app to FileViewModel(EngineFile.open(log.toString()), app, null)
    }

    /** Right-click with no text selection must offer highlight — applied to the whole line. */
    @Test
    fun rightClickLineHighlightAddsWholeLineRule() = runComposeUiTest {
        val (app, vm) = newVm("ctx.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            val preset = app.settings.highlightPresets.first()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("highlighter to be added") { app.settings.highlighters.size == 1 }
            val rule = app.settings.highlighters.single()
            assertEquals("row 7 ERROR", rule.pattern)
            assertTrue(rule.wholeLine)
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** Drag-select then a plain left click clears the selection and marks the line. */
    @Test
    fun plainClickClearsSelectionAndMarksCurrentLine() = runComposeUiTest {
        val (app, vm) = newVm("click.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(9) != null }
            awaitIdle()
            onNodeWithText("row 7 ERROR").performMouseInput {
                dragAndDrop(Offset(10f, center.y), Offset(80f, center.y))
            }
            runOnIdle { assertTrue(vm.selection?.isEmpty() == false) }
            onNodeWithText("row 9 ERROR").performMouseInput { click() }
            runOnIdle {
                assertNull(vm.selection)
                assertEquals(9L, vm.currentLine)
            }
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** A right-click must not wipe the drag selection — highlight applies to the selected text. */
    @Test
    fun rightClickKeepsSelectionForHighlight() = runComposeUiTest {
        val (app, vm) = newVm("sel.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            onNodeWithText("row 7 ERROR").performMouseInput {
                dragAndDrop(Offset(40f, center.y), Offset(100f, center.y))
            }
            runOnIdle { assertTrue(vm.selection?.isEmpty() == false) }
            val selectedText = runOnIdle { vm.selectedText() }
            assertTrue(!selectedText.isNullOrEmpty() && selectedText != "row 7 ERROR")

            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            runOnIdle {
                assertTrue(
                    vm.selection?.isEmpty() == false,
                    "right-click release must not clear the selection",
                )
            }
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            val preset = app.settings.highlightPresets.first()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("highlighter to be added") { app.settings.highlighters.size == 1 }
            val rule = app.settings.highlighters.single()
            assertEquals(selectedText, rule.pattern)
            assertTrue(!rule.wholeLine)
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /**
     * The column grid must match the rendered glyphs: dragging over exactly
     * N rendered chars produces a selection of exactly those N chars. (Regr:
     * a single-char "0" measure rounded up to a whole pixel and the inherited
     * M3 letter spacing drifted the grid one char off the text.)
     */
    @Test
    fun dragSelectionCoversExactlyTheRenderedChars() = runComposeUiTest {
        val line = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val (app, vm) = newVm("geo.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(0) != null }
            awaitIdle()
            // Write the measured line into the file after opening (fresh file
            // so the engine reads it as the first row).
            dir.resolve("geo.log").toFile().writeText(line + "\n")
            vm.refreshTick()
            awaitCondition("refreshed line") { vm.lineText(0) == line }
            awaitIdle()

            val bounds = onNodeWithText(line).fetchSemanticsNode().boundsInRoot
            val charWidth = bounds.width / line.length
            onNodeWithText(line).performMouseInput {
                dragAndDrop(
                    Offset(0.5f, center.y),
                    Offset(10f * charWidth + 1f, center.y),
                )
            }
            runOnIdle {
                val sel = vm.selection
                assertEquals(0, sel?.start?.col, "drag must start at column 0")
                assertEquals(10, sel?.end?.col, "10 rendered chars must select 10 columns")
                assertEquals(line.substring(0, 10), vm.selectedText())
            }
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /**
     * Multi-line selection: highlight must tint exactly the framed range on
     * each covered line — the first line from the selection start, the last
     * line up to the selection end — not the whole lines.
     */
    @Test
    fun multiLineSelectionHighlightCoversEverySelectedLine() = runComposeUiTest {
        val (app, vm) = newVm("multi.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(9) != null }
            awaitIdle()
            val row7 = onNodeWithText("row 7 ERROR").fetchSemanticsNode().boundsInRoot
            val row9 = onNodeWithText("row 9 ERROR").fetchSemanticsNode().boundsInRoot
            onNodeWithText("row 7 ERROR").performMouseInput {
                dragAndDrop(
                    Offset(10f, row7.center.y - row7.top),
                    Offset(80f, row9.top - row7.top + row9.height / 2),
                )
            }
            runOnIdle {
                val sel = vm.selection
                assertEquals(7L, sel?.start?.line)
                assertEquals(1, sel?.start?.col)
                assertEquals(9L, sel?.end?.line)
                assertEquals(9, sel?.end?.col)
            }
            onNodeWithText("row 8 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            val preset = app.settings.highlightPresets.first()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("highlighters to be added") { app.settings.highlighters.size == 3 }
            // Line 7: only the framed part [1, 11) — "ow 7 ERROR".
            assertEquals(
                HighlighterRule(pattern = "ow 7 ERROR", colorArgb = preset, ignoreCase = false, anchorLine = 7, anchorStart = 1, anchorEnd = 11),
                app.settings.highlighters[0],
            )
            // Line 8: fully covered → the whole line.
            assertEquals(
                HighlighterRule(pattern = "row 8 ERROR", colorArgb = preset, ignoreCase = false, anchorLine = 8, anchorStart = 0, anchorEnd = 11),
                app.settings.highlighters[1],
            )
            // Line 9: only the framed part [0, 9) — "row 9 ERR".
            assertEquals(
                HighlighterRule(pattern = "row 9 ERR", colorArgb = preset, ignoreCase = false, anchorLine = 9, anchorStart = 0, anchorEnd = 9),
                app.settings.highlighters[2],
            )
            // The anchored spans tint exactly the framed range on their own
            // lines and nothing elsewhere.
            runOnIdle {
                assertEquals(
                    listOf(ColoredSpan(LineSpan(1, 11), Color(preset.toInt()))),
                    vm.computeLineSpans(7, "row 7 ERROR"),
                )
                assertEquals(
                    listOf(ColoredSpan(LineSpan(0, 11), Color(preset.toInt()))),
                    vm.computeLineSpans(8, "row 8 ERROR"),
                )
                assertEquals(
                    listOf(ColoredSpan(LineSpan(0, 9), Color(preset.toInt()))),
                    vm.computeLineSpans(9, "row 9 ERROR"),
                )
                assertTrue(vm.computeLineSpans(10, "row 10 ERROR").isEmpty())
            }
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** Context menu "Remove Highlight" removes the current line's highlight (no selection). */
    @Test
    fun removeHighlightClearsWholeLineRule() = runComposeUiTest {
        val (app, vm) = newVm("rm.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            val preset = app.settings.highlightPresets.first()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("highlighter added") { app.settings.highlighters.size == 1 }

            // Right-click again (an empty selection sits on the line) → Highlight ▸ → Remove Highlight.
            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            onNodeWithText("Remove Highlight").performClick()
            awaitCondition("highlighter removed") { app.settings.highlighters.isEmpty() }
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** Context menu "Remove Highlight" removes the substring rule of the selected text. */
    @Test
    fun removeHighlightClearsSelectionRule() = runComposeUiTest {
        val (app, vm) = newVm("rmsel.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            // Select a word → highlight (substring rule).
            onNodeWithText("row 7 ERROR").performMouseInput {
                dragAndDrop(Offset(40f, center.y), Offset(100f, center.y))
            }
            runOnIdle {
                assertTrue(vm.selection?.isEmpty() == false)
                assertTrue(vm.selectedText()?.isNotEmpty() == true)
            }
            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            val preset = app.settings.highlightPresets.first()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("highlighter added") { app.settings.highlighters.size == 1 }
            val added = app.settings.highlighters.single()
            assertTrue(!added.wholeLine && added.anchorLine == null)

            // The selection survived; right-click → Highlight ▸ → Remove Highlight.
            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            onNodeWithText("Remove Highlight").performClick()
            awaitCondition("highlighter removed") { app.settings.highlighters.isEmpty() }
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** Context menu "Remove All Highlights" clears every rule at once. */
    @Test
    fun removeAllHighlightsClearsEveryRule() = runComposeUiTest {
        val (app, vm) = newVm("rmall.log")
        try {
            setContent { MaterialTheme { LogView(vm) } }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            val preset = app.settings.highlightPresets.first()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("highlighter added") { app.settings.highlighters.size == 1 }
            onNodeWithText("row 8 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performClick()
            awaitIdle()
            onNodeWithTag("hlPreset-$preset").performClick()
            awaitCondition("second highlighter added") { app.settings.highlighters.size == 2 }

            onNodeWithText("row 7 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithText("Remove All Highlights").performClick()
            awaitCondition("all highlighters removed") { app.settings.highlighters.isEmpty() }
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** Context menu must open with its top-left corner at the cursor. */
    @Test
    fun contextMenuOpensAtCursor() = runComposeUiTest {
        val (app, vm) = newVm("pos.log")
        try {
            // Mirror the real app: toolbar + tab bar chrome above the log view,
            // and a HiDPI (2x) density like a Retina display.
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    MaterialTheme {
                        Column {
                            Box(Modifier.fillMaxWidth().height(30.dp))
                            Box(Modifier.fillMaxWidth().height(34.dp))
                            Box(Modifier.weight(1f)) { LogView(vm) }
                        }
                    }
                }
            }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            // A high row (top of the file) so the tall menu fits below it (no bottom clamp).
            val rowBounds = onNodeWithText("row 0 ERROR").fetchSemanticsNode().boundsInRoot
            val rowCenter = rowBounds.center
            onNodeWithText("row 0 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            val menuTopLeft = onNodeWithTag("ctxMenu").fetchSemanticsNode().boundsInRoot.topLeft
            assertTrue(
                kotlin.math.abs(menuTopLeft.x - rowCenter.x) < 2f &&
                    kotlin.math.abs(menuTopLeft.y - rowCenter.y) < 2f,
                "menu top-left $menuTopLeft should open at the click point $rowCenter",
            )
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    /** The highlight submenu's top edge must align with the "Highlight" row's top edge. */
    @Test
    fun highlightSubmenuAlignsWithParentRow() = runComposeUiTest {
        val (app, vm) = newVm("sub.log")
        try {
            // Two presets keep the submenu short enough to open below the row
            // in the small test scene (no flip-above fallback).
            app.updateSettings { it.copy(highlightPresets = it.highlightPresets.take(2)) }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    MaterialTheme {
                        Column {
                            Box(Modifier.fillMaxWidth().height(30.dp))
                            Box(Modifier.fillMaxWidth().height(34.dp))
                            Box(Modifier.weight(1f)) { LogView(vm) }
                        }
                    }
                }
            }
            awaitCondition("first chunk to load") { vm.lineText(7) != null }
            awaitIdle()
            onNodeWithText("row 0 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithTag("hlMenu").performMouseInput { moveTo(center) }
            awaitIdle()
            val hlRow = onNodeWithTag("hlMenu").fetchSemanticsNode().boundsInRoot
            val sub = onNodeWithTag("hlSubMenu").fetchSemanticsNode().boundsInRoot
            assertTrue(
                kotlin.math.abs(sub.top - hlRow.top) < 2f,
                "submenu top ${sub.top} should align with the Highlight row top ${hlRow.top}",
            )
            assertTrue(
                kotlin.math.abs(sub.left - hlRow.right) < 2f,
                "submenu left ${sub.left} should abut the Highlight row right edge ${hlRow.right}",
            )
        } finally {
            vm.close()
            app.shutdown()
        }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timeout waiting for $what")
            Thread.sleep(50)
        }
    }
}
