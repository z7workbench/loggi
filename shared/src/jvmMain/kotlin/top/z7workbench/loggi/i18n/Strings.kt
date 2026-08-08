package top.z7workbench.loggi.i18n

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale
import top.z7workbench.loggi.settings.LocaleSetting

/**
 * All UI strings, one subclass per locale (see [EnStrings], [ZhStrings],
 * [ZhHantStrings], [FrStrings], [DeStrings], [RuStrings] — each in its own
 * file). A unit test asserts every locale overrides every member (no silent
 * fallback to English). [statusSize] is locale-neutral and lives in the base.
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
    abstract val lineTooLongPlaceholder: String
    abstract val copyTruncatedWarning: String
    abstract val highlightTruncatedWarning: String
    abstract val noFileOpen: String

    /** Locale-neutral byte-size formatting (KiB/MiB/GiB/TiB units). */
    fun statusSize(bytes: Long): String {
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
    abstract val aboutVersionLabel: String
    abstract val aboutLicense: String
    abstract val customColorTitle: String

    // OS integration (M11) — "Open with Loggi" shell verb / Open With menu
    abstract val openWithLoggi: String

    // File drag & drop onto the window
    abstract val dropToOpen: String

    // Settings dialog
    abstract val settingsTitle: String
    abstract val sectionAppearance: String
    abstract val themeLabel: String
    abstract val themeSystem: String
    abstract val themeLight: String
    abstract val themeDark: String
    abstract val colorSchemeLabel: String
    abstract val colorSchemeViolet: String
    abstract val colorSchemeBlue: String
    abstract val colorSchemeTeal: String
    abstract val colorSchemeGreen: String
    abstract val colorSchemeOrange: String
    abstract val colorSchemeAmber: String
    abstract val colorSchemeRose: String
    abstract val colorSchemeSlate: String
    abstract val colorSchemeIndigo: String
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

enum class AppLocale { EN, ZH, ZH_HANT, FR, DE, RU }

val LocalStrings = compositionLocalOf<Strings> { EnStrings() }

fun stringsFor(locale: AppLocale): Strings = when (locale) {
    AppLocale.EN -> EnStrings()
    AppLocale.ZH -> ZhStrings()
    AppLocale.ZH_HANT -> ZhHantStrings()
    AppLocale.FR -> FrStrings()
    AppLocale.DE -> DeStrings()
    AppLocale.RU -> RuStrings()
}

/**
 * Resolve the effective locale: the user setting wins, else the JVM default
 * (Traditional Chinese when the system script is `Hant` or the region is
 * TW/HK/MO; otherwise simplified Chinese for any `zh` language).
 */
fun resolveLocale(setting: LocaleSetting): AppLocale = when (setting) {
    LocaleSetting.EN -> AppLocale.EN
    LocaleSetting.ZH -> AppLocale.ZH
    LocaleSetting.ZH_HANT -> AppLocale.ZH_HANT
    LocaleSetting.FR -> AppLocale.FR
    LocaleSetting.DE -> AppLocale.DE
    LocaleSetting.RU -> AppLocale.RU
    LocaleSetting.SYSTEM -> {
        val l = Locale.getDefault()
        when {
            l.language.startsWith("zh") ->
                if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) AppLocale.ZH_HANT else AppLocale.ZH
            l.language.startsWith("fr") -> AppLocale.FR
            l.language.startsWith("de") -> AppLocale.DE
            l.language.startsWith("ru") -> AppLocale.RU
            else -> AppLocale.EN
        }
    }
}
