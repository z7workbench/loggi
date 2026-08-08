package top.z7workbench.loggi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.ui.LogView
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.FileViewModel

/** UI tests for log-view interactions (M8.5): context-menu highlight, click-to-clear. */
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
            onNodeWithText("Highlight", substring = true).performClick()
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
            onNodeWithText("Highlight", substring = true).performClick()
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

    /** Context menu must open at the cursor, not offset elsewhere. */
    @Test
    fun contextMenuOpensAtCursor() = runComposeUiTest {
        val (app, vm) = newVm("pos.log")
        try {
            // Mirror the real app: toolbar + tab bar chrome above the log view,
            // and a HiDPI (2x) density like a Retina display.
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
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
            // A row high enough that the menu fits below it (no bottom clamp).
            val rowBounds = onNodeWithText("row 4 ERROR").fetchSemanticsNode().boundsInRoot
            val rowCenterY = rowBounds.top + rowBounds.height / 2
            onNodeWithText("row 4 ERROR").performMouseInput { rightClick() }
            awaitIdle()
            val menuTop =
                onNodeWithText("Highlight", substring = true).fetchSemanticsNode().boundsInRoot.top
            assertTrue(
                kotlin.math.abs(menuTop - rowCenterY) < 40f,
                "menu top $menuTop should open at the click point (row center $rowCenterY)",
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
