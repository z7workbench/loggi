package top.z7workbench.loggi.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.z7workbench.loggi.engine.EngineFile
import top.z7workbench.loggi.engine.OpenCancelledException
import top.z7workbench.loggi.i18n.Strings
import top.z7workbench.loggi.i18n.resolveLocale
import top.z7workbench.loggi.i18n.stringsFor
import top.z7workbench.loggi.settings.AppSettings
import top.z7workbench.loggi.settings.HighlighterRule
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.settings.TabSession

/** Cap for the persisted global search history. */
private const val MAX_SEARCH_HISTORY = 20

/** One tab: path + display name + lifecycle state. */
class FileTab(val path: String) {
    var displayName: String? by mutableStateOf(null)
    var state: TabState by mutableStateOf(TabState.Indexing(0, 0))

    /** Set by close while indexing is still in flight. */
    @Volatile
    var cancelRequested = false

    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
    val title: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: fileName
}

sealed interface TabState {
    data class Indexing(val done: Long, val total: Long) : TabState
    data class Ready(val vm: FileViewModel) : TabState
    data class Failed(val message: String) : TabState
}

/** Request for the custom-color dialog (settings + highlight menu share it). */
data class ColorPickerRequest(val initialArgb: Long, val onPick: (Long) -> Unit)

/**
 * App-level view model: tabs, settings, session persistence, dialogs,
 * highlighters. [scope] is `Dispatchers.Default` — every blocking engine call
 * runs there, never on the UI thread.
 */
class AppViewModel(
    private val store: SettingsStore = SettingsStore.default(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var settings by mutableStateOf(store.load())
        private set

    val tabs = mutableStateListOf<FileTab>()
    var activeTab by mutableStateOf<FileTab?>(null)

    // Dialog / window visibility
    var showSettings by mutableStateOf(false)
    var showHighlighters by mutableStateOf(false)
    var showSearchHistory by mutableStateOf(false)
    var showAbout by mutableStateOf(false)
    var goToLineFor by mutableStateOf<FileViewModel?>(null)
    var renameFor by mutableStateOf<FileTab?>(null)
    var colorPicker by mutableStateOf<ColorPickerRequest?>(null)

    /** Global search history (persisted in `loggi.conf`), most recent first. */
    val searchHistory = mutableStateListOf<String>()

    /** Sessions awaiting tab creation, keyed by path (session restore). */
    private val pendingSessions = HashMap<String, TabSession>()

    // Bumped by requestSave(); the persistence watcher debounces and writes.
    // Declared before `init` — restoreSession() already triggers requestSave().
    private var saveRequested by mutableStateOf(0L)

    val strings: Strings get() = stringsFor(resolveLocale(settings.locale))

    val activeVm: FileViewModel? get() = (activeTab?.state as? TabState.Ready)?.vm

    init {
        searchHistory.addAll(settings.searchHistory)
        restoreSession()
        watchPersistence()
    }

    // ---- tabs ---------------------------------------------------------------

    fun openFile(path: String) {
        tabs.firstOrNull { it.path == path }?.let {
            activeTab = it
            return
        }
        val tab = FileTab(path)
        pendingSessions[path]?.let { tab.displayName = it.displayName }
        tabs.add(tab)
        activeTab = tab
        requestSave()
        scope.launch {
            try {
                val engine = EngineFile.open(
                    path,
                    onProgress = { d, t -> tab.state = TabState.Indexing(d, t) },
                    cancelled = { tab.cancelRequested || !tabs.contains(tab) },
                )
                tab.state = TabState.Ready(FileViewModel(engine, this@AppViewModel, pendingSessions.remove(path)))
            } catch (_: OpenCancelledException) {
                tabs.remove(tab)
            } catch (t: Throwable) {
                tab.state = TabState.Failed(t.message ?: t.toString())
            }
        }
    }

    fun closeTab(tab: FileTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tab.cancelRequested = true
        tabs.remove(tab)
        if (activeTab == tab) {
            activeTab = tabs.getOrNull(index.coerceAtMost(tabs.size - 1))
        }
        val vm = (tab.state as? TabState.Ready)?.vm
        if (vm != null) scope.launch { vm.close() }
        requestSave()
    }

    fun closeOthers(tab: FileTab) = tabs.filter { it != tab }.forEach(::closeTab)

    fun closeLeft(tab: FileTab) {
        val i = tabs.indexOf(tab)
        if (i > 0) tabs.take(i).forEach(::closeTab)
    }

    fun closeRight(tab: FileTab) {
        val i = tabs.indexOf(tab)
        if (i >= 0) tabs.drop(i + 1).forEach(::closeTab)
    }

    fun closeAll() = tabs.toList().forEach(::closeTab)

    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        tabs.add(to, tabs.removeAt(from))
        requestSave()
    }

    // ---- settings / highlighters ---------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
    }

    fun addHighlighter(rule: HighlighterRule) {
        updateSettings { it.copy(highlighters = it.highlighters + rule) }
    }

    fun removeHighlighter(index: Int) {
        updateSettings { it.copy(highlighters = it.highlighters.filterIndexed { i, _ -> i != index }) }
    }

    fun pushSearchHistory(pattern: String) {
        if (pattern.isBlank()) return
        searchHistory.remove(pattern)
        searchHistory.add(0, pattern)
        while (searchHistory.size > MAX_SEARCH_HISTORY) searchHistory.removeAt(searchHistory.lastIndex)
        updateSettings { it.copy(searchHistory = searchHistory.toList()) }
    }

    fun removeSearchHistory(pattern: String) {
        if (searchHistory.remove(pattern)) updateSettings { it.copy(searchHistory = searchHistory.toList()) }
    }

    fun clearSearchHistory() {
        if (searchHistory.isEmpty()) return
        searchHistory.clear()
        updateSettings { it.copy(searchHistory = emptyList()) }
    }

    // ---- persistence ------------------------------------------------------------

    /** Ask the persistence watcher to write settings + session soon. */
    fun requestSave() {
        saveRequested++
    }

    @OptIn(FlowPreview::class)
    private fun watchPersistence() {
        scope.launch {
            snapshotFlow { settings to saveRequested }
                .drop(1)
                .debounce(600)
                .collect { persist() }
        }
    }

    private fun persist() {
        val session = tabs.map { tab ->
            val vm = (tab.state as? TabState.Ready)?.vm
            TabSession(
                path = tab.path,
                displayName = tab.displayName,
                topLine = vm?.topLine ?: 0,
                searchPattern = vm?.searchPattern ?: "",
                ignoreCase = vm?.ignoreCase ?: false,
                regex = vm?.useRegex ?: false,
                pins = vm?.results?.pinsSnapshot() ?: emptyList(),
                follow = vm?.follow ?: false,
            )
        }
        store.save(
            settings.copy(
                sessionTabs = session,
                activeTabIndex = tabs.indexOf(activeTab),
            ),
        )
    }

    private fun restoreSession() {
        val s = settings
        if (!s.reopenOnStartup) return
        s.sessionTabs.forEach { pendingSessions[it.path] = it }
        s.sessionTabs.forEach { openFile(it.path) }
        activeTab = tabs.getOrNull(s.activeTabIndex) ?: tabs.firstOrNull()
    }

    /** Persist synchronously and release all engine handles (window close). */
    fun shutdown() {
        persist()
        tabs.toList().forEach { tab ->
            (tab.state as? TabState.Ready)?.vm?.let { vm -> scope.launch { vm.close() } }
                ?: run { tab.cancelRequested = true }
        }
        scope.cancel()
    }
}

// Session hand-off between AppViewModel.restoreSession and the FileViewModel
// constructor happens through `pendingSessions` (keyed by path).
