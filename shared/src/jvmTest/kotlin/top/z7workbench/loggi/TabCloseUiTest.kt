package top.z7workbench.loggi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.ui.TabBar
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.TabState

/**
 * Tab-close refresh regression: closing a tab (the "×" button / close-all)
 * must immediately switch the active content, and closing the last tab must
 * render the empty state instead of the previous tab's view.
 */
@OptIn(ExperimentalTestApi::class)
class TabCloseUiTest {
    private val dir = Files.createTempDirectory("loggi-tabclose-")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun newApp(): AppViewModel {
        (1..2).forEach { i ->
            Files.writeString(dir.resolve("$i.log"), (0 until 50).joinToString("\n") { "file$i row $it" } + "\n")
        }
        return AppViewModel(SettingsStore(dir.resolve("loggi.conf")))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setTabs(app: AppViewModel) {
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    TabBar(app, onNewTab = {})
                    Text(
                        (app.activeTab?.state as? TabState.Ready)?.let { "CONTENT:${it.vm.engine.path}" }
                            ?: "EMPTY-STATE",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun closingAllTabsShowsEmptyState() = runComposeUiTest {
        val app = newApp()
        try {
            app.openFile(dir.resolve("1.log").toString())
            app.openFile(dir.resolve("2.log").toString())
            waitFor("tabs ready") { app.tabs.size == 2 && app.tabs.all { it.state is TabState.Ready } }
            setTabs(app)
            awaitIdle()
            onNodeWithText("CONTENT:", substring = true).assertExists()

            app.closeAll()
            awaitIdle()
            assertEquals(0, app.tabs.size)
            assertNull(app.activeTab)
            onNodeWithText("EMPTY-STATE").assertExists()
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun closeButtonSwitchesToNextTab() = runComposeUiTest {
        val app = newApp()
        try {
            app.openFile(dir.resolve("1.log").toString())
            app.openFile(dir.resolve("2.log").toString())
            waitFor("tabs ready") { app.tabs.size == 2 && app.tabs.all { it.state is TabState.Ready } }
            app.activeTab = app.tabs[0]
            setTabs(app)
            awaitIdle()

            onAllNodesWithText("×", substring = true)[0].performClick()
            awaitIdle()
            assertEquals(1, app.tabs.size)
            assertEquals(app.tabs[0], app.activeTab)
            onNodeWithText("CONTENT:", substring = true).assertExists()

            onAllNodesWithText("×", substring = true)[0].performClick()
            awaitIdle()
            assertEquals(0, app.tabs.size)
            assertNull(app.activeTab)
            onNodeWithText("EMPTY-STATE").assertExists()
        } finally {
            app.shutdown()
        }
    }
}
