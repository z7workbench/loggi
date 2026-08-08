package top.z7workbench.loggi.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LocaleSetting { SYSTEM, EN, ZH }

enum class TabPlacement { HORIZONTAL, VERTICAL }

enum class SearchLayout { SIDE, BOTTOM, DETACHED }

@Serializable
data class HighlighterRule(
    val pattern: String,
    val colorArgb: Long,
    val ignoreCase: Boolean = true,
    val regex: Boolean = false,
    /** Tint the whole log line instead of just the matched text. */
    val wholeLine: Boolean = false,
    /**
     * Line-anchored selection highlight (multi-line selections): tint exactly
     * [anchorStart, anchorEnd) (raw char indices) on [anchorLine] only. When
     * set, [pattern] is kept as a display preview and no matching runs.
     */
    val anchorLine: Long? = null,
    val anchorStart: Int = -1,
    val anchorEnd: Int = -1,
)

@Serializable
data class TabSession(
    val path: String,
    val displayName: String? = null,
    val topLine: Long = 0,
    val searchPattern: String = "",
    val ignoreCase: Boolean = false,
    val regex: Boolean = false,
    val pins: List<Long> = emptyList(),
    val follow: Boolean = false,
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val locale: LocaleSetting = LocaleSetting.SYSTEM,
    val fontFamily: String = FONT_MONOSPACE,
    /** UI font family; [FONT_SYSTEM] = the OS default UI font. */
    val uiFontFamily: String = FONT_SYSTEM,
    val fontSizeSp: Float = 13f,
    val lineHeightFactor: Float = 1.2f,
    val wrapLines: Boolean = false,
    val expandTabs: Boolean = true,
    val tabStop: Int = 8,
    val tabPlacement: TabPlacement = TabPlacement.HORIZONTAL,
    val searchLayout: SearchLayout = SearchLayout.BOTTOM,
    val searchMatchColorArgb: Long = 0x66FFC107L,
    /** Tint the whole line of every search match, not just the matched text. */
    val searchMatchWholeLine: Boolean = false,
    /** Global search history (most recent first), persisted across restarts. */
    val searchHistory: List<String> = emptyList(),
    /** Restore the previous session's tabs at startup; off = always start empty. */
    val reopenOnStartup: Boolean = true,
    val highlightPresets: List<Long> = DEFAULT_PRESETS,
    val highlighters: List<HighlighterRule> = emptyList(),
    val splitterSideFraction: Float = 0.62f,
    val splitterBottomFraction: Float = 0.58f,
    val sessionTabs: List<TabSession> = emptyList(),
    val activeTabIndex: Int = -1,
) {
    companion object {
        const val FONT_MONOSPACE = "Monospace"
        const val FONT_SANS_SERIF = "SansSerif"
        const val FONT_SERIF = "Serif"
        /** Sentinel UI font family: use the OS default UI font. */
        const val FONT_SYSTEM = "System"
        val FONT_FAMILIES = listOf(FONT_MONOSPACE, FONT_SANS_SERIF, FONT_SERIF)

        val DEFAULT_PRESETS = listOf(
            0x66FF5252L, // red
            0x66FF9800L, // orange
            0x66FFEB3BL, // yellow
            0x664CAF50L, // green
            0x662196F3L, // blue
            0x669C27B0L, // purple
        )
    }
}

/**
 * Loads/saves `loggi.conf` as JSON. Resolution order:
 * 1. `-Dloggi.config=<path>` override;
 * 2. portable mode: `loggi.conf` in the working directory, when it exists;
 * 3. the per-OS app-config dir (`~/.config/loggi`, `%APPDATA%\loggi`,
 *    `~/Library/Application Support/loggi`).
 */
class SettingsStore(private val file: Path) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppSettings = runCatching {
        json.decodeFromString<AppSettings>(Files.readString(file))
    }.getOrElse { AppSettings() }

    fun save(settings: AppSettings) {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(AppSettings.serializer(), settings))
            runCatching {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        fun default(): SettingsStore {
            System.getProperty("loggi.config")?.takeIf { it.isNotBlank() }?.let {
                return SettingsStore(Path.of(it))
            }
            val portable = Path.of("loggi.conf").toAbsolutePath()
            if (Files.exists(portable)) return SettingsStore(portable)
            return SettingsStore(appConfigDir().resolve("loggi.conf"))
        }

        private fun appConfigDir(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = Path.of(System.getProperty("user.home"))
            return when {
                os.contains("win") -> Path.of(System.getenv("APPDATA") ?: home.toString()).resolve("loggi")
                os.contains("mac") -> home.resolve("Library/Application Support/loggi")
                else -> Path.of(System.getenv("XDG_CONFIG_HOME") ?: home.resolve(".config").toString()).resolve("loggi")
            }
        }
    }
}
