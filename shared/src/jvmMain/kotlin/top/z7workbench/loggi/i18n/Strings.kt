package top.z7workbench.loggi.i18n

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale
import top.z7workbench.loggi.settings.LocaleSetting

/**
 * All UI strings. The base class carries the English texts; [ZhStrings]
 * overrides every member. A unit test asserts the zh-Hans override is complete
 * (no member silently falls back to English).
 */
abstract class Strings {
    abstract val appTitle: String

    // Menu bar
    abstract val menuFile: String
    abstract val menuOpen: String
    abstract val menuCloseTab: String
    abstract val menuSettings: String
    abstract val menuExit: String
    abstract val menuSearch: String
    abstract val menuGoToLine: String
    abstract val menuFollow: String
    abstract val menuView: String
    abstract val menuLayoutSide: String
    abstract val menuLayoutBottom: String
    abstract val menuLayoutDetached: String
    abstract val menuHelp: String
    abstract val menuAbout: String

    // Tab bar / tab context menu
    abstract val tabClose: String
    abstract val tabCloseOthers: String
    abstract val tabCloseLeft: String
    abstract val tabCloseRight: String
    abstract val tabCloseAll: String
    abstract val tabRename: String
    abstract val tabCopyPath: String
    abstract val tabCopyName: String
    abstract val tabOpenFolder: String
    abstract val renameTitle: String
    abstract val renameLabel: String

    // Search bar + results pane
    abstract val searchPlaceholder: String
    abstract val searchButton: String
    abstract val stopButton: String
    abstract val ignoreCaseLabel: String
    abstract val regexLabel: String
    abstract val searchHistory: String
    abstract val searchHistoryTitle: String
    abstract val manageHistoryLabel: String
    abstract val clearAllLabel: String
    abstract val searchHistoryEmptyLabel: String
    abstract val pinnedLinesHeader: String
    abstract fun matchesCount(count: Long): String
    abstract fun searchProgress(processed: Long, total: Long): String

    // Log view context menu
    abstract val ctxCopy: String
    abstract val ctxCopyLines: String
    abstract val ctxCopyReference: String
    abstract val ctxHighlight: String
    abstract val ctxCustomColor: String
    abstract val ctxPin: String
    abstract val ctxUnpin: String
    abstract val unpinAllLabel: String
    abstract val ctxRemoveHighlighter: String
    abstract val ctxRemoveAllHighlights: String

    // Status / misc
    abstract val followButton: String
    abstract fun statusLineCount(count: Long): String
    abstract fun statusSize(bytes: Long): String
    abstract val lineTooLongPlaceholder: String
    abstract val copyTruncatedWarning: String
    abstract val highlightTruncatedWarning: String
    abstract val noFileOpen: String

    // Open / indexing
    abstract fun openingFile(name: String): String
    abstract fun indexingProgress(done: Long, total: Long): String
    abstract val cancelButton: String
    abstract val okButton: String
    abstract val openFailedTitle: String

    // Dialogs
    abstract val goToLineTitle: String
    abstract val goToLineLabel: String
    abstract val invalidLineNumber: String
    abstract val aboutTitle: String
    abstract fun aboutText(engineVersion: String): String
    abstract val customColorTitle: String

    // OS integration (M11) — "Open with Loggi" shell verb / Open With menu
    abstract val openWithLoggi: String

    // Settings dialog
    abstract val settingsTitle: String
    abstract val sectionAppearance: String
    abstract val themeLabel: String
    abstract val themeSystem: String
    abstract val themeLight: String
    abstract val themeDark: String
    abstract val languageLabel: String
    abstract val languageSystem: String
    abstract val sectionDisplay: String
    abstract val fontFamilyLabel: String
    abstract val uiFontFamilyLabel: String
    abstract val fontSystemDefault: String
    abstract val fontSizeLabel: String
    abstract val lineHeightLabel: String
    abstract val wrapLabel: String
    abstract val expandTabsLabel: String
    abstract val tabStopLabel: String
    abstract val sectionTabs: String
    abstract val tabPlacementLabel: String
    abstract val tabHorizontal: String
    abstract val tabVertical: String
    abstract val sectionSearch: String
    abstract val layoutLabel: String
    abstract val matchColorLabel: String
    abstract val sectionHighlighters: String
    abstract val addHighlighterHint: String
    abstract val sectionPresets: String
    abstract val sectionGeneral: String
    abstract val reopenOnStartupLabel: String
    abstract val wholeLineLabel: String
    abstract val searchMatchWholeLineLabel: String
    abstract val highlightersTitle: String
    abstract val highlighterAddLabel: String
    abstract val highlighterPatternPlaceholder: String
    abstract fun anchoredHighlightLabel(line: Long): String
}

class EnStrings : Strings() {
    override val appTitle = "Loggi"

    override val menuFile = "File"
    override val menuOpen = "Open…"
    override val menuCloseTab = "Close Tab"
    override val menuSettings = "Settings…"
    override val menuExit = "Exit"
    override val menuSearch = "Search"
    override val menuGoToLine = "Go to Line…"
    override val menuFollow = "Follow"
    override val menuView = "View"
    override val menuLayoutSide = "Search Pane: Left/Right"
    override val menuLayoutBottom = "Search Pane: Top/Bottom"
    override val menuLayoutDetached = "Search Pane: Detached Window"
    override val menuHelp = "Help"
    override val menuAbout = "About Loggi"

    override val tabClose = "Close"
    override val tabCloseOthers = "Close Others"
    override val tabCloseLeft = "Close to the Left"
    override val tabCloseRight = "Close to the Right"
    override val tabCloseAll = "Close All"
    override val tabRename = "Rename…"
    override val tabCopyPath = "Copy Absolute Path"
    override val tabCopyName = "Copy File Name"
    override val tabOpenFolder = "Open Folder"
    override val renameTitle = "Rename Tab"
    override val renameLabel = "Display name (empty = file name)"

    override val searchPlaceholder = "Search pattern"
    override val searchButton = "Search"
    override val stopButton = "Stop"
    override val ignoreCaseLabel = "Aa"
    override val regexLabel = ".*"
    override val searchHistory = "History"
    override val searchHistoryTitle = "Search History"
    override val manageHistoryLabel = "Manage History…"
    override val clearAllLabel = "Clear All"
    override val searchHistoryEmptyLabel = "No history yet"
    override val pinnedLinesHeader = "Pinned"
    override fun matchesCount(count: Long) = "$count matches"
    override fun searchProgress(processed: Long, total: Long) = "$processed / $total lines"

    override val ctxCopy = "Copy"
    override val ctxCopyLines = "Copy Line(s)"
    override val ctxCopyReference = "Copy Reference (name:line)"
    override val ctxHighlight = "Highlight"
    override val ctxCustomColor = "Custom Color…"
    override val ctxPin = "Pin Line(s)"
    override val ctxUnpin = "Unpin Line(s)"
    override val unpinAllLabel = "Unpin All"
    override val ctxRemoveHighlighter = "Remove Highlight"
    override val ctxRemoveAllHighlights = "Remove All Highlights"

    override val followButton = "Follow"
    override fun statusLineCount(count: Long) = "$count lines"
    override fun statusSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = bytes.toDouble()
        var u = -1
        do {
            v /= 1024.0
            u++
        } while (v >= 1024.0 && u < units.lastIndex)
        return "%.1f %s".format(v, units[u])
    }
    override val lineTooLongPlaceholder = "<line exceeds the 8 MiB display budget>"
    override val copyTruncatedWarning = "Selection too large; copied the first part only"
    override val highlightTruncatedWarning = "Selection too large; highlighted the first part only"
    override val noFileOpen = "Open a log file to begin (File → Open…)"

    override fun openingFile(name: String) = "Opening $name"
    override fun indexingProgress(done: Long, total: Long): String =
        if (total > 0) "Indexing… %d / %d bytes (%d%%)".format(done, total, done * 100 / total)
        else "Indexing… $done bytes"
    override val cancelButton = "Cancel"
    override val okButton = "OK"
    override val openFailedTitle = "Failed to open file"

    override val goToLineTitle = "Go to Line"
    override val goToLineLabel = "Line number"
    override val invalidLineNumber = "Invalid line number"
    override val aboutTitle = "About Loggi"
    override fun aboutText(engineVersion: String) =
        "Loggi — a log viewer for very large files.\nEngine: $engineVersion"
    override val customColorTitle = "Custom Color"
    override val openWithLoggi = "Open with Loggi"

    override val settingsTitle = "Settings"
    override val sectionAppearance = "Appearance"
    override val themeLabel = "Theme"
    override val themeSystem = "Follow system"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val languageLabel = "Language"
    override val languageSystem = "System default"
    override val sectionDisplay = "Display"
    override val fontFamilyLabel = "Log font"
    override val uiFontFamilyLabel = "UI font"
    override val fontSystemDefault = "System default"
    override val fontSizeLabel = "Font size"
    override val lineHeightLabel = "Line spacing"
    override val wrapLabel = "Wrap long lines"
    override val expandTabsLabel = "Expand tabs to spaces"
    override val tabStopLabel = "Tab stop"
    override val sectionTabs = "Tabs"
    override val tabPlacementLabel = "Tab placement"
    override val tabHorizontal = "Horizontal"
    override val tabVertical = "Vertical"
    override val sectionSearch = "Search"
    override val layoutLabel = "Search pane layout"
    override val matchColorLabel = "Match highlight color"
    override val sectionHighlighters = "Highlighters"
    override val addHighlighterHint = "Add from the log view context menu (Highlight), or edit rules in the Highlighters window"
    override val sectionPresets = "Highlight color presets"
    override val sectionGeneral = "General"
    override val reopenOnStartupLabel = "Reopen previous files on startup"
    override val wholeLineLabel = "Whole line"
    override val searchMatchWholeLineLabel = "Tint whole lines of matches"
    override val highlightersTitle = "Highlighters"
    override val highlighterAddLabel = "Add rule"
    override val highlighterPatternPlaceholder = "Pattern (plain text or regex)"
    override fun anchoredHighlightLabel(line: Long) = "Line $line:"
}

class ZhStrings : Strings() {
    override val appTitle = "Loggi"

    override val menuFile = "文件"
    override val menuOpen = "打开…"
    override val menuCloseTab = "关闭标签页"
    override val menuSettings = "设置…"
    override val menuExit = "退出"
    override val menuSearch = "搜索"
    override val menuGoToLine = "跳转到行…"
    override val menuFollow = "跟随"
    override val menuView = "视图"
    override val menuLayoutSide = "搜索面板：左右布局"
    override val menuLayoutBottom = "搜索面板：上下布局"
    override val menuLayoutDetached = "搜索面板：独立窗口"
    override val menuHelp = "帮助"
    override val menuAbout = "关于 Loggi"

    override val tabClose = "关闭"
    override val tabCloseOthers = "关闭其他"
    override val tabCloseLeft = "关闭左侧"
    override val tabCloseRight = "关闭右侧"
    override val tabCloseAll = "全部关闭"
    override val tabRename = "重命名…"
    override val tabCopyPath = "复制绝对路径"
    override val tabCopyName = "复制文件名"
    override val tabOpenFolder = "打开所在文件夹"
    override val renameTitle = "重命名标签页"
    override val renameLabel = "显示名称（留空使用文件名）"

    override val searchPlaceholder = "搜索模式"
    override val searchButton = "搜索"
    override val stopButton = "停止"
    override val ignoreCaseLabel = "Aa"
    override val regexLabel = ".*"
    override val searchHistory = "历史"
    override val searchHistoryTitle = "搜索历史"
    override val manageHistoryLabel = "管理搜索历史…"
    override val clearAllLabel = "全部清空"
    override val searchHistoryEmptyLabel = "暂无搜索历史"
    override val pinnedLinesHeader = "已固定"
    override fun matchesCount(count: Long) = "$count 个匹配"
    override fun searchProgress(processed: Long, total: Long) = "$processed / $total 行"

    override val ctxCopy = "复制"
    override val ctxCopyLines = "复制整行"
    override val ctxCopyReference = "复制引用（文件名:行号）"
    override val ctxHighlight = "高亮"
    override val ctxCustomColor = "自定义颜色…"
    override val ctxPin = "固定行"
    override val ctxUnpin = "取消固定"
    override val unpinAllLabel = "取消全部固定"
    override val ctxRemoveHighlighter = "移除高亮"
    override val ctxRemoveAllHighlights = "取消所有高光"

    override val followButton = "跟随"
    override fun statusLineCount(count: Long) = "$count 行"
    override fun statusSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = bytes.toDouble()
        var u = -1
        do {
            v /= 1024.0
            u++
        } while (v >= 1024.0 && u < units.lastIndex)
        return "%.1f %s".format(v, units[u])
    }
    override val lineTooLongPlaceholder = "<该行超过 8 MiB 显示上限>"
    override val copyTruncatedWarning = "选区过大，仅复制了前一部分"
    override val highlightTruncatedWarning = "选区过大，仅高亮前一部分"
    override val noFileOpen = "打开一个日志文件开始（文件 → 打开…）"

    override fun openingFile(name: String) = "正在打开 $name"
    override fun indexingProgress(done: Long, total: Long): String =
        if (total > 0) "正在索引… %d / %d 字节（%d%%）".format(done, total, done * 100 / total)
        else "正在索引… $done 字节"
    override val cancelButton = "取消"
    override val okButton = "确定"
    override val openFailedTitle = "打开文件失败"

    override val goToLineTitle = "跳转到行"
    override val goToLineLabel = "行号"
    override val invalidLineNumber = "无效的行号"
    override val aboutTitle = "关于 Loggi"
    override fun aboutText(engineVersion: String) =
        "Loggi —— 面向超大文件的日志查看器。\n引擎：$engineVersion"
    override val customColorTitle = "自定义颜色"
    override val openWithLoggi = "使用 Loggi 打开"

    override val settingsTitle = "设置"
    override val sectionAppearance = "外观"
    override val themeLabel = "主题"
    override val themeSystem = "跟随系统"
    override val themeLight = "浅色"
    override val themeDark = "深色"
    override val languageLabel = "语言"
    override val languageSystem = "系统默认"
    override val sectionDisplay = "显示"
    override val fontFamilyLabel = "日志字体"
    override val uiFontFamilyLabel = "界面字体"
    override val fontSystemDefault = "系统默认"
    override val fontSizeLabel = "字号"
    override val lineHeightLabel = "行距"
    override val wrapLabel = "长行自动换行"
    override val expandTabsLabel = "制表符展开为空格"
    override val tabStopLabel = "制表位宽度"
    override val sectionTabs = "标签页"
    override val tabPlacementLabel = "标签栏位置"
    override val tabHorizontal = "水平"
    override val tabVertical = "垂直"
    override val sectionSearch = "搜索"
    override val layoutLabel = "搜索面板布局"
    override val matchColorLabel = "匹配高亮颜色"
    override val sectionHighlighters = "高亮规则"
    override val addHighlighterHint = "可在日志视图右键菜单（高亮）添加，或在高亮规则窗口中编辑"
    override val sectionPresets = "高亮预设颜色"
    override val sectionGeneral = "常规"
    override val reopenOnStartupLabel = "启动时重新打开上次的文件"
    override val wholeLineLabel = "整行"
    override val searchMatchWholeLineLabel = "高亮匹配所在整行"
    override val highlightersTitle = "高亮规则"
    override val highlighterAddLabel = "添加规则"
    override val highlighterPatternPlaceholder = "匹配内容（纯文本或正则）"
    override fun anchoredHighlightLabel(line: Long) = "第 $line 行："
}

enum class AppLocale { EN, ZH }

val LocalStrings = compositionLocalOf<Strings> { EnStrings() }

fun stringsFor(locale: AppLocale): Strings = when (locale) {
    AppLocale.EN -> EnStrings()
    AppLocale.ZH -> ZhStrings()
}

/** Resolve the effective locale: the user setting wins, else the JVM default. */
fun resolveLocale(setting: LocaleSetting): AppLocale = when (setting) {
    LocaleSetting.EN -> AppLocale.EN
    LocaleSetting.ZH -> AppLocale.ZH
    LocaleSetting.SYSTEM ->
        if (Locale.getDefault().language.startsWith("zh")) AppLocale.ZH else AppLocale.EN
}
