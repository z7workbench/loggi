package top.z7workbench.loggi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.TabState

/**
 * End-to-end, headless: session restore → open + index → chunk read → search,
 * then shutdown persists the session back to `loggi.conf`.
 */
class AppViewModelTest {
    @Test
    fun sessionRestoreOpenSearchAndPersist() {
        val dir = Files.createTempDirectory("loggi-appvm-")
        val log = dir.resolve("session.log")
        Files.writeString(log, (0 until 1_000).joinToString("\n") { "row $it ERROR" } + "\n")
        val conf = dir.resolve("loggi.conf")
        val escapedPath = log.toString().replace("\\", "\\\\")
        Files.writeString(
            conf,
            """{"sessionTabs":[{"path":"$escapedPath","searchPattern":"ERROR","pins":[5]}],"activeTabIndex":0}""",
        )

        val app = AppViewModel(SettingsStore(conf))
        try {
            waitFor("tab to become ready") { app.activeVm != null }
            val tab = app.activeTab!!
            assertTrue(tab.state is TabState.Ready)
            val vm = app.activeVm!!
            assertEquals(1_000, vm.info.lineCount)
            assertEquals("ERROR", vm.searchPattern)
            assertTrue(vm.results.isPinned(5))

            vm.onVisibleRange(0, 50)
            waitFor("first chunk to load") { vm.lineText(0) != null }
            assertEquals("row 0 ERROR", vm.lineText(0))
            assertEquals("row 42 ERROR", vm.lineText(42))

            vm.startSearch()
            // Wait for the actual outcome, not the `searching` flag: the
            // flag starts `false` and is only set `true` inside the launched
            // coroutine, so a slow scheduler could make `!vm.searching` pass
            // before the search even started (CI flake).
            waitFor("search to finish") { vm.matchesFound == 1_000L }
            assertEquals(1_000, vm.matchesFound)
            // pins ∪ matches: 1000 matches + pin 5 is also a match → union = 1000.
            assertEquals(1_000, vm.results.size)
            assertEquals(0L, vm.results.lineAt(0))
            assertEquals(999L, vm.results.lineAt(999))
        } finally {
            app.shutdown()
        }

        // Session was persisted with the tab + pins.
        val reloaded = SettingsStore(conf).load()
        assertEquals(1, reloaded.sessionTabs.size)
        assertEquals(log.toString(), reloaded.sessionTabs[0].path)
        assertEquals(listOf(5L), reloaded.sessionTabs[0].pins)
        assertEquals("ERROR", reloaded.sessionTabs[0].searchPattern)

        dir.toFile().deleteRecursively()
    }

    @Test
    fun searchHistoryIsGlobalAndPersisted() {
        val dir = Files.createTempDirectory("loggi-hist-")
        val log = dir.resolve("h.log")
        Files.writeString(log, (0 until 100).joinToString("\n") { "row $it ERROR" } + "\n")
        val conf = dir.resolve("loggi.conf")

        val app = AppViewModel(SettingsStore(conf))
        try {
            app.openFile(log.toString())
            waitFor("tab to become ready") { app.activeVm != null }
            val vm = app.activeVm!!
            vm.searchPattern = "ERROR"
            vm.startSearch()
            waitFor("search to finish") { !vm.searching }
            assertEquals(listOf("ERROR"), app.searchHistory.toList())
        } finally {
            app.shutdown()
        }

        // Persisted globally, not per file, and survives a restart.
        assertEquals(listOf("ERROR"), SettingsStore(conf).load().searchHistory)
        val app2 = AppViewModel(SettingsStore(conf))
        try {
            assertEquals(listOf("ERROR"), app2.searchHistory.toList())
            app2.removeSearchHistory("ERROR")
            assertTrue(app2.searchHistory.isEmpty())
        } finally {
            app2.shutdown()
        }
        assertEquals(emptyList(), SettingsStore(conf).load().searchHistory)

        dir.toFile().deleteRecursively()
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timeout waiting for $what")
            Thread.sleep(50)
        }
    }
}
