package top.z7workbench.loggi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.z7workbench.loggi.engine.EngineFile
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.ui.SearchPane
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.FileViewModel

/**
 * M8.5 regression: after a search, the results pane must show rows (user
 * report: "搜索完之后搜索窗口不出现内容"). Drives the real JNI engine on a
 * temp file and renders the real `SearchPane`.
 */
@OptIn(ExperimentalTestApi::class)
class SearchPaneUiTest {
    private val dir = Files.createTempDirectory("loggi-ui-")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun newVm(patterns: Pair<String, Int> = "ERROR" to 500): Triple<AppViewModel, FileViewModel, Int> {
        val (needle, lines) = patterns
        val log = dir.resolve("ui-${System.nanoTime()}.log")
        Files.writeString(log, (0 until lines).joinToString("\n") { "row $it $needle" } + "\n")
        val app = AppViewModel(SettingsStore(dir.resolve("loggi-${System.nanoTime()}.conf")))
        val vm = FileViewModel(EngineFile.open(log.toString()), app, null)
        return Triple(app, vm, lines)
    }

    @Test
    fun searchPopulatesResultsList() = runComposeUiTest {
        val (app, vm, lines) = newVm()
        try {
            setContent { MaterialTheme { SearchPane(vm) } }
            runOnIdle {
                vm.searchPattern = "ERROR"
                vm.startSearch()
            }
            awaitCondition("search done", 15_000) { !vm.searching && vm.results.size == lines }
            awaitIdle()

            // The count label (outside the LazyColumn) must be up to date…
            onNodeWithText("$lines matches").assertExists()
            // …and the result rows themselves must be composed.
            val rows = onAllNodesWithText("1:", substring = true).fetchSemanticsNodes()
            assertTrue(rows.isNotEmpty(), "no result rows composed; results.size=${vm.results.size}")
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun imeSearchActionStartsSearch() = runComposeUiTest {
        val (app, vm, lines) = newVm()
        try {
            setContent { MaterialTheme { SearchPane(vm) } }
            onNode(hasSetTextAction()).performTextInput("ERROR")
            onNode(hasSetTextAction()).performImeAction()
            awaitCondition("search done via IME", 15_000) { !vm.searching && vm.results.size == lines }
            awaitIdle()
            assertEquals(lines, vm.results.size)
            onNodeWithText("$lines matches").assertExists()
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun pinAppearsInResultsWithoutSearch() = runComposeUiTest {
        val (app, vm, _) = newVm()
        try {
            setContent { MaterialTheme { SearchPane(vm) } }
            runOnIdle { vm.setPinned(41, true) }
            awaitIdle()
            onNodeWithText("42:", substring = false).assertExists()
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun resultsPaneScrollsHorizontally() = runComposeUiTest {
        val (app, vm, _) = newVm()
        try {
            setContent { MaterialTheme { SearchPane(vm) } }
            onNodeWithTag("resultsHScroll").assertExists()
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun resultRowsShareTheLogViewContextMenu() = runComposeUiTest {
        val (app, vm, lines) = newVm()
        try {
            setContent { MaterialTheme { SearchPane(vm) } }
            runOnIdle {
                vm.searchPattern = "ERROR"
                vm.startSearch()
            }
            awaitCondition("search done", 15_000) { !vm.searching && vm.results.size == lines }
            awaitIdle()
            // Only ~30 rows are composed in the test viewport — use the first.
            onNodeWithText("1:", substring = false).performMouseInput { rightClick() }
            awaitIdle()
            onNodeWithText("Pin Line(s)").performClick()
            awaitCondition("pin applied", 15_000) { vm.results.isPinned(0) }
        } finally {
            app.shutdown()
        }
    }

    private fun awaitCondition(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timeout waiting for $what")
            Thread.sleep(50)
        }
    }
}
